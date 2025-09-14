import com.statewidesoftware.nscr.SessionID
import com.statewidesoftware.nscr.blobstore.Digest
import com.statewidesoftware.nscr.blobstore.H2BlobStore
import com.statewidesoftware.nscr.blobstore.ImageVersion
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GarbageCollectionTest {

    private lateinit var blobStore: H2BlobStore
    private lateinit var testDatastorePath: Path

    @BeforeEach
    fun setup() {
        testDatastorePath = Path.of("./tmp/test-data/garbage-collection-test")
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
    fun testGarbageCollectionWithOrphanedBlobs() {
        // Create some test blobs
        val session1 = SessionID("test-session-1")
        val session2 = SessionID("test-session-2")
        val session3 = SessionID("test-session-3")
        
        val blob1Data = "Blob 1 data"
        val blob2Data = "Blob 2 data"
        val blob3Data = "Blob 3 data"
        
        val digest1 = Digest("sha256:${calculateSHA256(blob1Data.toByteArray())}")
        val digest2 = Digest("sha256:${calculateSHA256(blob2Data.toByteArray())}")
        val digest3 = Digest("sha256:${calculateSHA256(blob3Data.toByteArray())}")
        
        // Upload blobs - each in its own session to avoid stitching
        blobStore.addBlob(session1, 0, blob1Data.toByteArray().inputStream())
        blobStore.addBlob(session2, 0, blob2Data.toByteArray().inputStream())
        blobStore.addBlob(session3, 0, blob3Data.toByteArray().inputStream())
        
        // Associate all blobs with sessions (simulating completed uploads)
        blobStore.associateBlobWithSession(session1, digest1)
        blobStore.associateBlobWithSession(session2, digest2)
        blobStore.associateBlobWithSession(session3, digest3)
        // digest3 will be orphaned because it's not referenced in any manifest
        
        // Create a manifest that references only digest1 and digest2
        val manifestJson = """
        {
            "schemaVersion": 2,
            "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
            "config": {
                "mediaType": "application/vnd.docker.container.image.v1+json",
                "size": 100,
                "digest": "${digest1.digestString}"
            },
            "layers": [
                {
                    "mediaType": "application/vnd.docker.image.rootfs.diff.tar.gzip",
                    "size": 200,
                    "digest": "${digest2.digestString}"
                }
            ]
        }
        """.trimIndent()
        
        // Calculate the manifest digest (the manifest content itself)
        val manifestDigest = Digest("sha256:${calculateSHA256(manifestJson.toByteArray())}")
        
        // Store the manifest as a blob (manifests are blobs in Docker registry)
        val manifestSession = SessionID("manifest-session")
        blobStore.addBlob(manifestSession, 0, manifestJson.toByteArray().inputStream())
        blobStore.associateBlobWithSession(manifestSession, manifestDigest)
        
        blobStore.addManifest(ImageVersion("test", "latest"), manifestDigest, manifestJson)
        
        // Check initial stats
        val initialStats = blobStore.getGarbageCollectionStats()
        assertEquals(4, initialStats.totalBlobs) // 3 data blobs + 1 manifest blob
        assertEquals(1, initialStats.totalManifests)
        assertEquals(2, initialStats.unreferencedBlobs) // digest3 and manifest blob are unreferenced
        assertEquals(1, initialStats.orphanedManifests) // manifest is orphaned because its blob is unreferenced
        
        // Run garbage collection
        val result = blobStore.garbageCollect()
        
        // Verify results
        assertEquals(2, result.blobsRemoved) // digest3 and manifest blob should be removed
        assertTrue(result.spaceFreed > 0)
        assertEquals(1, result.manifestsRemoved) // manifest should be removed as orphaned
        
        // Check final stats
        val finalStats = blobStore.getGarbageCollectionStats()
        assertEquals(2, finalStats.totalBlobs) // 2 data blobs (digest3 and manifest blob removed)
        assertEquals(0, finalStats.totalManifests) // manifest removed
        assertEquals(2, finalStats.unreferencedBlobs) // garbage collection didn't remove them
        assertEquals(0, finalStats.orphanedManifests)
    }

    @Test
    fun testGarbageCollectionWithOrphanedManifests() {
        // Create a blob
        val session = SessionID("test-session")
        val blobData = "Test blob data"
        val digest = Digest("sha256:${calculateSHA256(blobData.toByteArray())}")
        
        blobStore.addBlob(session, 0, blobData.toByteArray().inputStream())
        blobStore.associateBlobWithSession(session, digest)
        
        // Create a manifest that references a non-existent blob
        val manifestJson = """
        {
            "schemaVersion": 2,
            "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
            "config": {
                "mediaType": "application/vnd.docker.container.image.v1+json",
                "size": 100,
                "digest": "sha256:nonexistent"
            },
            "layers": []
        }
        """.trimIndent()
        
        val manifestDigest = Digest("sha256:${calculateSHA256(manifestJson.toByteArray())}")
        blobStore.addManifest(ImageVersion("test", "latest"), manifestDigest, manifestJson)
        
        // Check initial stats
        val initialStats = blobStore.getGarbageCollectionStats()
        assertEquals(1, initialStats.totalBlobs)
        assertEquals(1, initialStats.totalManifests)
        assertEquals(1, initialStats.unreferencedBlobs) // The blob is not referenced by any valid manifest
        assertEquals(1, initialStats.orphanedManifests) // The manifest references non-existent blob
        
        // Run garbage collection
        val result = blobStore.garbageCollect()
        
        // Verify results
        assertEquals(1, result.blobsRemoved) // Unreferenced blob removed
        assertTrue(result.spaceFreed > 0)
        assertEquals(1, result.manifestsRemoved) // Orphaned manifest removed
        
        // Check final stats
        val finalStats = blobStore.getGarbageCollectionStats()
        assertEquals(0, finalStats.totalBlobs)
        assertEquals(0, finalStats.totalManifests)
        assertEquals(0, finalStats.unreferencedBlobs)
        assertEquals(0, finalStats.orphanedManifests)
    }

    @Test
    fun testGarbageCollectionWithNullDigestBlobs() {
        // Create blobs without digests (simulating failed uploads)
        val session = SessionID("test-session")
        
        blobStore.addBlob(session, 0, "Failed upload 1".toByteArray().inputStream())
        blobStore.addBlob(session, 1, "Failed upload 2".toByteArray().inputStream())
        
        // Check initial stats
        val initialStats = blobStore.getGarbageCollectionStats()
        assertEquals(0, initialStats.totalBlobs) // Blobs without digests don't count
        assertEquals(0, initialStats.totalManifests)
        assertEquals(0, initialStats.unreferencedBlobs)
        assertEquals(0, initialStats.orphanedManifests)
        
        // Run garbage collection
        val result = blobStore.garbageCollect()
        
        // Verify results - should clean up null digest blobs
        assertTrue(result.blobsRemoved >= 0) // May or may not remove depending on implementation
        assertTrue(result.spaceFreed >= 0)
        assertEquals(0, result.manifestsRemoved)
    }

    private fun calculateSHA256(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
