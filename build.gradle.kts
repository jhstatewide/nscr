import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.0.21"
    idea
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("com.github.ben-manes.versions") version "0.52.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.6"
    id("com.github.node-gradle.node") version "5.0.0"
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

// Node.js configuration
node {
    version.set("20.11.0")
    yarnVersion.set("1.22.19")
    download.set(true)
}

// Frontend build task
tasks.register<Exec>("buildFrontend") {
    group = "frontend"
    description = "Build frontend assets with esbuild"
    dependsOn("yarn_install")
    workingDir = file("frontend")
    commandLine("yarn", "build")
}

// Copy frontend assets to resources
tasks.register<Copy>("copyFrontendAssets") {
    group = "frontend"
    description = "Copy built frontend assets to resources"
    from("frontend/dist")
    into("src/main/resources/static")
    dependsOn("buildFrontend")
}

// Make processResources depend on copyFrontendAssets
tasks.named("processResources") {
    dependsOn("copyFrontendAssets")
}

// Make frontend build part of main build
tasks.named("build") {
    dependsOn("copyFrontendAssets")
}

// Development task for frontend
tasks.register<Exec>("devFrontend") {
    group = "frontend"
    description = "Start frontend development server"
    dependsOn("yarn_install")
    workingDir = file("frontend")
    commandLine("yarn", "dev")
}

// Registry Torture Test task
tasks.register<JavaExec>("tortureTest") {
    group = "testing"
    description = "Run registry torture test - randomly performs operations and validates correctness"
    
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.statewidesoftware.nscr.TortureTestMainKt")
    
    // Default arguments - can be overridden with -Pargs
    val registryUrl = project.findProperty("torture.registryUrl") as String? ?: "localhost:7000"
    val maxOperations = project.findProperty("torture.maxOperations") as String? ?: "50"
    val operationDelayMs = project.findProperty("torture.operationDelayMs") as String? ?: "2000"
    val outputFile = project.findProperty("torture.outputFile") as String?
    
    args = listOfNotNull(registryUrl, maxOperations, operationDelayMs, outputFile)
    
    // Ensure the registry is built
    dependsOn("build")
    
    // Set up logging
    standardOutput = System.out
    errorOutput = System.err
}

// Extended torture test with more operations
tasks.register<JavaExec>("tortureTestExtended") {
    group = "testing"
    description = "Run extended registry torture test with more operations and longer duration"
    
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.statewidesoftware.nscr.TortureTestMainKt")
    
    // Extended test parameters
    val registryUrl = project.findProperty("torture.registryUrl") as String? ?: "localhost:7000"
    val maxOperations = project.findProperty("torture.maxOperations") as String? ?: "200"
    val operationDelayMs = project.findProperty("torture.operationDelayMs") as String? ?: "1000"
    val outputFile = project.findProperty("torture.outputFile") as String? ?: "torture-test-extended-report.txt"
    
    args = listOf(registryUrl, maxOperations, operationDelayMs, outputFile)
    
    dependsOn("build")
    
    standardOutput = System.out
    errorOutput = System.err
}

// Quick torture test for CI/CD
tasks.register<JavaExec>("tortureTestQuick") {
    group = "testing"
    description = "Run quick registry torture test for CI/CD pipelines"
    
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.statewidesoftware.nscr.TortureTestMainKt")
    
    // Quick test parameters
    val registryUrl = project.findProperty("torture.registryUrl") as String? ?: "localhost:7000"
    val maxOperations = project.findProperty("torture.maxOperations") as String? ?: "20"
    val operationDelayMs = project.findProperty("torture.operationDelayMs") as String? ?: "500"
    val outputFile = project.findProperty("torture.outputFile") as String? ?: "torture-test-quick-report.txt"
    
    args = listOf(registryUrl, maxOperations, operationDelayMs, outputFile)
    
    dependsOn("build")
    
    standardOutput = System.out
    errorOutput = System.err
}