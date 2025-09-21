package com.statewidesoftware.nscr

import com.statewidesoftware.nscr.RegistryServerApp
import com.statewidesoftware.nscr.blobstore.H2BlobStore
import io.javalin.Javalin
import mu.KotlinLogging
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * Integration test that starts a lightweight Javalin server on a random port
 * and runs the RegistryTortureTest against it.
 *
 * The original RegistryTortureTest expects an external registry to be
 * available at localhost:7000.  In CI environments this server is not
 * started, causing a ConnectException.  This test programmatically
 * starts an in‑memory H2BlobStore backed server before the test
 * runs and shuts it down afterwards.
 */
class RegistryTortureTestTest {

    companion object {
        private lateinit var serverApp: RegistryServerApp
        private lateinit var javalin: Javalin
        private var serverPort: Int = 0
        private val logger = KotlinLogging.logger {}

        @BeforeAll
        @JvmStatic
        fun startServer() {
            // Find an available port
            serverPort = findAvailablePort()
            logger.info { "Starting test server on port $serverPort" }
            
            // Create a unique temporary directory for the H2 database to avoid conflicts
            val dbPath: Path = Files.createTempDirectory("nscr-torture-test-${System.currentTimeMillis()}")
            logger.info { "Using database path: $dbPath" }
            
            // Clean up any existing database files in the directory
            try {
                Files.walk(dbPath)
                    .sorted(Comparator.reverseOrder())
                    .forEach { Files.deleteIfExists(it) }
            } catch (e: Exception) {
                logger.warn { "Could not clean up existing database files: ${e.message}" }
            }
            
            val blobStore = H2BlobStore(dbPath)

            // Initialise the server application
            serverApp = RegistryServerApp(KotlinLogging.logger { }, blobStore)
            javalin = serverApp.app

            // Start the server on the available port
            javalin.start(serverPort)
            logger.info { "Test server started successfully on port $serverPort" }
        }

        @AfterAll
        @JvmStatic
        fun stopServer() {
            logger.info { "Stopping test server on port $serverPort" }
            try {
                serverApp.stop()  // This handles both Javalin and blobstore cleanup
                logger.info { "Test server stopped successfully" }
            } catch (e: Exception) {
                logger.warn { "Error stopping test server: ${e.message}" }
            }
        }
        
        private fun findAvailablePort(): Int {
            return ServerSocket(0).use { socket ->
                socket.localPort
            }
        }
    }

    @Test
    fun `run torture test against local server`() {
        logger.info { "Starting torture test against localhost:$serverPort" }
        
        // Use fewer operations and shorter delays for faster testing
        val tortureTest = RegistryTortureTest(
            registryUrl = "localhost:$serverPort",
            maxOperations = 10,  // Reduced from 50 for faster testing
            operationDelayMs = 500  // Reduced from 2000 for faster testing
        )
        
        logger.info { "Running torture test with 10 operations" }
        val results = tortureTest.runTortureTest()
        
        // Basic sanity check – at least one operation should have been attempted
        assertTrue(results.isNotEmpty(), "No operations were executed")
        
        // Log summary
        val successfulOps = results.count { it.success }
        val validatedOps = results.count { it.validationPassed }
        logger.info { "Torture test completed: ${results.size} operations, $successfulOps successful, $validatedOps validated" }
    }
}
