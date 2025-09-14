package blobstore

import com.statewidesoftware.nscr.SessionID
import com.statewidesoftware.nscr.blobstore.Digest
import com.statewidesoftware.nscr.blobstore.H2BlobStore
import com.statewidesoftware.nscr.blobstore.ImageVersion
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Path
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

class ManifestDeleteRaceConditionTest {
    private lateinit var blobStore: H2BlobStore
    private val testDatastorePath = Path.of("./tmp/test-data/manifest-delete-race-test")
    
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
    fun testRemoveManifestIfExists() {
        val repositoryName = "test-repo"
        val tag = "latest"
        val sessionId = SessionID("test-session")
        
        // Add a blob
        val testData = "test data for race condition test".toByteArray()
        blobStore.addBlob(sessionId, 0, testData.inputStream())
        
        // Calculate digest
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digestBytes = md.digest(testData)
        val digestString = digestBytes.joinToString("") { "%02x".format(it) }
        val digest = Digest("sha256:$digestString")
        
        blobStore.associateBlobWithSession(sessionId, digest)
        
        // Create a manifest
        val manifestJson = """
            {
                "schemaVersion": 2,
                "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
                "config": {
                    "mediaType": "application/vnd.docker.container.image.v1+json",
                    "size": ${testData.size},
                    "digest": "${digest.digestString}"
                },
                "layers": []
            }
        """.trimIndent()
        
        val imageVersion = ImageVersion(repositoryName, tag)
        blobStore.addManifest(imageVersion, digest, manifestJson)
        
        // Verify manifest exists
        assertTrue(blobStore.hasManifest(imageVersion), "Manifest should exist before deletion")
        
        // Test removeManifestIfExists on existing manifest
        val wasDeleted = blobStore.removeManifestIfExists(imageVersion)
        assertTrue(wasDeleted, "removeManifestIfExists should return true for existing manifest")
        
        // Verify manifest no longer exists
        assertFalse(blobStore.hasManifest(imageVersion), "Manifest should not exist after deletion")
        
        // Test removeManifestIfExists on non-existing manifest
        val wasDeletedAgain = blobStore.removeManifestIfExists(imageVersion)
        assertFalse(wasDeletedAgain, "removeManifestIfExists should return false for non-existing manifest")
    }
    
    @Test
    fun testConcurrentDeleteOperations() {
        val repositoryName = "concurrent-repo"
        val tag = "latest"
        val sessionId = SessionID("test-session")
        
        // Add a blob
        val testData = "test data for concurrent delete test".toByteArray()
        blobStore.addBlob(sessionId, 0, testData.inputStream())
        
        // Calculate digest
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digestBytes = md.digest(testData)
        val digestString = digestBytes.joinToString("") { "%02x".format(it) }
        val digest = Digest("sha256:$digestString")
        
        blobStore.associateBlobWithSession(sessionId, digest)
        
        // Create a manifest
        val manifestJson = """
            {
                "schemaVersion": 2,
                "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
                "config": {
                    "mediaType": "application/vnd.docker.container.image.v1+json",
                    "size": ${testData.size},
                    "digest": "${digest.digestString}"
                },
                "layers": []
            }
        """.trimIndent()
        
        val imageVersion = ImageVersion(repositoryName, tag)
        blobStore.addManifest(imageVersion, digest, manifestJson)
        
        // Verify manifest exists
        assertTrue(blobStore.hasManifest(imageVersion), "Manifest should exist before concurrent deletion")
        
        // Create multiple threads that try to delete the same manifest concurrently
        val executor = Executors.newFixedThreadPool(10)
        val latch = CountDownLatch(10)
        val results = mutableListOf<Boolean>()
        
        repeat(10) {
            executor.submit {
                try {
                    val result = blobStore.removeManifestIfExists(imageVersion)
                    synchronized(results) {
                        results.add(result)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }
        
        // Wait for all threads to complete
        assertTrue(latch.await(5, TimeUnit.SECONDS), "All threads should complete within 5 seconds")
        executor.shutdown()
        
        // Verify results
        assertEquals(10, results.size, "All threads should have completed")
        
        // Only one thread should have successfully deleted the manifest
        val successfulDeletes = results.count { it }
        assertEquals(1, successfulDeletes, "Exactly one thread should have successfully deleted the manifest")
        
        // The other 9 threads should have found the manifest already deleted
        val failedDeletes = results.count { !it }
        assertEquals(9, failedDeletes, "Nine threads should have found the manifest already deleted")
        
        // Verify manifest no longer exists
        assertFalse(blobStore.hasManifest(imageVersion), "Manifest should not exist after concurrent deletion")
    }
    
    @Test
    fun testDeleteNonExistentManifest() {
        val imageVersion = ImageVersion("non-existent-repo", "non-existent-tag")
        
        // Verify manifest doesn't exist
        assertFalse(blobStore.hasManifest(imageVersion), "Non-existent manifest should not exist")
        
        // Test removeManifestIfExists on non-existing manifest
        val wasDeleted = blobStore.removeManifestIfExists(imageVersion)
        assertFalse(wasDeleted, "removeManifestIfExists should return false for non-existing manifest")
    }
}
