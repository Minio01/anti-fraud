// Online-скоринг транзакций: gRPC, Redis-фичи, правила, ONNX (SPEC.md §5.1).
plugins {
    id("antifraud.spring-service")
}

dependencies {
    implementation(project(":proto"))
    implementation(project(":features-core"))
    implementation(libs.protobuf.java.util)
    implementation(libs.spring.boot.starter.grpc.server)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.resilience4j.circuitbreaker)
    implementation(libs.onnxruntime)
    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.testcontainers.junit)
}
