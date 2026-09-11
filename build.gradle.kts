plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
    id("io.ktor.plugin") version "3.5.2"
}

group = "moe.reimu"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    google()
}

application {
    mainClass.set("moe.reimu.jadxsrv.MainKt")
}

dependencies {
    val jadxVersion = "1.5.6"
    implementation("io.github.skylot:jadx-core:$jadxVersion")
    implementation("io.github.skylot:jadx-dex-input:$jadxVersion")
    implementation("io.github.skylot:jadx-java-input:$jadxVersion")
    implementation("io.github.skylot:jadx-kotlin-metadata:$jadxVersion")

    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-call-logging")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    implementation("ch.qos.logback:logback-classic:1.5.13")
    implementation("org.slf4j:slf4j-api:2.0.16")

    implementation("com.github.ajalt.clikt:clikt:5.0.1")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(11)
}