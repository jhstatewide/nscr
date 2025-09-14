import blobstore.Blobstore
import blobstore.Digest
import blobstore.H2BlobStore
import blobstore.ImageVersion
import io.javalin.Javalin
import mu.KLogger
import mu.KotlinLogging
import nscr.Config
import org.jdbi.v3.core.Handle
import java.security.MessageDigest

fun main() {
    val logger = KotlinLogging.logger {  }
    
    // Validate configuration before starting
    val configErrors = Config.validate()
    if (configErrors.isNotEmpty()) {
        logger.error("Configuration validation failed:")
        configErrors.forEach { error -> logger.error("  - $error") }
        System.exit(1)
    }
    
    // Print configuration on startup
    Config.printConfig()
    
    val app = RegistryServerApp(logger)
    app.start(Config.SERVER_PORT)
}

class RegistryServerApp(private val logger: KLogger, blobstore: Blobstore = H2BlobStore()) {
    val blobStore = blobstore
    val sessionTracker = SessionTracker()
    val app: Javalin = Javalin.create { config ->

    } ?: throw Error("Could not create Javalin app!")

    init {
        bindApp(app, logger)
        
        // Start cleanup task if blobstore supports it
        if (blobStore is H2BlobStore) {
            blobStore.startCleanupTask()
        }
        
        // Register shutdown hook for proper cleanup
        Runtime.getRuntime().addShutdownHook(Thread {
            logger.info("Shutting down RegistryServerApp...")
            stop()
        })
    }

    fun start(port: Int) {
        app.start(port)
    }

    fun stop() {
        try {
            logger.info("Stopping Javalin application...")
            app.stop()
            
            // Clean up blobstore resources
            if (blobStore is H2BlobStore) {
                blobStore.cleanup()
            }
            
            logger.info("RegistryServerApp stopped successfully")
        } catch (e: Exception) {
            logger.error("Error stopping RegistryServerApp: ${e.message}")
        }
    }

    fun javalinApp(): Javalin {
        return app
    }

    private fun bindApp(app: Javalin, logger: KLogger) {
        app.before { ctx ->
            logger.debug("BEFORE: ${ctx.method()} to ${ctx.url()}")
        }

        // Note: Database handles are now automatically managed by JDBI's useHandle/withHandle methods

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
            handleBlobExistenceCheck(ctx, digest, logger)
        }

        app.head("/v2/{name}/manifests/{tag}") { ctx ->
            val name = ctx.pathParam("name")
            val tag = ctx.pathParam("tag")
            val imageVersion = ImageVersion(name, tag)
            logger.info { "Checking on manifest for $imageVersion" }
            handleManifestExistenceCheck(ctx, imageVersion, logger)
        }

        app.get("/v2/{name}/manifests/{tag}") { ctx ->
            val name = ctx.pathParam("name")
            val tagOrDigest = ctx.pathParam("tag")
            val imageVersion = ImageVersion(name, tagOrDigest)
            val manifestType = "application/vnd.docker.distribution.manifest.v2+json"
            
            if (!blobStore.hasManifest(imageVersion)) {
                ctx.status(404)
                ctx.result("Manifest not found")
                return@get
            }
            
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
                    ctx.header("Location", Config.REGISTRY_URL)
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
            ctx.header("Location", Config.REGISTRY_URL)
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
            ctx.header("Location", Config.REGISTRY_URL)
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
                // Note: Handle is automatically closed by JDBI's useHandle method
            }
        }

        // Docker Registry API v2 endpoints for deletion and management
        app.delete("/v2/{name}/manifests/{reference}") { ctx ->
            val name = ctx.pathParam("name")
            val reference = ctx.pathParam("reference")
            val imageVersion = ImageVersion(name, reference)
            logger.info("Deleting manifest for $imageVersion")
            
            try {
                // Atomic delete operation - check and delete in one transaction
                val wasDeleted = blobStore.removeManifestIfExists(imageVersion)
                
                if (wasDeleted) {
                    ctx.status(202)
                    ctx.result("Manifest deleted")
                } else {
                    ctx.status(404)
                    ctx.result("Manifest not found")
                }
            } catch (e: Exception) {
                logger.error("Error deleting manifest for $imageVersion: ${e.message}", e)
                ctx.status(500)
                ctx.result("Internal server error")
            }
        }

        // List repositories
        app.get("/v2/_catalog") { ctx ->
            val repositories = blobStore.listRepositories()
            val response = mapOf("repositories" to repositories)
            ctx.json(response)
        }

        // List tags for a repository
        app.get("/v2/{name}/tags/list") { ctx ->
            val name = ctx.pathParam("name")
            val tags = blobStore.listTags(name)
            val response = mapOf(
                "name" to name,
                "tags" to tags
            )
            ctx.json(response)
        }

        // Garbage collection endpoint
        app.post("/api/garbage-collect") { ctx ->
            logger.info("Starting garbage collection...")
            val result = blobStore.garbageCollect()
            val response = mapOf(
                "blobsRemoved" to result.blobsRemoved,
                "spaceFreed" to result.spaceFreed,
                "manifestsRemoved" to result.manifestsRemoved
            )
            ctx.contentType("application/json")
            ctx.json(response)
        }

        // Garbage collection statistics endpoint
        app.get("/api/garbage-collect/stats") { ctx ->
            logger.info("Getting garbage collection statistics...")
            val stats = blobStore.getGarbageCollectionStats()
            val response = mapOf(
                "totalBlobs" to stats.totalBlobs,
                "totalManifests" to stats.totalManifests,
                "unreferencedBlobs" to stats.unreferencedBlobs,
                "orphanedManifests" to stats.orphanedManifests,
                "estimatedSpaceToFree" to stats.estimatedSpaceToFree
            )
            ctx.contentType("application/json")
            ctx.json(response)
        }

    }

    // Helper functions for DRY code
    private fun handleBlobExistenceCheck(ctx: io.javalin.http.Context, digest: Digest, logger: KLogger) {
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

    private fun handleManifestExistenceCheck(ctx: io.javalin.http.Context, imageVersion: ImageVersion, logger: KLogger) {
        if(blobStore.hasManifest(imageVersion)) {
            logger.debug("We DO have manifest for {}!", imageVersion)
            ctx.status(200)
        } else {
            logger.debug("We DO NOT have manifest for {}!", imageVersion)
            ctx.status(404)
            ctx.result("Not found")
        }
    }

    private fun generateSHA256(input: String): String {
        val bytes = input.toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        val sb = StringBuilder()
        for (b in digest) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}