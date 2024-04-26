import blobstore.Blobstore
import com.google.common.base.CharMatcher.any
import io.javalin.testtools.JavalinTest
import io.mockk.every
import io.mockk.mockk
import mu.KotlinLogging
import kotlin.test.Test
import kotlin.test.assertEquals

class ImagePushIntegrationTest {

    val klogger = KotlinLogging.logger {}
    val mockBlobStore = mockk<Blobstore>()

    init {
        every { mockBlobStore.addBlob(any(), any(), any()) } returns 202
    }

    private val javalinApp = RegistryServerApp(logger = klogger, mockBlobStore).javalinApp()

    @Test
    fun testHelloWorld() {
        JavalinTest.test(javalinApp) { server, client ->
            val response = client.get("/")
            assertEquals("Hello World", response.body?.string())
        }
    }

    @Test
    fun testImagePush() {
        JavalinTest.test(javalinApp) { server, client ->
            val response = client.post("/v2/test/blobs/uploads/")
            assertEquals(202, response.code)
        }
    }
}