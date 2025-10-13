package com.statewidesoftware.nscr

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import io.javalin.http.sse.SseClient
import java.util.concurrent.ConcurrentLinkedQueue
import mu.KotlinLogging
import kotlin.synchronized

/**
 * Custom logback appender that broadcasts log events to SSE clients
 */
class SseLogAppender : AppenderBase<ILoggingEvent>() {
    
    companion object {
        private val logClients = ConcurrentLinkedQueue<SseClient>()
        private val logger = KotlinLogging.logger("SseLogAppender")

        // Preallocate "log" so we don't have to allocate it each time
        private val LOG_TYPE : String = "log"
        
        // Minimum log level to broadcast (INFO and above by default)
        private var minLogLevel = ch.qos.logback.classic.Level.INFO
        
        fun addClient(client: SseClient) = synchronized(this) {
            logClients.add(client)
            client.onClose { 
                removeClient(client)
            }
            logger.info { "SSE client connected. Total clients: ${logClients.size}" }
        }
        
        fun removeClient(client: SseClient) = synchronized(this) {
            logClients.remove(client)
            logger.info { "SSE client disconnected. Remaining clients: ${logClients.size}" }
        }
        
        fun getClientCount(): Int = synchronized(this) { logClients.size }
        
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
            
            // Create a snapshot of clients to avoid concurrent modification issues
            val clientsSnapshot = synchronized(this) { logClients.toList() }
            
            // Broadcast to all clients and collect failed ones for removal
            val failedClients = clientsSnapshot.filter { client ->
                try {
                    client.sendEvent(LOG_TYPE, logEntry)
                    false // Success, don't remove
                } catch (e: Exception) {
                    true // Failed, mark for removal
                }
            }
            
            // Remove failed clients if any
            if (failedClients.isNotEmpty()) {
                synchronized(this) {
                    failedClients.forEach { logClients.remove(it) }
                    SseLogAppender.logger.info { "Removed ${failedClients.size} disconnected SSE clients during broadcast. Remaining clients: ${logClients.size}" }
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
            
            // Create a snapshot of clients to avoid concurrent modification issues
            val clientsSnapshot = synchronized(this) { logClients.toList() }
            
            // Broadcast to all clients and collect failed ones for removal
            val failedClients = clientsSnapshot.filter { client ->
                try {
                    client.sendEvent(LOG_TYPE, logEntry)
                    false // Success, don't remove
                } catch (e: Exception) {
                    true // Failed, mark for removal
                }
            }
            
            // Remove failed clients if any
            if (failedClients.isNotEmpty()) {
                synchronized(this) {
                    failedClients.forEach { logClients.remove(it) }
                    SseLogAppender.logger.info { "Removed ${failedClients.size} disconnected SSE clients during log append. Remaining clients: ${logClients.size}" }
                }
            }
        }
    }
}
