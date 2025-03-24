plugins {
    application
    kotlin("jvm") version "2.1.20"
    id("com.gradleup.shadow") version "9.0.0-beta11"
}

group = "me.gulya.gradle"
version = "1.0-SNAPSHOT"

application {
    mainClass = "MainKt"
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("org.gradle:gradle-tooling-api:8.13")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")

//    implementation("io.modelcontextprotocol:kotlin-sdk:0.3.0")
    implementation("com.github.wiremock-inc:anthropic-mcp-kotlin-sdk:877016f6cf")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.add("-Xdebug")
    }
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}
