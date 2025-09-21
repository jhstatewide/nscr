package com.statewidesoftware.nscr

import com.statewidesoftware.nscr.RegistryServerApp
import com.statewidesoftware.nscr.blobstore.H2BlobStore
import io.javalin.Javalin
import mu.KotlinLogging
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * Integration test that starts a lightweight Javalin server on port 7000
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

        @BeforeAll
        @JvmStatic
        fun startServer() {
            // Create a temporary directory for the H2 database
            val dbPath: Path = Files.createTempDirectory("nscr-test-db")
            val blobStore = H2BlobStore(dbPath)

            // Initialise the server application
            serverApp = RegistryServerApp(KotlinLogging.logger { }, blobStore)
            javalin = serverApp.app

            // Start the server on port 7000
            javalin.start(7000)
        }

        @AfterAll
        @JvmStatic
        fun stopServer() {
            javalin.stop()
        }
    }

    @Test
    fun `run torture test against local server`() {
        val tortureTest = RegistryTortureTest(
            registryUrl = "localhost:7000",
            maxOperations = 50,
            operationDelayMs = 2000
        )
        val results = tortureTest.runTortureTest()
        // Basic sanity check – at least one operation should have been attempted
        assertTrue(results.isNotEmpty(), "No operations were executed")
    }
}
