import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.0.21"
    idea
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("com.github.ben-manes.versions") version "0.52.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.6"
}

group = "com.statewidesoftware"
version = "1.0-SNAPSHOT"

application {
    mainClass.set("com.statewidesoftware.nscr.ServerKt")
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.0")
    implementation("io.javalin:javalin:6.7.0")
    // get the javalin test tools as well
    testImplementation("io.javalin:javalin-testtools:6.7.0")
    implementation("com.h2database:h2:2.3.232")
    implementation("org.jdbi", "jdbi3-core", "3.49.5")
    implementation("org.jdbi", "jdbi3-kotlin", "3.49.5")
    implementation("org.jdbi", "jdbi3-kotlin-sqlobject", "3.49.5")
    api("com.google.code.gson:gson:2.13.2")
    implementation("com.github.docker-java:docker-java-core:3.6.0")
    implementation("com.github.docker-java:docker-java-transport-httpclient5:3.6.0")
    implementation("org.slf4j:slf4j-api:2.0.12")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    implementation("ch.qos.logback:logback-core:1.5.18")
    implementation("io.github.microutils:kotlin-logging-jvm:2.1.20")
    // pull in mockk
    testImplementation("io.mockk:mockk:1.14.5")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

tasks.withType<KotlinCompile>() {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

idea {
    module {
        isDownloadSources = true
        isDownloadJavadoc = true
    }
}

val compileKotlin: KotlinCompile by tasks
compileKotlin.compilerOptions {
    freeCompilerArgs.add("-Xinline-classes")
}

// Detekt configuration
detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom("$projectDir/detekt.yml")
    baseline = file("$projectDir/detekt-baseline.xml")
}

tasks.detekt {
    reports {
        html.required.set(true)
        xml.required.set(false)
        txt.required.set(false)
        sarif.required.set(false)
    }
}