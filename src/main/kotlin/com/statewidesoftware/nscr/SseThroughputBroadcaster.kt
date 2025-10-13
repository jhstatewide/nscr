package com.statewidesoftware.nscr

import io.javalin.http.sse.SseClient
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import mu.KotlinLogging

/**
 * SSE broadcaster for throughput data
 */
class SseThroughputBroadcaster {

    companion object {
        private val throughputClients = ConcurrentLinkedQueue<SseClient>()
        private val logger = KotlinLogging.logger("SseThroughputBroadcaster")
        private val tracker = ThroughputTracker.getInstance()
        
        // Background scheduler for regular broadcasts
        private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
        private var isStarted = false
        private var lastHeartbeat = 0L
        private var lastActivityTime = 0L
        private var hasSentZeroUpdate = false

        fun addClient(client: SseClient) {
            throughputClients.add(client)
            client.onClose {
                throughputClients.remove(client)
                logger.info { "Throughput SSE client disconnected. Remaining clients: ${throughputClients.size}" }
            }
            logger.info { "Throughput SSE client connected. Total clients: ${throughputClients.size}" }
            
            // Start broadcasting if this is the first client
            if (!isStarted) {
                startBroadcasting()
            }
        }

        fun removeClient(client: SseClient) {
            throughputClients.remove(client)
            
            // Stop broadcasting if no clients remain
            if (throughputClients.isEmpty() && isStarted) {
                stopBroadcasting()
            }
        }

        fun getClientCount(): Int = throughputClients.size
        
        fun recordActivity() {
            lastActivityTime = System.currentTimeMillis()
        }

        private fun startBroadcasting() {
            if (isStarted) return
            
            isStarted = true
            logger.info { "Starting throughput broadcasting" }
            
            // Broadcast every 500ms
            scheduler.scheduleAtFixedRate({
                try {
                    broadcastThroughputUpdate()
                } catch (e: Exception) {
                    logger.error("Error broadcasting throughput update: ${e.message}", e)
                }
            }, 0, 500, TimeUnit.MILLISECONDS)
        }
        
        private fun stopBroadcasting() {
            if (!isStarted) return
            
            isStarted = false
            logger.info { "Stopping throughput broadcasting" }
            scheduler.shutdown()
        }

        private fun broadcastThroughputUpdate() {
            if (throughputClients.isEmpty()) return

            val now = System.currentTimeMillis()
            val timeSinceLastActivity = now - lastActivityTime
            
            // If no activity in the last 5 seconds, consider it idle
            if (timeSinceLastActivity > 5000) {
                // Send zero update once when transitioning to idle
                if (!hasSentZeroUpdate) {
                    sendZeroUpdate()
                    hasSentZeroUpdate = true
                }
                
                // Only send heartbeat every 10 seconds when idle
                if (now - lastHeartbeat > 10000) {
                    sendHeartbeat()
                    lastHeartbeat = now
                }
                return
            }
            
            // Reset zero update flag when we have activity
            hasSentZeroUpdate = false
            
            // We have recent activity, broadcast throughput data
            val snapshot = tracker.getCurrentThroughput()
            
            // Convert to web-friendly format
            val webData = mapOf(
                "timestamp" to snapshot.timestamp,
                "categories" to mapOf(
                    "blobUpload" to mapOf(
                        "current" to snapshot.categories[ThroughputCategory.BLOB_UPLOAD]!!.current,
                        "average" to snapshot.categories[ThroughputCategory.BLOB_UPLOAD]!!.average,
                        "totalBytes" to snapshot.categories[ThroughputCategory.BLOB_UPLOAD]!!.totalBytes
                    ),
                    "blobDownload" to mapOf(
                        "current" to snapshot.categories[ThroughputCategory.BLOB_DOWNLOAD]!!.current,
                        "average" to snapshot.categories[ThroughputCategory.BLOB_DOWNLOAD]!!.average,
                        "totalBytes" to snapshot.categories[ThroughputCategory.BLOB_DOWNLOAD]!!.totalBytes
                    ),
                    "manifestUpload" to mapOf(
                        "current" to snapshot.categories[ThroughputCategory.MANIFEST_UPLOAD]!!.current,
                        "average" to snapshot.categories[ThroughputCategory.MANIFEST_UPLOAD]!!.average,
                        "totalBytes" to snapshot.categories[ThroughputCategory.MANIFEST_UPLOAD]!!.totalBytes
                    ),
                    "manifestDownload" to mapOf(
                        "current" to snapshot.categories[ThroughputCategory.MANIFEST_DOWNLOAD]!!.current,
                        "average" to snapshot.categories[ThroughputCategory.MANIFEST_DOWNLOAD]!!.average,
                        "totalBytes" to snapshot.categories[ThroughputCategory.MANIFEST_DOWNLOAD]!!.totalBytes
                    )
                ),
                "overall" to mapOf(
                    "read" to mapOf(
                        "current" to snapshot.overall.read.current,
                        "average" to snapshot.overall.read.average,
                        "totalBytes" to snapshot.overall.read.totalBytes
                    ),
                    "write" to mapOf(
                        "current" to snapshot.overall.write.current,
                        "average" to snapshot.overall.write.average,
                        "totalBytes" to snapshot.overall.write.totalBytes
                    ),
                    "total" to mapOf(
                        "current" to snapshot.overall.total.current,
                        "average" to snapshot.overall.total.average,
                        "totalBytes" to snapshot.overall.total.totalBytes
                    )
                )
            )

            // Broadcast to all connected clients with improved cleanup
            val iterator = throughputClients.iterator()
            while (iterator.hasNext()) {
                val client = iterator.next()
                try {
                    client.sendEvent("throughput_update", webData)
                } catch (e: Exception) {
                    // Remove disconnected clients and log the cleanup
                    iterator.remove()
                    logger.info { "Removed disconnected throughput SSE client. Remaining clients: ${throughputClients.size}" }
                }
            }
        }
        
        fun shutdown() {
            stopBroadcasting()
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow()
                }
            } catch (e: InterruptedException) {
                scheduler.shutdownNow()
            }
        }
        
        private fun sendZeroUpdate() {
            if (throughputClients.isEmpty()) return
            
            val zeroData = mapOf(
                "timestamp" to System.currentTimeMillis(),
                "categories" to mapOf(
                    "blobUpload" to mapOf("current" to 0, "average" to 0, "totalBytes" to 0),
                    "blobDownload" to mapOf("current" to 0, "average" to 0, "totalBytes" to 0),
                    "manifestUpload" to mapOf("current" to 0, "average" to 0, "totalBytes" to 0),
                    "manifestDownload" to mapOf("current" to 0, "average" to 0, "totalBytes" to 0)
                ),
                "overall" to mapOf(
                    "read" to mapOf("current" to 0, "average" to 0, "totalBytes" to 0),
                    "write" to mapOf("current" to 0, "average" to 0, "totalBytes" to 0),
                    "total" to mapOf("current" to 0, "average" to 0, "totalBytes" to 0)
                )
            )
            
            val iterator = throughputClients.iterator()
            while (iterator.hasNext()) {
                val client = iterator.next()
                try {
                    client.sendEvent("throughput_update", zeroData)
                } catch (e: Exception) {
                    iterator.remove()
                    logger.info { "Removed disconnected client during zero update. Remaining clients: ${throughputClients.size}" }
                }
            }
        }
        
        private fun sendHeartbeat() {
            if (throughputClients.isEmpty()) return
            
            val heartbeatData = mapOf(
                "timestamp" to System.currentTimeMillis(),
                "idle" to true,
                "message" to "Registry is idle - no throughput activity",
                "type" to "heartbeat"
            )
            
            val iterator = throughputClients.iterator()
            while (iterator.hasNext()) {
                val client = iterator.next()
                try {
                    client.sendEvent("heartbeat", heartbeatData)
                } catch (e: Exception) {
                    iterator.remove()
                    logger.info { "Removed disconnected client during heartbeat. Remaining clients: ${throughputClients.size}" }
                }
            }
        }
    }
}
