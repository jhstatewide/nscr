package com.statewidesoftware.nscr.routes

import com.statewidesoftware.nscr.SseLogAppender
import com.statewidesoftware.nscr.SseRepositoryBroadcaster
import com.statewidesoftware.nscr.SseThroughputBroadcaster
import com.statewidesoftware.nscr.blobstore.Blobstore
import io.javalin.Javalin
import mu.KLogger

class SseRoutes(
    private val app: Javalin,
    private val blobStore: Blobstore,
    private val logger: KLogger
) {

    fun register() {
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

        // SSE endpoint for repository updates
        app.sse("/api/repositories/stream") { client ->
            try {
                SseRepositoryBroadcaster.addClient(client)
                client.sendEvent("connected", "Repository stream started")

                // Keep the connection alive
                try {
                    Thread.sleep(Long.MAX_VALUE) // Sleep indefinitely
                } catch (e: InterruptedException) {
                    // Connection closed
                }
            } catch (e: Exception) {
                logger.error { "Error in repository SSE connection: ${e.message}" }
            }
        }

        // SSE endpoint for throughput updates
        app.sse("/api/throughput/stream") { client ->
            try {
                SseThroughputBroadcaster.addClient(client)
                client.sendEvent("connected", "Throughput stream started")

                // Keep the connection alive
                try {
                    Thread.sleep(Long.MAX_VALUE) // Sleep indefinitely
                } catch (e: InterruptedException) {
                    // Connection closed
                }
            } catch (e: Exception) {
                logger.error { "Error in throughput SSE connection: ${e.message}" }
            }
        }
    }
}
