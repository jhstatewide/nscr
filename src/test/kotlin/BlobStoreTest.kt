import blobstore.Digest
import blobstore.H2BlobStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Path

class BlobStoreTest {

    private lateinit var blobStore: H2BlobStore
    private lateinit var testDatastorePath: Path

    @BeforeEach
    fun setup() {
        testDatastorePath = Path.of("./tmp/test-data/blobstore-test")
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
    fun testBlobStore() {
        val session = SessionID("test")
        // TODO: start session here somehow...
        val bytesWritten = blobStore.addBlob(session, 1, "test".toByteArray().inputStream())
        assert(bytesWritten == 4L)

        // this test fails, so before it gets to the part that fails below, let's implement
        // eachBlob, which will be a function that takes a lambda and calls it with each blob
        // in the store
        var row1Found = false

        blobStore.eachBlob { blobRow ->
            println("BlobRow: $blobRow")
            println("Number: ${blobRow.blobNumber}")
            if (blobRow.blobNumber == 1) {
                row1Found = true
            }
        }

        assert(row1Found)
    }
}