package com.statewidesoftware.nscr

import com.statewidesoftware.nscr.blobstore.H2BlobStore
import com.statewidesoftware.nscr.blobstore.Digest
import com.statewidesoftware.nscr.blobstore.ImageVersion
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Test the manifest upsert functionality to ensure it handles concurrent pushes correctly
 */
class ManifestUpsertTest {
    
    private lateinit var tempDir: Path
    private lateinit var blobStore: H2BlobStore
    
    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("nscr-manifest-upsert-test")
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
    fun `manifest upsert should handle concurrent pushes without constraint violations`() {
        // This test simulates the exact scenario from the bug report:
        // Multiple concurrent pushes of the same image (postgres:15) should not cause
        // unique constraint violations
        
        val imageVersion = ImageVersion("postgres", "15")
        val manifestJson1 = """
        {
            "schemaVersion": 2,
            "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
            "config": {
                "mediaType": "application/vnd.docker.container.image.v1+json",
                "size": 2,
                "digest": "sha256:b3d48187cc8a71167b6c8bb2b30230bf3badbaf0010a4426ca8e07af3eae038e"
            },
            "layers": [
                {
                    "mediaType": "application/vnd.docker.image.rootfs.diff.tar.gzip",
                    "size": 2,
                    "digest": "sha256:60098d868b9792904c01b227caa6f3665401baa0c2e8d8af6a809044881b3d2b"
                }
            ]
        }
        """.trimIndent()
        
        val manifestJson2 = """
        {
            "schemaVersion": 2,
            "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
            "config": {
                "mediaType": "application/vnd.docker.container.image.v1+json",
                "size": 2,
                "digest": "sha256:different-digest-for-same-image"
            },
            "layers": [
                {
                    "mediaType": "application/vnd.docker.image.rootfs.diff.tar.gzip",
                    "size": 2,
                    "digest": "sha256:different-layer-digest"
                }
            ]
        }
        """.trimIndent()
        
        val digest1 = Digest("sha256:b3d48187cc8a71167b6c8bb2b30230bf3badbaf0010a4426ca8e07af3eae038e")
        val digest2 = Digest("sha256:different-digest-for-same-image")
        
        // Test 1: Basic upsert functionality
        println("=== Testing basic upsert functionality ===")
        
        // First push
        blobStore.addManifest(imageVersion, digest1, manifestJson1)
        assertTrue(blobStore.hasManifest(imageVersion), "Manifest should exist after first push")
        
        // Second push (should update, not fail)
        blobStore.addManifest(imageVersion, digest2, manifestJson2)
        assertTrue(blobStore.hasManifest(imageVersion), "Manifest should still exist after second push")
        
        // Verify the manifest content was updated
        val retrievedManifest = blobStore.getManifest(imageVersion)
        assertTrue(retrievedManifest.contains("different-digest-for-same-image"), 
                  "Manifest should contain the updated content")
        
        println("=== Basic upsert test passed ===")
        
        // Test 2: Concurrent pushes (simulating the race condition)
        println("=== Testing concurrent pushes ===")
        
        val executor = Executors.newFixedThreadPool(10)
        val latch = CountDownLatch(10)
        val exceptions = mutableListOf<Exception>()
        
        // Create 10 concurrent pushes of the same image
        repeat(10) { threadId ->
            executor.submit {
                try {
                    val threadManifest = """
                    {
                        "schemaVersion": 2,
                        "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
                        "config": {
                            "mediaType": "application/vnd.docker.container.image.v1+json",
                            "size": 2,
                            "digest": "sha256:thread-$threadId-digest"
                        },
                        "layers": [
                            {
                                "mediaType": "application/vnd.docker.image.rootfs.diff.tar.gzip",
                                "size": 2,
                                "digest": "sha256:thread-$threadId-layer"
                            }
                        ]
                    }
                    """.trimIndent()
                    
                    val threadDigest = Digest("sha256:thread-$threadId-digest")
                    blobStore.addManifest(imageVersion, threadDigest, threadManifest)
                    println("Thread $threadId: Successfully pushed manifest")
                } catch (e: Exception) {
                    println("Thread $threadId: Exception during push: ${e.message}")
                    synchronized(exceptions) {
                        exceptions.add(e)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }
        
        // Wait for all threads to complete
        assertTrue(latch.await(30, TimeUnit.SECONDS), "All threads should complete within 30 seconds")
        executor.shutdown()
        
        // Verify no constraint violations occurred
        assertTrue(exceptions.isEmpty(), "No exceptions should occur during concurrent pushes. Exceptions: $exceptions")
        
        // Verify the manifest still exists (one of the threads should have succeeded)
        assertTrue(blobStore.hasManifest(imageVersion), "Manifest should still exist after concurrent pushes")
        
        println("=== Concurrent push test passed ===")
        println("=== All tests passed: Manifest upsert handles concurrent pushes correctly ===")
    }
    
    @Test
    fun `manifest upsert should work with different images`() {
        // Test that upsert works correctly with different images
        
        val postgresImage = ImageVersion("postgres", "15")
        val mysqlImage = ImageVersion("mysql", "8")
        
        val postgresManifest = """
        {
            "schemaVersion": 2,
            "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
            "config": {
                "mediaType": "application/vnd.docker.container.image.v1+json",
                "size": 2,
                "digest": "sha256:postgres-digest"
            },
            "layers": []
        }
        """.trimIndent()
        
        val mysqlManifest = """
        {
            "schemaVersion": 2,
            "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
            "config": {
                "mediaType": "application/vnd.docker.container.image.v1+json",
                "size": 2,
                "digest": "sha256:mysql-digest"
            },
            "layers": []
        }
        """.trimIndent()
        
        // Push both images
        blobStore.addManifest(postgresImage, Digest("sha256:postgres-digest"), postgresManifest)
        blobStore.addManifest(mysqlImage, Digest("sha256:mysql-digest"), mysqlManifest)
        
        // Both should exist
        assertTrue(blobStore.hasManifest(postgresImage), "Postgres manifest should exist")
        assertTrue(blobStore.hasManifest(mysqlImage), "MySQL manifest should exist")
        
        // Update postgres manifest
        val updatedPostgresManifest = postgresManifest.replace("postgres-digest", "updated-postgres-digest")
        blobStore.addManifest(postgresImage, Digest("sha256:updated-postgres-digest"), updatedPostgresManifest)
        
        // Both should still exist
        assertTrue(blobStore.hasManifest(postgresImage), "Postgres manifest should still exist after update")
        assertTrue(blobStore.hasManifest(mysqlImage), "MySQL manifest should still exist after postgres update")
        
        // Verify postgres was updated
        val retrievedPostgres = blobStore.getManifest(postgresImage)
        assertTrue(retrievedPostgres.contains("updated-postgres-digest"), "Postgres manifest should be updated")
        
        // Verify mysql was not affected
        val retrievedMysql = blobStore.getManifest(mysqlImage)
        assertTrue(retrievedMysql.contains("mysql-digest"), "MySQL manifest should not be affected")
        
        println("=== Different images test passed ===")
    }
}
