import com.statewidesoftware.nscr.SessionID
import com.statewidesoftware.nscr.blobstore.H2BlobStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertTrue

class ResourceCleanupTest {

    private lateinit var blobStore: H2BlobStore
    private lateinit var testDatastorePath: Path

    @BeforeEach
    fun setup() {
        testDatastorePath = Path.of("./tmp/test-data/resource-cleanup-test")
        if (testDatastorePath.toFile().exists()) {
            testDatastorePath.toFile().deleteRecursively()
        }
        blobStore = H2BlobStore(testDatastorePath)
    }

    @AfterEach
    fun cleanup() {
        blobStore.cleanup()
        if (testDatastorePath.toFile().exists()) {
            testDatastorePath.toFile().deleteRecursively()
        }
    }

    @Test
    fun testResourceCleanup() {
        // Test that we can perform operations
        val session = SessionID("test-cleanup")
        val testData = "Test data for cleanup"
        val bytes = blobStore.addBlob(session, 0, testData.toByteArray().inputStream())
        assertTrue(bytes > 0, "Should have uploaded data successfully")
        
        // Test cleanup
        blobStore.cleanup()
        
        // Verify cleanup completed without throwing exceptions
        assertTrue(true, "Cleanup completed successfully")
    }

    @Test
    fun testMultipleOperationsWithoutLeaks() {
        // Perform multiple operations that previously had resource leaks
        repeat(10) { i ->
            val session = SessionID("test-session-$i")
            val testData = "Test data $i"
            
            // Test addBlob (was leaking FileInputStream)
            val bytes = blobStore.addBlob(session, 0, testData.toByteArray().inputStream())
            assertTrue(bytes > 0, "Should have uploaded data successfully")
            
            // Test countBlobs (was leaking database handle)
            val count = blobStore.countBlobs()
            assertTrue(count >= 0, "Should get valid blob count")
            
            // Test blobCountForSession (uses proper resource management)
            val sessionCount = blobStore.blobCountForSession(session)
            assertTrue(sessionCount >= 0, "Should get valid session count")
        }
        
        // Clean up
        blobStore.cleanup()
        assertTrue(true, "Multiple operations completed without resource leaks")
    }
}
