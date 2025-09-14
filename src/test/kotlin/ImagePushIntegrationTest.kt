import com.statewidesoftware.nscr.RegistryServerApp
import com.statewidesoftware.nscr.blobstore.Blobstore
import io.javalin.testtools.JavalinTest
import io.mockk.every
import io.mockk.mockk
import mu.KotlinLogging
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImagePushIntegrationTest {

    val klogger = KotlinLogging.logger {}
    val mockBlobStore = mockk<Blobstore>(relaxed = true)

    init {
        every { mockBlobStore.addBlob(any(), any(), any()) } returns 202
    }

    private val javalinApp = RegistryServerApp(logger = klogger, mockBlobStore).app

    @Test
    fun testHelloWorld() {
        JavalinTest.test(javalinApp) { _, client ->
            val response = client.get("/")
            val body = response.body?.string()
            // Should return HTML content (web interface) or "Hello World" if web interface disabled
            assertTrue(body?.contains("<!DOCTYPE html>") == true || body == "Hello World")
        }
    }

    @Test
    fun testImagePush() {
        JavalinTest.test(javalinApp) { _, client ->
            val response = client.post("/v2/test/blobs/uploads/")
            assertEquals(202, response.code)
        }
    }
}