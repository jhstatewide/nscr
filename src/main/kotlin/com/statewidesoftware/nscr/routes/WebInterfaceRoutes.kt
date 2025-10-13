package com.statewidesoftware.nscr.routes

import com.statewidesoftware.nscr.Config
import com.statewidesoftware.nscr.SseLogAppender
import com.statewidesoftware.nscr.auth.requireWebAuth
import com.statewidesoftware.nscr.blobstore.Blobstore
import com.statewidesoftware.nscr.blobstore.H2BlobStore
import io.javalin.Javalin
import mu.KLogger

class WebInterfaceRoutes(
    private val app: Javalin,
    private val blobStore: Blobstore,
    private val logger: KLogger,
    private val startTime: Long
) {

    fun register() {
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
}
