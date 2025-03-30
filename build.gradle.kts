plugins {
    application
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
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

//    implementation("io.modelcontextprotocol:kotlin-sdk:0.4.0")
    implementation("com.github.wiremock-inc:anthropic-mcp-kotlin-sdk:877016f6cf")
}

kotlin.compilerOptions.freeCompilerArgs.addAll(
    "-Xdebug",
    "-Xmulti-dollar-interpolation",
)

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}
