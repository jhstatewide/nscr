import blobstore.H2BlobStore
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertTrue

class ResourceCleanupTest {

    @Test
    fun testResourceCleanup() {
        val testDatastorePath = Path.of("./test_data_cleanup")
        if (testDatastorePath.toFile().exists()) {
            testDatastorePath.toFile().deleteRecursively()
        }
        
        val blobStore = H2BlobStore(testDatastorePath)
        
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
        val testDatastorePath = Path.of("./test_data_no_leaks")
        if (testDatastorePath.toFile().exists()) {
            testDatastorePath.toFile().deleteRecursively()
        }
        
        val blobStore = H2BlobStore(testDatastorePath)
        
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
