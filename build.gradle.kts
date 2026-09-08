plugins {
    `java-library`
    `maven-publish`
}

group = "com.akargl.openrewrite"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
}

dependencies {
    api("org.openrewrite:rewrite-java:8.81.0")
    implementation("org.hibernate.orm:hibernate-core:6.6.18.Final")
    implementation("org.antlr:antlr4-runtime:4.13.0")

    testImplementation("org.openrewrite:rewrite-test:8.81.0")
    testRuntimeOnly("org.openrewrite:rewrite-java-21:8.81.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("jakarta.persistence:jakarta.persistence-api:3.1.0")
    testImplementation("javax.persistence:javax.persistence-api:2.2")
    testImplementation("org.springframework.data:spring-data-jpa:3.4.5")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
