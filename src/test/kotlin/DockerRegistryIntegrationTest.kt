package com.statewidesoftware.nscr

import com.statewidesoftware.nscr.blobstore.H2BlobStore
import com.statewidesoftware.nscr.RegistryServerApp
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.ExtendWith
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

@ExtendWith(TestcontainersExtension::class)
class DockerRegistryIntegrationTest {

    private lateinit var registry: GenericContainer<*>
    private val testImageName = "test-registry-image"
    private val testImageTag = "v1"

    @BeforeEach
    fun setup() {
        // Start registry server
        registry = GenericContainer<Nothing>("registry:2")
            .withExposedPorts(5000)
            .waitingFor(Wait.forLogMessage(".*listening on.*", 1))
            .withReuse(true)
        
        registry.start()
    }

    @AfterEach
    fun cleanup() {
        registry.stop()
    }

    @Test
    fun `docker push and pull should work with registry`() {
        // Build a simple test image
        val testImage = buildTestImage()
        
        // Tag the image for our registry
        val taggedImage = "$registry.host:$registry.firstMappedPort/$testImageName:$testImageTag"
        executeDockerCommand("tag", testImage, taggedImage)
        
        // Push to registry
        executeDockerCommand("push", taggedImage)
        
        // Pull from registry
        val pulledImage = "$testImageName-pulled:$testImageTag"
        executeDockerCommand("pull", taggedImage)
        executeDockerCommand("tag", taggedImage, pulledImage)
        
        // Verify content matches
        val originalContent = Files.readString(Path.of(testImage, "content.txt"))
        val pulledContent = Files.readString(Path.of(pulledImage, "content.txt"))
        assertEquals(originalContent, pulledContent, "Image content mismatch after pull")
    }

    private fun buildTestImage(): String {
        val imageDir = Files.createTempDirectory("test-image")
        Files.write(imageDir.resolve("Dockerfile"), """
            FROM alpine:latest
            RUN echo "Hello from test image" > /content.txt
            CMD ["cat", "/content.txt"]
        """.trimIndent().lines())
        Files.write(imageDir.resolve("content.txt"), "Hello from test image".toByteArray())
        
        executeDockerCommand("build", "-t", testImageName, ".")
        return testImageName
    }

    private fun executeDockerCommand(vararg args: String) {
        val process = ProcessBuilder("docker", *args)
            .inheritIO()
            .start()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw RuntimeException("Docker command failed: ${args.joinToString(" ")}")
        }
    }
}
