import blobstore.Digest
import blobstore.H2BlobStore
import java.nio.file.Path

class SimpleMultiPartTest {
    
    fun testMultiPartUpload() {
        val testDatastorePath = Path.of("./test_data_simple")
        if (testDatastorePath.toFile().exists()) {
            testDatastorePath.toFile().deleteRecursively()
        }
        val blobStore = H2BlobStore(testDatastorePath)
        
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
        if (blobStore.hasBlob(digest)) {
            println("SUCCESS: Multi-part upload works!")
        } else {
            println("FAILED: Multi-part upload failed!")
        }
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
