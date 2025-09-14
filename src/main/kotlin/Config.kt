package nscr

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Configuration object for NSCR that reads from environment variables
 * with sensible defaults for development.
 */
object Config {
    // Database configuration
    val DATABASE_PATH: Path = Paths.get(System.getenv("NSCR_DATABASE_PATH") ?: "./data/")
    val DATABASE_USER: String = System.getenv("NSCR_DB_USER") ?: "sa"
    val DATABASE_PASSWORD: String = System.getenv("NSCR_DB_PASSWORD") ?: "sa"
    val DATABASE_MAX_CONNECTIONS: Int = System.getenv("NSCR_DB_MAX_CONNECTIONS")?.toIntOrNull() ?: 10
    val DATABASE_MIN_CONNECTIONS: Int = System.getenv("NSCR_DB_MIN_CONNECTIONS")?.toIntOrNull() ?: 2
    
    // Server configuration
    val SERVER_PORT: Int = System.getenv("NSCR_PORT")?.toIntOrNull() ?: 7000
    val SERVER_HOST: String = System.getenv("NSCR_HOST") ?: "0.0.0.0"
    
    // Logging configuration
    val LOG_LEVEL: String = System.getenv("NSCR_LOG_LEVEL") ?: "INFO"
    
    // Registry configuration
    val REGISTRY_URL: String = System.getenv("NSCR_REGISTRY_URL") ?: "http://localhost:${SERVER_PORT}"
    
    // Authentication configuration (for future use)
    val JWT_SECRET: String = System.getenv("NSCR_JWT_SECRET") ?: "default-secret-key-change-in-production"
    val TOKEN_EXPIRY: Int = System.getenv("NSCR_TOKEN_EXPIRY")?.toIntOrNull() ?: 3600
    
    // Garbage collection configuration
    val GC_ENABLED: Boolean = System.getenv("NSCR_GC_ENABLED")?.toBoolean() ?: true
    val GC_INTERVAL_HOURS: Int = System.getenv("NSCR_GC_INTERVAL_HOURS")?.toIntOrNull() ?: 24
    
    // Multi-part upload configuration
    val MAX_UPLOAD_SIZE_MB: Long = System.getenv("NSCR_MAX_UPLOAD_SIZE_MB")?.toLongOrNull() ?: 1024L // 1GB default
    val CHUNK_SIZE_MB: Int = System.getenv("NSCR_CHUNK_SIZE_MB")?.toIntOrNull() ?: 10 // 10MB chunks
    
    /**
     * Print current configuration (excluding sensitive values)
     */
    fun printConfig() {
        println("=== NSCR Configuration ===")
        println("Database Path: $DATABASE_PATH")
        println("Database User: $DATABASE_USER")
        println("Database Max Connections: $DATABASE_MAX_CONNECTIONS")
        println("Database Min Connections: $DATABASE_MIN_CONNECTIONS")
        println("Server Port: $SERVER_PORT")
        println("Server Host: $SERVER_HOST")
        println("Log Level: $LOG_LEVEL")
        println("Registry URL: $REGISTRY_URL")
        println("Garbage Collection Enabled: $GC_ENABLED")
        println("GC Interval (hours): $GC_INTERVAL_HOURS")
        println("Max Upload Size (MB): $MAX_UPLOAD_SIZE_MB")
        println("Chunk Size (MB): $CHUNK_SIZE_MB")
        println("=========================")
    }
    
    /**
     * Validate configuration values
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        
        if (SERVER_PORT < 1 || SERVER_PORT > 65535) {
            errors.add("Invalid server port: $SERVER_PORT (must be 1-65535)")
        }
        
        if (DATABASE_MAX_CONNECTIONS < 1) {
            errors.add("Invalid max connections: $DATABASE_MAX_CONNECTIONS (must be >= 1)")
        }
        
        if (DATABASE_MIN_CONNECTIONS < 1) {
            errors.add("Invalid min connections: $DATABASE_MIN_CONNECTIONS (must be >= 1)")
        }
        
        if (DATABASE_MIN_CONNECTIONS > DATABASE_MAX_CONNECTIONS) {
            errors.add("Min connections ($DATABASE_MIN_CONNECTIONS) cannot be greater than max connections ($DATABASE_MAX_CONNECTIONS)")
        }
        
        if (TOKEN_EXPIRY < 60) {
            errors.add("Token expiry too short: $TOKEN_EXPIRY seconds (minimum 60)")
        }
        
        if (MAX_UPLOAD_SIZE_MB < 1) {
            errors.add("Invalid max upload size: $MAX_UPLOAD_SIZE_MB MB (must be >= 1)")
        }
        
        if (CHUNK_SIZE_MB < 1) {
            errors.add("Invalid chunk size: $CHUNK_SIZE_MB MB (must be >= 1)")
        }
        
        if (GC_INTERVAL_HOURS < 1) {
            errors.add("Invalid GC interval: $GC_INTERVAL_HOURS hours (must be >= 1)")
        }
        
        return errors
    }
}
