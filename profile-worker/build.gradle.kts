// Kafka → медленный профиль пользователя в Redis (SPEC.md §5.4).
plugins {
    id("antifraud.kotlin-spring-service")
}

dependencies {
    implementation(project(":proto"))
    implementation(project(":features-core"))
    implementation(libs.spring.boot.starter.kafka)
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    testImplementation(libs.testcontainers.junit)
}
