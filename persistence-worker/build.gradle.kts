// Kafka → PostgreSQL, API аудита и качества (SPEC.md §5.3).
plugins {
    id("antifraud.spring-service")
}

dependencies {
    implementation(project(":proto"))
}
