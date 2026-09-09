# OpenRewrite Hibernate migration recipes

Recipes for recurring source changes encountered when migrating from Hibernate 5 to Hibernate 6 and from Spring Boot 2 to Spring Boot 3.

## Use entity identifiers in HQL comparisons

`com.akargl.openrewrite.hibernate.UseEntityIdInHqlComparison` changes comparisons between an entity-valued HQL expression and a scalar identifier so that the identifier property is explicit.

```diff
-select a from MyEntity a where a = 123
+select a from MyEntity a where a.id = 123
```

The recipe discovers `@Entity`, `@MappedSuperclass`, `@Id`, and entity association metadata from Java source. It supports Java string literals and text blocks used by JPA/Hibernate query creation methods, JPA/Hibernate `@NamedQuery`, and Spring Data JPA `@Query`.

HQL parameters are not assumed to be identifiers. A parameter comparison is changed only when the recipe can prove that the Java value bound through `setParameter(...)`, or declared by a Spring Data repository method, has the same type as the entity identifier. Explicit Hibernate `Class` arguments are used to type otherwise untyped `null` bindings. Unbound parameters, entity-typed parameters, conflicting bindings, and parameterized named queries without an analyzable binding are left unchanged.

`IN` and `NOT IN` predicates are supported for compatible generic collections (including covariant bounds), arrays, literal lists, individually bound scalar parameters, and Hibernate `setParameterList(...)` bindings. An explicit element class on `setParameterList(...)` can make a raw collection safe to analyze; raw collections without that information, unknown element types, and collections of entities are left unchanged.

To avoid unsafe changes, it skips native SQL, dynamic string construction, ambiguous entity names, malformed HQL, unresolved paths, composite identifiers, and comparisons with `null`.

## Build and publish locally

The project requires JDK 21. Run the tests and publish the recipe JAR to the local Maven repository:

```shell
./gradlew clean test
./gradlew publishToMavenLocal
```

The published artifact coordinates are:

```text
com.akargl.openrewrite:openrewrite-hibernate-migration:0.1.0-SNAPSHOT
```

The aggregate recipe intended for normal use is:

```text
com.akargl.openrewrite.hibernate.MigrateHibernate5To6Queries
```

To run only the entity-ID recipe, use:

```text
com.akargl.openrewrite.hibernate.UseEntityIdInHqlComparison
```

The credential-free examples below use OpenRewrite Gradle plugin `7.41.0` from the Gradle Plugin Portal and Maven plugin `6.46.1` from Maven Central. These pinned public versions avoid requiring a Code Genome Project account.

## Run from a Gradle build

Add the following to the target project's `build.gradle.kts`. Gradle uses the public Plugin Portal for the plugin and the local Maven repository for the recipe:

```kotlin
plugins {
    id("org.openrewrite.rewrite") version "7.41.0"
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    rewrite("com.akargl.openrewrite:openrewrite-hibernate-migration:0.1.0-SNAPSHOT")
}

rewrite {
    activeRecipe("com.akargl.openrewrite.hibernate.MigrateHibernate5To6Queries")
}
```

Preview the changes before applying them:

```shell
./gradlew rewriteDryRun
./gradlew rewriteRun
git diff
```

The dry-run patch is written to `build/reports/rewrite/rewrite.patch`.

## Run from a Maven build

Add the OpenRewrite plugin to the target project's `pom.xml`:

```xml
<plugin>
    <groupId>org.openrewrite.maven</groupId>
    <artifactId>rewrite-maven-plugin</artifactId>
    <version>6.46.1</version>
    <configuration>
        <activeRecipes>
            <recipe>com.akargl.openrewrite.hibernate.MigrateHibernate5To6Queries</recipe>
        </activeRecipes>
    </configuration>
    <dependencies>
        <dependency>
            <groupId>com.akargl.openrewrite</groupId>
            <artifactId>openrewrite-hibernate-migration</artifactId>
            <version>0.1.0-SNAPSHOT</version>
        </dependency>
    </dependencies>
</plugin>
```

The plugin is resolved from Maven Central and the recipe from the local Maven repository, so no additional Maven repository or credentials are required. Preview and apply the recipe:

```shell
mvn rewrite:dryRun
mvn rewrite:run
git diff
```

## Run standalone without changing the target build

The recipe must still be published locally first with `./gradlew publishToMavenLocal`, but the target project's `pom.xml` or `build.gradle(.kts)` does not need to be edited.

### Standalone Gradle invocation

Create an `init.gradle.kts` file outside the target repository so it can be reused:

```kotlin
initscript {
    repositories {
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.openrewrite.rewrite:org.openrewrite.rewrite.gradle.plugin:7.41.0")
    }
}

rootProject {
    plugins.apply(org.openrewrite.gradle.RewritePlugin::class.java)

    dependencies {
        add(
            "rewrite",
            "com.akargl.openrewrite:openrewrite-hibernate-migration:0.1.0-SNAPSHOT"
        )
    }

    afterEvaluate {
        repositories {
            mavenLocal()
            mavenCentral()
        }
    }
}
```

From the target Gradle project, point its wrapper at the init script and select the recipe on the command line:

```shell
./gradlew --init-script /absolute/path/to/init.gradle.kts rewriteDryRun \
  -Drewrite.activeRecipe=com.akargl.openrewrite.hibernate.MigrateHibernate5To6Queries

./gradlew --init-script /absolute/path/to/init.gradle.kts rewriteRun \
  -Drewrite.activeRecipe=com.akargl.openrewrite.hibernate.MigrateHibernate5To6Queries
```

Do not combine this init-script setup with an existing `rewrite { ... }` block in the target build. See OpenRewrite's [standalone Gradle guide](https://docs.openrewrite.org/running-recipes/running-rewrite-on-a-gradle-project-without-modifying-the-build).

### Standalone Maven invocation

Maven resolves plugin `6.46.1` from Maven Central and the recipe from the local Maven repository. No `settings.xml` changes or credentials are required. From the target Maven project, preview the recipe:

```shell
mvn -U org.openrewrite.maven:rewrite-maven-plugin:6.46.1:dryRun \
  -Drewrite.recipeArtifactCoordinates=com.akargl.openrewrite:openrewrite-hibernate-migration:0.1.0-SNAPSHOT \
  -Drewrite.activeRecipes=com.akargl.openrewrite.hibernate.MigrateHibernate5To6Queries
```

Apply it:

```shell
mvn -U org.openrewrite.maven:rewrite-maven-plugin:6.46.1:run \
  -Drewrite.recipeArtifactCoordinates=com.akargl.openrewrite:openrewrite-hibernate-migration:0.1.0-SNAPSHOT \
  -Drewrite.activeRecipes=com.akargl.openrewrite.hibernate.MigrateHibernate5To6Queries
```

Always review the generated patch or `git diff` before committing the changes.

## Optional: use newer Code Genome releases

OpenRewrite is moving newer releases to the authenticated Code Genome Project repository. Use it only when a required plugin or OpenRewrite module is no longer available from the Gradle Plugin Portal or Maven Central, or when an organization already mirrors Code Genome internally. In that case, follow OpenRewrite's [repository and credential setup](https://docs.openrewrite.org/running-recipes/getting-started) and update the pinned plugin version deliberately.
