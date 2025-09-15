package com.statewidesoftware.nscr

import mu.KotlinLogging
import java.io.File

/**
 * Main class for running the Concurrent Registry Torture Test
 */
fun main(args: Array<String>) {
    val logger = KotlinLogging.logger {}
    
    // Parse command line arguments
    val registryUrl = args.getOrNull(0) ?: "localhost:7000"
    val numWorkers = args.getOrNull(1)?.toIntOrNull() ?: 4
    val operationsPerWorker = args.getOrNull(2)?.toIntOrNull() ?: 25
    val operationDelayMs = args.getOrNull(3)?.toLongOrNull() ?: 500L
    val maxConcurrentOperations = args.getOrNull(4)?.toIntOrNull() ?: 8
    val outputFile = args.getOrNull(5)
    
    logger.info { "Starting Concurrent Registry Torture Test" }
    logger.info { "Registry URL: $registryUrl" }
    logger.info { "Number of Workers: $numWorkers" }
    logger.info { "Operations per Worker: $operationsPerWorker" }
    logger.info { "Operation Delay: ${operationDelayMs}ms" }
    logger.info { "Max Concurrent Operations: $maxConcurrentOperations" }
    logger.info { "Output File: ${outputFile ?: "stdout"}" }
    
    try {
        // Create and run concurrent torture test
        val tortureTest = ConcurrentRegistryTortureTest(
            registryUrl = registryUrl,
            numWorkers = numWorkers,
            operationsPerWorker = operationsPerWorker,
            operationDelayMs = operationDelayMs,
            maxConcurrentOperations = maxConcurrentOperations
        )
        
        val results = tortureTest.runConcurrentTortureTest()
        
        // Generate report
        val report = tortureTest.generateReport(results)
        
        // Output report
        if (outputFile != null) {
            File(outputFile).writeText(report)
            logger.info { "Report written to: $outputFile" }
        } else {
            println(report)
        }
        
        // Exit with appropriate code
        val hasFailures = results.any { !it.success || !it.validationPassed }
        if (hasFailures) {
            logger.warn { "Concurrent torture test completed with failures" }
            System.exit(1)
        } else {
            logger.info { "Concurrent torture test completed successfully" }
            System.exit(0)
        }
        
    } catch (e: Exception) {
        logger.error(e) { "Concurrent torture test failed with exception" }
        System.exit(1)
    }
}
