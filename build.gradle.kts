import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.23"
    idea
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.statewidesoftware"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.2")
    implementation("io.javalin:javalin:6.1.3")
    // get the javalin test tools as well
    testImplementation("io.javalin:javalin-testtools:6.1.3")
    implementation("com.h2database:h2:2.2.222")
    implementation("org.jdbi", "jdbi3-core", "3.8.2")
    implementation("org.jdbi", "jdbi3-kotlin", "3.8.2")
    implementation("org.jdbi", "jdbi3-kotlin-sqlobject", "3.8.2")
    api("com.google.code.gson:gson:2.8.9")
    implementation("com.github.docker-java:docker-java-core:3.2.12")
    implementation("com.github.docker-java:docker-java-transport-httpclient5:3.2.12")
    implementation("org.slf4j:slf4j-api:1.7.32")
    implementation("ch.qos.logback:logback-classic:1.4.14")
    implementation("ch.qos.logback:logback-core:1.3.12")
    implementation("io.github.microutils:kotlin-logging-jvm:2.1.20")
    // pull in mockk
    testImplementation("io.mockk:mockk:1.13.10")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

tasks.withType<KotlinCompile>() {
    kotlinOptions.jvmTarget = "17"
}

idea {
    module {
        isDownloadSources = true
        isDownloadJavadoc = true
    }
}

val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions {
    freeCompilerArgs = listOf("-Xinline-classes")
}

application {
    mainClass.set("ServerKt")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "ServerKt"
    }
}