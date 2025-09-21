# Multi-stage build for NSCR (New and Shiny Container Registry)
FROM ubuntu:22.04 as builder

# Install Java 17 (matching project requirements)
RUN apt-get update && apt-get install -yq \
    openjdk-17-jdk \
    && rm -rf /var/lib/apt/lists/*

# Create app user
RUN adduser --disabled-password --gecos "" app

# Copy Gradle files first (for better caching)
COPY --chown=app gradle/ /home/app/gradle/
COPY --chown=app build.gradle.kts gradle.properties gradlew settings.gradle.kts /home/app/

# Copy source code
COPY --chown=app src/ /home/app/src/

# Build the application
WORKDIR /home/app
USER app
RUN ./gradlew build --no-daemon

# Runtime stage
FROM ubuntu:22.04

# Install Java 17 runtime and curl for health checks
RUN apt-get update && apt-get install -yq \
    openjdk-17-jre-headless \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Create app user
RUN adduser --disabled-password --gecos "" app

# Copy built application from builder stage
COPY --from=builder --chown=app /home/app/build/libs/ /home/app/libs/

# Set working directory and user
WORKDIR /home/app
USER app

# Expose the default registry port
EXPOSE 7000

# Set environment variables
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV PORT=7000

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:7000/v2/ || exit 1

# Run the application (Shadow plugin creates a fat JAR)
CMD ["java", "-jar", "/home/app/libs/nscr-1.0-SNAPSHOT-all.jar"]