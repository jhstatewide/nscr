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
        blobStore.addBlob(SessionID("test"), 1, "test".toByteArray().inputStream())
        // now make sure the blob is there
        blobStore.getBlob(ImageVersion("awesome", "latest")) { inputStream, handle ->
            val data = inputStream.readBytes()
            assert(data.contentEquals("test".toByteArray()))
        }
    }
}