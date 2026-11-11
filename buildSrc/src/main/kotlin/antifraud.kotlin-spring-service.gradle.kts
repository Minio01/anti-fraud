plugins {
    id("antifraud.spring-service")
    id("antifraud.kotlin-conventions")
    id("org.jetbrains.kotlin.plugin.spring")
}

val libs = the<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()

dependencies {
    implementation(lib("kotlin-reflect"))
    implementation(lib("kotlinx-coroutines-reactor"))
    implementation(lib("jackson-module-kotlin"))
    testImplementation(lib("kotlinx-coroutines-test"))
}
