plugins {
    kotlin("jvm") version "1.9.0"
    kotlin("kapt") version "1.9.0"
    id("com.github.johnrengelman.shadow") version "8.0.0"
    application
}

@Suppress("PropertyName")
val jackson_version: String by project
@Suppress("PropertyName")
val ktor_version: String by project

group = "pw.dipix.midnight"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    listOf("jackson-core", "jackson-annotations", "jackson-databind").forEach { implementation("com.fasterxml.jackson.core:$it:$jackson_version") }
    listOf("toml", "yaml").forEach { implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-$it:$jackson_version") }
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jackson_version")
    implementation("info.picocli:picocli:4.7.5")
    implementation("com.github.ajalt.mordant:mordant:2.2.0")
    implementation("io.ktor:ktor-client-core:$ktor_version")
    implementation("io.ktor:ktor-client-cio:$ktor_version")
    implementation("io.ktor:ktor-client-content-negotiation:$ktor_version")
    implementation("io.ktor:ktor-serialization-jackson:$ktor_version")

    testImplementation(kotlin("test"))

    kapt("info.picocli:picocli-codegen:4.7.5")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

kapt {
    arguments {
        arg("project", "${project.group}/${project.name}")
    }
}

application {
    mainClass.set("pw.dipix.midnight.MainKt")
    tasks.run.get().workingDir = File("run").apply { mkdirs() }
}