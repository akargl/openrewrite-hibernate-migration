package com.akargl.openrewrite.hibernate;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.hibernate.grammars.hql.HqlLexer;
import org.hibernate.grammars.hql.HqlParser;
import org.hibernate.grammars.hql.HqlParserBaseListener;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.marker.JavaSourceSet;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Makes the identifier property explicit when an HQL entity-valued path is compared with a scalar.
 */
public class UseEntityIdInHqlComparison extends ScanningRecipe<UseEntityIdInHqlComparison.Accumulator> {
    private final transient HqlQueryAnalysis queryAnalysis = new HqlQueryAnalysis(this);

    private static final String JAKARTA_ENTITY = "jakarta.persistence.Entity";
    private static final String JAVAX_ENTITY = "javax.persistence.Entity";
    private static final String JAKARTA_MAPPED_SUPERCLASS = "jakarta.persistence.MappedSuperclass";
    private static final String JAVAX_MAPPED_SUPERCLASS = "javax.persistence.MappedSuperclass";
    private static final String JAKARTA_ID = "jakarta.persistence.Id";
    private static final String JAVAX_ID = "javax.persistence.Id";
    private static final String JAKARTA_EMBEDDED_ID = "jakarta.persistence.EmbeddedId";
    private static final String JAVAX_EMBEDDED_ID = "javax.persistence.EmbeddedId";
    private static final String JAKARTA_ID_CLASS = "jakarta.persistence.IdClass";
    private static final String JAVAX_ID_CLASS = "javax.persistence.IdClass";

    private static final Set<String> QUERY_ANNOTATIONS = Set.of(
            "jakarta.persistence.NamedQuery",
            "javax.persistence.NamedQuery",
            "org.hibernate.annotations.NamedQuery",
            "org.springframework.data.jpa.repository.Query"
    );

    private static final Set<String> QUERY_METHOD_NAMES = Set.of(
            "createQuery", "createSelectionQuery", "createMutationQuery"
    );

    private static final List<MethodMatcher> QUERY_METHODS = List.of(
            new MethodMatcher("jakarta.persistence.EntityManager createQuery(..)", true),
            new MethodMatcher("javax.persistence.EntityManager createQuery(..)", true),
            new MethodMatcher("org.hibernate.Session createQuery(..)", true),
            new MethodMatcher("org.hibernate.Session createSelectionQuery(..)", true),
            new MethodMatcher("org.hibernate.Session createMutationQuery(..)", true),
            new MethodMatcher("org.hibernate.query.QueryProducer createQuery(..)", true),
            new MethodMatcher("org.hibernate.query.QueryProducer createSelectionQuery(..)", true),
            new MethodMatcher("org.hibernate.query.QueryProducer createMutationQuery(..)", true)
    );

    private static final Pattern SIMPLE_PATH = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*"
    );
    private static final Pattern PARAMETER = Pattern.compile("(?::[A-Za-z_$][A-Za-z0-9_$]*|\\?[0-9]+)");
    private static final Pattern NUMBER = Pattern.compile(
            "[+-]?(?:0[xX][0-9a-fA-F]+|[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)[lLfFdD]?"
    );

    @Override
    public String getDisplayName() {
        return "Use entity identifiers in HQL comparisons";
    }

    @Override
    public String getDescription() {
        return "Makes an entity's identifier property explicit when an HQL or JPQL query compares an " +
               "entity-valued expression with a scalar value, as required by Hibernate 6.";
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new JavaIsoVisitor<>() {
            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit compilationUnit, ExecutionContext ctx) {
                compilationUnit.getMarkers().findFirst(JavaSourceSet.class)
                        .filter(sourceSet -> acc.scannedSourceSets.add(sourceSet.getId()))
                        .ifPresent(sourceSet -> sourceSet.getClasspath().forEach(type ->
                                acc.classpathTypeNames.add(type.getFullyQualifiedName())));
                scanAttributedTypes(acc, compilationUnit.getTypesInUse().getTypesInUse());
                return super.visitCompilationUnit(compilationUnit, ctx);
            }

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.ClassDeclaration c = super.visitClassDeclaration(classDecl, ctx);
                boolean entity = hasAnyAnnotation(c.getLeadingAnnotations(), JAKARTA_ENTITY, JAVAX_ENTITY);
                boolean mappedSuperclass = hasAnyAnnotation(
                        c.getLeadingAnnotations(), JAKARTA_MAPPED_SUPERCLASS, JAVAX_MAPPED_SUPERCLASS
                );
                if (!entity && !mappedSuperclass) {
                    return c;
                }

                JavaType.FullyQualified classType = TypeUtils.asFullyQualified(c.getType());
                if (classType == null) {
                    return c;
                }

                EntityInfo info = new EntityInfo(classType.getFullyQualifiedName(), c.getSimpleName(), entity);
                if (entity) {
                    J.Annotation entityAnnotation = findAnyAnnotation(c.getLeadingAnnotations(), JAKARTA_ENTITY, JAVAX_ENTITY);
                    String configuredName = stringAttribute(entityAnnotation, "name");
                    if (configuredName != null && !configuredName.isBlank()) {
                        info.entityName = configuredName;
                    }
                }
                info.compositeIdentifier = hasAnyAnnotation(
                        c.getLeadingAnnotations(), JAKARTA_ID_CLASS, JAVAX_ID_CLASS
                );
                if (c.getExtends() != null) {
                    JavaType.FullyQualified superType = TypeUtils.asFullyQualified(c.getExtends().getType());
                    if (superType != null) {
                        info.superType = superType.getFullyQualifiedName();
                    }
                }

                for (Statement statement : c.getBody().getStatements()) {
                    switch (statement) {
                        case J.VariableDeclarations field -> scanField(info, field);
                        case J.MethodDeclaration method -> scanMethod(info, method);
                        default -> {
                        }
                    }
                }
                if (info.idProperties.size() != 1) {
                    info.compositeIdentifier = info.idProperties.size() > 1 || info.compositeIdentifier;
                }
                acc.types.put(info.fullyQualifiedName, info);
                return c;
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new JavaIsoVisitor<>() {
            private static final String IMPERATIVE_BINDINGS = "hqlImperativeBindings";

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                getCursor().putMessage(IMPERATIVE_BINDINGS, analyzeImperativeBindings(method));
                return super.visitMethodDeclaration(method, ctx);
            }

            @Override
            public J.Annotation visitAnnotation(J.Annotation annotation, ExecutionContext ctx) {
                J.Annotation a = super.visitAnnotation(annotation, ctx);
                String annotationType = annotationType(a);
                if (!QUERY_ANNOTATIONS.contains(annotationType) || shouldSkipSpringQuery(a)) {
                    return a;
                }

                List<Expression> arguments = a.getArguments();
                if (arguments == null) {
                    return a;
                }
                ParameterBindings bindings = "org.springframework.data.jpa.repository.Query".equals(annotationType) ?
                        springMethodBindings(getCursor()) : ParameterBindings.empty();
                String querySource = "@" + a.getSimpleName();
                return a.withArguments(mapExpressions(
                        arguments, expression -> rewriteQueryAttribute(
                                expression, acc, bindings, ctx, sourcePath(getCursor()), querySource
                        )
                ));
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                if (!QUERY_METHOD_NAMES.contains(m.getSimpleName()) || m.getArguments().isEmpty() ||
                        QUERY_METHODS.stream().noneMatch(matcher -> matcher.matches(m))) {
                    return m;
                }

                var arguments = new ArrayList<>(m.getArguments());
                Map<UUID, ParameterBindings> byQuery = getCursor().getNearestMessage(IMPERATIVE_BINDINGS);
                ParameterBindings bindings = ParameterBindings.empty();
                StaticQuery staticQuery = staticQuery(arguments.getFirst());
                if (staticQuery != null && byQuery != null) {
                    bindings = byQuery.getOrDefault(staticQuery.anchorId(), bindings);
                }
                arguments.set(0, rewriteStaticQuery(
                        arguments.getFirst(), acc, bindings, ctx, sourcePath(getCursor()), m.getSimpleName() + "(...)"
                ));
                return m.withArguments(arguments);
            }
        };
    }

    private static void scanField(EntityInfo info, J.VariableDeclarations field) {
        boolean id = hasAnyAnnotation(field.getLeadingAnnotations(), JAKARTA_ID, JAVAX_ID);
        boolean embeddedId = hasAnyAnnotation(field.getLeadingAnnotations(), JAKARTA_EMBEDDED_ID, JAVAX_EMBEDDED_ID);
        JavaType targetType = field.getType();
        for (J.VariableDeclarations.NamedVariable variable : field.getVariables()) {
            info.attributeTypes.put(variable.getSimpleName(), targetType);
            if (id) {
                info.idProperties.add(variable.getSimpleName());
            }
            if (embeddedId) {
                info.compositeIdentifier = true;
            }
        }
    }

    private static void scanMethod(EntityInfo info, J.MethodDeclaration method) {
        String property = getterProperty(method.getSimpleName());
        boolean hasParameters = !method.getParameters().isEmpty() &&
                !(method.getParameters().size() == 1 && method.getParameters().getFirst() instanceof J.Empty);
        if (property == null || hasParameters) {
            return;
        }
        JavaType targetType = method.getReturnTypeExpression() == null ? null :
                method.getReturnTypeExpression().getType();
        info.attributeTypes.put(property, targetType);
        if (hasAnyAnnotation(method.getLeadingAnnotations(), JAKARTA_ID, JAVAX_ID)) {
            info.idProperties.add(property);
        }
        if (hasAnyAnnotation(method.getLeadingAnnotations(), JAKARTA_EMBEDDED_ID, JAVAX_EMBEDDED_ID)) {
            info.compositeIdentifier = true;
        }
    }

    private static void scanAttributedTypes(Accumulator acc, Set<JavaType> typesInUse) {
        for (JavaType type : typesInUse) {
            scanAttributedType(acc, TypeUtils.asFullyQualified(type), new HashSet<>());
        }
    }

    private static void scanAttributedType(Accumulator acc, JavaType.FullyQualified type, Set<String> seen) {
        if (type == null || !seen.add(type.getFullyQualifiedName())) {
            return;
        }

        JavaType.FullyQualified superType = type.getSupertype();
        if (superType != null) {
            scanAttributedType(acc, superType, seen);
        }

        boolean entity = hasAnyTypeAnnotation(type.getAnnotations(), JAKARTA_ENTITY, JAVAX_ENTITY);
        boolean mappedSuperclass = hasAnyTypeAnnotation(
                type.getAnnotations(), JAKARTA_MAPPED_SUPERCLASS, JAVAX_MAPPED_SUPERCLASS
        );
        if (!entity && !mappedSuperclass) {
            return;
        }

        EntityInfo info = new EntityInfo(type.getFullyQualifiedName(), type.getClassName(), entity);
        info.compositeIdentifier = hasAnyTypeAnnotation(
                type.getAnnotations(), JAKARTA_ID_CLASS, JAVAX_ID_CLASS
        );
        if (superType != null) {
            info.superType = superType.getFullyQualifiedName();
        }

        for (JavaType.Variable member : type.getMembers()) {
            info.attributeTypes.put(member.getName(), member.getType());
            if (hasAnyTypeAnnotation(member.getAnnotations(), JAKARTA_ID, JAVAX_ID)) {
                info.idProperties.add(member.getName());
            }
            if (hasAnyTypeAnnotation(member.getAnnotations(), JAKARTA_EMBEDDED_ID, JAVAX_EMBEDDED_ID)) {
                info.compositeIdentifier = true;
            }
        }
        for (JavaType.Method method : type.getMethods()) {
            String property = getterProperty(method.getName());
            if (property == null || !method.getParameterTypes().isEmpty()) {
                continue;
            }
            info.attributeTypes.put(property, method.getReturnType());
            if (hasAnyTypeAnnotation(method.getAnnotations(), JAKARTA_ID, JAVAX_ID)) {
                info.idProperties.add(property);
            }
            if (hasAnyTypeAnnotation(method.getAnnotations(), JAKARTA_EMBEDDED_ID, JAVAX_EMBEDDED_ID)) {
                info.compositeIdentifier = true;
            }
        }
        if (info.idProperties.size() > 1) {
            info.compositeIdentifier = true;
        }
        acc.types.putIfAbsent(info.fullyQualifiedName, info);
    }

    private static boolean hasAnyTypeAnnotation(List<JavaType.FullyQualified> annotations, String... names) {
        Set<String> expected = Set.of(names);
        return annotations.stream().anyMatch(annotation -> expected.contains(annotation.getFullyQualifiedName()));
    }

    private Expression rewriteQueryAttribute(Expression expression, Accumulator acc, ParameterBindings bindings,
                                               ExecutionContext ctx, String sourcePath, String querySource) {
        return switch (expression) {
            case J.Literal literal -> rewriteStaticQuery(literal, acc, bindings, ctx, sourcePath, querySource);
            case J.Binary binary -> rewriteStaticQuery(binary, acc, bindings, ctx, sourcePath, querySource);
            case J.Assignment assignment when "query".equals(assignment.getVariable().toString()) ||
                    "value".equals(assignment.getVariable().toString()) ->
                    assignment.withAssignment(rewriteStaticQuery(
                            assignment.getAssignment(), acc, bindings, ctx, sourcePath, querySource
                    ));
            default -> expression;
        };
    }

    private Expression rewriteStaticQuery(Expression expression, Accumulator acc, ParameterBindings bindings,
                                          ExecutionContext ctx, String sourcePath, String querySource) {
        StaticQuery staticQuery = staticQuery(expression);
        if (staticQuery == null) {
            return expression;
        }
        String query = staticQuery.query();
        QueryAnalysis analysis = HqlRewriter.analyze(query, acc, bindings);
        String rewritten = analysis.rewrittenQuery();
        queryAnalysis.insertRow(ctx, new HqlQueryAnalysis.Row(
                sourcePath, querySource, analysis.outcome(), analysis.reason(), query, rewritten
        ));
        if (query.equals(rewritten)) {
            return expression;
        }
        return applyInsertions(expression, staticQuery, analysis.insertions(), acc, bindings);
    }

    private static StaticQuery staticQuery(Expression expression) {
        List<LiteralSegment> segments = new ArrayList<>();
        if (!collectStringLiterals(expression, segments) || segments.isEmpty()) {
            return null;
        }
        if (segments.size() > 1 && segments.stream().anyMatch(LiteralSegment::textBlock)) {
            return null;
        }
        StringBuilder query = new StringBuilder();
        List<LiteralSegment> positioned = new ArrayList<>(segments.size());
        for (LiteralSegment segment : segments) {
            int start = query.length();
            query.append(segment.value());
            positioned.add(segment.withRange(start, query.length()));
        }
        return new StaticQuery(positioned.getFirst().id(), query.toString(), positioned);
    }

    private static boolean collectStringLiterals(Expression expression, List<LiteralSegment> segments) {
        return switch (expression) {
            case J.Literal literal when literal.getValue() instanceof String value -> {
                String valueSource = literal.getValueSource();
                segments.add(new LiteralSegment(
                        literal.getId(), value, 0, 0, valueSource != null && valueSource.startsWith("\"\"\"")
                ));
                yield true;
            }
            case J.Binary binary when binary.getOperator() == J.Binary.Type.Addition -> {
                int originalSize = segments.size();
                boolean valid = collectStringLiterals(binary.getLeft(), segments) &&
                        collectStringLiterals(binary.getRight(), segments);
                if (!valid) {
                    segments.subList(originalSize, segments.size()).clear();
                }
                yield valid;
            }
            default -> false;
        };
    }

    private static Expression applyInsertions(Expression expression, StaticQuery query, List<Insertion> insertions,
                                              Accumulator acc, ParameterBindings bindings) {
        Map<UUID, List<Insertion>> byLiteral = new HashMap<>();
        for (Insertion insertion : insertions) {
            LiteralSegment segment = query.segmentAt(insertion.offset());
            if (segment != null) {
                byLiteral.computeIfAbsent(segment.id(), unused -> new ArrayList<>())
                        .add(new Insertion(insertion.offset() - segment.start(), insertion.text()));
            }
        }
        return applyLiteralInsertions(expression, byLiteral, acc, bindings);
    }

    private static Expression applyLiteralInsertions(Expression expression, Map<UUID, List<Insertion>> insertions,
                                                     Accumulator acc, ParameterBindings bindings) {
        return switch (expression) {
            case J.Literal literal when literal.getValue() instanceof String value &&
                    insertions.containsKey(literal.getId()) -> {
                StringBuilder rewritten = new StringBuilder(value);
                insertions.get(literal.getId()).stream()
                        .sorted((left, right) -> Integer.compare(right.offset(), left.offset()))
                        .forEach(insertion -> rewritten.insert(insertion.offset(), insertion.text()));
                String newValue = rewritten.toString();
                yield literal.withValue(newValue).withValueSource(
                        renderStringLiteral(literal.getValueSource(), newValue, acc, bindings)
                );
            }
            case J.Binary binary when binary.getOperator() == J.Binary.Type.Addition -> binary
                    .withLeft(applyLiteralInsertions(binary.getLeft(), insertions, acc, bindings))
                    .withRight(applyLiteralInsertions(binary.getRight(), insertions, acc, bindings));
            default -> expression;
        };
    }

    private static String renderStringLiteral(String oldValueSource, String value, Accumulator acc,
                                              ParameterBindings bindings) {
        if (oldValueSource != null && oldValueSource.startsWith("\"\"\"")) {
            String sourceBody = oldValueSource.substring(3, oldValueSource.length() - 3);
            return "\"\"\"" + HqlRewriter.analyze(sourceBody, acc, bindings).rewrittenQuery() + "\"\"\"";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('\"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            escaped.append(switch (ch) {
                case '\\' -> "\\\\";
                case '\"' -> "\\\"";
                case '\n' -> "\\n";
                case '\r' -> "\\r";
                case '\t' -> "\\t";
                case '\b' -> "\\b";
                case '\f' -> "\\f";
                default -> String.valueOf(ch);
            });
        }
        return escaped.append('\"').toString();
    }

    private static String sourcePath(Cursor cursor) {
        J.CompilationUnit compilationUnit = cursor.firstEnclosing(J.CompilationUnit.class);
        return compilationUnit == null ? "" : compilationUnit.getSourcePath().toString();
    }

    private static boolean shouldSkipSpringQuery(J.Annotation annotation) {
        if (!"org.springframework.data.jpa.repository.Query".equals(annotationType(annotation)) ||
                annotation.getArguments() == null) {
            return false;
        }
        for (Expression argument : annotation.getArguments()) {
            if (argument instanceof J.Assignment assignment) {
                if ("nativeQuery".equals(assignment.getVariable().toString())) {
                    return !J.Literal.isLiteralValue(assignment.getAssignment(), false);
                }
            }
        }
        return false;
    }

    private static List<Expression> mapExpressions(List<Expression> expressions,
                                                   Function<Expression, Expression> mapper) {
        List<Expression> mapped = new ArrayList<>(expressions.size());
        for (Expression expression : expressions) {
            mapped.add(mapper.apply(expression));
        }
        return mapped;
    }

    private static boolean hasAnyAnnotation(List<J.Annotation> annotations, String... names) {
        return findAnyAnnotation(annotations, names) != null;
    }

    private static J.Annotation findAnyAnnotation(List<J.Annotation> annotations, String... names) {
        Set<String> expected = Set.of(names);
        for (J.Annotation annotation : annotations) {
            if (expected.contains(annotationType(annotation))) {
                return annotation;
            }
        }
        return null;
    }

    private static String annotationType(J.Annotation annotation) {
        JavaType.FullyQualified type = TypeUtils.asFullyQualified(annotation.getType());
        return type == null ? annotation.getSimpleName() : type.getFullyQualifiedName();
    }

    private static String stringAttribute(J.Annotation annotation, String name) {
        if (annotation == null || annotation.getArguments() == null) {
            return null;
        }
        for (Expression argument : annotation.getArguments()) {
            if (argument instanceof J.Assignment assignment &&
                    name.equals(assignment.getVariable().toString()) &&
                    assignment.getAssignment() instanceof J.Literal literal &&
                    literal.getValue() instanceof String value) {
                return value;
            }
        }
        return null;
    }

    private static String fullyQualifiedName(JavaType type) {
        JavaType.FullyQualified fullyQualified = TypeUtils.asFullyQualified(type);
        return fullyQualified == null ? null : fullyQualified.getFullyQualifiedName();
    }

    private static String getterProperty(String methodName) {
        String stem;
        if (methodName.startsWith("get") && methodName.length() > 3) {
            stem = methodName.substring(3);
        } else if (methodName.startsWith("is") && methodName.length() > 2) {
            stem = methodName.substring(2);
        } else {
            return null;
        }
        if (stem.length() > 1 && Character.isUpperCase(stem.charAt(0)) && Character.isUpperCase(stem.charAt(1))) {
            return stem;
        }
        return Character.toLowerCase(stem.charAt(0)) + stem.substring(1);
    }

    private static Map<UUID, ParameterBindings> analyzeImperativeBindings(J.MethodDeclaration method) {
        Map<String, UUID> variables = new HashMap<>();
        Set<String> ambiguousVariables = new HashSet<>();

        new JavaIsoVisitor<Integer>() {
            @Override
            public J.VariableDeclarations.NamedVariable visitVariable(
                    J.VariableDeclarations.NamedVariable variable, Integer ignored) {
                J.VariableDeclarations.NamedVariable v = super.visitVariable(variable, ignored);
                UUID queryId = findQueryId(v.getInitializer());
                if (queryId != null) {
                    UUID previous = variables.putIfAbsent(v.getSimpleName(), queryId);
                    if (previous != null && !previous.equals(queryId)) {
                        ambiguousVariables.add(v.getSimpleName());
                    }
                }
                return v;
            }

            @Override
            public J.Assignment visitAssignment(J.Assignment assignment, Integer ignored) {
                J.Assignment a = super.visitAssignment(assignment, ignored);
                if (a.getVariable() instanceof J.Identifier identifier) {
                    String variableName = identifier.getSimpleName();
                    if (variables.containsKey(variableName)) {
                        ambiguousVariables.add(variableName);
                    }
                }
                return a;
            }
        }.visit(method, 0);

        Map<UUID, ParameterBindings> result = new HashMap<>();
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation methodInvocation, Integer ignored) {
                J.MethodInvocation m = super.visitMethodInvocation(methodInvocation, ignored);
                if (!("setParameter".equals(m.getSimpleName()) || "setParameterList".equals(m.getSimpleName())) ||
                        m.getArguments().size() < 2) {
                    return m;
                }

                UUID queryId = null;
                UUID chainedQueryId = findQueryId(m.getSelect());
                if (chainedQueryId != null) {
                    queryId = chainedQueryId;
                } else if (m.getSelect() instanceof J.Identifier identifier) {
                    String variableName = identifier.getSimpleName();
                    if (!ambiguousVariables.contains(variableName)) {
                        queryId = variables.get(variableName);
                    }
                }

                String parameter = parameterKey(m.getArguments().get(0));
                if (queryId != null && parameter != null) {
                    ParameterBindings bindings = result.computeIfAbsent(queryId, unused -> new ParameterBindings());
                    if ("setParameterList".equals(m.getSimpleName())) {
                        bindings.addCollection(
                                parameter, m.getArguments().get(1).getType(), explicitClassType(m)
                        );
                    } else {
                        bindings.add(parameter, boundValueType(m));
                    }
                }
                return m;
            }
        }.visit(method, 0);
        return result;
    }

    private static JavaType boundValueType(J.MethodInvocation binding) {
        JavaType valueType = binding.getArguments().get(1).getType();
        if (ParameterBindings.normalizedTypeName(valueType) != null) {
            return valueType;
        }
        JavaType explicitType = explicitClassType(binding);
        return explicitType == null ? valueType : explicitType;
    }

    private static JavaType explicitClassType(J.MethodInvocation binding) {
        if (binding.getArguments().size() < 3) {
            return null;
        }
        JavaType explicitType = binding.getArguments().get(2).getType();
        if (explicitType instanceof JavaType.Parameterized parameterized &&
                "java.lang.Class".equals(parameterized.getFullyQualifiedName()) &&
                parameterized.getTypeParameters().size() == 1) {
            return parameterized.getTypeParameters().getFirst();
        }
        return null;
    }

    private static ParameterBindings springMethodBindings(Cursor cursor) {
        J.MethodDeclaration method = cursor.firstEnclosing(J.MethodDeclaration.class);
        if (method == null) {
            return ParameterBindings.empty();
        }

        ParameterBindings bindings = new ParameterBindings();
        int position = 0;
        for (Statement parameter : method.getParameters()) {
            if (!(parameter instanceof J.VariableDeclarations declarations)) {
                continue;
            }
            for (J.VariableDeclarations.NamedVariable variable : declarations.getVariables()) {
                position++;
                String name = variable.getSimpleName();
                J.Annotation param = findAnyAnnotation(
                        declarations.getLeadingAnnotations(), "org.springframework.data.repository.query.Param"
                );
                String configuredName = annotationStringValue(param);
                bindings.add(":" + (configuredName == null ? name : configuredName), variable.getType());
                bindings.add("?" + position, variable.getType());
            }
        }
        return bindings;
    }

    private static String annotationStringValue(J.Annotation annotation) {
        if (annotation == null || annotation.getArguments() == null) {
            return null;
        }
        for (Expression argument : annotation.getArguments()) {
            if (argument instanceof J.Literal literal && literal.getValue() instanceof String value) {
                return value;
            }
            if (argument instanceof J.Assignment assignment &&
                    assignment.getAssignment() instanceof J.Literal literal &&
                    literal.getValue() instanceof String value) {
                return value;
            }
        }
        return null;
    }

    private static UUID findQueryId(Expression expression) {
        if (!(expression instanceof J.MethodInvocation method)) {
            return null;
        }
        if (isQueryMethod(method) && !method.getArguments().isEmpty()) {
            StaticQuery query = staticQuery(method.getArguments().getFirst());
            return query == null ? null : query.anchorId();
        }
        return findQueryId(method.getSelect());
    }

    private static boolean isQueryMethod(J.MethodInvocation method) {
        return QUERY_METHOD_NAMES.contains(method.getSimpleName()) &&
                QUERY_METHODS.stream().anyMatch(matcher -> matcher.matches(method));
    }

    private static String parameterKey(Expression expression) {
        if (!(expression instanceof J.Literal literal)) {
            return null;
        }
        Object value = literal.getValue();
        if (value instanceof String name && !name.isBlank()) {
            return ":" + name;
        }
        if (value instanceof Number position && position.intValue() > 0) {
            return "?" + position.intValue();
        }
        return null;
    }

    static final class Accumulator {
        final Map<String, EntityInfo> types = new LinkedHashMap<>();
        final Set<String> classpathTypeNames = new HashSet<>();
        final Set<UUID> scannedSourceSets = new HashSet<>();

        EntityInfo entityByQueryName(String queryName) {
            EntityInfo match = null;
            for (EntityInfo candidate : types.values()) {
                if (!candidate.entity) {
                    continue;
                }
                boolean matches = queryName.equals(candidate.entityName) ||
                        queryName.equals(candidate.fullyQualifiedName) ||
                        queryName.equals(simpleName(candidate.fullyQualifiedName));
                if (matches) {
                    if (match != null && match != candidate) {
                        return null;
                    }
                    match = candidate;
                }
            }
            return match;
        }

        EntityInfo type(String fullyQualifiedName) {
            return fullyQualifiedName == null ? null : types.get(fullyQualifiedName);
        }

        List<String> dependencyCandidates(String queryName) {
            return classpathTypeNames.stream()
                    .filter(typeName -> queryName.equals(typeName) || queryName.equals(simpleName(typeName)))
                    .sorted()
                    .toList();
        }

        String identifier(EntityInfo entity) {
            return identifier(entity, new HashSet<>());
        }

        JavaType identifierType(EntityInfo entity) {
            return identifierType(entity, new HashSet<>());
        }

        private String identifier(EntityInfo type, Set<String> seen) {
            if (type == null || type.compositeIdentifier || !seen.add(type.fullyQualifiedName)) {
                return null;
            }
            if (type.idProperties.size() == 1) {
                return type.idProperties.iterator().next();
            }
            return identifier(type(type.superType), seen);
        }

        private JavaType identifierType(EntityInfo type, Set<String> seen) {
            if (type == null || type.compositeIdentifier || !seen.add(type.fullyQualifiedName)) {
                return null;
            }
            if (type.idProperties.size() == 1) {
                return type.attributeTypes.get(type.idProperties.iterator().next());
            }
            return identifierType(type(type.superType), seen);
        }

        private static String simpleName(String fullyQualifiedName) {
            int separator = Math.max(fullyQualifiedName.lastIndexOf('.'), fullyQualifiedName.lastIndexOf('$'));
            return separator < 0 ? fullyQualifiedName : fullyQualifiedName.substring(separator + 1);
        }
    }

    static final class EntityInfo {
        final String fullyQualifiedName;
        String entityName;
        final boolean entity;
        String superType;
        boolean compositeIdentifier;
        final Set<String> idProperties = new LinkedHashSet<>();
        final Map<String, JavaType> attributeTypes = new HashMap<>();

        EntityInfo(String fullyQualifiedName, String entityName, boolean entity) {
            this.fullyQualifiedName = fullyQualifiedName;
            this.entityName = entityName;
            this.entity = entity;
        }
    }

    private static final class ParameterBindings {
        private final Map<String, BoundType> types = new HashMap<>();
        private final Set<String> ambiguous = new HashSet<>();

        static ParameterBindings empty() {
            return new ParameterBindings();
        }

        void add(String parameter, JavaType type) {
            BoundType boundType = BoundType.from(type);
            add(parameter, boundType);
        }

        void addCollection(String parameter, JavaType collectionType, JavaType explicitElementType) {
            BoundType collection = BoundType.from(collectionType);
            String elementType = collection == null || collection.elementType() == null ?
                    normalizedTypeName(explicitElementType) : collection.elementType();
            String valueType = collection == null ? null : collection.valueType();
            add(parameter, elementType == null ? null : new BoundType(valueType, elementType));
        }

        private void add(String parameter, BoundType boundType) {
            if (boundType == null) {
                ambiguous.add(parameter);
                types.remove(parameter);
                return;
            }
            BoundType previous = types.putIfAbsent(parameter, boundType);
            if (previous != null && !previous.equals(boundType)) {
                ambiguous.add(parameter);
                types.remove(parameter);
            }
        }

        boolean matchesIdentifier(String parameter, JavaType identifierType) {
            if (ambiguous.contains(parameter)) {
                return false;
            }
            BoundType bindingType = types.get(parameter);
            String idType = normalizedTypeName(identifierType);
            return bindingType != null && bindingType.valueType().equals(idType);
        }

        boolean collectionElementsMatchIdentifier(String parameter, JavaType identifierType) {
            if (ambiguous.contains(parameter)) {
                return false;
            }
            BoundType bindingType = types.get(parameter);
            String idType = normalizedTypeName(identifierType);
            return bindingType != null && bindingType.elementType() != null && bindingType.elementType().equals(idType);
        }

        private static String normalizedTypeName(JavaType type) {
            if (type instanceof JavaType.Primitive primitive) {
                if (primitive == JavaType.Primitive.None || primitive == JavaType.Primitive.Null ||
                        primitive == JavaType.Primitive.Void) {
                    return null;
                }
                return primitive.getClassName();
            }
            if (type instanceof JavaType.GenericTypeVariable generic &&
                    generic.getVariance() != JavaType.GenericTypeVariable.Variance.CONTRAVARIANT &&
                    generic.getBounds().size() == 1) {
                return normalizedTypeName(generic.getBounds().getFirst());
            }
            JavaType.FullyQualified fullyQualified = TypeUtils.asFullyQualified(type);
            return fullyQualified == null ? null : fullyQualified.getFullyQualifiedName();
        }

        private record BoundType(String valueType, String elementType) {
            static BoundType from(JavaType type) {
                if (type instanceof JavaType.Array array) {
                    String elementType = normalizedTypeName(array.getElemType());
                    return elementType == null ? null : new BoundType(elementType + "[]", elementType);
                }
                String valueType = normalizedTypeName(type);
                if (valueType == null) {
                    return null;
                }
                if (type instanceof JavaType.Parameterized parameterized &&
                        TypeUtils.isAssignableTo("java.lang.Iterable", parameterized)) {
                    List<JavaType> parameters = parameterized.getTypeParameters();
                    String elementType = parameters.size() == 1 ? normalizedTypeName(parameters.getFirst()) : null;
                    return new BoundType(valueType, elementType);
                }
                return new BoundType(valueType, null);
            }
        }
    }

    private enum PathKind {
        ENTITY,
        SCALAR,
        UNKNOWN
    }

    private record PathResolution(PathKind kind, EntityInfo entity) {
        static PathResolution entity(EntityInfo entity) {
            return new PathResolution(PathKind.ENTITY, entity);
        }

        static PathResolution scalar() {
            return new PathResolution(PathKind.SCALAR, null);
        }

        static PathResolution unknown() {
            return new PathResolution(PathKind.UNKNOWN, null);
        }
    }

    private record Insertion(int offset, String text) {
    }

    private record LiteralSegment(UUID id, String value, int start, int end, boolean textBlock) {
        LiteralSegment withRange(int newStart, int newEnd) {
            return new LiteralSegment(id, value, newStart, newEnd, textBlock);
        }
    }

    private record StaticQuery(UUID anchorId, String query, List<LiteralSegment> segments) {
        LiteralSegment segmentAt(int offset) {
            for (LiteralSegment segment : segments) {
                if ((offset > segment.start() && offset <= segment.end()) ||
                        (offset == 0 && segment.start() == 0)) {
                    return segment;
                }
            }
            return null;
        }
    }

    private record QueryAnalysis(String rewrittenQuery, String outcome, String reason, List<Insertion> insertions) {
        QueryAnalysis(String rewrittenQuery, String outcome, String reason) {
            this(rewrittenQuery, outcome, reason, List.of());
        }
    }

    private static final class HqlRewriter {
        private HqlRewriter() {
        }

        static QueryAnalysis analyze(String hql, Accumulator acc, ParameterBindings bindings) {
            HqlLexer lexer = new HqlLexer(CharStreams.fromString(hql));
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            HqlParser parser = new HqlParser(tokens);
            SyntaxErrors errors = new SyntaxErrors();
            lexer.removeErrorListeners();
            parser.removeErrorListeners();
            lexer.addErrorListener(errors);
            parser.addErrorListener(errors);

            HqlParser.StatementContext statement;
            try {
                statement = parser.statement();
            } catch (RuntimeException ignored) {
                return new QueryAnalysis(hql, "SKIPPED", "The HQL parser threw an exception.");
            }
            if (errors.failed) {
                return new QueryAnalysis(hql, "SKIPPED", "Invalid HQL: " + errors.firstMessage);
            }

            UnsupportedStructureDetector unsupported = new UnsupportedStructureDetector();
            ParseTreeWalker.DEFAULT.walk(unsupported, statement);
            if (unsupported.found) {
                return new QueryAnalysis(
                        hql, "SKIPPED", "Subqueries and set operations are skipped because alias scopes are ambiguous."
                );
            }

            Map<String, EntityInfo> aliases = new HashMap<>();
            Set<String> reasons = new LinkedHashSet<>();
            ParseTreeWalker.DEFAULT.walk(new AliasCollector(acc, aliases, reasons), statement);
            List<Insertion> insertions = new ArrayList<>();
            ParseTreeWalker.DEFAULT.walk(
                    new ComparisonCollector(acc, aliases, bindings, hql, insertions, reasons), statement
            );
            if (insertions.isEmpty()) {
                if (reasons.isEmpty()) {
                    reasons.add(aliases.isEmpty() ?
                            "No entity aliases could be resolved from scanned source or referenced dependency types." :
                            "No entity-valued equality or IN comparison requiring an identifier was found.");
                }
                return new QueryAnalysis(hql, "UNCHANGED", String.join(" ", reasons));
            }

            insertions.sort((left, right) -> Integer.compare(right.offset(), left.offset()));
            StringBuilder rewritten = new StringBuilder(hql);
            int previousOffset = -1;
            for (Insertion insertion : insertions) {
                if (insertion.offset() != previousOffset) {
                    rewritten.insert(insertion.offset(), insertion.text());
                    previousOffset = insertion.offset();
                }
            }
            return new QueryAnalysis(
                    rewritten.toString(), "CHANGED",
                    "Added an explicit identifier to " + insertions.size() + " entity-valued operand(s).",
                    List.copyOf(insertions)
            );
        }
    }

    /**
     * Alias scopes in subqueries and set operands must be modeled independently. Until that model is needed,
     * leaving these queries unchanged is safer than applying a potentially incorrect outer-scope rewrite.
     */
    private static final class UnsupportedStructureDetector extends HqlParserBaseListener {
        boolean found;

        @Override
        public void enterSubquery(HqlParser.SubqueryContext ctx) {
            found = true;
        }

        @Override
        public void enterSetOperator(HqlParser.SetOperatorContext ctx) {
            found = true;
        }
    }

    private static final class AliasCollector extends HqlParserBaseListener {
        private final Accumulator acc;
        private final Map<String, EntityInfo> aliases;
        private final Set<String> reasons;

        private AliasCollector(Accumulator acc, Map<String, EntityInfo> aliases, Set<String> reasons) {
            this.acc = acc;
            this.aliases = aliases;
            this.reasons = reasons;
        }

        @Override
        public void enterRootEntity(HqlParser.RootEntityContext ctx) {
            EntityInfo entity = acc.entityByQueryName(ctx.entityName().getText());
            if (entity != null) {
                String alias = ctx.variable() == null ? Accumulator.simpleName(ctx.entityName().getText()) :
                        variableName(ctx.variable());
                aliases.put(alias.toLowerCase(Locale.ROOT), entity);
            } else {
                String queryName = ctx.entityName().getText();
                List<String> dependencyCandidates = acc.dependencyCandidates(queryName);
                if (dependencyCandidates.isEmpty()) {
                    reasons.add("Entity '" + queryName +
                            "' was not found in scanned source or referenced dependency types.");
                } else {
                    reasons.add("Entity '" + queryName + "' matches dependency type(s) " +
                            String.join(", ", dependencyCandidates) +
                            ", but annotation and identifier metadata is unavailable because none is referenced " +
                            "as a Java type in the scanned module.");
                }
            }
        }

        @Override
        public void enterJoin(HqlParser.JoinContext ctx) {
            if (!(ctx.joinTarget() instanceof HqlParser.JoinPathContext join)) {
                return;
            }
            if (join.variable() == null) {
                return;
            }
            PathResolution joined = resolvePath(join.path().getText(), aliases, acc);
            if (joined.kind() == PathKind.ENTITY) {
                aliases.put(variableName(join.variable()).toLowerCase(Locale.ROOT), joined.entity());
                return;
            }
            EntityInfo joinedEntity = acc.entityByQueryName(join.path().getText());
            if (joinedEntity != null) {
                aliases.put(variableName(join.variable()).toLowerCase(Locale.ROOT), joinedEntity);
            }
        }

        private static String variableName(HqlParser.VariableContext variable) {
            return variable.identifier() != null ? variable.identifier().getText() : variable.nakedIdentifier().getText();
        }
    }

    private static final class ComparisonCollector extends HqlParserBaseListener {
        private final Accumulator acc;
        private final Map<String, EntityInfo> aliases;
        private final ParameterBindings bindings;
        private final String hql;
        private final List<Insertion> insertions;
        private final Set<String> reasons;

        private ComparisonCollector(Accumulator acc, Map<String, EntityInfo> aliases, ParameterBindings bindings, String hql,
                                    List<Insertion> insertions, Set<String> reasons) {
            this.acc = acc;
            this.aliases = aliases;
            this.bindings = bindings;
            this.hql = hql;
            this.insertions = insertions;
            this.reasons = reasons;
        }

        @Override
        public void enterComparisonPredicate(HqlParser.ComparisonPredicateContext ctx) {
            HqlParser.ExpressionContext leftExpression = ctx.expression(0);
            HqlParser.ExpressionContext rightExpression = ctx.expression(1);
            if (leftExpression == null || rightExpression == null || !isEqualityOperator(leftExpression, rightExpression)) {
                return;
            }

            String left = source(leftExpression);
            String right = source(rightExpression);
            PathResolution leftPath = resolvePath(left, aliases, acc);
            PathResolution rightPath = resolvePath(right, aliases, acc);

            if (leftPath.kind() == PathKind.ENTITY) {
                if (isScalar(right, rightPath, leftPath.entity(), bindings, acc)) {
                    addIdentifier(leftExpression, leftPath.entity());
                } else {
                    reasons.add(nonScalarReason(right, rightPath));
                }
            }
            if (rightPath.kind() == PathKind.ENTITY) {
                if (isScalar(left, leftPath, rightPath.entity(), bindings, acc)) {
                    addIdentifier(rightExpression, rightPath.entity());
                } else {
                    reasons.add(nonScalarReason(left, leftPath));
                }
            }
        }

        @Override
        public void enterInPredicate(HqlParser.InPredicateContext ctx) {
            HqlParser.ExpressionContext testedExpression = ctx.expression();
            if (testedExpression == null) {
                return;
            }
            String tested = source(testedExpression);
            PathResolution testedPath = resolvePath(tested, aliases, acc);
            if (testedPath.kind() != PathKind.ENTITY) {
                return;
            }
            if (!inListMatchesIdentifier(ctx.inList(), testedPath.entity())) {
                reasons.add("The IN values are not proven to match the entity identifier type.");
                return;
            }
            addIdentifier(testedExpression, testedPath.entity());
        }

        private boolean inListMatchesIdentifier(HqlParser.InListContext inList, EntityInfo entity) {
            JavaType identifierType = acc.identifierType(entity);
            if (identifierType == null) {
                return false;
            }
            if (inList instanceof HqlParser.ParamInListContext parameterList) {
                String parameter = parameterList.parameter().getText();
                return bindings.collectionElementsMatchIdentifier(parameter, identifierType);
            }
            if (!(inList instanceof HqlParser.ExplicitTupleInListContext tuple)) {
                return false;
            }

            List<HqlParser.ExpressionOrPredicateContext> items = tuple.expressionOrPredicate();
            if (items.isEmpty()) {
                return false;
            }
            boolean singleton = items.size() == 1;
            for (HqlParser.ExpressionOrPredicateContext item : items) {
                String value = source(item);
                if (PARAMETER.matcher(value).matches()) {
                    boolean compatible = bindings.matchesIdentifier(value, identifierType) ||
                            (singleton && bindings.collectionElementsMatchIdentifier(value, identifierType));
                    if (!compatible) {
                        return false;
                    }
                } else {
                    PathResolution valuePath = resolvePath(value, aliases, acc);
                    if (!isScalar(value, valuePath, entity, bindings, acc)) {
                        return false;
                    }
                }
            }
            return true;
        }

        private boolean isEqualityOperator(HqlParser.ExpressionContext left, HqlParser.ExpressionContext right) {
            int start = left.getStop().getStopIndex() + 1;
            int end = right.getStart().getStartIndex();
            if (start < 0 || end < start || end > hql.length()) {
                return false;
            }
            String operator = hql.substring(start, end).replaceAll("\\s+", "");
            return "=".equals(operator) || "!=".equals(operator) || "<>".equals(operator);
        }

        private void addIdentifier(HqlParser.ExpressionContext expression, EntityInfo entity) {
            String id = acc.identifier(entity);
            if (id != null) {
                insertions.add(new Insertion(expression.getStop().getStopIndex() + 1, "." + id));
            } else {
                reasons.add("Entity '" + entity.entityName + "' does not have one resolvable identifier property.");
            }
        }

        private String nonScalarReason(String value, PathResolution path) {
            if ("null".equalsIgnoreCase(value.trim())) {
                return "Entity comparisons with null are valid and are left unchanged.";
            }
            if (PARAMETER.matcher(value.trim()).matches()) {
                return "Parameter '" + value.trim() +
                       "' is unbound, ambiguous, or does not match the entity identifier type.";
            }
            if (path.kind() == PathKind.ENTITY) {
                return "Entity-to-entity comparisons are valid and are left unchanged.";
            }
            return "The opposite operand '" + value.trim() + "' is not proven to be an identifier value.";
        }

        private String source(org.antlr.v4.runtime.ParserRuleContext context) {
            int start = context.getStart().getStartIndex();
            int end = context.getStop().getStopIndex() + 1;
            return start < 0 || end < start || end > hql.length() ? context.getText() : hql.substring(start, end).trim();
        }
    }

    private static boolean isScalar(String expression, PathResolution path, EntityInfo comparedEntity,
                                    ParameterBindings bindings, Accumulator acc) {
        String candidate = expression.trim();
        if (PARAMETER.matcher(candidate).matches()) {
            return bindings.matchesIdentifier(candidate, acc.identifierType(comparedEntity));
        }
        return !"null".equalsIgnoreCase(candidate) &&
                (path.kind() == PathKind.SCALAR || NUMBER.matcher(candidate).matches() || isQuoted(candidate) ||
                 "true".equalsIgnoreCase(candidate) || "false".equalsIgnoreCase(candidate));
    }

    private static boolean isQuoted(String value) {
        return value.length() >= 2 && value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\'';
    }

    private static PathResolution resolvePath(String path, Map<String, EntityInfo> aliases, Accumulator acc) {
        String candidate = path.trim();
        if (!SIMPLE_PATH.matcher(candidate).matches()) {
            return PathResolution.unknown();
        }
        String[] elements = candidate.split("\\.");
        EntityInfo current = aliases.get(elements[0].toLowerCase(Locale.ROOT));
        if (current == null) {
            return PathResolution.unknown();
        }
        if (elements.length == 1) {
            return PathResolution.entity(current);
        }
        for (int i = 1; i < elements.length; i++) {
            JavaType targetType = attributeType(current, elements[i], acc, new HashSet<>());
            if (targetType == null) {
                return PathResolution.unknown();
            }
            EntityInfo target = acc.type(fullyQualifiedName(targetType));
            if (target == null || !target.entity) {
                return i == elements.length - 1 ? PathResolution.scalar() : PathResolution.unknown();
            }
            current = target;
        }
        return PathResolution.entity(current);
    }

    private static JavaType attributeType(EntityInfo type, String attribute, Accumulator acc, Set<String> seen) {
        if (type == null || !seen.add(type.fullyQualifiedName)) {
            return null;
        }
        if (type.attributeTypes.containsKey(attribute)) {
            return type.attributeTypes.get(attribute);
        }
        return attributeType(acc.type(type.superType), attribute, acc, seen);
    }

    private static final class SyntaxErrors extends BaseErrorListener {
        boolean failed;
        String firstMessage = "unknown syntax error";

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                                int charPositionInLine, String msg, RecognitionException e) {
            failed = true;
            if ("unknown syntax error".equals(firstMessage)) {
                firstMessage = "line " + line + ":" + charPositionInLine + " " + msg;
            }
        }
    }
}
