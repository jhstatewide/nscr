import blobstore.Blobstore
import blobstore.Digest
import blobstore.H2BlobStore
import blobstore.ImageVersion
import io.javalin.Javalin
import mu.KLogger
import mu.KotlinLogging
import org.jdbi.v3.core.Handle
import java.security.MessageDigest

fun main() {
    val logger = KotlinLogging.logger {  }
    val app = RegistryServerApp(logger)
    app.start(7000)
}

class RegistryServerApp(logger: KLogger, blobstore: Blobstore = H2BlobStore()) {
    val blobStore = blobstore
    val sessionTracker = SessionTracker()
    val app: Javalin = Javalin.create { config ->

    } ?: throw Error("Could not create Javalin app!")

    init {
        bindApp(app, logger)
    }

    fun start(port: Int) {
        app.start(port)
    }

    fun javalinApp(): Javalin {
        return app
    }

    private fun bindApp(app: Javalin, logger: KLogger) {
        app.before { ctx ->
            logger.debug("BEFORE: ${ctx.method()} to ${ctx.url()}")
        }

        app.after("/v2/{name}/blobs/{tag}") { ctx ->
            logger.debug("Closing database handle!")
            ctx.attribute<Handle>("handle")?.close()
        }

        app.get("/") { ctx ->
            logger.debug("Got a request to URL: ${ctx.url()}")
            ctx.result("Hello World")
        }

        app.get("/v2") { ctx ->
            logger.info { "Access GET /v2" }
            ctx.header("Docker-Distribution-API-Version", "registry/2.0")
            ctx.result("200 OK")
        }

        app.head("/v2/{image}/blobs/{digest}") { ctx ->
            val image = ctx.pathParam("image")
            val digest = Digest(ctx.pathParam("digest"))
            logger.info("Checking on /v2/$image/blobs/${digest.digestString} ($image ${digest.digestString})")
            if (!blobStore.hasBlob(digest)) {
                logger.debug("We do not have $digest")
                ctx.status(404)
                ctx.result("Not found")
            } else {
                logger.debug("We DO have $digest")
                ctx.status(200)
                ctx.result("OK")
            }
        }

        app.head("/v2/{name}/manifests/{tag}") { ctx ->
            val name = ctx.pathParam("name")
            val tag = ctx.pathParam("tag")
            val imageVersion = ImageVersion(name, tag)
            logger.info { "Checking on manifest for $imageVersion" }
            if(blobStore.hasManifest(imageVersion)) {
                logger.debug("We DO have manifest for {}!", imageVersion)
                ctx.status(200)
            } else {
                logger.debug("We DO NOT have manifest for {}!", imageVersion)
                ctx.status(404)
                ctx.result("Not found")
            }
        }

        app.get("/v2/{name}/manifests/{tag}") { ctx ->
            val name = ctx.pathParam("name")
            val tagOrDigest = ctx.pathParam("tag")
            val imageVersion = ImageVersion(name, tagOrDigest)
            val manifestType = "application/vnd.docker.distribution.manifest.v2+json"
            if (imageVersion.tag.startsWith("sha256:")) {
                // by digest
                logger.debug("Want to look up digest for {}!", imageVersion)
                ctx.status(200)
                ctx.header("Docker-Content-Digest", imageVersion.tag)
                ctx.contentType(manifestType)
                ctx.result(blobStore.getManifest(imageVersion))
            } else {
                val digest = blobStore.digestForManifest(imageVersion)
                logger.debug("Digest for manifest {} is {}", imageVersion, digest)
                ctx.status(200)
                ctx.header("Docker-Content-Digest", digest.digestString)
                ctx.contentType(manifestType)
                ctx.result(blobStore.getManifest(imageVersion))
            }
        }

        app.post("/v2/{image}/blobs/uploads") { ctx ->
            logger.debug("Got a post to UPLOADS!")
            // see if we have the query param 'digest',
            // as in /v2/test/blobs/uploads?digest=sha256:1234
            val digest = ctx.queryParam("digest")
            if (digest != null) {
                logger.debug("Got a digest: $digest")
                if (blobStore.hasBlob(Digest(digest))) {
                    logger.debug("We already have this blob!")
                    ctx.status(201)
                    ctx.header("Location", "http://localhost")
                    ctx.result("Created")
                    return@post
                }
            }

            // we want to return a session id here...
            val sessionID = sessionTracker.newSession()
            val newLocation = "/v2/uploads/${blobStore.nextSessionLocation(sessionID)}"

            if (digest != null) {
                logger.debug("Associating $digest with $sessionID")
                blobStore.associateBlobWithSession(sessionID, Digest(digest))
            }

            logger.info("Telling the uploader to go to $newLocation")
            ctx.header("Location", newLocation)
            ctx.header("Docker-Upload-UUID", sessionID.id)
            ctx.status(202)
            ctx.result("OK")
        }

        app.patch("/v2/uploads/{sessionID}/{blobNumber}") { ctx ->
            val sessionID = SessionID(ctx.pathParam("sessionID"))
            val blobNumber = ctx.pathParam("blobNumber").toIntOrNull()
            val contentRange = ctx.header("Content-Range")
            val contentLength = ctx.header("Content-Length")?.toIntOrNull()
            logger.info("Uploading to $sessionID with content range: $contentRange and length: $contentLength")

            val uploadedBytes = blobStore.addBlob(sessionID, blobNumber, ctx.bodyInputStream())

            ctx.status(202)
            // we have to give a location to upload to next...
            ctx.header("Location", "/v2/uploads/${blobStore.nextSessionLocation(sessionID)}")
            ctx.header("Range", "0-${uploadedBytes}")
            ctx.header("Content-Length", "0")
            ctx.header("Docker-Upload-UUID", sessionID.id)
            ctx.result("Accepted")
        }

        app.put("/v2/uploads/{sessionID}/{blobNumber}") { ctx ->
            val sessionID = SessionID(ctx.pathParam("sessionID"))
            val blobNumber = ctx.pathParam("blobNumber").toIntOrNull()
            val digest = Digest(ctx.queryParam("digest") ?: throw Error("No digest provided as query param!"))
            logger.debug("Got a put request for $sessionID/$blobNumber for $digest!")
            // 201 Created
            blobStore.associateBlobWithSession(sessionID, digest)
            ctx.header("Location", "http://localhost") // TODO: fix me! I have no idea why this is here...
            ctx.status(201)
            ctx.result("Created")
        }

        app.put("/v2/{name}/manifests/{reference}") { ctx ->
            val name = ctx.pathParam("name")
            val reference = ctx.pathParam("reference")
            logger.info("Tackling manifest named $name:$reference!")

            val contentType = ctx.header("Content-Type")
            val manifestType = "application/vnd.docker.distribution.manifest.v2+json"
            if (contentType != manifestType) {
                error("Mime type blooper! You must upload manifest of type: $manifestType instead of $contentType!")
            }
            val body = ctx.body()
            logger.info("Uploaded manifest is: $body")
            // get digest for this crap...
            val sha = generateSHA256(body)
            val digestString = "sha256:$sha"
            blobStore.addManifest(ImageVersion(name, reference), Digest(sha), body)

            ctx.status(201)
            ctx.header("Location", "http://localhost")
            ctx.header("Docker-Content-Digest", digestString)
            ctx.header("Content-Length", "0")
            ctx.result("Created")
        }

        app.get("/api/blobs") { ctx ->
            val blobList = StringBuilder()
            blobStore.eachBlob { blobRow ->
                blobList.append(blobRow.digest)
                blobList.append("\n")
            }
            ctx.result(blobList.toString())
        }

        app.get("/v2/{name}/blobs/{tag}") { ctx ->
            val name = ctx.pathParam("name")
            val tagOrDigest = ctx.pathParam("tag")
            val imageVersion = ImageVersion(name, tagOrDigest)
            blobStore.getBlob(imageVersion) { stream, handle ->
                ctx.result(stream)
                ctx.status(200)
                ctx.attribute("handle", handle)
            }
        }
    }

    private fun generateSHA256(input: String): String {
        val bytes = input.toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.fold("") { str, it -> str + "%02x".format(it) }
    }
}