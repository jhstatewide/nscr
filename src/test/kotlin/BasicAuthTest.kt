package com.statewidesoftware.nscr

import com.statewidesoftware.nscr.blobstore.H2BlobStore
import io.javalin.testtools.JavalinTest
import mu.KotlinLogging
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.util.Base64
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.Files
import java.util.UUID
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

class BasicAuthTest {
    private lateinit var app: RegistryServerApp
    private lateinit var testDbPath: Path
    
    @BeforeEach
    fun setUp() {
        // Create unique test database path
        testDbPath = Paths.get("./tmp/test-data/basic-auth-test-${UUID.randomUUID()}")
        if (Files.exists(testDbPath)) {
            Files.walk(testDbPath)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
        Files.createDirectories(testDbPath)
    }
    
    @AfterEach
    fun tearDown() {
        try {
            app.stop()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
        
        // Clear environment variables
        System.clearProperty("NSCR_AUTH_ENABLED")
        System.clearProperty("NSCR_AUTH_USERNAME")
        System.clearProperty("NSCR_AUTH_PASSWORD")
        
        // Clean up test database files
        if (Files.exists(testDbPath)) {
            try {
                Files.walk(testDbPath)
                    .sorted(Comparator.reverseOrder())
                    .forEach { Files.deleteIfExists(it) }
            } catch (e: Exception) {
                // Ignore cleanup errors - files might be locked
            }
        }
    }
    
    private fun createApp(authEnabled: Boolean = false) {
        if (authEnabled) {
            System.setProperty("NSCR_AUTH_ENABLED", "true")
            System.setProperty("NSCR_AUTH_USERNAME", "testuser")
            System.setProperty("NSCR_AUTH_PASSWORD", "testpass")
        } else {
            System.setProperty("NSCR_AUTH_ENABLED", "false")
        }
        app = RegistryServerApp(KotlinLogging.logger {  }, H2BlobStore(testDbPath))
    }
    
    @Test
    fun `test registry access without auth when disabled`() {
        createApp(authEnabled = false)
        JavalinTest.test(app.app) { _, client ->
            val response = client.get("/v2")
            assertEquals(200, response.code)
        }
    }
    
    @Test
    fun `test registry access with auth when enabled`() {
        createApp(authEnabled = true)
        
        JavalinTest.test(app.app) { _, client ->
            // Test without auth - should fail
            val responseNoAuth = client.get("/v2")
            assertEquals(401, responseNoAuth.code)
            
            // Test with correct auth - should succeed
            val authHeader = "Basic " + Base64.getEncoder().encodeToString("testuser:testpass".toByteArray())
            val responseWithAuth = client.get("/v2") { req ->
                req.header("Authorization", authHeader)
            }
            assertEquals(200, responseWithAuth.code)
            
            // Test with incorrect auth - should fail
            val wrongAuthHeader = "Basic " + Base64.getEncoder().encodeToString("wronguser:wrongpass".toByteArray())
            val responseWrongAuth = client.get("/v2") { req ->
                req.header("Authorization", wrongAuthHeader)
            }
            assertEquals(401, responseWrongAuth.code)
        }
    }
    
    @Test
    fun `test root endpoint is not protected`() {
        createApp(authEnabled = true)
        
        JavalinTest.test(app.app) { _, client ->
            // Root endpoint should be accessible without auth
            val response = client.get("/")
            assertEquals(200, response.code)
            val body = response.body?.string()
            // Should return HTML content (web interface) or "Hello World" if web interface disabled
            assertTrue(body?.contains("<!DOCTYPE html>") == true || body == "Hello World")
        }
    }
}
