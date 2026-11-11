plugins {
    id("antifraud.jvm-conventions")
    id("org.springframework.boot")
}

val libs = the<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()

dependencies {
    implementation(lib("spring-boot-starter-webflux"))
    implementation(lib("spring-boot-starter-actuator"))
    implementation(lib("micrometer-prometheus"))

    testImplementation(lib("spring-boot-starter-test"))
}
