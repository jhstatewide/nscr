import io.javalin.testtools.JavalinTest
import mu.KotlinLogging
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerLifecycleTest {

    @Test
    fun testServerStartupAndShutdown() {
        val logger = KotlinLogging.logger {}
        val app = RegistryServerApp(logger)
        
        // Test that the server can be created and started
        val javalinApp = app.javalinApp()
        assertTrue(javalinApp != null, "Javalin app should be created")
        
        // Test basic functionality
        JavalinTest.test(javalinApp) { _, client ->
            val response = client.get("/")
            assertEquals("Hello World", response.body?.string())
        }
        
        // Test registry API
        JavalinTest.test(javalinApp) { _, client ->
            val response = client.get("/v2")
            assertEquals(200, response.code)
        }
        
        // Test that stop method works without throwing exceptions
        try {
            app.stop()
            assertTrue(true, "Server stopped successfully")
        } catch (e: Exception) {
            assertTrue(false, "Server stop should not throw exceptions: ${e.message}")
        }
    }
}
