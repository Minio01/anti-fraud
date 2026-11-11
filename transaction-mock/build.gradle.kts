// Генератор транзакций с фрод-паттернами и REST-управлением (SPEC.md §5.2).
plugins {
    id("antifraud.kotlin-spring-service")
}

dependencies {
    implementation(project(":proto"))
    implementation(libs.spring.boot.starter.grpc.client)
}
