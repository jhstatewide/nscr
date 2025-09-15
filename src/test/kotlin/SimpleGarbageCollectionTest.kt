package com.statewidesoftware.nscr

import com.statewidesoftware.nscr.blobstore.H2BlobStore
import com.statewidesoftware.nscr.blobstore.Digest
import com.statewidesoftware.nscr.blobstore.ImageVersion
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.*

class SimpleGarbageCollectionTest {
    
    private lateinit var tempDir: Path
    private lateinit var blobStore: H2BlobStore
    
    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("nscr-gc-test")
        blobStore = H2BlobStore(tempDir)
    }
    
    @AfterEach
    fun tearDown() {
        blobStore.cleanup()
        Files.deleteIfExists(tempDir.resolve("blobstore.mv.db"))
        Files.deleteIfExists(tempDir.resolve("blobstore.trace.db"))
        Files.deleteIfExists(tempDir)
    }
    
    @Test
    fun `garbage collection should remove orphaned manifests that reference never-stored blobs`() {
        // This test verifies that manifests referencing blobs that were never stored
        // are correctly identified as orphaned and removed
        
        // Create a manifest that references a blob that was never stored
        val neverStoredBlobDigest = Digest("never-stored-blob-digest")
        
        val orphanedManifestJson = """
        {
            "schemaVersion": 2,
            "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
            "config": {
                "mediaType": "application/vnd.docker.container.image.v1+json",
                "size": 100,
                "digest": "sha256:${neverStoredBlobDigest.digestString}"
            },
            "layers": []
        }
        """.trimIndent()
        
        val orphanedImage = ImageVersion("orphaned-repo", "orphaned-tag")
        blobStore.addManifest(orphanedImage, Digest("orphaned-manifest-digest"), orphanedManifestJson)
        
        // Verify initial state
        assertTrue(blobStore.hasManifest(orphanedImage))
        assertEquals(1, blobStore.listRepositories().size)
        
        // Run garbage collection
        val gcResult = blobStore.garbageCollect()
        
        // Verify results
        // - Orphaned manifest should be removed
        assertFalse(blobStore.hasManifest(orphanedImage))
        assertEquals(0, blobStore.listRepositories().size)
        assertTrue(gcResult.manifestsRemoved > 0)
        
        println("GC Result: blobsRemoved=${gcResult.blobsRemoved}, spaceFreed=${gcResult.spaceFreed}, manifestsRemoved=${gcResult.manifestsRemoved}")
    }
    
    @Test
    fun `garbage collection should not remove manifests when no blobs are garbage collected`() {
        // This test verifies that when there are no unreferenced blobs,
        // no manifests are removed
        
        // Create a manifest that references a blob that doesn't exist
        // but the blob was never stored (not just garbage collected)
        val neverStoredBlobDigest = Digest("never-stored-blob-digest")
        
        val manifestJson = """
        {
            "schemaVersion": 2,
            "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
            "config": {
                "mediaType": "application/vnd.docker.container.image.v1+json",
                "size": 100,
                "digest": "sha256:${neverStoredBlobDigest.digestString}"
            },
            "layers": []
        }
        """.trimIndent()
        
        val image = ImageVersion("test-repo", "test-tag")
        blobStore.addManifest(image, Digest("manifest-digest"), manifestJson)
        
        // Verify initial state
        assertTrue(blobStore.hasManifest(image))
        assertEquals(1, blobStore.listRepositories().size)
        
        // Run garbage collection
        val gcResult = blobStore.garbageCollect()
        
        // Verify results
        // - No blobs should be removed (none were stored)
        assertEquals(0, gcResult.blobsRemoved)
        assertEquals(0, gcResult.spaceFreed)
        
        // - Orphaned manifest should be removed
        assertTrue(gcResult.manifestsRemoved > 0)
        assertFalse(blobStore.hasManifest(image))
        assertEquals(0, blobStore.listRepositories().size)
        
        println("GC Result: blobsRemoved=${gcResult.blobsRemoved}, spaceFreed=${gcResult.spaceFreed}, manifestsRemoved=${gcResult.manifestsRemoved}")
    }
    
    @Test
    fun `garbage collection should handle empty registry correctly`() {
        // This test verifies that garbage collection works correctly on an empty registry
        
        // Verify initial state
        assertEquals(0, blobStore.listRepositories().size)
        
        // Run garbage collection
        val gcResult = blobStore.garbageCollect()
        
        // Verify results
        assertEquals(0, gcResult.blobsRemoved)
        assertEquals(0, gcResult.spaceFreed)
        assertEquals(0, gcResult.manifestsRemoved)
        
        println("GC Result: blobsRemoved=${gcResult.blobsRemoved}, spaceFreed=${gcResult.spaceFreed}, manifestsRemoved=${gcResult.manifestsRemoved}")
    }
    
    @Test
    fun `garbage collection should remove unreferenced blobs`() {
        // This test verifies that unreferenced blobs are removed
        
        // Create a blob that will be unreferenced
        val sessionId = SessionID(UUID.randomUUID().toString())
        val blobContent = "unreferenced-blob-content".toByteArray()
        blobStore.addBlob(sessionId, 1, ByteArrayInputStream(blobContent))
        
        // Associate the blob with a digest (this will validate the digest matches)
        val blobDigest = Digest(calculateSHA256(blobContent))
        blobStore.associateBlobWithSession(sessionId, blobDigest)
        
        // Verify the blob exists
        assertTrue(blobStore.hasBlob(blobDigest))
        
        // Run garbage collection
        val gcResult = blobStore.garbageCollect()
        
        // Verify results
        // - Unreferenced blob should be removed
        assertTrue(gcResult.blobsRemoved > 0)
        assertTrue(gcResult.spaceFreed > 0)
        assertFalse(blobStore.hasBlob(blobDigest))
        
        println("GC Result: blobsRemoved=${gcResult.blobsRemoved}, spaceFreed=${gcResult.spaceFreed}, manifestsRemoved=${gcResult.manifestsRemoved}")
    }
    
    private fun calculateSHA256(content: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(content)
        return hash.joinToString("") { "%02x".format(it) }
    }
}
