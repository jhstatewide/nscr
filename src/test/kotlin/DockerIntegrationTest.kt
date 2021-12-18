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
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration


class DockerIntegrationTest {

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

        val cb = ResultCallback.Adapter<PullResponseItem>()
        dockerClient.pullImageCmd("ubuntu:20.04").exec(cb).awaitCompletion()
        cb.awaitCompletion()

        dockerClient.tagImageCmd("ubuntu:20.04", "localhost:7000/ubuntu:20.04", "latest").exec()

        val logger = KotlinLogging.logger {  }
        logger.info { "I am debugging!" }
        appInstance(logger).start(7000)

        val cb2 = ResultCallback.Adapter<PushResponseItem>()
        dockerClient.pushImageCmd("localhost:7000/ubuntu:20.04").exec(cb2).awaitCompletion()
        cb2.awaitCompletion()
    }
}