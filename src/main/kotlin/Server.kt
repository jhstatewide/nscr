import blobstore.Digest
import blobstore.H2BlobStore
import io.javalin.Javalin
import org.slf4j.LoggerFactory
import java.util.*

val blobStore = H2BlobStore()
val sessionTracker = SessionTracker()

fun main(args: Array<String>) {
    // System.setProperty(org.slf4j.simple.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "INFO");
    val logger = LoggerFactory.getLogger("nscr")
    val app = Javalin.create() { config ->
        config.enableDevLogging()
        config.requestLogger { ctx, ms ->
            logger.info("CTX: ${ctx.method()} ${ctx.fullUrl()}")
        }
    }.start(7000)
    app.get("/") { ctx ->
        logger.debug("Got a request to URL: ${ctx.url()}")
        ctx.result("Hello World")
    }
    app.get("/v2") { ctx ->
        ctx.header("Docker-Distribution-API-Version", "registry/2.0")
        ctx.result("200 OK")
    }
    app.head("/v2/:image/blobs/:digest") { ctx ->
        val image = ctx.pathParam("image")
        val digest = Digest(ctx.pathParam("digest"))
        logger.debug("Checking on $image $digest")
        if (!blobStore.hasBlob(digest)) {
            logger.debug("We do not have $digest")
            ctx.status(404)
        } else {
            logger.debug("We DO have $digest")
            ctx.status(200)
            ctx.result("OK")
        }
    }
    app.post("/v2/:image/blobs/uploads") { ctx ->
        logger.debug("Got a post to UPLOADS!")
        // we want to return a session id here...
        val sessionID = sessionTracker.newSession()
        ctx.header("Location", "/v2/uploads/${blobStore.nextSessionLocation(sessionID)}")
        ctx.header("Docker-Upload-UUID", sessionID.id)
        ctx.status(202)
        ctx.result("OK")
    }
    app.patch("/v2/uploads/:sessionID/:blobNumber") { ctx ->
        try {
            logger.debug("Got a request to patch a blob!")
            val sessionID = ctx.pathParam("sessionID")
            val blobNumber = ctx.pathParam("blobNumber")
            val contentRange = ctx.header("Content-Range")
            val contentLength = ctx.header("Content-Length")
            logger.debug("Patch uploads context headers: ${ctx.headerMap()}")
            logger.debug("Uploading to $sessionID with content range: $contentRange and length: $contentLength")

            val blob = ctx.bodyAsBytes()
            logger.info("The patched blob is: ${blob.size} bytes long!")
            // TODO: we need to add the blob here and POST i guess completes things???
            // plan is take the upload and then somehow correlate to the post and apply the digest...
            ctx.status(202)
            // we have to give a location to upload to next...
            val uuid = UUID.randomUUID()
            ctx.header("Location", "/v2/uploads/${uuid}")
            ctx.header("Range", "0-${blob.size}")
            ctx.header("Content-Length", "0")
            ctx.header("Docker-Upload-UUID", sessionID)
            ctx.result("Accepted")
        } catch (e: Exception) {
            logger.warn("Error during patch: ${e.message}")
        }

    }
    app.put("/v2/uploads/:uuid/") { ctx ->
        val uuid = ctx.pathParam("uuid")
        val digest = Digest(ctx.queryParam("digest") ?: throw Error("No digest provided as query param!"))
        logger.debug("Got a put request for $uuid for $digest!")
        val bytesFromBody = ctx.bodyAsBytes()
        logger.debug("The body is: ${String(bytesFromBody)}")
        blobStore.addBlob(digest, bytesFromBody)
        // 201 Created
        ctx.status(201)
        ctx.result("Created")
    }
    app.put("/v2/:image/manifests/:version") { ctx ->
        ctx.status(201)
        ctx.header("Location","http://haha.com")
        ctx.header("Docker-Content-Digest", "sha256:a3ed95caeb02ffe68cdd9fd84406680ae93d633cb16422d00e8a7c22955b46d4")
        ctx.header("Content-Length", "0")
        ctx.result("Created")
    }
}