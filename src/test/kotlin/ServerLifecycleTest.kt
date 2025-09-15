import com.statewidesoftware.nscr.RegistryServerApp
import com.statewidesoftware.nscr.blobstore.H2BlobStore
import io.javalin.testtools.JavalinTest
import mu.KotlinLogging
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.Files
import java.util.UUID
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

class ServerLifecycleTest {
    private lateinit var app: RegistryServerApp
    private lateinit var blobStore: H2BlobStore
    private lateinit var testDbPath: Path

    @BeforeEach
    fun setUp() {
        // Create unique test database path
        testDbPath = Paths.get("./tmp/test-data/server-lifecycle-test-${UUID.randomUUID()}")
        if (Files.exists(testDbPath)) {
            Files.walk(testDbPath)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
        Files.createDirectories(testDbPath)
        
        val logger = KotlinLogging.logger {}
        blobStore = H2BlobStore(testDbPath)
        app = RegistryServerApp(logger, blobStore)
    }
    
    @AfterEach
    fun tearDown() {
        try {
            app.stop()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
        
        try {
            blobStore.cleanup()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
        
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

    @Test
    fun testServerStartupAndShutdown() {
        // Test that the server can be created and started
        val javalinApp = app.app
        assertTrue(javalinApp != null, "Javalin app should be created")
        
        // Test basic functionality and registry API
        JavalinTest.test(javalinApp) { _, client ->
            // Test basic functionality
            val response1 = client.get("/")
            val body = response1.body?.string()
            // Should return HTML content (web interface) or "Hello World" if web interface disabled
            assertTrue(body?.contains("<!DOCTYPE html>") == true || body == "Hello World")
            
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
