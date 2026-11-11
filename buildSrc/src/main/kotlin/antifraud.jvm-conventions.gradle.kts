import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
}

group = "com.antifraud"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
