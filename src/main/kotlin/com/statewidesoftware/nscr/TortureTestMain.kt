package com.statewidesoftware.nscr

import mu.KotlinLogging
import java.io.File

/**
 * Main class for running the Registry Torture Test
 */
fun main(args: Array<String>) {
    val logger = KotlinLogging.logger {}
    
    // Parse command line arguments
    val registryUrl = args.getOrNull(0) ?: "localhost:7000"
    val maxOperations = args.getOrNull(1)?.toIntOrNull() ?: 50
    val operationDelayMs = args.getOrNull(2)?.toLongOrNull() ?: 2000L
    val outputFile = args.getOrNull(3)
    
    logger.info { "Starting Registry Torture Test" }
    logger.info { "Registry URL: $registryUrl" }
    logger.info { "Max Operations: $maxOperations" }
    logger.info { "Operation Delay: ${operationDelayMs}ms" }
    logger.info { "Output File: ${outputFile ?: "stdout"}" }
    
    try {
        // Create and run torture test
        val tortureTest = RegistryTortureTest(
            registryUrl = registryUrl,
            maxOperations = maxOperations,
            operationDelayMs = operationDelayMs
        )
        
        val results = tortureTest.runTortureTest()
        
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
            logger.warn { "Torture test completed with failures" }
            System.exit(1)
        } else {
            logger.info { "Torture test completed successfully" }
            System.exit(0)
        }
        
    } catch (e: Exception) {
        logger.error(e) { "Torture test failed with exception" }
        System.exit(1)
    }
}
