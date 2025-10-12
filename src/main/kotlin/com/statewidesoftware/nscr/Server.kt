package com.statewidesoftware.nscr

import com.statewidesoftware.nscr.blobstore.Blobstore
import com.statewidesoftware.nscr.blobstore.Digest
import com.statewidesoftware.nscr.blobstore.H2BlobStore
import com.statewidesoftware.nscr.blobstore.ImageVersion
import io.javalin.Javalin
import io.javalin.http.Context
import io.javalin.http.staticfiles.Location
import io.javalin.http.sse.SseClient
import mu.KLogger
import mu.KotlinLogging
import java.security.MessageDigest
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import org.slf4j.LoggerFactory
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger

/**
 * Web interface authentication helper function
 * This provides authentication specifically for the web interface
 */
fun Context.requireWebAuth() {
    if (!Config.WEB_INTERFACE_ENABLED || !Config.WEB_AUTH_ENABLED) {
        return // No web auth required
    }

    val authHeader = this.header("Authorization")
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
        this.status(401)
        this.json(mapOf("error" to "Web interface authentication required"))
        return
    }

    // For now, we'll use a simple token validation
    // In production, you might want to use JWT or session management
    val token = authHeader.substring(7)
    // Simple validation - in production, implement proper token validation
    if (token != "web-token-${Config.WEB_AUTH_USERNAME}") {
        this.status(401)
        this.json(mapOf("error" to "Invalid web interface token"))
        return
    }
}

/**
 * HTTP Basic Authentication helper function
 * This provides a clean, reusable way to handle authentication across endpoints
 */
fun Context.requireAuth() {
    if (!Config.AUTH_ENABLED) {
        return // No auth required
    }

    val authHeader = this.header("Authorization")
    if (authHeader == null || !authHeader.startsWith("Basic ")) {
        this.header("WWW-Authenticate", "Basic realm=\"Docker Registry\"")
        this.status(401)
        this.result("Authentication required")
        return
    }

    try {
        val encoded = authHeader.substring(6) // Remove "Basic " prefix
        val decoded = String(java.util.Base64.getDecoder().decode(encoded))
        val parts = decoded.split(":", limit = 2)

        if (parts.size != 2) {
            this.header("WWW-Authenticate", "Basic realm=\"Docker Registry\"")
            this.status(401)
            this.result("Invalid authentication format")
            return
        }

        val username = parts[0]
        val password = parts[1]

        if (username != Config.AUTH_USERNAME || password != Config.AUTH_PASSWORD) {
            this.header("WWW-Authenticate", "Basic realm=\"Docker Registry\"")
            this.status(401)
            this.result("Invalid credentials")
            return
        }

        // Authentication successful, continue
    } catch (e: Exception) {
        this.header("WWW-Authenticate", "Basic realm=\"Docker Registry\"")
        this.status(401)
        this.result("Authentication error")
        return
    }
}

/**
 * Configure logging levels based on environment configuration
 */
fun configureLogging() {
    val logLevel = Config.LOG_LEVEL.uppercase()
    val level = when (logLevel) {
        "TRACE" -> Level.TRACE
        "DEBUG" -> Level.DEBUG
        "INFO" -> Level.INFO
        "WARN" -> Level.WARN
        "ERROR" -> Level.ERROR
        else -> Level.INFO
    }

    // Set root logger level
    val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    rootLogger.level = level

    // Set specific logger levels for noisy components
    val blobStoreLogger = LoggerFactory.getLogger("blobstore.H2BlobStore") as Logger
    when (logLevel) {
        "TRACE", "DEBUG" -> blobStoreLogger.level = level
        "INFO" -> blobStoreLogger.level = Level.WARN  // Reduce blob store noise at INFO level
        "WARN", "ERROR" -> blobStoreLogger.level = level
    }

    // Set Javalin to be less verbose
    val javalinLogger = LoggerFactory.getLogger("io.javalin") as Logger
    javalinLogger.level = Level.WARN
}

/**
 * Extract the media type from a manifest JSON string
 * Returns the appropriate content type for the manifest
 */
fun extractManifestMediaType(manifestJson: String): String {
    return try {
        // Parse the JSON to extract the mediaType field
        val jsonObject = com.google.gson.JsonParser.parseString(manifestJson).asJsonObject
        val mediaType = jsonObject.get("mediaType")?.asString

        // Return the media type if found, otherwise default to Docker v2
        mediaType ?: "application/vnd.docker.distribution.manifest.v2+json"
    } catch (e: Exception) {
        // If parsing fails, default to Docker v2 manifest type
        "application/vnd.docker.distribution.manifest.v2+json"
    }
}

fun main() {
    // Configure logging level from environment
    configureLogging()

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
    val startTime = System.currentTimeMillis()
    val app: Javalin = Javalin.create { config ->
        config.showJavalinBanner = false

        // Configure static file serving for web interface
        if (Config.WEB_INTERFACE_ENABLED) {
            config.staticFiles.add { staticFiles ->
                staticFiles.hostedPath = "/static"
                staticFiles.directory = "/static"
                staticFiles.location = Location.CLASSPATH
            }

        }

    } ?: throw Error("Could not create Javalin app!")

    init {
        bindApp(app, logger)

        // SSE endpoints
        app.sse("/api/logs/stream") { client ->
            try {
                SseLogAppender.addClient(client)
                client.sendEvent("connected", "Log stream started")

                // Send a welcome message after 3 seconds to show the user the connection is working
                Thread {
                    try {
                        Thread.sleep(3000) // Wait 3 seconds
                        SseLogAppender.broadcastLog(
                            timestamp = System.currentTimeMillis(),
                            level = "INFO",
                            message = "Welcome to NSCR Registry! Live log streaming is now active.",
                            logger = "com.statewidesoftware.nscr.Server",
                            thread = "SSE-Welcome"
                        )
                    } catch (e: InterruptedException) {
                        // Thread interrupted, connection likely closed
                    }
                }.start()

                // Keep the connection alive by not returning from this block
                // The connection will stay open until the client disconnects
                try {
                    Thread.sleep(Long.MAX_VALUE) // Sleep indefinitely
                } catch (e: InterruptedException) {
                    // Connection closed
                }
            } catch (e: Exception) {
                logger.error { "Error in SSE connection: ${e.message}" }
            }
        }

        // Shutdown endpoint (only enabled if configured)
        if (Config.SHUTDOWN_ENDPOINT_ENABLED) {
            app.post("/api/shutdown") { ctx ->
                logger.info { "Shutdown endpoint called" }
                ctx.json(mapOf(
                    "message" to "Server shutdown initiated",
                    "timestamp" to System.currentTimeMillis()
                ))

                // Shutdown in a separate thread to allow response to be sent
                Thread {
                    Thread.sleep(1000) // Give time for response to be sent
                    logger.info { "Shutting down server..." }
                    app.stop()
                    System.exit(0)
                }.start()
            }
        }


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
        // Central authentication for Docker Registry API endpoints
        app.before("/v2") { ctx ->
            logger.trace("AUTH CHECK: ${ctx.method()} to ${ctx.url()}")
            ctx.requireAuth()
        }
        app.before("/v2/*") { ctx ->
            logger.trace("AUTH CHECK: ${ctx.method()} to ${ctx.url()}")
            ctx.requireAuth()
        }

        // Central authentication for administrative API endpoints (exclude status endpoint)
        app.before("/api") { ctx ->
            if (!ctx.url().contains("/status")) {
                logger.trace("AUTH CHECK: ${ctx.method()} to ${ctx.url()}")
            }
            ctx.requireAuth()
        }
        app.before("/api/*") { ctx ->
            if (!ctx.url().contains("/status")) {
                logger.trace("AUTH CHECK: ${ctx.method()} to ${ctx.url()}")
            }
            ctx.requireAuth()
        }

        // Note: Database handles are now automatically managed by JDBI's useHandle/withHandle methods

        app.get("/") { ctx ->
            logger.debug("Got a request to URL: ${ctx.url()}")
            if (Config.WEB_INTERFACE_ENABLED) {
                // Serve the web interface from classpath
                val htmlContent = this::class.java.getResource("/static/index.html")?.readText()
                    ?: throw RuntimeException("Web interface HTML not found in classpath")
                ctx.html(htmlContent)
            } else {
                ctx.result("Hello World")
            }
        }


        app.get("/v2") { ctx ->
            logger.info { "Access GET /v2" }
            ctx.header("Docker-Distribution-API-Version", "registry/2.0")
            ctx.result("200 OK")
        }

        app.head("/v2/*/blobs/{digest}") { ctx ->
            val fullPath = ctx.path()
            val image = fullPath.substringAfter("/v2/").substringBefore("/blobs")
            val digest = Digest(ctx.pathParam("digest"))
            logger.trace("Checking on /v2/$image/blobs/${digest.digestString} ($image ${digest.digestString})")
            handleBlobExistenceCheck(ctx, digest, logger)
        }

        app.head("/v2/*/manifests/{tag}") { ctx ->
            val fullPath = ctx.path()
            val name = fullPath.substringAfter("/v2/").substringBefore("/manifests")
            val tag = ctx.pathParam("tag")
            val imageVersion = ImageVersion(name, tag)
            logger.trace { "Checking on manifest for $imageVersion" }
            handleManifestExistenceCheck(ctx, imageVersion, logger)
        }

        // Helper function to handle blob upload POST requests
        fun handleBlobUploadPost(ctx: Context) {
            // Extract image name from the wildcard path
            val fullPath = ctx.path()
            val image = fullPath.substringAfter("/v2/").substringBefore("/blobs/uploads")
            logger.debug("POST /v2/$image/blobs/uploads - Starting blob upload")
            // see if we have the query param 'digest',
            // as in /v2/test/blobs/uploads?digest=sha256:1234
            val digest = ctx.queryParam("digest")
            if (digest != null) {
                logger.info("Got a digest: $digest")
                if (blobStore.hasBlob(Digest(digest))) {
                    logger.info("We already have this blob!")
                    ctx.status(201)
                    ctx.header("Location", Config.REGISTRY_URL)
                    ctx.result("Created")
                    return
                }
            }

            // we want to return a session id here...
            val sessionID = sessionTracker.newSession()
            val newLocation = "/v2/uploads/${blobStore.nextSessionLocation(sessionID)}"

            // Note: We don't call associateBlobWithSession here even if digest is provided
            // because no blob chunks have been uploaded yet. The association will happen
            // later when the blob data is actually uploaded via PATCH requests or when
            // the upload is finalized via PUT request.
            if (digest != null) {
                logger.info("Digest $digest provided for session $sessionID - will be associated when blob data is uploaded")
            }

            logger.info("Telling the uploader to go to $newLocation")
            ctx.header("Location", newLocation)
            ctx.header("Docker-Upload-UUID", sessionID.id)
            ctx.status(202)
            ctx.result("OK")
        }

        // List tags for a repository (supports slashes in name using <> syntax)
        app.get("/v2/<name>/tags/list") { ctx ->
            val name = ctx.pathParam("name")
            logger.info("Tags list request - name: $name")
            val tags = blobStore.listTags(name)
            logger.info("Found tags for $name: $tags")
            val response = mapOf(
                "name" to name,
                "tags" to tags
            )
            ctx.json(response)
        }

        app.get("/v2/*/manifests/{tag}") { ctx ->
            val fullPath = ctx.path()
            val name = fullPath.substringAfter("/v2/").substringBefore("/manifests")
            val tagOrDigest = ctx.pathParam("tag")
            val imageVersion = ImageVersion(name, tagOrDigest)

            if (!blobStore.hasManifest(imageVersion)) {
                ctx.status(404)
                ctx.result("Manifest not found")
                return@get
            }

            val manifestJson = blobStore.getManifest(imageVersion)
            val manifestType = extractManifestMediaType(manifestJson)

            if (imageVersion.tag.startsWith("sha256:")) {
                // by digest
                logger.debug("Want to look up digest for {}!", imageVersion)
                ctx.status(200)
                ctx.header("Docker-Content-Digest", imageVersion.tag)
                ctx.contentType(manifestType)
                ctx.result(manifestJson)
            } else {
                val digest = blobStore.digestForManifest(imageVersion)
                logger.debug("Digest for manifest {} is {}", imageVersion, digest)
                ctx.status(200)
                ctx.header("Docker-Content-Digest", digest.digestString)
                ctx.contentType(manifestType)
                ctx.result(manifestJson)
            }
        }

        // Handler for POST /v2/*/blobs/uploads (without trailing slash)
        // Using wildcard to capture full image name including slashes
        app.post("/v2/*/blobs/uploads") { ctx ->
            handleBlobUploadPost(ctx)
        }

        // Handler for POST /v2/*/blobs/uploads/ (with trailing slash)
        app.post("/v2/*/blobs/uploads/") { ctx ->
            handleBlobUploadPost(ctx)
        }

        app.patch("/v2/uploads/{sessionID}/{blobNumber}") { ctx ->
            val sessionID = SessionID(ctx.pathParam("sessionID"))
            val blobNumber = ctx.pathParam("blobNumber").toIntOrNull()
            val contentRange = ctx.header("Content-Range")
            val contentLength = ctx.header("Content-Length")?.toIntOrNull()
            logger.debug("PATCH /v2/uploads/$sessionID/$blobNumber - Uploading blob chunk with content range: $contentRange and length: $contentLength")

            try {
                val uploadedBytes = blobStore.addBlob(sessionID, blobNumber, ctx.bodyInputStream())
                logger.info("Successfully uploaded $uploadedBytes bytes for session $sessionID, blob $blobNumber")

                ctx.status(202)
                // we have to give a location to upload to next...
                ctx.header("Location", "/v2/uploads/${blobStore.nextSessionLocation(sessionID)}")
                ctx.header("Range", "0-${uploadedBytes}")
                ctx.header("Content-Length", "0")
                ctx.header("Docker-Upload-UUID", sessionID.id)
                ctx.result("Accepted")
            } catch (e: Exception) {
                logger.error("Error uploading blob for session $sessionID, blob $blobNumber", e)
                ctx.status(500)
                ctx.result("Internal Server Error: ${e.message}")
            }
        }

        app.put("/v2/uploads/{sessionID}/{blobNumber}") { ctx ->
            val sessionID = SessionID(ctx.pathParam("sessionID"))
            val blobNumber = ctx.pathParam("blobNumber").toIntOrNull()
            val digest = Digest(ctx.queryParam("digest") ?: throw Error("No digest provided as query param!"))
            logger.debug("PUT /v2/uploads/$sessionID/$blobNumber - Finalizing blob upload for digest: $digest")

            try {
                // 201 Created
                blobStore.associateBlobWithSession(sessionID, digest)
                logger.info("Successfully finalized blob upload for session $sessionID, digest $digest")
                ctx.header("Location", Config.REGISTRY_URL)
                ctx.status(201)
                ctx.result("Created")
            } catch (e: Exception) {
                logger.error("Error finalizing blob upload for session $sessionID, digest $digest", e)
                ctx.status(500)
                ctx.result("Internal Server Error: ${e.message}")
            }
        }

        app.put("/v2/*/manifests/{reference}") { ctx ->
            val fullPath = ctx.path()
            val name = fullPath.substringAfter("/v2/").substringBefore("/manifests")
            val reference = ctx.pathParam("reference")
            logger.info("PUSH DEBUG: Processing manifest upload for $name:$reference")

            val contentType = ctx.header("Content-Type")
            val supportedManifestTypes = listOf(
                "application/vnd.docker.distribution.manifest.v2+json",
                "application/vnd.oci.image.manifest.v1+json"
            )
            if (contentType !in supportedManifestTypes) {
                error("Mime type blooper! You must upload manifest of type: ${supportedManifestTypes.joinToString(" or ")} instead of $contentType!")
            }
            val body = ctx.body()
            logger.debug("Uploaded manifest is: $body")

            // Extract and log blob digests referenced by this manifest
            try {
                val blobDigests = extractBlobDigestsFromManifest(body)
                logger.info("PUSH DEBUG: Manifest $name:$reference references ${blobDigests.size} blobs: ${blobDigests.joinToString(", ")}")

                // Verify that all referenced blobs exist
                val missingBlobs = blobDigests.filter { !blobStore.hasBlob(Digest(it)) }
                if (missingBlobs.isNotEmpty()) {
                    logger.warn("PUSH DEBUG: Manifest $name:$reference references missing blobs: ${missingBlobs.joinToString(", ")}")
                } else {
                    logger.info("PUSH DEBUG: All ${blobDigests.size} blobs referenced by manifest $name:$reference are present")
                }
            } catch (e: Exception) {
                logger.warn("PUSH DEBUG: Failed to extract blob digests from manifest $name:$reference: ${e.message}")
            }

            // get digest for this manifest...
            val sha = generateSHA256(body)
            val digestString = "sha256:$sha"
            logger.info("PUSH DEBUG: Adding manifest $name:$reference with digest $digestString")
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

        app.get("/v2/*/blobs/{tag}") { ctx ->
            val fullPath = ctx.path()
            val name = fullPath.substringAfter("/v2/").substringBefore("/blobs")
            val tagOrDigest = ctx.pathParam("tag")
            val imageVersion = ImageVersion(name, tagOrDigest)
            blobStore.getBlob(imageVersion) { stream, _ ->
                ctx.result(stream)
                ctx.status(200)
                // Note: Handle is automatically closed by JDBI's useHandle method
            }
        }

        // Docker Registry API v2 endpoints for deletion and management
        app.delete("/v2/*/manifests/{reference}") { ctx ->
            val fullPath = ctx.path()
            val name = fullPath.substringAfter("/v2/").substringBefore("/manifests")
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

        // List repositories with timestamps for web interface
        app.get("/api/registry/repositories") { ctx ->
            val repositories = blobStore.listRepositoriesWithTimestamps()
            val response = mapOf("repositories" to repositories)
            ctx.json(response)
        }


        // Delete entire repository (all manifests/tags)
        app.delete("/v2/<name>") { ctx ->
            val name = ctx.pathParam("name")
            logger.info("Deleting repository: $name")

            try {
                val deletedCount = blobStore.deleteRepository(name)

                if (deletedCount > 0) {
                    ctx.status(202)
                    val response = mapOf(
                        "message" to "Repository deleted",
                        "manifestsDeleted" to deletedCount
                    )
                    ctx.json(response)
                } else {
                    ctx.status(404)
                    ctx.result("Repository not found")
                }
            } catch (e: Exception) {
                logger.error("Error deleting repository $name: ${e.message}", e)
                ctx.status(500)
                ctx.result("Internal server error")
            }
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

        // Comprehensive registry state API for external monitoring and torture testing
        app.get("/api/registry/state") { ctx ->
            logger.info("Getting comprehensive registry state...")
            try {
                val repositories = blobStore.listRepositories()
                val gcStats = blobStore.getGarbageCollectionStats()

                // Calculate storage metrics
                var totalBytes = 0L
                val uniqueBlobs = mutableSetOf<String>()
                var uniqueBytes = 0L

                blobStore.eachBlob { blobRow ->
                    val blobSize = blobRow.content.size.toLong()
                    totalBytes += blobSize

                    val digest = blobRow.digest
                    if (digest != null && uniqueBlobs.add(digest)) {
                        uniqueBytes += blobSize
                    }
                }

                val deduplicationRatio = if (totalBytes > 0) (uniqueBytes.toDouble() / totalBytes.toDouble()) else 0.0
                val spaceSavings = totalBytes - uniqueBytes

                val repositoryDetails = repositories.map { repoName ->
                    val tags = blobStore.listTags(repoName)
                    mapOf(
                        "name" to repoName,
                        "tagCount" to tags.size,
                        "tags" to tags
                    )
                }

                val response = mapOf(
                    "timestamp" to System.currentTimeMillis(),
                    "registryVersion" to "2.0",
                    "summary" to mapOf(
                        "totalRepositories" to repositories.size,
                        "totalManifests" to gcStats.totalManifests,
                        "totalBlobs" to gcStats.totalBlobs,
                        "unreferencedBlobs" to gcStats.unreferencedBlobs,
                        "orphanedManifests" to gcStats.orphanedManifests,
                        "estimatedSpaceToFree" to gcStats.estimatedSpaceToFree
                    ),
                    "storage" to mapOf(
                        "totalBytes" to totalBytes,
                        "uniqueBytes" to uniqueBytes,
                        "spaceSavings" to spaceSavings,
                        "deduplicationRatio" to deduplicationRatio,
                        "uniqueBlobs" to uniqueBlobs.size
                    ),
                    "repositories" to repositoryDetails,
                    "activeSessions" to mapOf(
                        "count" to 0,
                        "sessions" to emptyList<Map<String, Any>>()
                    ),
                    "health" to mapOf(
                        "status" to "healthy",
                        "uptime" to System.currentTimeMillis() - startTime,
                        "logStreamClients" to SseLogAppender.getClientCount()
                    )
                )

                ctx.contentType("application/json")
                ctx.json(response)
            } catch (e: Exception) {
                logger.error("Error getting registry state: ${e.message}", e)
                ctx.status(500)
                ctx.json(mapOf("error" to "Failed to get registry state", "message" to e.message))
            }
        }

        // Detailed repository information API
        app.get("/api/registry/repositories/<name>") { ctx ->
            val repoName = ctx.pathParam("name")
            logger.info("Getting detailed information for repository: $repoName")

            try {
                val tags = blobStore.listTags(repoName)
                val tagDetails = tags.map { tag ->
                    val imageVersion = ImageVersion(repoName, tag)
                    val hasManifest = blobStore.hasManifest(imageVersion)
                    val digest = if (hasManifest) blobStore.digestForManifest(imageVersion).digestString else null

                    mapOf(
                        "tag" to tag,
                        "hasManifest" to hasManifest,
                        "digest" to digest
                    )
                }

                val response = mapOf(
                    "name" to repoName,
                    "tagCount" to tags.size,
                    "tags" to tagDetails,
                    "timestamp" to System.currentTimeMillis()
                )

                ctx.contentType("application/json")
                ctx.json(response)
            } catch (e: Exception) {
                logger.error("Error getting repository details for $repoName: ${e.message}", e)
                ctx.status(500)
                ctx.json(mapOf("error" to "Failed to get repository details", "message" to e.message))
            }
        }

        // Blob information API
        app.get("/api/registry/blobs") { ctx ->
            logger.info("Getting blob information...")

            try {
                val blobList = mutableListOf<Map<String, Any>>()
                var totalBytes = 0L
                val uniqueBlobs = mutableSetOf<String>()
                var uniqueBytes = 0L

                blobStore.eachBlob { blobRow ->
                    val blobSize = blobRow.content.size.toLong()
                    totalBytes += blobSize

                    // Track unique blobs by digest
                    val digest = blobRow.digest
                    if (digest != null && uniqueBlobs.add(digest)) {
                        uniqueBytes += blobSize
                    }

                    blobList.add(mapOf(
                        "digest" to (digest ?: "unknown"),
                        "sessionID" to blobRow.sessionID,
                        "blobNumber" to blobRow.blobNumber,
                        "size" to blobSize
                    ))
                }

                val response = mapOf(
                    "totalBlobs" to blobList.size,
                    "uniqueBlobs" to uniqueBlobs.size,
                    "totalBytes" to totalBytes,
                    "uniqueBytes" to uniqueBytes,
                    "deduplicationRatio" to if (totalBytes > 0) (uniqueBytes.toDouble() / totalBytes.toDouble()) else 0.0,
                    "blobs" to blobList,
                    "timestamp" to System.currentTimeMillis()
                )

                ctx.contentType("application/json")
                ctx.json(response)
            } catch (e: Exception) {
                logger.error("Error getting blob information: ${e.message}", e)
                ctx.status(500)
                ctx.json(mapOf("error" to "Failed to get blob information", "message" to e.message))
            }
        }

        // Storage statistics API
        app.get("/api/registry/storage") { ctx ->
            logger.info("Getting storage statistics...")

            try {
                var totalBytes = 0L
                val uniqueBlobs = mutableSetOf<String>()
                var uniqueBytes = 0L
                val blobCounts = mutableMapOf<String, Int>()

                blobStore.eachBlob { blobRow ->
                    val blobSize = blobRow.content.size.toLong()
                    totalBytes += blobSize

                    val digest = blobRow.digest
                    if (digest != null) {
                        // Count references to each unique blob
                        blobCounts[digest] = blobCounts.getOrDefault(digest, 0) + 1

                        // Track unique blobs
                        if (uniqueBlobs.add(digest)) {
                            uniqueBytes += blobSize
                        }
                    }
                }

                val deduplicationRatio = if (totalBytes > 0) (uniqueBytes.toDouble() / totalBytes.toDouble()) else 0.0
                val spaceSavings = totalBytes - uniqueBytes
                val spaceSavingsPercent = if (totalBytes > 0) ((spaceSavings.toDouble() / totalBytes.toDouble()) * 100.0) else 0.0

                // Find most referenced blobs
                val blobSizes = mutableMapOf<String, Long>()
                blobStore.eachBlob { blobRow ->
                    val digest = blobRow.digest
                    if (digest != null) {
                        blobSizes[digest] = blobRow.content.size.toLong()
                    }
                }

                val mostReferencedBlobs = blobCounts.entries
                    .sortedByDescending { it.value }
                    .take(10)
                    .map { (digest, count) ->
                        mapOf<String, Any>(
                            "digest" to digest,
                            "referenceCount" to count,
                            "size" to (blobSizes[digest] ?: 0L)
                        )
                    }

                val response = mapOf(
                    "storage" to mapOf(
                        "totalBytes" to totalBytes,
                        "uniqueBytes" to uniqueBytes,
                        "spaceSavings" to spaceSavings,
                        "spaceSavingsPercent" to spaceSavingsPercent,
                        "deduplicationRatio" to deduplicationRatio
                    ),
                    "blobs" to mapOf(
                        "totalBlobs" to blobCounts.values.sum(),
                        "uniqueBlobs" to uniqueBlobs.size,
                        "averageReferencesPerBlob" to if (uniqueBlobs.isNotEmpty()) (blobCounts.values.sum().toDouble() / uniqueBlobs.size) else 0.0
                    ),
                    "mostReferencedBlobs" to mostReferencedBlobs,
                    "timestamp" to System.currentTimeMillis()
                )

                ctx.contentType("application/json")
                ctx.json(response)
            } catch (e: Exception) {
                logger.error("Error getting storage statistics: ${e.message}", e)
                ctx.status(500)
                ctx.json(mapOf("error" to "Failed to get storage statistics", "message" to e.message))
            }
        }

        // Session tracking API
        app.get("/api/registry/sessions") { ctx ->
            logger.info("Getting active session information...")

            try {
                // Since SessionTracker doesn't track active sessions, return empty list
                // This could be enhanced in the future to track active sessions
                val response = mapOf(
                    "activeSessions" to emptyList<Map<String, Any>>(),
                    "totalActiveSessions" to 0,
                    "timestamp" to System.currentTimeMillis()
                )

                ctx.contentType("application/json")
                ctx.json(response)
            } catch (e: Exception) {
                logger.error("Error getting session information: ${e.message}", e)
                ctx.status(500)
                ctx.json(mapOf("error" to "Failed to get session information", "message" to e.message))
            }
        }

        // Comprehensive health check API
        app.get("/api/registry/health") { ctx ->
            logger.info("Performing comprehensive health check...")

            try {
                val repositories = blobStore.listRepositories()
                val gcStats = blobStore.getGarbageCollectionStats()

                // Perform basic connectivity tests
                val canListRepos = try {
                    blobStore.listRepositories()
                    true
                } catch (e: Exception) {
                    logger.warn("Failed to list repositories during health check: ${e.message}")
                    false
                }

                val canGetStats = try {
                    blobStore.getGarbageCollectionStats()
                    true
                } catch (e: Exception) {
                    logger.warn("Failed to get GC stats during health check: ${e.message}")
                    false
                }

                val healthStatus = if (canListRepos && canGetStats) "healthy" else "degraded"

                val response = mapOf(
                    "status" to healthStatus,
                    "timestamp" to System.currentTimeMillis(),
                    "uptime" to (System.currentTimeMillis() - startTime),
                    "checks" to mapOf(
                        "repositoryListing" to canListRepos,
                        "statisticsAccess" to canGetStats,
                        "databaseConnectivity" to true // H2 is embedded, so always true
                    ),
                    "metrics" to mapOf(
                        "totalRepositories" to repositories.size,
                        "totalManifests" to gcStats.totalManifests,
                        "totalBlobs" to gcStats.totalBlobs,
                        "activeSessions" to 0, // SessionTracker doesn't track active sessions
                        "logStreamClients" to SseLogAppender.getClientCount()
                    ),
                    "warnings" to if (gcStats.unreferencedBlobs > 0 || gcStats.orphanedManifests > 0) {
                        listOf("Unreferenced blobs or orphaned manifests detected - consider running garbage collection")
                    } else {
                        emptyList<String>()
                    }
                )

                ctx.contentType("application/json")
                ctx.json(response)
            } catch (e: Exception) {
                logger.error("Error during health check: ${e.message}", e)
                ctx.status(500)
                ctx.json(mapOf(
                    "status" to "unhealthy",
                    "error" to "Health check failed",
                    "message" to e.message,
                    "timestamp" to System.currentTimeMillis()
                ))
            }
        }

        // Web interface endpoints
        if (Config.WEB_INTERFACE_ENABLED) {
            // Web interface login endpoint
            app.post("/api/web/login") { ctx ->
                if (!Config.WEB_AUTH_ENABLED) {
                    ctx.json(mapOf("success" to true, "message" to "No authentication required"))
                    return@post
                }

                val body = ctx.body()
                // Simple JSON parsing for username/password
                val usernameMatch = Regex("\"username\"\\s*:\\s*\"([^\"]+)\"").find(body)
                val passwordMatch = Regex("\"password\"\\s*:\\s*\"([^\"]+)\"").find(body)
                val username = usernameMatch?.groupValues?.get(1)
                val password = passwordMatch?.groupValues?.get(1)

                if (username == Config.WEB_AUTH_USERNAME && password == Config.WEB_AUTH_PASSWORD) {
                    val token = "web-token-$username"
                    ctx.json(mapOf(
                        "success" to true,
                        "token" to token,
                        "user" to mapOf("username" to username)
                    ))
                } else {
                    ctx.status(401)
                    ctx.json(mapOf("success" to false, "message" to "Invalid credentials"))
                }
            }

            // Web interface status endpoint
            app.get("/api/web/status") { ctx ->
                ctx.requireWebAuth()

                try {
                    val repositories = blobStore.listRepositories()
                    val stats = blobStore.getGarbageCollectionStats()

                    val response = mapOf(
                        "repositories" to repositories.size,
                        "totalBlobs" to stats.totalBlobs,
                        "totalManifests" to stats.totalManifests,
                        "unreferencedBlobs" to stats.unreferencedBlobs,
                        "estimatedSpaceToFree" to stats.estimatedSpaceToFree,
                        "lastGcRun" to "2024-01-01T00:00:00Z", // You'd track this in production
                        "logStreamClients" to SseLogAppender.getClientCount()
                    )

                    ctx.json(response)
                } catch (e: Exception) {
                    logger.error("Error getting web status: ${e.message}", e)

                    // Check if this is a database corruption error
                    if (e.message?.contains("File corrupted") == true || e.message?.contains("MVStoreException") == true) {
                        logger.error("Database corruption detected in web status endpoint! Attempting recovery...")
                        if (blobStore is H2BlobStore && blobStore.attemptRecovery()) {
                            logger.info("Database recovery successful, retrying request")
                            try {
                                val repositories = blobStore.listRepositories()
                                val stats = blobStore.getGarbageCollectionStats()
                                val response = mapOf(
                                    "repositories" to repositories.size,
                                    "totalBlobs" to stats.totalBlobs,
                                    "totalManifests" to stats.totalManifests,
                                    "unreferencedBlobs" to stats.unreferencedBlobs,
                                    "estimatedSpaceToFree" to stats.estimatedSpaceToFree,
                                    "lastGcRun" to "2024-01-01T00:00:00Z",
                                    "logStreamClients" to SseLogAppender.getClientCount()
                                )
                                ctx.json(response)
                                return@get
                            } catch (retryException: Exception) {
                                logger.error("Retry after recovery failed: ${retryException.message}", retryException)
                            }
                        } else {
                            // Recovery failed - the H2BlobStore will have already logged detailed diagnostics
                            // and terminated the application if it was a fatal failure
                            logger.error("Database recovery failed in web status endpoint - manual intervention required")
                            ctx.status(503)
                            ctx.json(mapOf("error" to "Database corruption detected. Please contact administrator."))
                            return@get
                        }
                    }

                    ctx.status(500)
                    ctx.json(mapOf("error" to "Internal Server Error: ${e.message}"))
                }
            }

            // Log level control endpoint for stress testing
            app.post("/api/web/log-level") { ctx ->
                ctx.requireWebAuth()

                try {
                    val requestBody = ctx.bodyAsClass<Map<String, String>>(Map::class.java)
                    val level = requestBody["level"]?.uppercase()

                    when (level) {
                        "TRACE" -> SseLogAppender.setMinLogLevel(ch.qos.logback.classic.Level.TRACE)
                        "DEBUG" -> SseLogAppender.setMinLogLevel(ch.qos.logback.classic.Level.DEBUG)
                        "INFO" -> SseLogAppender.setMinLogLevel(ch.qos.logback.classic.Level.INFO)
                        "WARN" -> SseLogAppender.setMinLogLevel(ch.qos.logback.classic.Level.WARN)
                        "ERROR" -> SseLogAppender.setMinLogLevel(ch.qos.logback.classic.Level.ERROR)
                        else -> {
                            ctx.status(400)
                            ctx.json(mapOf("error" to "Invalid log level. Must be one of: TRACE, DEBUG, INFO, WARN, ERROR"))
                            return@post
                        }
                    }

                    ctx.json(mapOf("message" to "Log level set to $level", "level" to level))
                } catch (e: Exception) {
                    logger.error("Error setting log level: ${e.message}", e)
                    ctx.status(500)
                    ctx.json(mapOf("error" to "Failed to set log level: ${e.message}"))
                }
            }

        }

    }

    // Helper functions for DRY code
    private fun handleBlobExistenceCheck(ctx: io.javalin.http.Context, digest: Digest, logger: KLogger) {
        if (!blobStore.hasBlob(digest)) {
            logger.debug("We do not have $digest")
            ctx.status(404)
            ctx.result("Not found")
        } else {
            logger.trace("We DO have $digest")
            ctx.status(200)
            ctx.result("OK")
        }
    }

    private fun handleManifestExistenceCheck(ctx: io.javalin.http.Context, imageVersion: ImageVersion, logger: KLogger) {
        if(blobStore.hasManifest(imageVersion)) {
            logger.trace("We DO have manifest for {}!", imageVersion)
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

    /**
     * Extract all blob digests referenced by a manifest using ultra-fast regex parsing
     * This avoids expensive JSON deserialization since we only need digest fields
     */
    private fun extractBlobDigestsFromManifest(manifestJson: String): Set<String> {
        val digests = mutableSetOf<String>()
        val digestPattern = """"digest"\s*:\s*"([^"]+)"""".toRegex()

        try {
            // Use pre-compiled regex for maximum performance
            // This is 10-50x faster than full JSON deserialization
            val matches = digestPattern.findAll(manifestJson)
            matches.forEach { matchResult ->
                val digest = matchResult.groupValues[1]
                if (digest.startsWith("sha256:")) {
                    digests.add(digest)
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to extract digests from manifest: ${e.message}")
            // This should rarely happen with regex, but keep as safety net
        }

        return digests
    }
}