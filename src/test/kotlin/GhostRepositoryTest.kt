package blobstore

import SessionID
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Path
import java.nio.file.Files
import kotlin.io.path.Path

class GhostRepositoryTest {
    private lateinit var blobStore: H2BlobStore
    private val testDatastorePath = Path.of("./tmp/test-data/ghost-repository-test")
    
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
    fun testGhostRepositoryPrevention() {
        // Create a repository with a manifest and blob
        val repositoryName = "test-repo"
        val tag = "latest"
        val sessionId = SessionID("test-session")
        
        // Add a blob
        val testData = "test data for ghost repository test".toByteArray()
        blobStore.addBlob(sessionId, 0, testData.inputStream())
        
        // Calculate proper SHA-256 digest
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digestBytes = md.digest(testData)
        val digestString = digestBytes.joinToString("") { "%02x".format(it) }
        val digest = Digest("sha256:$digestString")
        
        // Associate blob with session
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
                "layers": [
                    {
                        "mediaType": "application/vnd.docker.image.rootfs.diff.tar.gzip",
                        "size": ${testData.size},
                        "digest": "${digest.digestString}"
                    }
                ]
            }
        """.trimIndent()
        
        // Add the manifest
        blobStore.addManifest(ImageVersion(repositoryName, tag), digest, manifestJson)
        
        // Verify repository appears in list
        val repositoriesBefore = blobStore.listRepositories()
        assertTrue(repositoriesBefore.contains(repositoryName), "Repository should appear in list before deletion")
        
        // Remove the manifest (this simulates deleting all tags from a repository)
        blobStore.removeManifest(ImageVersion(repositoryName, tag))
        
        // Verify repository no longer appears in list (ghost repository prevention)
        val repositoriesAfter = blobStore.listRepositories()
        assertFalse(repositoriesAfter.contains(repositoryName), "Repository should not appear in list after all manifests are deleted")
        
        // Verify the blob still exists (it's not automatically garbage collected)
        assertTrue(blobStore.hasBlob(digest), "Blob should still exist after manifest deletion")
    }
    
    @Test
    fun testRepositoryWithMultipleTags() {
        val repositoryName = "multi-tag-repo"
        val sessionId = SessionID("test-session")
        
        // Add a blob
        val testData = "test data for multi-tag test".toByteArray()
        blobStore.addBlob(sessionId, 0, testData.inputStream())
        
        // Calculate digest
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digestBytes = md.digest(testData)
        val digestString = digestBytes.joinToString("") { "%02x".format(it) }
        val digest = Digest("sha256:$digestString")
        
        blobStore.associateBlobWithSession(sessionId, digest)
        
        // Create manifest for tag1
        val manifestJson1 = """
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
        
        blobStore.addManifest(ImageVersion(repositoryName, "tag1"), digest, manifestJson1)
        
        // Create manifest for tag2
        val manifestJson2 = """
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
        
        blobStore.addManifest(ImageVersion(repositoryName, "tag2"), digest, manifestJson2)
        
        // Verify repository appears in list
        val repositoriesBefore = blobStore.listRepositories()
        assertTrue(repositoriesBefore.contains(repositoryName), "Repository should appear in list with multiple tags")
        
        // Remove one tag
        blobStore.removeManifest(ImageVersion(repositoryName, "tag1"))
        
        // Repository should still appear (has remaining tag)
        val repositoriesAfterOne = blobStore.listRepositories()
        assertTrue(repositoriesAfterOne.contains(repositoryName), "Repository should still appear after removing one tag")
        
        // Remove the last tag
        blobStore.removeManifest(ImageVersion(repositoryName, "tag2"))
        
        // Repository should no longer appear
        val repositoriesAfterAll = blobStore.listRepositories()
        assertFalse(repositoriesAfterAll.contains(repositoryName), "Repository should not appear after removing all tags")
    }
}
