package com.statewidesoftware.nscr

import com.statewidesoftware.nscr.auth.requireAuth
import com.statewidesoftware.nscr.auth.requireWebAuth
import com.statewidesoftware.nscr.blobstore.Blobstore
import com.statewidesoftware.nscr.blobstore.H2BlobStore
import com.statewidesoftware.nscr.routes.AdminApiRoutes
import com.statewidesoftware.nscr.routes.DockerRegistryRoutes
import com.statewidesoftware.nscr.routes.SseRoutes
import com.statewidesoftware.nscr.routes.WebInterfaceRoutes
import io.javalin.Javalin
import io.javalin.http.staticfiles.Location
import mu.KLogger
import mu.KotlinLogging
import com.statewidesoftware.nscr.ThroughputTracker
import com.statewidesoftware.nscr.SseThroughputBroadcaster
import com.statewidesoftware.nscr.SseLogAppender
import com.statewidesoftware.nscr.SseRepositoryBroadcaster

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

            // Clean up throughput tracking
            ThroughputTracker.getInstance().shutdown()
            SseThroughputBroadcaster.shutdown()

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

        // Register route handlers
        DockerRegistryRoutes(app, blobStore, sessionTracker, logger).register()
        AdminApiRoutes(app, blobStore, logger, startTime).register()
        WebInterfaceRoutes(app, blobStore, logger, startTime).register()
        SseRoutes(app, blobStore, logger).register()
    }
}