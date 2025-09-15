package com.statewidesoftware.nscr

import com.statewidesoftware.nscr.blobstore.H2BlobStore
import com.statewidesoftware.nscr.blobstore.Digest
import com.statewidesoftware.nscr.blobstore.ImageVersion
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

class DebugGarbageCollectionTest {
    
    private lateinit var tempDir: Path
    private lateinit var blobStore: H2BlobStore
    
    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("nscr-gc-debug")
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
    fun `debug orphaned manifest detection`() {
        // Create a manifest that references a blob that was never stored
        val neverStoredBlobDigest = Digest("sha256:never-stored-blob-digest")
        
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
        
        println("=== BEFORE GARBAGE COLLECTION ===")
        println("Manifest exists: ${blobStore.hasManifest(orphanedImage)}")
        println("Repository count: ${blobStore.listRepositories().size}")
        println("Blob exists: ${blobStore.hasBlob(neverStoredBlobDigest)}")
        
        // Run garbage collection
        val gcResult = blobStore.garbageCollect()
        
        println("=== AFTER GARBAGE COLLECTION ===")
        println("GC Result: blobsRemoved=${gcResult.blobsRemoved}, spaceFreed=${gcResult.spaceFreed}, manifestsRemoved=${gcResult.manifestsRemoved}")
        println("Manifest exists: ${blobStore.hasManifest(orphanedImage)}")
        println("Repository count: ${blobStore.listRepositories().size}")
        println("Blob exists: ${blobStore.hasBlob(neverStoredBlobDigest)}")
        
        // The test should pass if the manifest is removed
        assert(!blobStore.hasManifest(orphanedImage))
        assert(blobStore.listRepositories().isEmpty())
        assert(gcResult.manifestsRemoved > 0)
    }
}
