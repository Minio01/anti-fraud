// Kafka → PostgreSQL, API аудита и качества (SPEC.md §5.3).
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    id("antifraud.spring-service")
}

val jooqCodegen by sourceSets.creating
val migrationDirectory = layout.projectDirectory.dir("src/main/resources/db/migration")
val generatedJooq = layout.buildDirectory.dir("generated/sources/jooq")

dependencies {
    implementation(project(":proto"))

    add(jooqCodegen.implementationConfigurationName, platform(SpringBootPlugin.BOM_COORDINATES))
    add(jooqCodegen.implementationConfigurationName, "org.jooq:jooq-codegen")
    add(jooqCodegen.implementationConfigurationName, "org.flywaydb:flyway-core")
    add(jooqCodegen.implementationConfigurationName, "org.testcontainers:testcontainers-postgresql")
    add(jooqCodegen.runtimeOnlyConfigurationName, "org.flywaydb:flyway-database-postgresql")
    add(jooqCodegen.runtimeOnlyConfigurationName, "org.postgresql:postgresql")
}

tasks.register<JavaExec>("generateJooq") {
    group = "build"
    description = "Applies Flyway migrations to a temporary PostgreSQL database and generates jOOQ types."
    classpath = jooqCodegen.runtimeClasspath
    mainClass = "com.antifraud.persistence.codegen.GenerateJooq"
    args(migrationDirectory.asFile.absolutePath, generatedJooq.get().asFile.absolutePath)
    inputs.dir(migrationDirectory).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(generatedJooq)
}
