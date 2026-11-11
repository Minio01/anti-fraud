// Online-скоринг транзакций: gRPC, Redis-фичи, правила, ONNX (SPEC.md §5.1).
plugins {
    id("antifraud.spring-service")
}

dependencies {
    implementation(project(":proto"))
    implementation(libs.spring.boot.starter.grpc.server)
}
