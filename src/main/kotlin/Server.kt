import blobstore.Digest
import blobstore.H2BlobStore
import io.javalin.Javalin
import org.slf4j.LoggerFactory
import java.util.*

fun main(args: Array<String>) {
    System.setProperty(org.slf4j.simple.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "INFO");
    val logger = LoggerFactory.getLogger("NSCR")
    val blobStore = H2BlobStore()
    val app = Javalin.create() { config ->
        config.enableDevLogging()
        config.requestLogger { ctx, ms ->
            logger.info("CTX: ${ctx.method()} ${ctx.fullUrl()}")
        }
    }.start(7000)
    app.get("/") { ctx -> ctx.result("Hello World") }
    app.get("/v2") { ctx ->
        ctx.header("Docker-Distribution-API-Version", "registry/2.0")
        ctx.result("200 OK")
    }
    app.head("/v2/:image/blobs/:digest") { ctx ->
        val image = ctx.pathParam("image")
        val digest = Digest(ctx.pathParam("digest"))
        logger.info("Want to upload $image $digest")
        if (!blobStore.hasBlob(digest)) {
            ctx.status(404)
        } else {
            ctx.status(200)
            ctx.result("OK")
        }
    }
    app.post("/v2/:image/blobs/uploads") { ctx ->
        logger.info("Got a post to UPLOADS!")
        // we want to return a session id here...
        val uuid = UUID.randomUUID()
        ctx.header("Location", "/v2/uploads/${uuid}")
        ctx.status(202)
        ctx.result("OK")
    }
    app.patch("/v2/uploads/:uuid") { ctx ->
        logger.info("Got a request to patch a blob!")
        val uploadUUID = ctx.pathParam("uuid")
        val contentRange = ctx.header("Content-Range")
        val contentLength = ctx.header("Content-Length")
        val bodyStream = ctx.bodyAsInputStream()
        val blob = bodyStream.readAllBytes()
        logger.info("Uploading to $uploadUUID with content range: $contentRange and length: $contentLength")
        ctx.status(202)
        // we have to give a location to upload to next...
        val uuid = UUID.randomUUID()
        ctx.header("Location", "/v2/uploads/${uuid}")
        ctx.result("Accepted")
    }
}