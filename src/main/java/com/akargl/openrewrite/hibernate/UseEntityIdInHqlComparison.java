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
                return a.withArguments(mapExpressions(
                        arguments, expression -> rewriteQueryAttribute(expression, acc, bindings)
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
                if (arguments.getFirst() instanceof J.Literal literal && byQuery != null) {
                    bindings = byQuery.getOrDefault(literal.getId(), bindings);
                }
                arguments.set(0, rewriteLiteral(arguments.getFirst(), acc, bindings));
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

    private static Expression rewriteQueryAttribute(Expression expression, Accumulator acc, ParameterBindings bindings) {
        return switch (expression) {
            case J.Literal literal -> rewriteLiteral(literal, acc, bindings);
            case J.Assignment assignment when "query".equals(assignment.getVariable().toString()) ||
                    "value".equals(assignment.getVariable().toString()) ->
                    assignment.withAssignment(rewriteLiteral(assignment.getAssignment(), acc, bindings));
            default -> expression;
        };
    }

    private static Expression rewriteLiteral(Expression expression, Accumulator acc, ParameterBindings bindings) {
        if (!(expression instanceof J.Literal literal) || !(literal.getValue() instanceof String query)) {
            return expression;
        }
        String rewritten = HqlRewriter.rewrite(query, acc, bindings);
        if (query.equals(rewritten)) {
            return literal;
        }
        return literal.withValue(rewritten).withValueSource(
                renderStringLiteral(literal.getValueSource(), rewritten, acc, bindings)
        );
    }

    private static String renderStringLiteral(String oldValueSource, String value, Accumulator acc,
                                              ParameterBindings bindings) {
        if (oldValueSource != null && oldValueSource.startsWith("\"\"\"")) {
            String sourceBody = oldValueSource.substring(3, oldValueSource.length() - 3);
            return "\"\"\"" + HqlRewriter.rewrite(sourceBody, acc, bindings) + "\"\"\"";
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
                J.Literal query = findQueryLiteral(v.getInitializer());
                if (query != null) {
                    UUID previous = variables.putIfAbsent(v.getSimpleName(), query.getId());
                    if (previous != null && !previous.equals(query.getId())) {
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
                J.Literal chainedQuery = findQueryLiteral(m.getSelect());
                if (chainedQuery != null) {
                    queryId = chainedQuery.getId();
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

    private static J.Literal findQueryLiteral(Expression expression) {
        if (!(expression instanceof J.MethodInvocation method)) {
            return null;
        }
        if (isQueryMethod(method) && !method.getArguments().isEmpty() &&
                method.getArguments().getFirst() instanceof J.Literal literal) {
            return literal.getValue() instanceof String ? literal : null;
        }
        return findQueryLiteral(method.getSelect());
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

    private static final class HqlRewriter {
        private HqlRewriter() {
        }

        static String rewrite(String hql, Accumulator acc, ParameterBindings bindings) {
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
                return hql;
            }
            if (errors.failed) {
                return hql;
            }

            UnsupportedStructureDetector unsupported = new UnsupportedStructureDetector();
            ParseTreeWalker.DEFAULT.walk(unsupported, statement);
            if (unsupported.found) {
                return hql;
            }

            Map<String, EntityInfo> aliases = new HashMap<>();
            ParseTreeWalker.DEFAULT.walk(new AliasCollector(acc, aliases), statement);
            List<Insertion> insertions = new ArrayList<>();
            ParseTreeWalker.DEFAULT.walk(new ComparisonCollector(acc, aliases, bindings, hql, insertions), statement);
            if (insertions.isEmpty()) {
                return hql;
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
            return rewritten.toString();
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

        private AliasCollector(Accumulator acc, Map<String, EntityInfo> aliases) {
            this.acc = acc;
            this.aliases = aliases;
        }

        @Override
        public void enterRootEntity(HqlParser.RootEntityContext ctx) {
            EntityInfo entity = acc.entityByQueryName(ctx.entityName().getText());
            if (entity != null) {
                String alias = ctx.variable() == null ? Accumulator.simpleName(ctx.entityName().getText()) :
                        variableName(ctx.variable());
                aliases.put(alias.toLowerCase(Locale.ROOT), entity);
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

        private ComparisonCollector(Accumulator acc, Map<String, EntityInfo> aliases, ParameterBindings bindings, String hql,
                                    List<Insertion> insertions) {
            this.acc = acc;
            this.aliases = aliases;
            this.bindings = bindings;
            this.hql = hql;
            this.insertions = insertions;
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

            if (leftPath.kind() == PathKind.ENTITY && isScalar(right, rightPath, leftPath.entity(), bindings, acc)) {
                addIdentifier(leftExpression, leftPath.entity());
            }
            if (rightPath.kind() == PathKind.ENTITY && isScalar(left, leftPath, rightPath.entity(), bindings, acc)) {
                addIdentifier(rightExpression, rightPath.entity());
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
            if (testedPath.kind() != PathKind.ENTITY || !inListMatchesIdentifier(ctx.inList(), testedPath.entity())) {
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
            }
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

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                                int charPositionInLine, String msg, RecognitionException e) {
            failed = true;
        }
    }
}
