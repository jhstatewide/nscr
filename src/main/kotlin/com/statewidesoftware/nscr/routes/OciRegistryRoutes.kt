package com.statewidesoftware.nscr.routes

import com.statewidesoftware.nscr.Config
import com.statewidesoftware.nscr.SessionID
import com.statewidesoftware.nscr.SessionTracker
import com.statewidesoftware.nscr.SseRepositoryBroadcaster
import com.statewidesoftware.nscr.blobstore.Blobstore
import com.statewidesoftware.nscr.blobstore.Digest
import com.statewidesoftware.nscr.blobstore.ImageVersion
import com.statewidesoftware.nscr.extractBlobDigestsFromManifest
import com.statewidesoftware.nscr.extractManifestMediaType
import com.statewidesoftware.nscr.generateSHA256
import io.javalin.Javalin
import io.javalin.http.Context
import mu.KLogger

class OciRegistryRoutes(
    private val app: Javalin,
    private val blobStore: Blobstore,
    private val sessionTracker: SessionTracker,
    private val logger: KLogger
) {

    fun register() {
        app.get("/v2") { ctx ->
            logger.info { "Registry API v2 base endpoint accessed" }
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
            logger.debug("Starting blob upload for image: $image")
            // see if we have the query param 'digest',
            // as in /v2/test/blobs/uploads?digest=sha256:1234
            val digest = ctx.queryParam("digest")
            if (digest != null) {
                logger.debug("Digest provided: $digest")
                if (blobStore.hasBlob(Digest(digest))) {
                    logger.info("Blob already exists, skipping upload")
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
                logger.debug("Digest $digest provided for session $sessionID - will be associated when blob data is uploaded")
            }

            logger.debug("Upload session created: $newLocation")
            ctx.header("Location", newLocation)
            ctx.header("Docker-Upload-UUID", sessionID.id)
            ctx.status(202)
            ctx.result("OK")
        }

        // List tags for a repository (supports slashes in name using <> syntax)
        app.get("/v2/<name>/tags/list") { ctx ->
            val name = ctx.pathParam("name")
            logger.debug("Listing tags for repository: $name")
            val tags = blobStore.listTags(name)
            logger.debug("Found ${tags.size} tags for $name: $tags")
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
            logger.debug("Uploading blob chunk $blobNumber for session $sessionID (range: $contentRange, length: $contentLength)")

            try {
                val uploadedBytes = blobStore.addBlob(sessionID, blobNumber, ctx.bodyInputStream())
                logger.debug("Successfully uploaded $uploadedBytes bytes for session $sessionID, blob $blobNumber")

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
            logger.debug("Finalizing blob upload for digest: $digest")

            try {
                // 201 Created
                blobStore.associateBlobWithSession(sessionID, digest)
                logger.debug("Successfully finalized blob upload for session $sessionID, digest $digest")
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
            logger.debug("Processing manifest upload for $name:$reference")

            val contentType = ctx.header("Content-Type")
            val supportedManifestTypes = listOf(
                "application/vnd.docker.distribution.manifest.v2+json",
                "application/vnd.oci.image.manifest.v1+json"
            )
            if (contentType !in supportedManifestTypes) {
                error("Mime type blooper! You must upload manifest of type: ${supportedManifestTypes.joinToString(" or ")} instead of $contentType!")
            }
            val body = ctx.body()
            logger.debug("Manifest content: $body")

            // Extract and log blob digests referenced by this manifest
            try {
                val blobDigests = extractBlobDigestsFromManifest(body)
                logger.debug("Manifest $name:$reference references ${blobDigests.size} blobs: ${blobDigests.joinToString(", ")}")

                // Verify that all referenced blobs exist
                val missingBlobs = blobDigests.filter { !blobStore.hasBlob(Digest(it)) }
                if (missingBlobs.isNotEmpty()) {
                    logger.warn("Manifest $name:$reference references missing blobs: ${missingBlobs.joinToString(", ")}")
                } else {
                    logger.debug("All ${blobDigests.size} blobs referenced by manifest $name:$reference are present")
                }
            } catch (e: Exception) {
                logger.warn("Failed to extract blob digests from manifest $name:$reference: ${e.message}")
            }

            // get digest for this manifest...
            val sha = generateSHA256(body)
            val digestString = "sha256:$sha"
            logger.debug("Storing manifest $name:$reference with digest $digestString")
            blobStore.addManifest(ImageVersion(name, reference), Digest(sha), body)

            // Broadcast repository update
            SseRepositoryBroadcaster.broadcastRepositoryUpdate("manifest_added", name)

            logger.info("Successfully pushed image: $name:$reference")
            ctx.status(201)
            ctx.header("Location", Config.REGISTRY_URL)
            ctx.header("Docker-Content-Digest", digestString)
            ctx.header("Content-Length", "0")
            ctx.result("Created")
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

        // OCI Registry API v2 endpoints for deletion and management
        app.delete("/v2/*/manifests/{reference}") { ctx ->
            val fullPath = ctx.path()
            val name = fullPath.substringAfter("/v2/").substringBefore("/manifests")
            val reference = ctx.pathParam("reference")
            val imageVersion = ImageVersion(name, reference)
            logger.debug("Deleting manifest for $imageVersion")

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

        // Delete entire repository (all manifests/tags)
        app.delete("/v2/<name>") { ctx ->
            val name = ctx.pathParam("name")
            logger.info("Deleting repository: $name")

            try {
                val deletedCount = blobStore.deleteRepository(name)

                if (deletedCount > 0) {
                    // Broadcast repository update
                    SseRepositoryBroadcaster.broadcastRepositoryUpdate("repository_deleted", name)

                    logger.info("Successfully deleted repository: $name ($deletedCount manifests removed)")
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
    }

    // Helper functions for DRY code
    private fun handleBlobExistenceCheck(ctx: Context, digest: Digest, logger: KLogger) {
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

    private fun handleManifestExistenceCheck(ctx: Context, imageVersion: ImageVersion, logger: KLogger) {
        if(blobStore.hasManifest(imageVersion)) {
            logger.trace("We DO have manifest for {}!", imageVersion)
            ctx.status(200)
        } else {
            logger.debug("We DO NOT have manifest for {}!", imageVersion)
            ctx.status(404)
            ctx.result("Not found")
        }
    }
}
