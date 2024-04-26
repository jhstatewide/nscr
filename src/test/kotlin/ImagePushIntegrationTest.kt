import blobstore.Blobstore
import io.javalin.testtools.JavalinTest
import io.mockk.mockk
import mu.KotlinLogging
import kotlin.test.Test
import kotlin.test.assertEquals

class ImagePushIntegrationTest {

    val klogger = KotlinLogging.logger {}
    val mockBlobStore = mockk<Blobstore>()
    private val javalinApp = RegistryServerApp(logger = klogger, mockBlobStore).javalinApp()
    @Test
    fun testImagePush() {
        JavalinTest.test(javalinApp) { server, client ->
            val response = client.get("/")
            assertEquals("Hello World", response.body?.string())
        }
    }
}