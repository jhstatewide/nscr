package com.statewidesoftware.nscr.routes

import com.statewidesoftware.nscr.SseLogAppender
import com.statewidesoftware.nscr.SseRepositoryBroadcaster
import com.statewidesoftware.nscr.ThroughputTracker
import com.statewidesoftware.nscr.TimeRange
import com.statewidesoftware.nscr.blobstore.Blobstore
import com.statewidesoftware.nscr.blobstore.ImageVersion
import com.statewidesoftware.nscr.extractBlobDigestsFromManifest
import io.javalin.Javalin
import mu.KLogger

class AdminApiRoutes(
    private val app: Javalin,
    private val blobStore: Blobstore,
    private val logger: KLogger,
    private val startTime: Long
) {

    fun register() {
        app.get("/api/blobs") { ctx ->
            val blobList = StringBuilder()
            blobStore.eachBlobSize { digest, _ ->
                blobList.append(digest)
                blobList.append("\n")
            }
            ctx.result(blobList.toString())
        }

        // List repositories with timestamps for web interface
        app.get("/api/registry/repositories") { ctx ->
            val repositories = blobStore.listRepositoriesWithTimestamps()
            val response = mapOf("repositories" to repositories)
            ctx.json(response)
        }

        // Fix blob sizes endpoint
        app.post("/api/fix-blob-sizes") { ctx ->
            logger.info("Manual blob size repair initiated")
            try {
                val fixedCount = blobStore.fixBlobSizes()
                ctx.json(mapOf(
                    "message" to "Blob size repair completed",
                    "blobsFixed" to fixedCount
                ))
            } catch (e: Exception) {
                logger.error("Error fixing blob sizes: ${e.message}", e)
                ctx.status(500)
                ctx.json(mapOf("error" to "Failed to fix blob sizes", "message" to e.message))
            }
        }

        // Garbage collection endpoint
        app.post("/api/garbage-collect") { ctx ->
            logger.info("Manual garbage collection initiated")
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
            logger.debug("Retrieving garbage collection statistics")
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
            logger.debug("Retrieving registry state")
            try {
                val repositories = blobStore.listRepositories()
                val gcStats = blobStore.getGarbageCollectionStats()

                // Calculate storage metrics
                var totalBytes = 0L
                val uniqueBlobs = mutableSetOf<String>()
                var uniqueBytes = 0L

                blobStore.eachBlobSize { digest, size ->
                    totalBytes += size

                    if (digest != null && uniqueBlobs.add(digest)) {
                        uniqueBytes += size
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
            logger.debug("Retrieving repository details: $repoName")

            try {
                val tags = blobStore.listTags(repoName)

                // Create a single blob size map for efficient lookup (only scan blobs once)
                // Use the new streaming method to avoid loading blob content into memory
                val blobSizeMap = mutableMapOf<String, Long>()
                blobStore.eachBlobSize { digest, size ->
                    blobSizeMap[digest] = size
                }

                val tagDetails = tags.map { tag ->
                    val imageVersion = ImageVersion(repoName, tag)
                    val hasManifest = blobStore.hasManifest(imageVersion)
                    val digest = if (hasManifest) blobStore.digestForManifest(imageVersion).digestString else null

                    // Calculate image size by parsing manifest and summing blob sizes
                    val imageSize = if (hasManifest) {
                        try {
                            val manifestJson = blobStore.getManifest(imageVersion)
                            val blobDigests = extractBlobDigestsFromManifest(manifestJson)

                            // Sum up the sizes of all referenced blobs using the pre-built map
                            blobDigests.sumOf { digestStr ->
                                blobSizeMap[digestStr] ?: 0L
                            }
                        } catch (e: Exception) {
                            logger.warn("Failed to calculate size for $repoName:$tag: ${e.message}")
                            0L
                        }
                    } else {
                        0L
                    }

                    mapOf(
                        "tag" to tag,
                        "hasManifest" to hasManifest,
                        "digest" to digest,
                        "sizeBytes" to imageSize
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
            logger.debug("Retrieving blob inventory")

            try {
                val blobList = mutableListOf<Map<String, Any>>()
                var totalBytes = 0L
                val uniqueBlobs = mutableSetOf<String>()
                var uniqueBytes = 0L

                blobStore.eachBlobMetadata { sessionID, digest, blobNumber, size ->
                    totalBytes += size

                    // Track unique blobs by digest
                    if (digest != "unknown" && uniqueBlobs.add(digest)) {
                        uniqueBytes += size
                    }

                    blobList.add(mapOf(
                        "digest" to digest,
                        "sessionID" to sessionID,
                        "blobNumber" to (blobNumber ?: "null"),
                        "size" to size
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
            logger.debug("Calculating storage statistics")

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
                blobStore.eachBlobSize { digest, size ->
                    blobSizes[digest] = size
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
            logger.debug("Retrieving active sessions")

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
            logger.debug("Running health check")

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

        // Throughput monitoring endpoints
        app.get("/api/throughput/history/minutes") { ctx ->
            val minutes = ctx.queryParam("count")?.toIntOrNull() ?: 60
            val stats = ThroughputTracker.getInstance().getMinuteStats(minutes)
            ctx.json(mapOf(
                "timeRange" to "minute",
                "dataPoints" to stats
            ))
        }

        app.get("/api/throughput/history/hours") { ctx ->
            val hours = ctx.queryParam("count")?.toIntOrNull() ?: 24
            val stats = ThroughputTracker.getInstance().getHourStats(hours)
            ctx.json(mapOf(
                "timeRange" to "hour",
                "dataPoints" to stats
            ))
        }

        app.get("/api/throughput/history/days") { ctx ->
            val days = ctx.queryParam("count")?.toIntOrNull() ?: 7
            val stats = ThroughputTracker.getInstance().getDayStats(days)
            ctx.json(mapOf(
                "timeRange" to "day",
                "dataPoints" to stats
            ))
        }

        app.get("/api/throughput/peak") { ctx ->
            val range = ctx.queryParam("range") ?: "hour"
            val timeRange = when (range.lowercase()) {
                "minute" -> TimeRange.MINUTE
                "hour" -> TimeRange.HOUR
                "day" -> TimeRange.DAY
                else -> TimeRange.HOUR
            }
            val peak = ThroughputTracker.getInstance().getPeakThroughput(timeRange)
            ctx.json(peak)
        }

        app.get("/api/throughput/current") { ctx ->
            val current = ThroughputTracker.getInstance().getCurrentThroughput()
            ctx.json(current)
        }
    }
}
