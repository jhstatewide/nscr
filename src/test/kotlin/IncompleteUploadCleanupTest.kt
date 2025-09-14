package blobstore

import SessionID
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.Path

class IncompleteUploadCleanupTest {
    private lateinit var blobStore: H2BlobStore
    private val testDatastorePath = Path.of("./tmp/test-data/incomplete-upload-cleanup-test")
    
    @BeforeEach
    fun setUp() {
        // Clean up any existing test data
        if (Files.exists(testDatastorePath)) {
            Files.walk(testDatastorePath)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
        Files.createDirectories(testDatastorePath)
        
        blobStore = H2BlobStore(testDatastorePath)
    }
    
    @AfterEach
    fun tearDown() {
        blobStore.stopCleanupTask()
        if (Files.exists(testDatastorePath)) {
            Files.walk(testDatastorePath)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }
    
    @Test
    fun testCleanupIncompleteUploads() {
        // Create some incomplete uploads (blobs with null digests)
        val session1 = SessionID("test-session-1")
        val session2 = SessionID("test-session-2")
        val session3 = SessionID("test-session-3")
        
        // Add blobs with null digests (incomplete uploads)
        blobStore.addBlob(session1, 0, "test data 1".byteInputStream())
        blobStore.addBlob(session2, 0, "test data 2".byteInputStream())
        blobStore.addBlob(session3, 0, "test data 3".byteInputStream())
        
        // Add one complete blob for comparison
        val completeSession = SessionID("complete-session")
        val completeData = "complete data".toByteArray()
        blobStore.addBlob(completeSession, 0, completeData.inputStream())
        // Calculate proper SHA-256 digest
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digestBytes = md.digest(completeData)
        val digestString = digestBytes.joinToString("") { "%02x".format(it) }
        blobStore.associateBlobWithSession(completeSession, Digest("sha256:$digestString"))
        
        // Verify initial state
        val initialStats = blobStore.getGarbageCollectionStats()
        assertEquals(1, initialStats.totalBlobs) // Only the complete blob is counted (incomplete blobs have null digests)
        
        // Run cleanup
        val result = blobStore.cleanupIncompleteUploads()
        
        // Verify cleanup results
        assertTrue(result.blobsRemoved >= 3) // Should remove at least the 3 incomplete blobs
        assertTrue(result.spaceFreed > 0)
        assertTrue(result.sessionsRemoved >= 3) // Should clean at least 3 sessions
        
        // Verify final state
        val finalStats = blobStore.getGarbageCollectionStats()
        assertEquals(1, finalStats.totalBlobs) // Only the complete blob should remain (incomplete blobs were removed)
    }
    
    @Test
    fun testCleanupTaskStartAndStop() {
        // Test that the cleanup task can be started and stopped
        assertDoesNotThrow {
            blobStore.startCleanupTask()
        }
        
        assertDoesNotThrow {
            blobStore.stopCleanupTask()
        }
    }
    
    @Test
    fun testCleanupWithMixedBlobs() {
        // Create a mix of complete and incomplete blobs
        val incompleteSession = SessionID("incomplete-session")
        val completeSession = SessionID("complete-session")
        
        // Add incomplete blob
        val incompleteData = "incomplete data".toByteArray()
        blobStore.addBlob(incompleteSession, 0, incompleteData.inputStream())
        
        // Add complete blob
        val completeData = "complete data".toByteArray()
        blobStore.addBlob(completeSession, 0, completeData.inputStream())
        // Calculate proper SHA-256 digest
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digestBytes = md.digest(completeData)
        val digestString = digestBytes.joinToString("") { "%02x".format(it) }
        blobStore.associateBlobWithSession(completeSession, Digest("sha256:$digestString"))
        
        // Verify initial state
        val initialStats = blobStore.getGarbageCollectionStats()
        assertEquals(1, initialStats.totalBlobs) // Only the complete blob is counted (incomplete blob has null digest)
        
        // Run cleanup
        val result = blobStore.cleanupIncompleteUploads()
        
        // Verify only incomplete blob was removed
        assertEquals(1, result.blobsRemoved)
        assertTrue(result.spaceFreed > 0)
        assertEquals(1, result.sessionsRemoved)
        
        // Verify final state
        val finalStats = blobStore.getGarbageCollectionStats()
        assertEquals(1, finalStats.totalBlobs) // Only complete blob remains
    }
}
