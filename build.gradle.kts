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
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.github.docker-java:docker-java-core:3.6.0")
    implementation("com.github.docker-java:docker-java-transport-httpclient5:3.6.0")
    implementation("org.slf4j:slf4j-api:2.0.12")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    implementation("ch.qos.logback:logback-core:1.5.18")
    implementation("io.github.microutils:kotlin-logging-jvm:2.1.20")
    // pull in mockk
    testImplementation("io.mockk:mockk:1.14.5")
    testImplementation("org.testcontainers:testcontainers:1.21.3")
    testImplementation("org.testcontainers:junit-jupiter:1.21.3")
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
    // Make detekt non-blocking - it can run but won't fail the build
    ignoreFailures = true
}

// Node.js configuration
node {
    version.set("20.11.0")
    yarnVersion.set("1.22.19")
    download.set(true)
}

// Frontend npm install task
tasks.register<Exec>("npm_install") {
    group = "frontend"
    description = "Install frontend dependencies with npm"
    workingDir = file("frontend")
    commandLine("npm", "install")
}

// Frontend build task
tasks.register<Exec>("buildFrontend") {
    group = "frontend"
    description = "Build frontend assets with esbuild"
    dependsOn("npm_install")
    workingDir = file("frontend")
    commandLine("npm", "run", "build")
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
    dependsOn("npm_install")
    workingDir = file("frontend")
    commandLine("npm", "run", "dev")
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

// Concurrent torture test with multiple workers
tasks.register<JavaExec>("tortureTestConcurrent") {
    group = "testing"
    description = "Run concurrent registry torture test with N workers hitting the registry simultaneously"
    
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.statewidesoftware.nscr.ConcurrentTortureTestMainKt")
    
    // Concurrent test parameters
    val registryUrl = project.findProperty("torture.registryUrl") as String? ?: "localhost:7000"
    val numWorkers = project.findProperty("torture.numWorkers") as String? ?: "4"
    val operationsPerWorker = project.findProperty("torture.operationsPerWorker") as String? ?: "25"
    val operationDelayMs = project.findProperty("torture.operationDelayMs") as String? ?: "500"
    val maxConcurrentOperations = project.findProperty("torture.maxConcurrentOperations") as String? ?: "8"
    val outputFile = project.findProperty("torture.outputFile") as String? ?: "torture-test-concurrent-report.txt"
    
    args = listOf(registryUrl, numWorkers, operationsPerWorker, operationDelayMs, maxConcurrentOperations, outputFile)
    
    dependsOn("build")
    
    standardOutput = System.out
    errorOutput = System.err
}

// High-intensity concurrent torture test
tasks.register<JavaExec>("tortureTestConcurrentIntense") {
    group = "testing"
    description = "Run high-intensity concurrent registry torture test with many workers and operations"
    
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.statewidesoftware.nscr.ConcurrentTortureTestMainKt")
    
    // High-intensity test parameters
    val registryUrl = project.findProperty("torture.registryUrl") as String? ?: "localhost:7000"
    val numWorkers = project.findProperty("torture.numWorkers") as String? ?: "8"
    val operationsPerWorker = project.findProperty("torture.operationsPerWorker") as String? ?: "50"
    val operationDelayMs = project.findProperty("torture.operationDelayMs") as String? ?: "200"
    val maxConcurrentOperations = project.findProperty("torture.maxConcurrentOperations") as String? ?: "16"
    val outputFile = project.findProperty("torture.outputFile") as String? ?: "torture-test-concurrent-intense-report.txt"
    
    args = listOf(registryUrl, numWorkers, operationsPerWorker, operationDelayMs, maxConcurrentOperations, outputFile)
    
    dependsOn("build")
    
    standardOutput = System.out
    errorOutput = System.err
}

// Stress test with maximum concurrency
tasks.register<JavaExec>("tortureTestConcurrentStress") {
    group = "testing"
    description = "Run maximum stress concurrent registry torture test - use with caution"
    
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.statewidesoftware.nscr.ConcurrentTortureTestMainKt")
    
    // Maximum stress test parameters
    val registryUrl = project.findProperty("torture.registryUrl") as String? ?: "localhost:7000"
    val numWorkers = project.findProperty("torture.numWorkers") as String? ?: "16"
    val operationsPerWorker = project.findProperty("torture.operationsPerWorker") as String? ?: "100"
    val operationDelayMs = project.findProperty("torture.operationDelayMs") as String? ?: "100"
    val maxConcurrentOperations = project.findProperty("torture.maxConcurrentOperations") as String? ?: "32"
    val outputFile = project.findProperty("torture.outputFile") as String? ?: "torture-test-concurrent-stress-report.txt"
    
    args = listOf(registryUrl, numWorkers, operationsPerWorker, operationDelayMs, maxConcurrentOperations, outputFile)
    
    dependsOn("build")
    
    standardOutput = System.out
    errorOutput = System.err
}

// Profiling task for JVisualVM and Java Mission Control
tasks.register<JavaExec>("runProfile") {
    group = "application"
    description = "Run the server with profiling enabled for JVisualVM and Java Mission Control"
    
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.statewidesoftware.nscr.ServerKt")
    
    // JVM arguments for profiling
    jvmArgs = listOf(
        // Enable JMX for JVisualVM and Java Mission Control
        "-Dcom.sun.management.jmxremote",
        "-Dcom.sun.management.jmxremote.port=9999",
        "-Dcom.sun.management.jmxremote.authenticate=false",
        "-Dcom.sun.management.jmxremote.ssl=false",
        "-Dcom.sun.management.jmxremote.local.only=false",
        
        // Enable JFR (Java Flight Recorder) for detailed profiling
        "-XX:+FlightRecorder",
        
        // Additional profiling-friendly JVM settings
        "-XX:+UnlockDiagnosticVMOptions",
        "-XX:+DebugNonSafepoints",
        
        // Memory settings for better profiling visibility
        "-Xmx2g",
        "-Xms1g",
        
        // GC logging for analysis (Java 17 compatible)
        "-XX:+UseG1GC",
        // "-Xlog:gc*:gc-profile.log:time"
    )
    
    dependsOn("build")
    
    standardOutput = System.out
    errorOutput = System.err
}

// Task to clean up test data while preserving .keep file
tasks.register<Delete>("cleanupTestData") {
    group = "verification"
    description = "Clean up test data directories while preserving .keep file"
    
    val testDataDir = file("tmp/test-data")
    
    doFirst {
        if (!testDataDir.exists()) {
            println("📁 Test data directory does not exist: $testDataDir")
            return@doFirst
        }
        
        val keepFile = file("$testDataDir/.keep")
        if (!keepFile.exists()) {
            println("⚠️  Warning: .keep file not found, creating it...")
            keepFile.writeText("""# This file ensures the tmp/test-data directory is preserved in git
# Test artifacts are automatically cleaned up but this file remains
""")
        }
        
        val dirsToDelete = testDataDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        println("🧹 Found ${dirsToDelete.size} test directories to clean up")
        
        dirsToDelete.forEach { dir ->
            println("🗑️  Removing: ${dir.name}")
        }
    }
    
    // Remove all directories and files except .keep
    doLast {
        val cleanupDir = file("tmp/test-data")
        if (cleanupDir.exists()) {
            cleanupDir.listFiles()?.forEach { file ->
                if (file.isDirectory && file.name != ".keep") {
                    file.deleteRecursively()
                } else if (file.isFile && file.name != ".keep") {
                    file.delete()
                }
            }
        }
        
        val keepFile = file("tmp/test-data/.keep")
        if (keepFile.exists()) {
            println("✅ Cleanup complete! .keep file preserved")
        } else {
            println("❌ Error: .keep file was accidentally removed!")
            throw GradleException(".keep file was removed during cleanup")
        }
    }
}

// Make cleanupTestData run as part of the clean task
tasks.named("clean") { dependsOn("cleanupTestData", "cleanupAllTestDockerImages") }

// Task to clean up Docker images created by tests
tasks.register<Exec>("cleanupDockerImages") {
    group = "verification"
    description = "Clean up Docker images created by test runs"
    
    commandLine("docker", "image", "prune", "-f")
    
    doFirst {
        println("🐳 Cleaning up Docker images created by tests...")
    }
    
    doLast {
        println("✅ Docker image cleanup completed")
    }
}

// Task to clean up specific test images (nscr-test-registry-* prefixed images)
tasks.register<Exec>("cleanupTestDockerImages") {
    group = "verification"
    description = "Clean up Docker images with nscr-test-registry-* prefix (created by tests)"
    
    commandLine("./scripts/cleanup-docker-images.sh")
}

// Task to clean up all test-related Docker images (nscr-test-registry-* and localhost:*nscr-test-* from old runs)
tasks.register<Exec>("cleanupAllTestDockerImages") {
    group = "verification"
    description = "Clean up all test-related Docker images (nscr-test-registry-* and localhost:*nscr-test-* from old runs)"
    
    commandLine("./scripts/cleanup-docker-images.sh")
}

// Task to build Docker image of the NSCR project
tasks.register<Exec>("dockerBuild") {
    group = "docker"
    description = "Build Docker image of the NSCR project"
    
    doFirst {
        println("🐳 Building Docker image for NSCR project...")
        println("📦 This will create a containerized version of the registry")
    }
    
    commandLine("docker", "build", "-t", "nscr:latest", ".")
    
    doLast {
        println("✅ Docker image built successfully!")
        println("🚀 You can now run: docker run -p 7000:7000 nscr:latest")
    }
}

// Task to run the NSCR Docker container
tasks.register<Exec>("dockerRun") {
    group = "docker"
    description = "Run the NSCR Docker container"
    
    doFirst {
        println("🚀 Starting NSCR Docker container...")
        println("🌐 Registry will be available at http://localhost:7000")
    }
    
    commandLine("docker", "run", "-p", "7000:7000", "--name", "nscr-container", "nscr:latest")
    
    doLast {
        println("✅ NSCR container started!")
        println("🛑 To stop: docker stop nscr-container")
        println("🗑️  To remove: docker rm nscr-container")
    }
}

// Task to stop and remove the NSCR Docker container
tasks.register<Exec>("dockerStop") {
    group = "docker"
    description = "Stop and remove the NSCR Docker container"
    
    doFirst {
        println("🛑 Stopping NSCR Docker container...")
    }
    
    commandLine("bash", "-c", """
        docker stop nscr-container 2>/dev/null || echo "Container not running"
        docker rm nscr-container 2>/dev/null || echo "Container not found"
    """.trimIndent())
    
    doLast {
        println("✅ NSCR container stopped and removed!")
    }
}

// Task to show Docker image information
tasks.register<Exec>("dockerInfo") {
    group = "docker"
    description = "Show information about the NSCR Docker image"
    
    doFirst {
        println("📊 NSCR Docker Image Information")
        println("================================")
    }
    
    commandLine("docker", "images", "nscr")
    
    doLast {
        println("")
        println("💡 Usage:")
        println("   ./gradlew dockerBuild  - Build the image")
        println("   ./gradlew dockerRun    - Run the container")
        println("   ./gradlew dockerStop   - Stop the container")
    }
}
