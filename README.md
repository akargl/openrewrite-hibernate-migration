# OpenRewrite Hibernate migration recipes

Recipes for recurring source changes encountered when migrating from Hibernate 5 to Hibernate 6 and from Spring Boot 2 to Spring Boot 3.

## Use entity identifiers in HQL comparisons

`org.openrewrite.contrib.hibernate.UseEntityIdInHqlComparison` changes comparisons between an entity-valued HQL expression and a scalar identifier so that the identifier property is explicit.

```diff
-select a from MyEntity a where a = 123
+select a from MyEntity a where a.id = 123
```

The recipe discovers `@Entity`, `@MappedSuperclass`, `@Id`, and entity association metadata from Java source. It supports Java string literals and text blocks used by JPA/Hibernate query creation methods, JPA/Hibernate `@NamedQuery`, and Spring Data JPA `@Query`.

HQL parameters are not assumed to be identifiers. A parameter comparison is changed only when the recipe can prove that the Java value bound through `setParameter(...)`, or declared by a Spring Data repository method, has the same type as the entity identifier. Unbound parameters, entity-typed parameters, conflicting bindings, and parameterized named queries without an analyzable binding are left unchanged.

`IN` and `NOT IN` predicates are supported for compatible generic collections, arrays, literal lists, individually bound scalar parameters, and Hibernate `setParameterList(...)` bindings. Raw collections, unknown element types, and collections of entities are left unchanged.

To avoid unsafe changes, it skips native SQL, dynamic string construction, ambiguous entity names, malformed HQL, unresolved paths, and composite identifiers.

Run the tests with:

```shell
./gradlew test
```
