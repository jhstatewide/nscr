package com.statewidesoftware.nscr

import io.javalin.http.sse.SseClient
import java.util.concurrent.ConcurrentLinkedQueue
import mu.KotlinLogging

/**
 * SSE broadcaster for repository updates
 */
class SseRepositoryBroadcaster {

    companion object {
        private val repositoryClients = ConcurrentLinkedQueue<SseClient>()
        private val logger = KotlinLogging.logger { "SseRepositoryBroadcaster" }

        fun addClient(client: SseClient) {
            repositoryClients.add(client)
            client.onClose {
                repositoryClients.remove(client)
                logger.info { "Repository SSE client disconnected. Remaining clients: ${repositoryClients.size}" }
            }
            logger.info { "Repository SSE client connected. Total clients: ${repositoryClients.size}" }
        }

        fun removeClient(client: SseClient) {
            repositoryClients.remove(client)
        }

        fun getClientCount(): Int = repositoryClients.size

        fun broadcastRepositoryUpdate(action: String, repositoryName: String, timestamp: Long = System.currentTimeMillis()) {
            if (repositoryClients.isEmpty()) return

            val update = mapOf(
                "action" to action,
                "repository" to repositoryName,
                "timestamp" to timestamp
            )

            // Broadcast to all connected clients with improved cleanup
            val iterator = repositoryClients.iterator()
            while (iterator.hasNext()) {
                val client = iterator.next()
                try {
                    client.sendEvent("repository_update", update)
                } catch (e: Exception) {
                    // Remove disconnected clients and log the cleanup
                    iterator.remove()
                    logger.info { "Removed disconnected repository SSE client. Remaining clients: ${repositoryClients.size}" }
                }
            }
        }

        fun broadcastRepositoryListUpdate() {
            if (repositoryClients.isEmpty()) return

            val update = mapOf(
                "action" to "list_updated",
                "timestamp" to System.currentTimeMillis()
            )

            // Broadcast to all connected clients with improved cleanup
            val iterator = repositoryClients.iterator()
            while (iterator.hasNext()) {
                val client = iterator.next()
                try {
                    client.sendEvent("repository_list_updated", update)
                } catch (e: Exception) {
                    // Remove disconnected clients and log the cleanup
                    iterator.remove()
                    logger.info { "Removed disconnected repository SSE client. Remaining clients: ${repositoryClients.size}" }
                }
            }
        }
    }
}
