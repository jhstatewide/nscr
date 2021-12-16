import blobstore.H2BlobStore
import blobstore.ImageVersion
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Path

class BlobStoreTest {

    private lateinit var blobStore: H2BlobStore

    @BeforeEach
    fun setup() {
        val testDatastorePath = Path.of("./test_data")
        // delete the test datastore if it exists
        if (testDatastorePath.toFile().exists()) {
            testDatastorePath.toFile().deleteRecursively()
        }
        blobStore = H2BlobStore(testDatastorePath)
    }

    @Test
    fun testBlobStore() {
        val session = SessionID("test")
        // TODO: start session here somehow...
        val bytesWritten = blobStore.addBlob(session, 1, "test".toByteArray().inputStream())
        assert(bytesWritten == 4L)
        // i guess now we just walk all the digests or something???
    }
}