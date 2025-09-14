import blobstore.Digest
import blobstore.H2BlobStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertTrue

class SimpleMultiPartTest {
    
    private lateinit var blobStore: H2BlobStore
    private lateinit var testDatastorePath: Path

    @BeforeEach
    fun setup() {
        testDatastorePath = Path.of("./tmp/test-data/simple-multipart-test")
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
    fun testMultiPartUpload() {
        val session = SessionID("test-session")
        
        // Upload two chunks
        val chunk1 = "Hello "
        val chunk2 = "World!"
        val fullData = chunk1 + chunk2
        
        blobStore.addBlob(session, 0, chunk1.toByteArray().inputStream())
        blobStore.addBlob(session, 1, chunk2.toByteArray().inputStream())
        
        // Calculate digest
        val digest = Digest("sha256:${calculateSHA256(fullData.toByteArray())}")
        
        // Associate with session (should stitch)
        blobStore.associateBlobWithSession(session, digest)
        
        // Verify blob exists
        assertTrue(blobStore.hasBlob(digest), "Multi-part upload should work")
    }
    
    private fun calculateSHA256(data: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }
}

fun main() {
    SimpleMultiPartTest().testMultiPartUpload()
}
