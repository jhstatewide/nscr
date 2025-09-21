package com.statewidesoftware.nscr

import com.google.gson.Gson
import com.google.gson.JsonObject
import mu.KotlinLogging
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.URI
import java.time.Duration
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Concurrent Registry Torture Test - A high-intensity correctness test that runs
 * N concurrent workers performing random operations (push, delete, query) on a
 * running NSCR registry and validates the registry state after each operation.
 * 
 * This test is designed to stress-test the registry under concurrent load and
 * catch race conditions, deadlocks, and other concurrency-related issues.
 */
class ConcurrentRegistryTortureTest(
    private val registryUrl: String = "localhost:7000",
    private val numWorkers: Int = 4,
    private val operationsPerWorker: Int = 25,
    private val operationDelayMs: Long = 500,
    private val maxConcurrentOperations: Int = 8
) {
    private val logger = KotlinLogging.logger {}
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    private val gson = Gson()
    
    // Thread pool for concurrent operations
    private val executor = Executors.newFixedThreadPool(numWorkers)
    private val semaphore = Semaphore(maxConcurrentOperations)
    
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
    
    // Thread-safe statistics tracking
    private val totalOperations = AtomicInteger(0)
    private val successCount = AtomicInteger(0)
    private val failureCount = AtomicInteger(0)
    private val validationPassedCount = AtomicInteger(0)
    private val validationFailedCount = AtomicInteger(0)
    private val startTime = AtomicLong(System.currentTimeMillis())
    
    // Thread-safe registry state tracking
    private val knownRepositoriesLock = ReentrantReadWriteLock()
    private val knownRepositories = mutableSetOf<String>()
    private val knownTags = mutableMapOf<String, MutableSet<String>>()
    
    // Results collection with thread-safe access
    private val resultsLock = ReentrantReadWriteLock()
    private val results = mutableListOf<OperationResult>()
    
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
        val validationPassed: Boolean,
        val workerId: Int,
        val timestamp: Long
    )
    
    data class CommandResult(
        val exitCode: Int,
        val output: String,
        val error: String
    )
    
    data class WorkerStats(
        val workerId: Int,
        val operationsCompleted: Int,
        val successCount: Int,
        val failureCount: Int,
        val validationPassedCount: Int,
        val validationFailedCount: Int,
        val duration: Long
    )
    
    /**
     * Main concurrent torture test execution
     */
    fun runConcurrentTortureTest(): List<OperationResult> {
        logger.info { "Starting Concurrent Registry Torture Test" }
        logger.info { "Registry URL: $registryUrl" }
        logger.info { "Number of Workers: $numWorkers" }
        logger.info { "Operations per Worker: $operationsPerWorker" }
        logger.info { "Operation Delay: ${operationDelayMs}ms" }
        logger.info { "Max Concurrent Operations: $maxConcurrentOperations" }
        
        try {
            // Initial registry check
            if (!checkRegistryHealth()) {
                throw RuntimeException("Registry is not healthy")
            }
            
            // Get initial state
            val initialStats = getRegistryStats()
            logger.info { "Initial registry state: $initialStats" }
            
            // Create and submit worker tasks
            val futures = mutableListOf<Future<WorkerStats>>()
            repeat(numWorkers) { workerId ->
                val future = executor.submit<WorkerStats> {
                    runWorker(workerId)
                }
                futures.add(future)
            }
            
            // Wait for all workers to complete and collect results
            val workerStats = mutableListOf<WorkerStats>()
            futures.forEach { future ->
                try {
                    val stats = future.get()
                    workerStats.add(stats)
                } catch (e: Exception) {
                    logger.error(e) { "Worker failed with exception" }
                }
            }
            
            // Log worker statistics
            logger.info { "Worker Statistics:" }
            workerStats.forEach { stats ->
                logger.info { "Worker ${stats.workerId}: ${stats.operationsCompleted} ops, " +
                    "${stats.successCount} success, ${stats.failureCount} failed, " +
                    "${stats.validationPassedCount} validated, ${stats.duration}ms" }
            }
            
        } catch (e: Exception) {
            logger.error(e) { "Concurrent torture test failed with exception" }
            throw e
        } finally {
            // Final statistics
            val duration = System.currentTimeMillis() - startTime.get()
            logger.info { "Concurrent torture test completed" }
            logger.info { "Total operations: ${totalOperations.get()}" }
            logger.info { "Successful: ${successCount.get()}" }
            logger.info { "Failed: ${failureCount.get()}" }
            logger.info { "Validation passed: ${validationPassedCount.get()}" }
            logger.info { "Validation failed: ${validationFailedCount.get()}" }
            logger.info { "Duration: ${duration}ms" }
            logger.info { "Success rate: ${(successCount.get() * 100.0 / totalOperations.get()).toInt()}%" }
            logger.info { "Validation rate: ${(validationPassedCount.get() * 100.0 / totalOperations.get()).toInt()}%" }
            
            // Shutdown executor
            executor.shutdown()
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.warn { "Executor did not terminate gracefully" }
                executor.shutdownNow()
            }
        }
        
        return resultsLock.read { results.toList() }
    }
    
    /**
     * Run a single worker
     */
    private fun runWorker(workerId: Int): WorkerStats {
        val workerStartTime = System.currentTimeMillis()
        var operationsCompleted = 0
        var workerSuccessCount = 0
        var workerFailureCount = 0
        var workerValidationPassedCount = 0
        var workerValidationFailedCount = 0
        
        logger.info { "Worker $workerId started" }
        
        try {
            repeat(operationsPerWorker) { iteration ->
                // Acquire semaphore to limit concurrent operations
                semaphore.acquire()
                
                try {
                    val operation = selectRandomOperation()
                    logger.debug { "Worker $workerId - Operation ${iteration + 1}/$operationsPerWorker: $operation" }
                    
                    val result = executeOperation(operation, workerId)
                    
                    // Thread-safe result collection
                    resultsLock.write {
                        results.add(result)
                    }
                    
                    operationsCompleted++
                    totalOperations.incrementAndGet()
                    
                    // Log result
                    if (result.success && result.validationPassed) {
                        logger.debug { "Worker $workerId - ✓ $operation succeeded and validation passed" }
                        successCount.incrementAndGet()
                        validationPassedCount.incrementAndGet()
                        workerSuccessCount++
                        workerValidationPassedCount++
                    } else {
                        logger.warn { "Worker $workerId - ✗ $operation failed or validation failed: ${result.message}" }
                        failureCount.incrementAndGet()
                        validationFailedCount.incrementAndGet()
                        workerFailureCount++
                        workerValidationFailedCount++
                    }
                    
                    // Delay between operations
                    if (operationDelayMs > 0) {
                        Thread.sleep(operationDelayMs)
                    }
                    
                } finally {
                    semaphore.release()
                }
            }
            
        } catch (e: Exception) {
            logger.error(e) { "Worker $workerId failed with exception" }
        }
        
        val workerDuration = System.currentTimeMillis() - workerStartTime
        logger.info { "Worker $workerId completed: $operationsCompleted operations in ${workerDuration}ms" }
        
        return WorkerStats(
            workerId = workerId,
            operationsCompleted = operationsCompleted,
            successCount = workerSuccessCount,
            failureCount = workerFailureCount,
            validationPassedCount = workerValidationPassedCount,
            validationFailedCount = workerValidationFailedCount,
            duration = workerDuration
        )
    }
    
    /**
     * Execute a Docker command via ProcessBuilder
     */
    private fun executeDockerCommand(vararg args: String): CommandResult {
        val processBuilder = ProcessBuilder("docker", *args)
        processBuilder.redirectErrorStream(false)
        
        val process = processBuilder.start()
        val output = process.inputStream.bufferedReader().readText()
        val error = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        
        return CommandResult(exitCode, output, error)
    }
    
    /**
     * Check if registry is healthy and accessible
     */
    private fun checkRegistryHealth(): Boolean {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://$registryUrl/api/web/status"))
                .timeout(Duration.ofSeconds(5))
                .build()
            
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() == 200
        } catch (e: Exception) {
            logger.error(e) { "Failed to check registry health" }
            false
        }
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
    private fun executeOperation(operation: String, workerId: Int): OperationResult {
        return try {
            when (operation) {
                "push" -> executePushOperation(workerId)
                "delete" -> executeDeleteOperation(workerId)
                "query" -> executeQueryOperation(workerId)
                "list_repos" -> executeListReposOperation(workerId)
                "list_tags" -> executeListTagsOperation(workerId)
                else -> throw IllegalArgumentException("Unknown operation: $operation")
            }
        } catch (e: Exception) {
            logger.error(e) { "Worker $workerId - Operation $operation failed" }
            OperationResult(
                operation = operation,
                success = false,
                message = e.message ?: "Unknown error",
                expectedStats = null,
                actualStats = null,
                validationPassed = false,
                workerId = workerId,
                timestamp = System.currentTimeMillis()
            )
        }
    }
    
    /**
     * Execute a push operation
     */
    private fun executePushOperation(workerId: Int): OperationResult {
        val image = testImages.random()
        val tag = image.tags.random()
        // Use test-specific prefix to avoid conflicts with real images
        val testImageName = "nscr-test-${image.name}"
        val registryImage = "${registryUrl}/${testImageName}:${tag}"
        
        logger.debug { "Worker $workerId - Pushing $registryImage" }
        
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
            
            // Thread-safe update of our tracking
            knownRepositoriesLock.write {
                knownRepositories.add(testImageName)
                knownTags.getOrPut(testImageName) { mutableSetOf() }.add(tag)
            }
            
            // Get stats after operation
            val statsAfter = getRegistryStats()
            
            // Validate state
            val validationPassed = validatePushOperation(statsBefore, statsAfter, testImageName, tag)
            
            // Clean up local image
            try {
                executeDockerCommand("rmi", registryImage)
            } catch (e: Exception) {
                logger.warn(e) { "Worker $workerId - Failed to clean up local image $registryImage" }
            }
            
            return OperationResult(
                operation = "push",
                success = true,
                message = "Successfully pushed $registryImage",
                expectedStats = statsBefore,
                actualStats = statsAfter,
                validationPassed = validationPassed,
                workerId = workerId,
                timestamp = System.currentTimeMillis()
            )
            
        } catch (e: Exception) {
            return OperationResult(
                operation = "push",
                success = false,
                message = "Failed to push $registryImage: ${e.message}",
                expectedStats = statsBefore,
                actualStats = getRegistryStats(),
                validationPassed = false,
                workerId = workerId,
                timestamp = System.currentTimeMillis()
            )
        }
    }
    
    /**
     * Execute a delete operation
     */
    private fun executeDeleteOperation(workerId: Int): OperationResult {
        val repositories = knownRepositoriesLock.read { knownRepositories.toList() }
        
        if (repositories.isEmpty()) {
            return OperationResult(
                operation = "delete",
                success = true,
                message = "No repositories to delete",
                expectedStats = getRegistryStats(),
                actualStats = getRegistryStats(),
                validationPassed = true,
                workerId = workerId,
                timestamp = System.currentTimeMillis()
            )
        }
        
        val repository = repositories.random()
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
                // Thread-safe update of our tracking
                knownRepositoriesLock.write {
                    knownRepositories.remove(repository)
                    knownTags.remove(repository)
                }
                
                val statsAfter = getRegistryStats()
                val validationPassed = validateDeleteOperation(statsBefore, statsAfter, repository)
                
                return OperationResult(
                    operation = "delete",
                    success = true,
                    message = "Successfully deleted repository $repository",
                    expectedStats = statsBefore,
                    actualStats = statsAfter,
                    validationPassed = validationPassed,
                    workerId = workerId,
                    timestamp = System.currentTimeMillis()
                )
            } else {
                return OperationResult(
                    operation = "delete",
                    success = false,
                    message = "Failed to delete repository $repository: ${response.statusCode()}",
                    expectedStats = statsBefore,
                    actualStats = getRegistryStats(),
                    validationPassed = false,
                    workerId = workerId,
                    timestamp = System.currentTimeMillis()
                )
            }
            
        } catch (e: Exception) {
            return OperationResult(
                operation = "delete",
                success = false,
                message = "Failed to delete repository $repository: ${e.message}",
                expectedStats = statsBefore,
                actualStats = getRegistryStats(),
                validationPassed = false,
                workerId = workerId,
                timestamp = System.currentTimeMillis()
            )
        }
    }
    
    /**
     * Execute a query operation (get repository info)
     */
    private fun executeQueryOperation(workerId: Int): OperationResult {
        val repositories = knownRepositoriesLock.read { knownRepositories.toList() }
        
        if (repositories.isEmpty()) {
            return OperationResult(
                operation = "query",
                success = true,
                message = "No repositories to query",
                expectedStats = getRegistryStats(),
                actualStats = getRegistryStats(),
                validationPassed = true,
                workerId = workerId,
                timestamp = System.currentTimeMillis()
            )
        }
        
        val repository = repositories.random()
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
                    validationPassed = validationPassed,
                    workerId = workerId,
                    timestamp = System.currentTimeMillis()
                )
            } else {
                return OperationResult(
                    operation = "query",
                    success = false,
                    message = "Failed to query repository $repository: ${response.statusCode()}",
                    expectedStats = statsBefore,
                    actualStats = getRegistryStats(),
                    validationPassed = false,
                    workerId = workerId,
                    timestamp = System.currentTimeMillis()
                )
            }
            
        } catch (e: Exception) {
            return OperationResult(
                operation = "query",
                success = false,
                message = "Failed to query repository $repository: ${e.message}",
                expectedStats = statsBefore,
                actualStats = getRegistryStats(),
                validationPassed = false,
                workerId = workerId,
                timestamp = System.currentTimeMillis()
            )
        }
    }
    
    /**
     * Execute a list repositories operation
     */
    private fun executeListReposOperation(workerId: Int): OperationResult {
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
                    validationPassed = validationPassed,
                    workerId = workerId,
                    timestamp = System.currentTimeMillis()
                )
            } else {
                return OperationResult(
                    operation = "list_repos",
                    success = false,
                    message = "Failed to list repositories: ${response.statusCode()}",
                    expectedStats = statsBefore,
                    actualStats = getRegistryStats(),
                    validationPassed = false,
                    workerId = workerId,
                    timestamp = System.currentTimeMillis()
                )
            }
            
        } catch (e: Exception) {
            return OperationResult(
                operation = "list_repos",
                success = false,
                message = "Failed to list repositories: ${e.message}",
                expectedStats = statsBefore,
                actualStats = getRegistryStats(),
                validationPassed = false,
                workerId = workerId,
                timestamp = System.currentTimeMillis()
            )
        }
    }
    
    /**
     * Execute a list tags operation
     */
    private fun executeListTagsOperation(workerId: Int): OperationResult {
        val repositories = knownRepositoriesLock.read { knownRepositories.toList() }
        
        if (repositories.isEmpty()) {
            return OperationResult(
                operation = "list_tags",
                success = true,
                message = "No repositories to list tags for",
                expectedStats = getRegistryStats(),
                actualStats = getRegistryStats(),
                validationPassed = true,
                workerId = workerId,
                timestamp = System.currentTimeMillis()
            )
        }
        
        val repository = repositories.random()
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
                    validationPassed = validationPassed,
                    workerId = workerId,
                    timestamp = System.currentTimeMillis()
                )
            } else {
                return OperationResult(
                    operation = "list_tags",
                    success = false,
                    message = "Failed to list tags for repository $repository: ${response.statusCode()}",
                    expectedStats = statsBefore,
                    actualStats = getRegistryStats(),
                    validationPassed = false,
                    workerId = workerId,
                    timestamp = System.currentTimeMillis()
                )
            }
            
        } catch (e: Exception) {
            return OperationResult(
                operation = "list_tags",
                success = false,
                message = "Failed to list tags for repository $repository: ${e.message}",
                expectedStats = statsBefore,
                actualStats = getRegistryStats(),
                validationPassed = false,
                workerId = workerId,
                timestamp = System.currentTimeMillis()
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
        
        report.appendLine("=== Concurrent Registry Torture Test Report ===")
        report.appendLine("Total Operations: ${results.size}")
        report.appendLine("Successful Operations: ${results.count { it.success }}")
        report.appendLine("Failed Operations: ${results.count { !it.success }}")
        report.appendLine("Validation Passed: ${results.count { it.validationPassed }}")
        report.appendLine("Validation Failed: ${results.count { !it.validationPassed }}")
        report.appendLine()
        
        // Worker breakdown
        val workerGroups = results.groupBy { it.workerId }
        report.appendLine("Worker Breakdown:")
        workerGroups.forEach { (workerId, ops) ->
            val successCount = ops.count { it.success }
            val validationCount = ops.count { it.validationPassed }
            report.appendLine("  Worker $workerId: ${ops.size} total, $successCount successful, $validationCount validated")
        }
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
                report.appendLine("  Worker ${result.workerId} - ${result.operation}: ${result.message}")
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
