package com.statewidesoftware.nscr

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.AfterAll
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.utility.DockerImageName
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait

/**
 * Kotlin wrapper so fluent methods like .waitingFor(...) work nicely.
 */
class KGenericContainer(imageName: DockerImageName) : GenericContainer<KGenericContainer>(imageName)

@Testcontainers
class DockerRegistryIntegrationTest {

    companion object {
        private val REGISTRY_IMAGE = DockerImageName.parse("registry:2")

        @Container
        @JvmStatic
        private val registry: KGenericContainer = KGenericContainer(REGISTRY_IMAGE)
            .withExposedPorts(5000)
            .waitingFor(Wait.forLogMessage(".*listening on.*", 1))
            .withReuse(true)

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            // Container will be started automatically by @Container,
            // but you can add extra readiness checks here if you want.
            assertTrue(registry.isRunning, "Registry should be running")
        }

        @AfterAll
        @JvmStatic
        fun afterAll() {
            // Stopped automatically; explicit stop() not required.
        }
    }

    @Test
    fun `registry should expose a port`() {
        val host = registry.host
        val port = registry.firstMappedPort
        assertTrue(port > 0, "Mapped port must be > 0 (got $port) @ $host")
    }
}
