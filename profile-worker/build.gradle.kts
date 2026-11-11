// Kafka → медленный профиль пользователя в Redis (SPEC.md §5.4).
plugins {
    id("antifraud.kotlin-spring-service")
}

dependencies {
    implementation(project(":proto"))
}
