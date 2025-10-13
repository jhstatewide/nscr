package com.statewidesoftware.nscr.auth

import com.statewidesoftware.nscr.Config
import io.javalin.http.Context

/**
 * Web interface authentication helper function
 * This provides authentication specifically for the web interface
 */
fun Context.requireWebAuth() {
    if (!Config.WEB_INTERFACE_ENABLED || !Config.WEB_AUTH_ENABLED) {
        return // No web auth required
    }

    val authHeader = this.header("Authorization")
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
        this.status(401)
        this.json(mapOf("error" to "Web interface authentication required"))
        return
    }

    // For now, we'll use a simple token validation
    // In production, you might want to use JWT or session management
    val token = authHeader.substring(7)
    // Simple validation - in production, implement proper token validation
    if (token != "web-token-${Config.WEB_AUTH_USERNAME}") {
        this.status(401)
        this.json(mapOf("error" to "Invalid web interface token"))
        return
    }
}

/**
 * HTTP Basic Authentication helper function
 * This provides a clean, reusable way to handle authentication across endpoints
 */
fun Context.requireAuth() {
    if (!Config.AUTH_ENABLED) {
        return // No auth required
    }

    val authHeader = this.header("Authorization")
    if (authHeader == null || !authHeader.startsWith("Basic ")) {
        this.header("WWW-Authenticate", "Basic realm=\"Docker Registry\"")
        this.status(401)
        this.result("Authentication required")
        return
    }

    try {
        val encoded = authHeader.substring(6) // Remove "Basic " prefix
        val decoded = String(java.util.Base64.getDecoder().decode(encoded))
        val parts = decoded.split(":", limit = 2)

        if (parts.size != 2) {
            this.header("WWW-Authenticate", "Basic realm=\"Docker Registry\"")
            this.status(401)
            this.result("Invalid authentication format")
            return
        }

        val username = parts[0]
        val password = parts[1]

        if (username != Config.AUTH_USERNAME || password != Config.AUTH_PASSWORD) {
            this.header("WWW-Authenticate", "Basic realm=\"Docker Registry\"")
            this.status(401)
            this.result("Invalid credentials")
            return
        }

        // Authentication successful, continue
    } catch (e: Exception) {
        this.header("WWW-Authenticate", "Basic realm=\"Docker Registry\"")
        this.status(401)
        this.result("Authentication error")
        return
    }
}
