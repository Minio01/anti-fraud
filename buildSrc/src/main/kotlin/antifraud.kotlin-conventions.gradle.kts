plugins {
    id("antifraud.jvm-conventions")
    id("org.jetbrains.kotlin.jvm")
}

val libs = the<VersionCatalogsExtension>().named("libs")

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

dependencies {
    testImplementation(libs.findLibrary("kotlin-test-junit5").get())
}
