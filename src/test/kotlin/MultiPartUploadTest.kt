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

class MultiPartUploadTest {

    private lateinit var blobStore: H2BlobStore
    private lateinit var testDatastorePath: Path

    @BeforeEach
    fun setup() {
        testDatastorePath = Path.of("./tmp/test-data/multipart-upload-test")
        // delete the test datastore if it exists
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
        val session = SessionID("test-multipart")
        
        // Create test data that we'll split into chunks
        val testData = "This is a test blob that will be split into multiple parts for testing multi-part uploads. " +
                "It needs to be long enough to make sense to split into chunks. " +
                "Let's add some more content to make it substantial. " +
                "This should be enough data to test the multi-part upload functionality properly."
        
        // Calculate the expected digest
        val expectedDigest = calculateSHA256(testData.toByteArray())
        val digest = Digest("sha256:$expectedDigest")
        
        // Split the data into 3 chunks
        val chunkSize = testData.length / 3
        val chunk1 = testData.substring(0, chunkSize)
        val chunk2 = testData.substring(chunkSize, chunkSize * 2)
        val chunk3 = testData.substring(chunkSize * 2)
        
        // Upload chunks
        val bytes1 = blobStore.addBlob(session, 0, chunk1.toByteArray().inputStream())
        val bytes2 = blobStore.addBlob(session, 1, chunk2.toByteArray().inputStream())
        val bytes3 = blobStore.addBlob(session, 2, chunk3.toByteArray().inputStream())
        
        // Verify chunks were uploaded
        assertEquals(chunk1.length.toLong(), bytes1)
        assertEquals(chunk2.length.toLong(), bytes2)
        assertEquals(chunk3.length.toLong(), bytes3)
        
        // Verify we have 3 chunks
        assertEquals(3, blobStore.blobCountForSession(session))
        
        // Associate the blob with the session (this should trigger stitching)
        blobStore.associateBlobWithSession(session, digest)
        
        // Verify the blob now exists with the correct digest
        assertTrue(blobStore.hasBlob(digest))
        
        // Verify chunks were cleaned up (should have 0 chunks now)
        assertEquals(0, blobStore.blobCountForSession(session))
        
        // Test retrieving the blob
        var retrievedData = ""
        blobStore.getBlob(ImageVersion("test", digest.digestString)) { stream, handle ->
            retrievedData = stream.bufferedReader().readText()
            handle.close()
        }
        
        // Verify the retrieved data matches the original
        assertEquals(testData, retrievedData)
    }

    @Test
    fun testSinglePartUpload() {
        val session = SessionID("test-single")
        val testData = "Single part upload test data"
        val expectedDigest = calculateSHA256(testData.toByteArray())
        val digest = Digest("sha256:$expectedDigest")
        
        // Upload single chunk
        val bytes = blobStore.addBlob(session, 0, testData.toByteArray().inputStream())
        assertEquals(testData.length.toLong(), bytes)
        
        // Verify we have 1 chunk
        assertEquals(1, blobStore.blobCountForSession(session))
        
        // Associate the blob with the session
        blobStore.associateBlobWithSession(session, digest)
        
        // Verify the blob exists
        assertTrue(blobStore.hasBlob(digest))
        
        // Verify chunks were cleaned up
        assertEquals(0, blobStore.blobCountForSession(session))
    }

    @Test
    fun testDigestMismatch() {
        val session = SessionID("test-mismatch")
        val testData = "Test data for digest mismatch"
        
        // Upload chunk
        blobStore.addBlob(session, 0, testData.toByteArray().inputStream())
        
        // Try to associate with wrong digest
        val wrongDigest = Digest("sha256:wrongdigest")
        
        try {
            blobStore.associateBlobWithSession(session, wrongDigest)
            assertTrue(false, "Should have thrown exception for digest mismatch")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Digest mismatch"))
        }
    }

    private fun calculateSHA256(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
