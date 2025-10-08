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
        
        // Minimum log level to broadcast (INFO and above by default)
        private var minLogLevel = ch.qos.logback.classic.Level.INFO
        
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
        
        fun setMinLogLevel(level: ch.qos.logback.classic.Level) {
            minLogLevel = level
            logger.info { "SSE log level set to: $level" }
        }
        
        fun broadcastLog(timestamp: Long, level: String, message: String, logger: String, thread: String) {
            val logEntry = mapOf(
                "timestamp" to timestamp,
                "level" to level,
                "message" to message,
                "logger" to logger,
                "thread" to thread
            )
            
            // Broadcast to all connected clients with improved cleanup
            val iterator = logClients.iterator()
            while (iterator.hasNext()) {
                val client = iterator.next()
                try {
                    client.sendEvent("log", logEntry)
                } catch (e: Exception) {
                    // Remove disconnected clients and log the cleanup
                    iterator.remove()
                    SseLogAppender.logger.info { "Removed disconnected SSE client during broadcast. Remaining clients: ${logClients.size}" }
                }
            }
        }
    }
    
    override fun append(event: ILoggingEvent) {
        if (!isStarted()) return
        
        // Filter out low-level logs to reduce spam during stress testing
        if (event.level.isGreaterOrEqual(minLogLevel)) {
            val logEntry = mapOf(
                "timestamp" to event.timeStamp,
                "level" to event.level.toString(),
                "message" to event.formattedMessage,
                "logger" to event.loggerName,
                "thread" to event.threadName
            )
            
            // Broadcast to all connected clients with improved cleanup
            val iterator = logClients.iterator()
            while (iterator.hasNext()) {
                val client = iterator.next()
                try {
                    client.sendEvent("log", logEntry)
                } catch (e: Exception) {
                    // Remove disconnected clients and log the cleanup
                    iterator.remove()
                    SseLogAppender.logger.info { "Removed disconnected SSE client during log append. Remaining clients: ${logClients.size}" }
                }
            }
        }
    }
}
