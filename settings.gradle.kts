plugins {
    // Автоматически скачивает JDK 25, если он не найден локально
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "anti-fraud"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(
    "proto",
    "scoring-service",
    "transaction-mock",
    "live-monitor",
    "persistence-worker",
    "profile-worker",
)
