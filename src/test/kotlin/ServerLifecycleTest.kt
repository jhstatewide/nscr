import com.statewidesoftware.nscr.RegistryServerApp
import io.javalin.testtools.JavalinTest
import mu.KotlinLogging
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerLifecycleTest {

    @Test
    fun testServerStartupAndShutdown() {
        val logger = KotlinLogging.logger {}
        val app = RegistryServerApp(logger)
        
        // Test that the server can be created and started
        val javalinApp = app.app
        assertTrue(javalinApp != null, "Javalin app should be created")
        
        // Test basic functionality and registry API
        JavalinTest.test(javalinApp) { _, client ->
            // Test basic functionality
            val response1 = client.get("/")
            assertEquals("Hello World", response1.body?.string())
            
            // Test registry API
            val response2 = client.get("/v2")
            assertEquals(200, response2.code)
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
