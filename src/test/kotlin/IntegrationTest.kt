package com.statewidesoftware.nscr

import com.statewidesoftware.nscr.blobstore.Blobstore
import com.statewidesoftware.nscr.blobstore.H2BlobStore
import com.statewidesoftware.nscr.blobstore.ImageVersion
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.model.PullResponseItem
import com.github.dockerjava.api.model.PushResponseItem
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import com.github.dockerjava.transport.DockerHttpClient
import io.javalin.testtools.JavalinTest
import io.mockk.every
import io.mockk.mockk
import mu.KotlinLogging
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.utility.DockerImageName
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.nio.file.Path
import java.time.Duration
import java.net.ServerSocket

/**
 * Comprehensive integration tests covering:
 * - Docker container integration
 * - Registry API integration
 * - Image push/pull operations
 * - Web interface functionality
 */

/**
 * Kotlin wrapper so fluent methods like .waitingFor(...) work nicely.
 */
class KGenericContainer(imageName: DockerImageName) : GenericContainer<KGenericContainer>(imageName)

@Testcontainers
class IntegrationTest {
    
    companion object {
        private val REGISTRY_IMAGE = DockerImageName.parse("registry:2")
        private val testBlobStoreDirectory: Path = Path.of("./tmp/test-data/integration-test")

        @Container
        @JvmStatic
        private val registry: KGenericContainer = KGenericContainer(REGISTRY_IMAGE)
            .withExposedPorts(5000)
            .waitingFor(Wait.forLogMessage(".*listening on.*", 1))
            .withReuse(true)

        @JvmStatic
        @BeforeAll
        fun setup() {
            // Setup test blobstore
            if (testBlobStoreDirectory.toFile().exists()) {
                testBlobStoreDirectory.toFile().deleteRecursively()
            }
            testBlobStoreDirectory.toFile().mkdirs()
            
            // Verify registry container is running
            assertTrue(registry.isRunning, "Registry should be running")
        }

        @JvmStatic
        @AfterAll
        fun cleanup() {
            // Cleanup test blobstore
            if (testBlobStoreDirectory.toFile().exists()) {
                testBlobStoreDirectory.toFile().deleteRecursively()
            }
        }
    }

    // ===== DOCKER CONTAINER INTEGRATION TESTS =====
    
    @Test
    fun `registry should expose a port`() {
        val host = registry.host
        val port = registry.firstMappedPort
        assertTrue(port > 0, "Mapped port must be > 0 (got $port) @ $host")
    }
    
    @Test
    fun `docker integration should work with real docker client`() {
        // Find an available port dynamically
        val serverSocket = ServerSocket(0)
        val availablePort = serverSocket.localPort
        serverSocket.close()
        
        val config: DockerClientConfig = DefaultDockerClientConfig.createDefaultConfigBuilder()
            .withRegistryUrl("http://localhost:$availablePort")
            .withCustomSslConfig(null)
            .build()
        val httpClient: DockerHttpClient = ApacheDockerHttpClient.Builder()
            .dockerHost(config.dockerHost)
            .sslConfig(config.sslConfig)
            .maxConnections(100)
            .connectionTimeout(Duration.ofSeconds(30))
            .responseTimeout(Duration.ofSeconds(45))
            .build()

        val dockerClient = DockerClientImpl.getInstance(config, httpClient)
        dockerClient.pingCmd().exec()

        val hostedTaggedImage = "localhost:$availablePort/ubuntu:20.04"

        val cb = ResultCallback.Adapter<PullResponseItem>()
        dockerClient.pullImageCmd("ubuntu:20.04").exec(cb).awaitCompletion()
        cb.awaitCompletion()

        dockerClient.tagImageCmd("ubuntu:20.04", "localhost:$availablePort/ubuntu", "20.04").exec()

        val logger = KotlinLogging.logger { }
        logger.info { "Starting integration test with Docker client" }
        val blobStore = H2BlobStore(testBlobStoreDirectory)
        RegistryServerApp(logger, blobStore).start(availablePort)

        // Get number of blobs from blobStore before push
        val numBlobs = blobStore.countBlobs()

        val cb2 = ResultCallback.Adapter<PushResponseItem>()
        dockerClient.pushImageCmd(hostedTaggedImage).exec(cb2).awaitCompletion()
        cb2.awaitCompletion()
        
        // Expect numBlobs to be greater than it was
        assertTrue(numBlobs < blobStore.countBlobs(), "Blob count should increase after push")
        logger.debug { "numBlobs: $numBlobs. New count: ${blobStore.countBlobs()}" }

        // Let's make sure we got a manifest as well...
        val manifest = blobStore.getManifest(ImageVersion("ubuntu", "20.04"))
        logger.debug { "manifest: $manifest" }
        // Assert that manifest is not blank
        assertTrue(manifest.isNotBlank(), "Manifest should not be blank")
    }

    // ===== WEB INTERFACE INTEGRATION TESTS =====
    
    @Test
    fun `web interface should return HTML content`() {
        val klogger = KotlinLogging.logger {}
        val mockBlobStore = mockk<Blobstore>(relaxed = true)

        every { mockBlobStore.addBlob(any(), any(), any()) } returns 202

        val javalinApp = RegistryServerApp(logger = klogger, mockBlobStore).app

        JavalinTest.test(javalinApp) { _, client ->
            val response = client.get("/")
            val body = response.body?.string()
            // Should return HTML content (web interface) or "Hello World" if web interface disabled
            assertTrue(body?.contains("<!DOCTYPE html>") == true || body == "Hello World")
        }
    }

    @Test
    fun `blob upload endpoint should work`() {
        val klogger = KotlinLogging.logger {}
        val mockBlobStore = mockk<Blobstore>(relaxed = true)

        every { mockBlobStore.addBlob(any(), any(), any()) } returns 202

        val javalinApp = RegistryServerApp(logger = klogger, mockBlobStore).app

        JavalinTest.test(javalinApp) { _, client ->
            val response = client.post("/v2/test/blobs/uploads/")
            assertEquals(202, response.code)
        }
    }

    // ===== REGISTRY API INTEGRATION TESTS =====
    
    @Test
    fun `registry API should handle basic operations`() {
        val logger = KotlinLogging.logger {}
        val blobStore = H2BlobStore(testBlobStoreDirectory)
        val app = RegistryServerApp(logger, blobStore)

        JavalinTest.test(app.app) { _, client ->
            // Test registry API v2 endpoints
            val v2Response = client.get("/v2/")
            assertEquals(200, v2Response.code)
            
            // Test catalog endpoint
            val catalogResponse = client.get("/v2/_catalog")
            assertEquals(200, catalogResponse.code)
            
            // Test blob upload initiation
            val uploadResponse = client.post("/v2/test/blobs/uploads/")
            assertEquals(202, uploadResponse.code)
        }
    }
}
