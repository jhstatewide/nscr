import blobstore.H2BlobStore
import blobstore.ImageVersion
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.model.Container
import com.github.dockerjava.api.model.PullResponseItem
import com.github.dockerjava.api.model.PushResponseItem
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import com.github.dockerjava.transport.DockerHttpClient
import mu.KotlinLogging
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Duration


class DockerIntegrationTest {
    companion object {
        val testBlobStoreDirectory: Path = Path.of("./tmp/test-data/docker-integration-test")

        @JvmStatic
        @BeforeAll
        fun destroyTestBlobstore() {
            // delete all contents of test blobstore if it exists
            if (testBlobStoreDirectory.toFile().exists()) {
                testBlobStoreDirectory.toFile().deleteRecursively()
            }
            // recreate the test blobstore
            testBlobStoreDirectory.toFile().mkdirs()
        }

        @JvmStatic
        @AfterAll
        fun cleanupTestBlobstore() {
            // delete all contents of test blobstore after tests
            if (testBlobStoreDirectory.toFile().exists()) {
                testBlobStoreDirectory.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun testDocker() {
        val config: DockerClientConfig = DefaultDockerClientConfig.createDefaultConfigBuilder()
            .withRegistryUrl("http://localhost:7000")
            .withCustomSslConfig(null)
            .build()
        val httpClient: DockerHttpClient = ApacheDockerHttpClient.Builder()
            .dockerHost(config.dockerHost)
            .sslConfig(config.sslConfig)
            .maxConnections(100)
            .connectionTimeout(Duration.ofSeconds(30))
            .responseTimeout(Duration.ofSeconds(45))
            .build()

        val dockerClient = DockerClientImpl.getInstance(config, httpClient);
        dockerClient.pingCmd().exec()

        val containers: List<Container> = dockerClient.listContainersCmd().exec()
        containers.forEach {
            println(it.id)
        }

        val hostedTaggedImage = "localhost:7000/ubuntu:20.04"

        val cb = ResultCallback.Adapter<PullResponseItem>()
        dockerClient.pullImageCmd("ubuntu:20.04").exec(cb).awaitCompletion()
        cb.awaitCompletion()

        dockerClient.tagImageCmd("ubuntu:20.04", "localhost:7000/ubuntu", "20.04").exec()

        val logger = KotlinLogging.logger {  }
        logger.info { "I am debugging!" }
        val blobStore = H2BlobStore(testBlobStoreDirectory)
        RegistryServerApp(logger, blobStore).start(7000)

        // get number of blobs from blobStore
        val numBlobs = blobStore.countBlobs()

        val cb2 = ResultCallback.Adapter<PushResponseItem>()
        dockerClient.pushImageCmd(hostedTaggedImage).exec(cb2).awaitCompletion()
        cb2.awaitCompletion()
        // expect numBlobs to be greater than it was
        assert(numBlobs < blobStore.countBlobs())
        logger.debug { "numBlobs: $numBlobs. New count: ${blobStore.countBlobs()}" }

        // let's make sure we got a manifest as well...
        val manifest = blobStore.getManifest(ImageVersion("ubuntu", "20.04"))
        logger.debug { "manifest: $manifest" }
        // assert that manifest is not blank
        assert(manifest.isNotBlank())
    }
}