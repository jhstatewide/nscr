package com.statewidesoftware.nscr

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import io.javalin.http.sse.SseClient
import java.util.concurrent.ConcurrentLinkedQueue
import mu.KotlinLogging

/**
 * Custom logback appender that broadcasts log events to SSE clients
 */
class SseLogAppender : AppenderBase<ILoggingEvent>() {
    
    companion object {
        private val logClients = ConcurrentLinkedQueue<SseClient>()
        private val logger = KotlinLogging.logger { "SseLogAppender" }
        
        fun addClient(client: SseClient) {
            logClients.add(client)
            client.onClose { 
                logClients.remove(client)
                logger.info { "SSE client disconnected. Remaining clients: ${logClients.size}" }
            }
            logger.info { "SSE client connected. Total clients: ${logClients.size}" }
        }
        
        fun removeClient(client: SseClient) {
            logClients.remove(client)
        }
        
        fun getClientCount(): Int = logClients.size
        
        fun broadcastLog(timestamp: Long, level: String, message: String, logger: String, thread: String) {
            val logEntry = mapOf(
                "timestamp" to timestamp,
                "level" to level,
                "message" to message,
                "logger" to logger,
                "thread" to thread
            )
            
            // Broadcast to all connected clients
            val iterator = logClients.iterator()
            while (iterator.hasNext()) {
                val client = iterator.next()
                try {
                    client.sendEvent("log", logEntry)
                } catch (e: Exception) {
                    // Remove disconnected clients
                    iterator.remove()
                }
            }
        }
    }
    
    override fun append(event: ILoggingEvent) {
        if (!isStarted()) return
        
        val logEntry = mapOf(
            "timestamp" to event.timeStamp,
            "level" to event.level.toString(),
            "message" to event.formattedMessage,
            "logger" to event.loggerName,
            "thread" to event.threadName
        )
        
        // Broadcast to all connected clients
        val iterator = logClients.iterator()
        while (iterator.hasNext()) {
            val client = iterator.next()
            try {
                client.sendEvent("log", logEntry)
            } catch (e: Exception) {
                // Remove disconnected clients
                iterator.remove()
            }
        }
    }
}
