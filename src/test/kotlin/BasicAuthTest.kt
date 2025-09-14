package com.statewidesoftware.nscr

import com.statewidesoftware.nscr.blobstore.H2BlobStore
import io.javalin.testtools.JavalinTest
import mu.KotlinLogging
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.util.Base64

class BasicAuthTest {
    private lateinit var app: RegistryServerApp
    
    fun setUp() {
        // Set up test environment with auth disabled
        System.setProperty("NSCR_AUTH_ENABLED", "false")
        app = RegistryServerApp(KotlinLogging.logger {  }, H2BlobStore())
    }
    
    fun tearDown() {
        app.stop()
        // Clear environment variables
        System.clearProperty("NSCR_AUTH_ENABLED")
        System.clearProperty("NSCR_AUTH_USERNAME")
        System.clearProperty("NSCR_AUTH_PASSWORD")
    }
    
    @Test
    fun `test registry access without auth when disabled`() {
        setUp()
        JavalinTest.test(app.app) { _, client ->
            val response = client.get("/v2")
            assertEquals(200, response.code)
        }
        tearDown()
    }
    
    @Test
    fun `test registry access with auth when enabled`() {
        // Enable auth with test credentials
        System.setProperty("NSCR_AUTH_ENABLED", "true")
        System.setProperty("NSCR_AUTH_USERNAME", "testuser")
        System.setProperty("NSCR_AUTH_PASSWORD", "testpass")
        
        // Create new app with auth enabled
        app = RegistryServerApp(KotlinLogging.logger {  }, H2BlobStore())
        
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
        
        app.stop()
        // Clear environment variables
        System.clearProperty("NSCR_AUTH_ENABLED")
        System.clearProperty("NSCR_AUTH_USERNAME")
        System.clearProperty("NSCR_AUTH_PASSWORD")
    }
    
    @Test
    fun `test root endpoint is not protected`() {
        // Enable auth
        System.setProperty("NSCR_AUTH_ENABLED", "true")
        System.setProperty("NSCR_AUTH_USERNAME", "testuser")
        System.setProperty("NSCR_AUTH_PASSWORD", "testpass")
        
        app = RegistryServerApp(KotlinLogging.logger {  }, H2BlobStore())
        
        JavalinTest.test(app.app) { _, client ->
            // Root endpoint should be accessible without auth
            val response = client.get("/")
            assertEquals(200, response.code)
            val body = response.body?.string()
            // Should return HTML content (web interface) or "Hello World" if web interface disabled
            assertTrue(body?.contains("<!DOCTYPE html>") == true || body == "Hello World")
        }
        
        app.stop()
        // Clear environment variables
        System.clearProperty("NSCR_AUTH_ENABLED")
        System.clearProperty("NSCR_AUTH_USERNAME")
        System.clearProperty("NSCR_AUTH_PASSWORD")
    }
}
