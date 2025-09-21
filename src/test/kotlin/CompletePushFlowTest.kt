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

/**
 * This test reproduces the exact bug that was reported:
 * "When pushing an image, layers show up as 'unreferenced' and get garbage collected
 * even though they should be referenced by the manifest"
 */
class CompletePushFlowTest {
    
    private lateinit var tempDir: Path
    private lateinit var blobStore: H2BlobStore
    
    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("nscr-push-flow-test")
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
    fun `complete push flow should not garbage collect referenced blobs`() {
        // This test reproduces the exact scenario from the bug report:
        // 1. Push 5 layers (blobs) - exactly like the user's dockage/mailcatcher push
        // 2. Push a manifest that references those layers
        // 3. Run garbage collection
        // 4. Verify that the layers are NOT removed (they should be referenced)
        
        println("=== SIMULATING EXACT DOCKER PUSH FLOW: dockage/mailcatcher ===")
        
        // Step 1: Upload 5 blob layers (exactly like the user's scenario)
        val layerDigests = mutableListOf<Digest>()
        val layerContents = mutableListOf<ByteArray>()
        
        // Create realistic layer content that simulates actual Docker layers
        val layerDescriptions = listOf(
            "Base layer with OS packages and dependencies",
            "Application runtime and libraries layer", 
            "Mailcatcher application code and configuration",
            "SMTP server components and networking setup",
            "Final layer with startup scripts and environment"
        )
        
        for (i in 1..5) {
            val sessionId = SessionID(UUID.randomUUID().toString())
            val layerContent = layerDescriptions[i-1].toByteArray()
            layerContents.add(layerContent)
            
            // Upload the layer
            blobStore.addBlob(sessionId, 0, ByteArrayInputStream(layerContent))
            
            // Calculate and associate the digest
            val layerDigest = Digest("sha256:${calculateSHA256(layerContent)}")
            layerDigests.add(layerDigest)
            blobStore.associateBlobWithSession(sessionId, layerDigest)
            
            println("Pushed layer $i: ${layerDigest.digestString}")
            println("  Content: ${layerDescriptions[i-1]}")
            assertTrue(blobStore.hasBlob(layerDigest), "Layer $i should exist after push")
        }
        
        // Step 2: Create a manifest that references all 5 layers (like Docker does)
        val manifestJson = createRealisticManifestJson(layerDigests)
        val manifestDigest = Digest("sha256:${calculateSHA256(manifestJson.toByteArray())}")
        val imageVersion = ImageVersion("dockage/mailcatcher", "latest")
        
        println("Created manifest for dockage/mailcatcher:latest")
        println("Manifest digest: ${manifestDigest.digestString}")
        println("Manifest references ${layerDigests.size} layers")
        
        // Add the manifest (this is what ties the layers to the repository)
        blobStore.addManifest(imageVersion, manifestDigest, manifestJson)
        
        // Verify manifest exists
        assertTrue(blobStore.hasManifest(imageVersion), "Manifest should exist after push")
        
        // Step 3: Verify all layers are still there before GC
        println("=== BEFORE GARBAGE COLLECTION ===")
        layerDigests.forEachIndexed { index, digest ->
            assertTrue(blobStore.hasBlob(digest), "Layer ${index + 1} should exist before GC")
            println("Layer ${index + 1} exists: ${blobStore.hasBlob(digest)}")
        }
        
        // Get initial stats
        val initialStats = blobStore.getGarbageCollectionStats()
        println("Initial GC stats: ${initialStats.totalBlobs} blobs, ${initialStats.unreferencedBlobs} unreferenced")
        
        // Step 4: Run garbage collection (this is where the bug occurred)
        println("=== RUNNING GARBAGE COLLECTION ===")
        val gcResult = blobStore.garbageCollect()
        
        println("GC Result: blobsRemoved=${gcResult.blobsRemoved}, spaceFreed=${gcResult.spaceFreed}, manifestsRemoved=${gcResult.manifestsRemoved}")
        
        // Step 5: Verify that referenced layers are NOT removed (this is the critical assertion)
        println("=== AFTER GARBAGE COLLECTION ===")
        layerDigests.forEachIndexed { index, digest ->
            assertTrue(blobStore.hasBlob(digest), "Layer ${index + 1} should still exist after GC (it's referenced by manifest)")
            println("Layer ${index + 1} exists: ${blobStore.hasBlob(digest)}")
        }
        
        // The manifest should also still exist
        assertTrue(blobStore.hasManifest(imageVersion), "Manifest should still exist after GC")
        
        // CRITICAL ASSERTIONS: No blobs should have been removed since they're all referenced
        assertEquals(0, gcResult.blobsRemoved, "BUG REPRODUCTION: No blobs should be removed since they're all referenced by the manifest")
        assertEquals(0, gcResult.spaceFreed, "BUG REPRODUCTION: No space should be freed since no blobs were removed")
        assertEquals(0, gcResult.manifestsRemoved, "BUG REPRODUCTION: No manifests should be removed")
        
        // Verify the repository is still accessible
        val repositories = blobStore.listRepositories()
        assertTrue(repositories.contains("dockage/mailcatcher"), "Repository should still be listed")
        
        val tags = blobStore.listTags("dockage/mailcatcher")
        assertTrue(tags.contains("latest"), "Latest tag should still be available")
        
        println("=== TEST PASSED: All referenced blobs preserved during GC ===")
        println("=== This test would have caught the original bug ===")
    }
    
    @Test
    fun `garbage collection should remove unreferenced blobs but keep referenced ones`() {
        // This test verifies the mixed scenario:
        // 1. Upload some referenced blobs (with manifest)
        // 2. Upload some unreferenced blobs (orphaned)
        // 3. Run GC
        // 4. Verify referenced blobs remain, unreferenced blobs are removed
        
        println("=== TESTING MIXED SCENARIO: REFERENCED + UNREFERENCED BLOBS ===")
        
        // Create 2 referenced blobs
        val referencedDigests = mutableListOf<Digest>()
        for (i in 1..2) {
            val sessionId = SessionID(UUID.randomUUID().toString())
            val content = "Referenced blob $i".toByteArray()
            blobStore.addBlob(sessionId, 0, ByteArrayInputStream(content))
            val digest = Digest("sha256:${calculateSHA256(content)}")
            referencedDigests.add(digest)
            blobStore.associateBlobWithSession(sessionId, digest)
        }
        
        // Create 2 unreferenced blobs (orphaned)
        val unreferencedDigests = mutableListOf<Digest>()
        for (i in 1..2) {
            val sessionId = SessionID(UUID.randomUUID().toString())
            val content = "Unreferenced blob $i".toByteArray()
            blobStore.addBlob(sessionId, 0, ByteArrayInputStream(content))
            val digest = Digest("sha256:${calculateSHA256(content)}")
            unreferencedDigests.add(digest)
            blobStore.associateBlobWithSession(sessionId, digest)
        }
        
        // Create manifest that only references the first 2 blobs
        val manifestJson = createManifestJson(referencedDigests)
        val manifestDigest = Digest("sha256:${calculateSHA256(manifestJson.toByteArray())}")
        val imageVersion = ImageVersion("test/repo", "latest")
        blobStore.addManifest(imageVersion, manifestDigest, manifestJson)
        
        println("Created manifest referencing ${referencedDigests.size} blobs")
        println("Created ${unreferencedDigests.size} unreferenced blobs")
        
        // Verify initial state
        referencedDigests.forEach { assertTrue(blobStore.hasBlob(it)) }
        unreferencedDigests.forEach { assertTrue(blobStore.hasBlob(it)) }
        
        // Run garbage collection
        val gcResult = blobStore.garbageCollect()
        
        println("GC Result: blobsRemoved=${gcResult.blobsRemoved}, spaceFreed=${gcResult.spaceFreed}, manifestsRemoved=${gcResult.manifestsRemoved}")
        
        // Verify results
        referencedDigests.forEach { 
            assertTrue(blobStore.hasBlob(it), "Referenced blob should still exist after GC")
        }
        unreferencedDigests.forEach { 
            assertFalse(blobStore.hasBlob(it), "Unreferenced blob should be removed by GC")
        }
        
        assertEquals(2, gcResult.blobsRemoved, "Should remove 2 unreferenced blobs")
        assertTrue(gcResult.spaceFreed > 0, "Should free some space")
        assertEquals(0, gcResult.manifestsRemoved, "Should not remove any manifests")
        
        println("=== TEST PASSED: Referenced blobs preserved, unreferenced blobs removed ===")
    }
    
    @Test
    fun `docker push workflow should preserve all layers after garbage collection`() {
        // This test simulates the exact workflow described in the bug report:
        // 1. Docker push nscr.lan:7000/dockage/mailcatcher (5 layers pushed)
        // 2. Image appears in UI
        // 3. Garbage collection runs
        // 4. All 5 layers should still be there (not garbage collected)
        
        println("=== SIMULATING EXACT DOCKER PUSH WORKFLOW ===")
        println("Command: docker push nscr.lan:7000/dockage/mailcatcher")
        
        // Step 1: Simulate the 5 layers being pushed (as shown in the user's output)
        val pushedLayers = mutableListOf<Digest>()
        val layerNames = listOf(
            "0c677f96b698",  // These are the actual layer IDs from the user's output
            "728e2abe2a79",
            "9cfc12d53df6", 
            "aedc3bda2944",
            "latest"         // The final layer
        )
        
        for (i in 1..5) {
            val sessionId = SessionID(UUID.randomUUID().toString())
            val layerContent = "Docker layer content for ${layerNames[i-1]}".toByteArray()
            
            // Push the layer
            blobStore.addBlob(sessionId, 0, ByteArrayInputStream(layerContent))
            
            // Calculate digest and associate
            val layerDigest = Digest("sha256:${calculateSHA256(layerContent)}")
            pushedLayers.add(layerDigest)
            blobStore.associateBlobWithSession(sessionId, layerDigest)
            
            println("Pushed ${layerNames[i-1]}: ${layerDigest.digestString}")
        }
        
        // Step 2: Create the manifest (this is what makes the image visible in UI)
        val manifestJson = createRealisticManifestJson(pushedLayers)
        val manifestDigest = Digest("sha256:${calculateSHA256(manifestJson.toByteArray())}")
        val imageVersion = ImageVersion("dockage/mailcatcher", "latest")
        
        blobStore.addManifest(imageVersion, manifestDigest, manifestJson)
        
        println("Created manifest: ${manifestDigest.digestString}")
        println("Image should now be visible in UI")
        
        // Step 3: Verify the image is accessible (like in the UI)
        assertTrue(blobStore.hasManifest(imageVersion), "Image should be accessible")
        val repositories = blobStore.listRepositories()
        assertTrue(repositories.contains("dockage/mailcatcher"), "Repository should be listed")
        
        // Step 4: Check initial state before GC
        val initialStats = blobStore.getGarbageCollectionStats()
        println("Before GC: ${initialStats.totalBlobs} total blobs, ${initialStats.unreferencedBlobs} unreferenced")
        
        // Verify all layers exist before GC
        pushedLayers.forEachIndexed { index, digest ->
            assertTrue(blobStore.hasBlob(digest), "Layer ${index + 1} should exist before GC")
        }
        
        // Step 5: Run garbage collection (this is where the bug occurred)
        println("Running garbage collection...")
        val gcResult = blobStore.garbageCollect()
        
        println("GC Result: blobsRemoved=${gcResult.blobsRemoved}, spaceFreed=${gcResult.spaceFreed}, manifestsRemoved=${gcResult.manifestsRemoved}")
        
        // Step 6: CRITICAL VERIFICATION - All layers should still exist
        println("=== VERIFYING LAYERS AFTER GC ===")
        pushedLayers.forEachIndexed { index, digest ->
            assertTrue(blobStore.hasBlob(digest), "Layer ${index + 1} (${layerNames[index]}) should still exist after GC")
            println("Layer ${index + 1} (${layerNames[index]}) exists: ${blobStore.hasBlob(digest)}")
        }
        
        // The image should still be accessible
        assertTrue(blobStore.hasManifest(imageVersion), "Image should still be accessible after GC")
        
        // CRITICAL ASSERTIONS - This is what was failing in the original bug
        assertEquals(0, gcResult.blobsRemoved, "BUG: No blobs should be removed - they're all referenced by the manifest")
        assertEquals(0, gcResult.spaceFreed, "BUG: No space should be freed - no blobs were removed")
        assertEquals(0, gcResult.manifestsRemoved, "BUG: No manifests should be removed")
        
        // Final verification - the image should still be pullable
        val finalRepositories = blobStore.listRepositories()
        assertTrue(finalRepositories.contains("dockage/mailcatcher"), "Repository should still be listed after GC")
        
        val finalTags = blobStore.listTags("dockage/mailcatcher")
        assertTrue(finalTags.contains("latest"), "Latest tag should still be available after GC")
        
        println("=== WORKFLOW TEST PASSED ===")
        println("=== This test reproduces the exact bug scenario ===")
        println("=== All 5 layers preserved after garbage collection ===")
    }
    
    private fun createRealisticManifestJson(layerDigests: List<Digest>): String {
        // Create a realistic Docker manifest that references the layers
        // This simulates what Docker actually creates when pushing an image
        val layersJson = layerDigests.joinToString(",") { digest ->
            """
            {
                "mediaType": "application/vnd.docker.image.rootfs.diff.tar.gzip",
                "size": 1024,
                "digest": "${digest.digestString}"
            }
            """.trimIndent()
        }
        
        return """
        {
            "schemaVersion": 2,
            "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
            "config": {
                "mediaType": "application/vnd.docker.container.image.v1+json",
                "size": 100,
                "digest": "${layerDigests.first().digestString}"
            },
            "layers": [$layersJson]
        }
        """.trimIndent()
    }
    
    private fun createManifestJson(layerDigests: List<Digest>): String {
        // Create a realistic Docker manifest that references the layers
        val layersJson = layerDigests.joinToString(",") { digest ->
            """
            {
                "mediaType": "application/vnd.docker.image.rootfs.diff.tar.gzip",
                "size": 1024,
                "digest": "${digest.digestString}"
            }
            """.trimIndent()
        }
        
        return """
        {
            "schemaVersion": 2,
            "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
            "config": {
                "mediaType": "application/vnd.docker.container.image.v1+json",
                "size": 100,
                "digest": "${layerDigests.first().digestString}"
            },
            "layers": [$layersJson]
        }
        """.trimIndent()
    }
    
    private fun calculateSHA256(content: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(content)
        return hash.joinToString("") { "%02x".format(it) }
    }
}
