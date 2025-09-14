# Multi-stage build for NSCR (New and Shiny Container Registry)
FROM gradle:8.5-jdk17 AS builder

# Set working directory
WORKDIR /app

# Copy gradle files first for better caching
COPY build.gradle.kts gradle.properties settings.gradle.kts ./
COPY gradle/ gradle/

# Download dependencies
RUN gradle dependencies --no-daemon

# Copy source code
COPY src/ src/

# Build the application (skip tests since Docker isn't available in build container)
RUN gradle shadowJar --no-daemon -x test

# Runtime stage
FROM eclipse-temurin:17-jre

# Install curl for health checks
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

# Create app user for security
RUN groupadd -r nscr && useradd -r -g nscr nscr

# Set working directory
WORKDIR /app

# Copy the built shadow jar from builder stage
COPY --from=builder /app/build/libs/nscr-1.0-SNAPSHOT-all.jar app.jar

# Create data directory for persistent storage
RUN mkdir -p /app/data && chown -R nscr:nscr /app

# Switch to non-root user
USER nscr

# Expose the registry port
EXPOSE 7000

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
  CMD curl -f http://localhost:7000/v2 || exit 1

# Run the application
CMD ["java", "-jar", "app.jar"]
