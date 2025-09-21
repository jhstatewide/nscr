package com.statewidesoftware.nscr

// Docker operations will be handled via ProcessBuilder for simplicity
import com.google.gson.Gson
import com.google.gson.JsonObject
import mu.KotlinLogging
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.URI
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import java.io.IOException   // <‑‑ add this line
import java.net.Socket   // <‑‑ new import for port checking

/**
 * Registry Torture Test - A comprehensive correctness test that randomly performs
 * operations (push, delete, query) on a running NSCR registry and validates
 * the registry state after each operation.
 */
class RegistryTortureTest(
    private val registryUrl: String = "localhost:7000",
    private val maxOperations: Int = 100,
    private val operationDelayMs: Long = 1000
) {
    private val logger = KotlinLogging.logger {}

        private fun isPortOpen(host: String, port: Int): Boolean {
            return try {
                Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(host, port), 2000)
                    true
                }
            } catch (e: IOException) {
                false
            }
        }
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    private val gson = Gson()
    
    // Docker operations will be handled via ProcessBuilder
    
    // Test data - images with multiple tags for comprehensive testing
    private val testImages = listOf(
        TestImage("alpine", listOf("latest", "3.18", "3.17", "3.16")),
        TestImage("debian", listOf("latest", "bookworm", "bullseye", "buster")),
        TestImage("ubuntu", listOf("latest", "22.04", "20.04", "18.04")),
        TestImage("nginx", listOf("latest", "alpine", "1.25", "1.24")),
        TestImage("redis", listOf("latest", "alpine", "7.2", "7.0")),
        TestImage("postgres", listOf("latest", "alpine", "15", "14")),
        TestImage("node", listOf("latest", "alpine", "20", "18")),
        TestImage("python", listOf("latest", "alpine", "3.11", "3.10")),
        TestImage("busybox", listOf("latest", "1.36", "1.35")),
        TestImage("hello-world", listOf("latest")),
        TestImage("httpd", listOf("latest", "alpine", "2.4")),
        TestImage("memcached", listOf("latest", "alpine", "1.6"))
    )
    
    // Statistics tracking
    private val operationCount = AtomicInteger(0)
    private val successCount = AtomicInteger(0)
    private val failureCount = AtomicInteger(0)
    private val startTime = AtomicLong(System.currentTimeMillis())
    
    // Registry state tracking
    private val knownRepositories = mutableSetOf<String>()
    private val knownTags = mutableMapOf<String, MutableSet<String>>()
    
    data class TestImage(val name: String, val tags: List<String>)
    
    data class RegistryStats(
        val repositories: Int,
        val totalBlobs: Int,
        val totalManifests: Int
    )
    
    data class OperationResult(
        val operation: String,
        val success: Boolean,
        val message: String,
        val expectedStats: RegistryStats?,
        val actualStats: RegistryStats?,
        val validationPassed: Boolean
    )
    
    data class CommandResult(
        val exitCode: Int,
        val output: String,
        val error: String
    )
    
    /**
     * Main torture test execution
     */
    fun runTortureTest(): List<OperationResult> {
        logger.info { "Starting Registry Torture Test" }
        logger.info { "Registry URL: $registryUrl" }
        logger.info { "Max Operations: $maxOperations" }
        logger.info { "Operation Delay: ${operationDelayMs}ms" }
        
        val results = mutableListOf<OperationResult>()
        
        try {
            // Initial registry check with retry logic
            if (!checkRegistryHealth()) {
                throw RuntimeException("Registry is not healthy")
            }
            
            // Get initial state
            val initialStats = getRegistryStats()
            logger.info { "Initial registry state: $initialStats" }
            
            // Perform random operations
            repeat(maxOperations) { iteration ->
                val operation = selectRandomOperation()
                val progress = String.format("%.1f%%", (iteration + 1) * 100.0 / maxOperations)
                logger.info { "[$progress] Operation ${iteration + 1}/$maxOperations: $operation" }
                
                val result = executeOperation(operation)
                results.add(result)
                
                // Log result with progress
                if (result.success && result.validationPassed) {
                    logger.info { "[$progress] ✓ $operation succeeded and validation passed" }
                    successCount.incrementAndGet()
                } else {
                    logger.warn { "[$progress] ✗ $operation failed or validation failed: ${result.message}" }
                    failureCount.incrementAndGet()
                }
                
                // Delay between operations
                if (operationDelayMs > 0) {
                    Thread.sleep(operationDelayMs)
                }
            }
            
        } catch (e: Exception) {
            logger.error(e) { "Torture test failed with exception" }
            throw e
        } finally {
            // Final statistics
            val duration = System.currentTimeMillis() - startTime.get()
            logger.info { "Torture test completed" }
            logger.info { "Total operations: ${operationCount.get()}" }
            logger.info { "Successful: ${successCount.get()}" }
            logger.info { "Failed: ${failureCount.get()}" }
            logger.info { "Duration: ${duration}ms" }
            logger.info { "Success rate: ${(successCount.get() * 100.0 / operationCount.get()).toInt()}%" }
        }
        
        return results
    }
    
    /**
     * Execute a Docker command via ProcessBuilder
     */
    private fun executeDockerCommand(vararg args: String): CommandResult {
        val processBuilder = ProcessBuilder("docker", *args)
        processBuilder.redirectErrorStream(false)

        return try {
            val process = processBuilder.start()
            // Wait up to 30 seconds for the docker command to finish
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                CommandResult(-1, "", "Docker command timed out after 30s")
            } else {
                val output = process.inputStream.bufferedReader().readText()
                val error = process.errorStream.bufferedReader().readText()
                val exitCode = process.exitValue()
                CommandResult(exitCode, output, error)
            }
        } catch (e: IOException) {
            // Docker binary not found or failed to start
            CommandResult(-1, "", "Failed to execute docker command: ${e.message}")
        }
    }
    
    /**
     * Check if registry is healthy and accessible
     * Retries up to 5 times with a short delay between attempts.
     */
    private fun checkRegistryHealth(): Boolean {
        val maxRetries = 5
        repeat(maxRetries) { attempt ->
            try {
                val uri = URI.create("http://$registryUrl/api/web/status")
                logger.debug { "Attempt ${attempt + 1}/$maxRetries: Checking health at $uri" }

                // Resolve hostname to IP for debugging
                val host = uri.host
                val port = uri.port.takeIf { it != -1 } ?: 80
                val ipAddresses = java.net.InetAddress.getAllByName(host).map { it.hostAddress }
                logger.debug { "Resolved $host to ${ipAddresses.joinToString()}" }

                // Check raw TCP connectivity before HTTP request
                val tcpOpen = isPortOpen(host, port)
                logger.debug { "TCP port $port on $host open: $tcpOpen" }

                val request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(5))
                    .build()

                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() == 200) {
                    return true
                } else {
                    logger.warn { "Health check returned status ${response.statusCode()}, body: ${response.body()}" }
                }
            } catch (e: Exception) {
                logger.warn(e) { "Attempt ${attempt + 1}/$maxRetries failed to check registry health" }
            }

            // Wait before next attempt
            Thread.sleep(500)
        }
        logger.error { "Registry health check failed after $maxRetries attempts" }
        return false
    }
    
    /**
     * Get current registry statistics
     */
    private fun getRegistryStats(): RegistryStats {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://$registryUrl/api/web/status"))
            .timeout(Duration.ofSeconds(10))
            .build()
        
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw RuntimeException("Failed to get registry stats: ${response.statusCode()}")
        }
        
        val json = gson.fromJson(response.body(), JsonObject::class.java)
        return RegistryStats(
            repositories = json.get("repositories")?.asInt ?: 0,
            totalBlobs = json.get("totalBlobs")?.asInt ?: 0,
            totalManifests = json.get("totalManifests")?.asInt ?: 0
        )
    }
    
    /**
     * Select a random operation to perform
     */
    private fun selectRandomOperation(): String {
        val operations = listOf("push", "delete", "query", "list_repos", "list_tags")
        return operations[ThreadLocalRandom.current().nextInt(operations.size)]
    }
    
    /**
     * Execute the selected operation
     */
    private fun executeOperation(operation: String): OperationResult {
        operationCount.incrementAndGet()
        
        return try {
            when (operation) {
                "push" -> executePushOperation()
                "delete" -> executeDeleteOperation()
                "query" -> executeQueryOperation()
                "list_repos" -> executeListReposOperation()
                "list_tags" -> executeListTagsOperation()
                else -> throw IllegalArgumentException("Unknown operation: $operation")
            }
        } catch (e: Exception) {
            logger.error(e) { "Operation $operation failed" }
            OperationResult(operation, false, e.message ?: "Unknown error", null, null, false)
        }
    }
    
    /**
     * Execute a push operation
     */
    private fun executePushOperation(): OperationResult {
        val image = testImages.random()
        val tag = image.tags.random()
        // Use test-specific prefix to avoid conflicts with real images
        val testImageName = "nscr-test-registry-${image.name}"
        val registryImage = "${registryUrl}/${testImageName}:${tag}"
        
        logger.debug { "Pushing $registryImage" }
        
        // Get stats before operation
        val statsBefore = getRegistryStats()
        
        try {
            // Pull the image first
            val pullResult = executeDockerCommand("pull", "${image.name}:${tag}")
            if (pullResult.exitCode != 0) {
                throw RuntimeException("Failed to pull image: ${pullResult.error}")
            }
            
            // Tag for registry
            val tagResult = executeDockerCommand("tag", "${image.name}:${tag}", registryImage)
            if (tagResult.exitCode != 0) {
                throw RuntimeException("Failed to tag image: ${tagResult.error}")
            }
            
            // Push to registry
            val pushResult = executeDockerCommand("push", registryImage)
            if (pushResult.exitCode != 0) {
                throw RuntimeException("Failed to push image: ${pushResult.error}")
            }
            
            // Update our tracking
            knownRepositories.add(testImageName)
            knownTags.getOrPut(testImageName) { mutableSetOf() }.add(tag)
            
            // Get stats after operation
            val statsAfter = getRegistryStats()
            
            // Validate state
            val validationPassed = validatePushOperation(statsBefore, statsAfter, testImageName, tag)
            
            // Clean up local image
            try {
                executeDockerCommand("rmi", registryImage)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to clean up local image $registryImage" }
            }
            
            return OperationResult(
                operation = "push",
                success = true,
                message = "Successfully pushed $registryImage",
                expectedStats = statsBefore,
                actualStats = statsAfter,
                validationPassed = validationPassed
            )
            
        } catch (e: Exception) {
            return OperationResult(
                operation = "push",
                success = false,
                message = "Failed to push $registryImage: ${e.message}",
                expectedStats = statsBefore,
                actualStats = getRegistryStats(),
                validationPassed = false
            )
        }
    }
    
    /**
     * Execute a delete operation
     */
    private fun executeDeleteOperation(): OperationResult {
        if (knownRepositories.isEmpty()) {
            return OperationResult(
                operation = "delete",
                success = true,
                message = "No repositories to delete",
                expectedStats = getRegistryStats(),
                actualStats = getRegistryStats(),
                validationPassed = true
            )
        }
        
        val repository = knownRepositories.random()
        val statsBefore = getRegistryStats()
        
        try {
            // Delete repository via API
            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://$registryUrl/v2/$repository"))
                .DELETE()
                .timeout(Duration.ofSeconds(30))
                .build()
            
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            
            if (response.statusCode() in 200..299) {
                // Update our tracking
                knownRepositories.remove(repository)
                knownTags.remove(repository)
                
                val statsAfter = getRegistryStats()
                val validationPassed = validateDeleteOperation(statsBefore, statsAfter, repository)
                
                return OperationResult(
                    operation = "delete",
                    success = true,
                    message = "Successfully deleted repository $repository",
                    expectedStats = statsBefore,
                    actualStats = statsAfter,
                    validationPassed = validationPassed
                )
            } else {
                return OperationResult(
                    operation = "delete",
                    success = false,
                    message = "Failed to delete repository $repository: ${response.statusCode()}",
                    expectedStats = statsBefore,
                    actualStats = getRegistryStats(),
                    validationPassed = false
                )
            }
            
        } catch (e: Exception) {
            return OperationResult(
                operation = "delete",
                success = false,
                message = "Failed to delete repository $repository: ${e.message}",
                expectedStats = statsBefore,
                actualStats = getRegistryStats(),
                validationPassed = false
            )
        }
    }
    
    /**
     * Execute a query operation (get repository info)
     */
    private fun executeQueryOperation(): OperationResult {
        if (knownRepositories.isEmpty()) {
            return OperationResult(
                operation = "query",
                success = true,
                message = "No repositories to query",
                expectedStats = getRegistryStats(),
                actualStats = getRegistryStats(),
                validationPassed = true
            )
        }
        
        val repository = knownRepositories.random()
        val statsBefore = getRegistryStats()
        
        try {
            // Query repository tags
            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://$registryUrl/v2/$repository/tags/list"))
                .timeout(Duration.ofSeconds(10))
                .build()
            
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            
            if (response.statusCode() == 200) {
                val json = gson.fromJson(response.body(), JsonObject::class.java)
                val tags = json.getAsJsonArray("tags")?.map { it.asString } ?: emptyList()
                
                val statsAfter = getRegistryStats()
                val validationPassed = validateQueryOperation(statsBefore, statsAfter, repository, tags)
                
                return OperationResult(
                    operation = "query",
                    success = true,
                    message = "Successfully queried repository $repository with ${tags.size} tags",
                    expectedStats = statsBefore,
                    actualStats = statsAfter,
                    validationPassed = validationPassed
                )
            } else {
                return OperationResult(
                    operation = "query",
                    success = false,
                    message = "Failed to query repository $repository: ${response.statusCode()}",
                    expectedStats = statsBefore,
                    actualStats = getRegistryStats(),
                    validationPassed = false
                )
            }
            
        } catch (e: Exception) {
            return OperationResult(
                operation = "query",
                success = false,
                message = "Failed to query repository $repository: ${e.message}",
                expectedStats = statsBefore,
                actualStats = getRegistryStats(),
                validationPassed = false
            )
        }
    }
    
    /**
     * Execute a list repositories operation
     */
    private fun executeListReposOperation(): OperationResult {
        val statsBefore = getRegistryStats()
        
        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://$registryUrl/v2/_catalog"))
                .timeout(Duration.ofSeconds(10))
                .build()
            
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            
            if (response.statusCode() == 200) {
                val json = gson.fromJson(response.body(), JsonObject::class.java)
                val repositories = json.getAsJsonArray("repositories")?.map { it.asString } ?: emptyList()
                
                val statsAfter = getRegistryStats()
                val validationPassed = validateListReposOperation(statsBefore, statsAfter, repositories)
                
                return OperationResult(
                    operation = "list_repos",
                    success = true,
                    message = "Successfully listed ${repositories.size} repositories",
                    expectedStats = statsBefore,
                    actualStats = statsAfter,
                    validationPassed = validationPassed
                )
            } else {
                return OperationResult(
                    operation = "list_repos",
                    success = false,
                    message = "Failed to list repositories: ${response.statusCode()}",
                    expectedStats = statsBefore,
                    actualStats = getRegistryStats(),
                    validationPassed = false
                )
            }
            
        } catch (e: Exception) {
            return OperationResult(
                operation = "list_repos",
                success = false,
                message = "Failed to list repositories: ${e.message}",
                expectedStats = statsBefore,
                actualStats = getRegistryStats(),
                validationPassed = false
            )
        }
    }
    
    /**
     * Execute a list tags operation
     */
    private fun executeListTagsOperation(): OperationResult {
        if (knownRepositories.isEmpty()) {
            return OperationResult(
                operation = "list_tags",
                success = true,
                message = "No repositories to list tags for",
                expectedStats = getRegistryStats(),
                actualStats = getRegistryStats(),
                validationPassed = true
            )
        }
        
        val repository = knownRepositories.random()
        val statsBefore = getRegistryStats()
        
        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://$registryUrl/v2/$repository/tags/list"))
                .timeout(Duration.ofSeconds(10))
                .build()
            
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            
            if (response.statusCode() == 200) {
                val json = gson.fromJson(response.body(), JsonObject::class.java)
                val tags = json.getAsJsonArray("tags")?.map { it.asString } ?: emptyList()
                
                val statsAfter = getRegistryStats()
                val validationPassed = validateListTagsOperation(statsBefore, statsAfter, repository, tags)
                
                return OperationResult(
                    operation = "list_tags",
                    success = true,
                    message = "Successfully listed ${tags.size} tags for repository $repository",
                    expectedStats = statsBefore,
                    actualStats = statsAfter,
                    validationPassed = validationPassed
                )
            } else {
                return OperationResult(
                    operation = "list_tags",
                    success = false,
                    message = "Failed to list tags for repository $repository: ${response.statusCode()}",
                    expectedStats = statsBefore,
                    actualStats = getRegistryStats(),
                    validationPassed = false
                )
            }
            
        } catch (e: Exception) {
            return OperationResult(
                operation = "list_tags",
                success = false,
                message = "Failed to list tags for repository $repository: ${e.message}",
                expectedStats = statsBefore,
                actualStats = getRegistryStats(),
                validationPassed = false
            )
        }
    }
    
    /**
     * Validate push operation results
     */
    private fun validatePushOperation(
        statsBefore: RegistryStats,
        statsAfter: RegistryStats,
        repository: String,
        tag: String
    ): Boolean {
        // After a push, we expect:
        // - Repository count to increase (if new repository) or stay same (if existing)
        // - Manifest count to increase
        // - Blob count to increase (new layers)
        
        val repoCountValid = statsAfter.repositories >= statsBefore.repositories
        val manifestCountValid = statsAfter.totalManifests > statsBefore.totalManifests
        val blobCountValid = statsAfter.totalBlobs >= statsBefore.totalBlobs
        
        val valid = repoCountValid && manifestCountValid && blobCountValid
        
        if (!valid) {
            logger.warn { "Push validation failed for $repository:$tag" }
            logger.warn { "Before: repos=${statsBefore.repositories}, manifests=${statsBefore.totalManifests}, blobs=${statsBefore.totalBlobs}" }
            logger.warn { "After: repos=${statsAfter.repositories}, manifests=${statsAfter.totalManifests}, blobs=${statsAfter.totalBlobs}" }
            logger.warn { "Repo count valid: $repoCountValid, Manifest count valid: $manifestCountValid, Blob count valid: $blobCountValid" }
        }
        
        return valid
    }
    
    /**
     * Validate delete operation results
     */
    private fun validateDeleteOperation(
        statsBefore: RegistryStats,
        statsAfter: RegistryStats,
        repository: String
    ): Boolean {
        // After a delete, we expect:
        // - Repository count to decrease
        // - Manifest count to decrease
        // - Blob count to stay same or decrease (blobs might be shared)
        
        val repoCountValid = statsAfter.repositories < statsBefore.repositories
        val manifestCountValid = statsAfter.totalManifests < statsBefore.totalManifests
        val blobCountValid = statsAfter.totalBlobs <= statsBefore.totalBlobs
        
        val valid = repoCountValid && manifestCountValid && blobCountValid
        
        if (!valid) {
            logger.warn { "Delete validation failed for $repository" }
            logger.warn { "Before: repos=${statsBefore.repositories}, manifests=${statsBefore.totalManifests}, blobs=${statsBefore.totalBlobs}" }
            logger.warn { "After: repos=${statsAfter.repositories}, manifests=${statsAfter.totalManifests}, blobs=${statsAfter.totalBlobs}" }
            logger.warn { "Repo count valid: $repoCountValid, Manifest count valid: $manifestCountValid, Blob count valid: $blobCountValid" }
        }
        
        return valid
    }
    
    /**
     * Validate query operation results
     */
    private fun validateQueryOperation(
        statsBefore: RegistryStats,
        statsAfter: RegistryStats,
        repository: String,
        tags: List<String>
    ): Boolean {
        // Query operations should not change registry state
        val valid = statsBefore == statsAfter
        
        if (!valid) {
            logger.warn { "Query validation failed for $repository" }
            logger.warn { "Before: $statsBefore" }
            logger.warn { "After: $statsAfter" }
        }
        
        return valid
    }
    
    /**
     * Validate list repositories operation results
     */
    private fun validateListReposOperation(
        statsBefore: RegistryStats,
        statsAfter: RegistryStats,
        repositories: List<String>
    ): Boolean {
        // List operations should not change registry state
        val valid = statsBefore == statsAfter
        
        if (!valid) {
            logger.warn { "List repos validation failed" }
            logger.warn { "Before: $statsBefore" }
            logger.warn { "After: $statsAfter" }
        }
        
        return valid
    }
    
    /**
     * Validate list tags operation results
     */
    private fun validateListTagsOperation(
        statsBefore: RegistryStats,
        statsAfter: RegistryStats,
        repository: String,
        tags: List<String>
    ): Boolean {
        // List operations should not change registry state
        val valid = statsBefore == statsAfter
        
        if (!valid) {
            logger.warn { "List tags validation failed for $repository" }
            logger.warn { "Before: $statsBefore" }
            logger.warn { "After: $statsAfter" }
        }
        
        return valid
    }
    
    /**
     * Generate a comprehensive test report
     */
    fun generateReport(results: List<OperationResult>): String {
        val report = StringBuilder()
        
        report.appendLine("=== Registry Torture Test Report ===")
        report.appendLine("Total Operations: ${results.size}")
        report.appendLine("Successful Operations: ${results.count { it.success }}")
        report.appendLine("Failed Operations: ${results.count { !it.success }}")
        report.appendLine("Validation Passed: ${results.count { it.validationPassed }}")
        report.appendLine("Validation Failed: ${results.count { !it.validationPassed }}")
        report.appendLine()
        
        // Operation breakdown
        val operationGroups = results.groupBy { it.operation }
        report.appendLine("Operation Breakdown:")
        operationGroups.forEach { (operation, ops) ->
            val successCount = ops.count { it.success }
            val validationCount = ops.count { it.validationPassed }
            report.appendLine("  $operation: ${ops.size} total, $successCount successful, $validationCount validated")
        }
        report.appendLine()
        
        // Failed operations
        val failedOps = results.filter { !it.success || !it.validationPassed }
        if (failedOps.isNotEmpty()) {
            report.appendLine("Failed Operations:")
            failedOps.forEach { result ->
                report.appendLine("  ${result.operation}: ${result.message}")
            }
            report.appendLine()
        }
        
        // Final registry state
        try {
            val finalStats = getRegistryStats()
            report.appendLine("Final Registry State:")
            report.appendLine("  Repositories: ${finalStats.repositories}")
            report.appendLine("  Manifests: ${finalStats.totalManifests}")
            report.appendLine("  Blobs: ${finalStats.totalBlobs}")
        } catch (e: Exception) {
            report.appendLine("Failed to get final registry state: ${e.message}")
        }
        
        return report.toString()
    }
}
