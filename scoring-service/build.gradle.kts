// Online-скоринг транзакций: gRPC, Redis-фичи, правила, ONNX (SPEC.md §5.1).
plugins {
    id("antifraud.spring-service")
}

dependencies {
    implementation(project(":proto"))
    implementation(libs.protobuf.java.util)
    implementation(libs.spring.boot.starter.grpc.server)
    implementation(libs.spring.boot.starter.data.redis)
    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.testcontainers.junit)
}
