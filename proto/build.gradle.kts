// Контракты: gRPC-сервис скоринга и Protobuf-сообщения для Kafka.
// Из .proto генерируются Java-классы, Kotlin DSL-билдеры и корутинные gRPC-стабы.
plugins {
    id("antifraud.kotlin-conventions")
    id("com.google.protobuf")
}

val versions = the<VersionCatalogsExtension>().named("libs")
fun version(alias: String) = versions.findVersion(alias).get().requiredVersion

dependencies {
    api(libs.protobuf.kotlin)
    api(libs.grpc.protobuf)
    api(libs.grpc.stub)
    api(libs.grpc.kotlin.stub)
    api(libs.kotlinx.coroutines.core)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${version("protobuf")}"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:${version("grpc")}"
        }
        create("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:${version("grpcKotlin")}:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc")
                create("grpckt")
            }
            task.builtins {
                create("kotlin")
            }
        }
    }
}
