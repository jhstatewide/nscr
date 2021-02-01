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
    app.before { ctx ->
        logger.info("BEFORE: ${ctx.method()} to ${ctx.url()}")
    }
    app.after { ctx ->
        logger.info("AFTER: ${ctx.method()} to ${ctx.url()}")
    }
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
        val newLocation = "/v2/uploads/${blobStore.nextSessionLocation(sessionID)}"
        logger.info("Telling the uploader to go to $newLocation")
        ctx.header("Location", newLocation)
        ctx.header("Docker-Upload-UUID", sessionID.id)
        ctx.status(202)
        ctx.result("OK")
    }

    app.patch("/v2/uploads/:sessionID/:blobNumber") { ctx ->

        logger.info("Got a request to patch a blob!")
        val sessionID = SessionID(ctx.pathParam("sessionID"))
        val blobNumber = ctx.pathParam("blobNumber").toIntOrNull()
        val contentRange = ctx.header("Content-Range")
        val contentLength = ctx.header("Content-Length")?.toIntOrNull()
        logger.info("Patch uploads context headers: ${ctx.headerMap()}")
        logger.info("Uploading to $sessionID with content range: $contentRange and length: $contentLength")

        val uploadedBytes = blobStore.addBlob(sessionID, blobNumber, ctx.bodyAsInputStream())

        // TODO: we need to add the blob here and POST i guess completes things???
        // plan is take the upload and then somehow correlate to the post and apply the digest...
        ctx.status(202)
        // we have to give a location to upload to next...
        ctx.header("Location", "/v2/uploads/${blobStore.nextSessionLocation(sessionID)}")
        ctx.header("Range", "0-${uploadedBytes}")
        ctx.header("Content-Length", "0")
        ctx.header("Docker-Upload-UUID", sessionID.id)
        ctx.result("Accepted")
        logger.info("PATCH ALL SET!")
    }
    app.put("/v2/uploads/:sessionID/:blobNumber") { ctx ->
        val sessionID = SessionID(ctx.pathParam("sessionID"))
        val blobNumber = ctx.pathParam("blobNumber").toIntOrNull()
        val digest = Digest(ctx.queryParam("digest") ?: throw Error("No digest provided as query param!"))
        logger.debug("Got a put request for $sessionID/$blobNumber for $digest!")
        // 201 Created
        // TODO: we MUST link sessionID to the uploaded blob digest!
        blobStore.buildBlob(sessionID, digest)
        ctx.header("Location", "http://hoho.com")
        ctx.status(201)
        ctx.result("Created")
        logger.debug("PUT REQUEST DONE!")
    }
    app.put("/v2/:image/manifests/:version") { ctx ->
        logger.info("Manifest all good!")
        ctx.status(201)
        ctx.header("Location", "http://haha.com")
        ctx.header("Docker-Content-Digest", "sha256:a3ed95caeb02ffe68cdd9fd84406680ae93d633cb16422d00e8a7c22955b46d4")
        ctx.header("Content-Length", "0")
        ctx.result("Created")
    }
}