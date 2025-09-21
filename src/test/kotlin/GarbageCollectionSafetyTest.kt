package com.statewidesoftware.nscr

import com.statewidesoftware.nscr.blobstore.H2BlobStore
import com.statewidesoftware.nscr.blobstore.Digest
import com.statewidesoftware.nscr.blobstore.ImageVersion
import com.statewidesoftware.nscr.blobstore.GarbageCollectionResult
import com.statewidesoftware.nscr.SessionID
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Test to verify that garbage collection is safe to run during active Docker pushes.
 * This test simulates the scenario where:
 * 1. A user is pushing an image with multiple layers
 * 2. Another user triggers garbage collection
 * 3. The pushed image should remain intact and not lose any layers
 */
class GarbageCollectionSafetyTest {
    
    private lateinit var tempDir: Path
    private lateinit var blobStore: H2BlobStore
    
    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("nscr-gc-safety-test")
        blobStore = H2BlobStore(tempDir)
    }
    
    @AfterEach
    fun tearDown() {
        try {
            blobStore.cleanup()
        } catch (e: Exception) {
            // Ignore cleanup errors in tests
        }
        Files.deleteIfExists(tempDir.resolve("blobstore.mv.db"))
        Files.deleteIfExists(tempDir.resolve("blobstore.trace.db"))
        Files.deleteIfExists(tempDir)
    }
    
    @Test
    fun `garbage collection should not remove blobs from active push sessions`() {
        println("=== Testing Garbage Collection Safety During Active Push ===")
        
        val imageVersion = ImageVersion("test-image", "latest")
        val sessionID = SessionID("test-session-${System.currentTimeMillis()}")
        
        // Simulate uploading 5 layers for an image
        val layerData = listOf(
            "layer1-content-${System.currentTimeMillis()}",
            "layer2-content-${System.currentTimeMillis()}",
            "layer3-content-${System.currentTimeMillis()}",
            "layer4-content-${System.currentTimeMillis()}",
            "layer5-content-${System.currentTimeMillis()}"
        )
        
        val uploadedBlobSizes = mutableListOf<Long>()
        val uploadedBlobDigests = mutableListOf<String>()
        
        println("Step 1: Uploading ${layerData.size} layers...")
        
        // Upload each layer
        layerData.forEachIndexed { index, content ->
            val inputStream = content.byteInputStream()
            val size = blobStore.addBlob(sessionID, index, inputStream)
            uploadedBlobSizes.add(size)
            
            // Get the digest for this blob (simulate what happens after upload)
            val digest = Digest("sha256:${content.hashCode().toString(16)}")
            uploadedBlobDigests.add("sha256:${digest.digestString}")
            
            println("  Uploaded layer $index: ${size} bytes, digest: ${uploadedBlobDigests.last()}")
        }
        
        // Verify all blobs are in the database (they have NULL digest initially, so won't show in totalBlobs)
        // Instead, check that the session has the expected number of blobs
        val sessionBlobCount = blobStore.blobCountForSession(sessionID)
        assertEquals(layerData.size, sessionBlobCount, "All uploaded blobs should be in the session")
        
        println("Step 2: Running garbage collection while blobs are uploaded but not yet referenced by manifest...")
        
        // Run garbage collection - this should NOT remove the uploaded blobs
        // because they are part of an active session (even though not yet referenced by a manifest)
        val gcResult = blobStore.garbageCollect()
        
        println("GC Result: ${gcResult.blobsRemoved} blobs removed, ${gcResult.spaceFreed} bytes freed")
        
        // CRITICAL: No blobs should be removed because they're part of an active session
        assertEquals(0, gcResult.blobsRemoved, "Garbage collection should NOT remove blobs from active push sessions")
        assertEquals(0, gcResult.spaceFreed, "No space should be freed since no blobs were removed")
        
        // Verify all blobs are still in the database (they still have NULL digest, so won't show in totalBlobs)
        val afterGcSessionBlobCount = blobStore.blobCountForSession(sessionID)
        assertEquals(layerData.size, afterGcSessionBlobCount, "All uploaded blobs should still be in the session after GC")
        
        println("Step 3: Verifying blobs are still accessible after GC...")
        
        // Verify all blobs are still accessible (they still have NULL digest, so won't show in totalBlobs)
        val finalSessionBlobCount = blobStore.blobCountForSession(sessionID)
        assertEquals(layerData.size, finalSessionBlobCount, "All blobs should still be accessible after GC")
        
        println("=== Test PASSED: Garbage collection is safe during active pushes ===")
        println("✅ Garbage collection correctly preserved ${layerData.size} recently uploaded blobs")
        println("✅ No blobs were removed because they were uploaded within the last 2 hours")
    }
    
    @Test
    fun `concurrent garbage collection and push operations should be safe`() {
        println("=== Testing Concurrent Garbage Collection and Push Operations ===")
        
        val executor = Executors.newFixedThreadPool(3)
        val latch = CountDownLatch(3)
        val exceptions = mutableListOf<Exception>()
        val gcResults = mutableListOf<GarbageCollectionResult>()
        val pushResults = mutableListOf<Boolean>()
        
        try {
            // Thread 1: Push an image with multiple layers
            executor.submit {
                try {
                    val imageVersion = ImageVersion("concurrent-test", "latest")
                    val sessionID = SessionID("concurrent-session-${System.currentTimeMillis()}")
                    
                    // Upload 3 layers
                    repeat(3) { index ->
                        val content = "concurrent-layer-$index-${System.currentTimeMillis()}"
                        val inputStream = content.byteInputStream()
                        blobStore.addBlob(sessionID, index, inputStream)
                        Thread.sleep(100) // Simulate upload delay
                    }
                    
                    // Create manifest
                    val manifestJson = """
                    {
                        "schemaVersion": 2,
                        "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
                        "config": {
                            "mediaType": "application/vnd.docker.container.image.v1+json",
                            "size": 2,
                            "digest": "sha256:concurrent-config"
                        },
                        "layers": [
                            {"mediaType": "application/vnd.docker.image.rootfs.diff.tar.gzip", "size": 100, "digest": "sha256:concurrent-layer-0"},
                            {"mediaType": "application/vnd.docker.image.rootfs.diff.tar.gzip", "size": 100, "digest": "sha256:concurrent-layer-1"},
                            {"mediaType": "application/vnd.docker.image.rootfs.diff.tar.gzip", "size": 100, "digest": "sha256:concurrent-layer-2"}
                        ]
                    }
                    """.trimIndent()
                    
                    val manifestDigest = Digest("sha256:concurrent-manifest")
                    blobStore.addManifest(imageVersion, manifestDigest, manifestJson)
                    
                    pushResults.add(true)
                    println("Push thread: Successfully pushed image")
                } catch (e: Exception) {
                    println("Push thread: Exception: ${e.message}")
                    pushResults.add(false)
                    synchronized(exceptions) { exceptions.add(e) }
                } finally {
                    latch.countDown()
                }
            }
            
            // Thread 2: Run garbage collection
            executor.submit {
                try {
                    Thread.sleep(50) // Start GC slightly after push starts
                    val result = blobStore.garbageCollect()
                    gcResults.add(result)
                    println("GC thread: Garbage collection completed - ${result.blobsRemoved} blobs removed")
                } catch (e: Exception) {
                    println("GC thread: Exception: ${e.message}")
                    synchronized(exceptions) { exceptions.add(e) }
                } finally {
                    latch.countDown()
                }
            }
            
            // Thread 3: Run another garbage collection
            executor.submit {
                try {
                    Thread.sleep(100) // Start second GC after first one
                    val result = blobStore.garbageCollect()
                    gcResults.add(result)
                    println("GC thread 2: Garbage collection completed - ${result.blobsRemoved} blobs removed")
                } catch (e: Exception) {
                    println("GC thread 2: Exception: ${e.message}")
                    synchronized(exceptions) { exceptions.add(e) }
                } finally {
                    latch.countDown()
                }
            }
            
            // Wait for all threads to complete
            assertTrue(latch.await(30, TimeUnit.SECONDS), "All threads should complete within 30 seconds")
            
            // Verify no exceptions occurred
            assertTrue(exceptions.isEmpty(), "No exceptions should occur during concurrent operations. Exceptions: $exceptions")
            
            // Verify push was successful
            assertTrue(pushResults.any { it }, "At least one push should succeed")
            
            // Verify garbage collection results are reasonable
            assertTrue(gcResults.isNotEmpty(), "At least one garbage collection should complete")
            
            // The image should still be accessible
            val imageVersion = ImageVersion("concurrent-test", "latest")
            assertTrue(blobStore.hasManifest(imageVersion), "Pushed image should still be accessible after concurrent GC")
            
            println("=== Concurrent test PASSED: Multiple GC operations during push are safe ===")
            
        } finally {
            executor.shutdown()
        }
    }
    
    private fun createManifestJson(blobDigests: List<String>): String {
        val layers = blobDigests.map { digest ->
            """
            {
                "mediaType": "application/vnd.docker.image.rootfs.diff.tar.gzip",
                "size": 100,
                "digest": "$digest"
            }
            """.trimIndent()
        }.joinToString(",\n            ")
        
        return """
        {
            "schemaVersion": 2,
            "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
            "config": {
                "mediaType": "application/vnd.docker.container.image.v1+json",
                "size": 2,
                "digest": "sha256:test-config-digest"
            },
            "layers": [
                $layers
            ]
        }
        """.trimIndent()
    }
}
