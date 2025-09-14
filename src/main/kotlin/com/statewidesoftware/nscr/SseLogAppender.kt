package com.statewidesoftware.nscr

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import io.javalin.http.sse.SseClient
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Custom logback appender that broadcasts log events to SSE clients
 */
class SseLogAppender : AppenderBase<ILoggingEvent>() {
    
    companion object {
        private val logClients = ConcurrentLinkedQueue<SseClient>()
        
        fun addClient(client: SseClient) {
            logClients.add(client)
            client.onClose { 
                logClients.remove(client)
                println("SSE client disconnected. Remaining clients: ${logClients.size}")
            }
            println("SSE client connected. Total clients: ${logClients.size}")
        }
        
        fun removeClient(client: SseClient) {
            logClients.remove(client)
        }
        
        fun getClientCount(): Int = logClients.size
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
