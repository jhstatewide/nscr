package com.statewidesoftware.nscr

import com.statewidesoftware.nscr.blobstore.H2BlobStore
import io.javalin.testtools.JavalinTest
import mu.KotlinLogging
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BlobUploadTest {

    private val logger = KotlinLogging.logger {}
    private lateinit var tempDir: File
    private lateinit var blobStore: H2BlobStore
    private lateinit var app: RegistryServerApp

    @BeforeEach
    fun setup() {
        tempDir = Files.createTempDirectory("nscr-test-${UUID.randomUUID()}").toFile()
        blobStore = H2BlobStore(tempDir.toPath())
        app = RegistryServerApp(logger, blobStore)
    }

    @AfterEach
    fun cleanup() {
        try {
            app.stop()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
        if (tempDir.exists()) {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `test blob upload session creation`() {
        JavalinTest.test(app.app) { _, client ->
            logger.info { "Testing blob upload session creation..." }
            
            // Test POST to /v2/{image}/blobs/uploads/ - this should create a session
            val response = client.post("/v2/test-image/blobs/uploads/")
            
            logger.info { "Response code: ${response.code}" }
            logger.info { "Response headers: ${response.headers}" }
            logger.info { "Response body: ${response.body?.string()}" }
            
            assertEquals(202, response.code, "Expected 202 Accepted for blob upload session creation")
            
            // Check for required headers
            val location = response.header("Location")
            val uploadUuid = response.header("Docker-Upload-UUID")
            
            assertNotNull(location, "Location header should be present")
            assertNotNull(uploadUuid, "Docker-Upload-UUID header should be present")
            
            logger.info { "Location: $location" }
            logger.info { "Upload UUID: $uploadUuid" }
            
            // Location should be in format /v2/uploads/{sessionID}/{blobNumber}
            assertTrue(location!!.startsWith("/v2/uploads/"), "Location should start with /v2/uploads/")
        }
    }

    @Test
    fun `test blob upload with digest parameter`() {
        JavalinTest.test(app.app) { _, client ->
            logger.info { "Testing blob upload with digest parameter..." }
            
            val testDigest = "sha256:1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
            
            // Test POST with digest parameter
            val response = client.post("/v2/test-image/blobs/uploads/?digest=$testDigest")
            
            logger.info { "Response code: ${response.code}" }
            logger.info { "Response headers: ${response.headers}" }
            logger.info { "Response body: ${response.body?.string()}" }
            
            // When digest is provided but no blob chunks exist, should return 202 (Accepted)
            // This is the correct behavior - the server should accept the request and wait for blob data
            assertEquals(202, response.code, "Expected 202 for blob upload with digest - server should accept and wait for blob data")
        }
    }

    @Test
    fun `test basic server functionality`() {
        JavalinTest.test(app.app) { _, client ->
            logger.info { "Testing basic server functionality..." }
            
            // Test that the server is responding
            val response = client.get("/v2")
            assertEquals(200, response.code, "Server should respond to /v2 endpoint")
            
            // Test that we can create an upload session
            val uploadResponse = client.post("/v2/test/blobs/uploads/")
            assertEquals(202, uploadResponse.code, "Should be able to create upload session")
            
            logger.info { "Basic server functionality test passed!" }
        }
    }
}