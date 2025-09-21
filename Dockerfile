# Multi-stage build for NSCR (New and Shiny Container Registry)
FROM ubuntu:22.04 as builder

# Update package lists with cache mount
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt/lists \
    apt-get update

# Install curl first with cache mount
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt/lists \
    apt-get install -yq curl

# Add Node.js repository
RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash -

# Install Java 17 and Node.js with cache mount
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt/lists \
    apt-get update && apt-get install -yq \
    openjdk-17-jdk \
    nodejs

# Create app user
RUN adduser --disabled-password --gecos "" app

# Copy Gradle wrapper and configuration files first (rarely change - cached layer)
COPY --chown=app gradle/ /home/app/gradle/
COPY --chown=app gradlew /home/app/
COPY --chown=app gradle.properties settings.gradle.kts /home/app/

# Copy build configuration (changes less frequently - cached layer)
COPY --chown=app build.gradle.kts detekt.yml /home/app/

# Copy frontend dependencies (changes less frequently than source - cached layer)
COPY --chown=app frontend/package*.json /home/app/frontend/

# Copy source code (changes most frequently - invalidates cache)
COPY --chown=app src/ /home/app/src/
COPY --chown=app frontend/src/ /home/app/frontend/src/
COPY --chown=app frontend/tsconfig.json frontend/esbuild.config.js /home/app/frontend/

# Set working directory
WORKDIR /home/app

# Create Gradle directories as root first
RUN mkdir -p /home/app/.gradle/wrapper/dists && \
    chown -R app:app /home/app/.gradle

# Switch to app user
USER app

# Install frontend dependencies with npm cache mount
RUN --mount=type=cache,target=/root/.npm \
    cd frontend && npm install

# Build the application (skip tests for Docker image)
RUN ./gradlew build -x test --no-daemon

# Runtime stage
FROM ubuntu:22.04

# Update package lists with cache mount
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt/lists \
    apt-get update

# Install curl first with cache mount
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt/lists \
    apt-get install -yq curl

# Add Node.js repository
RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash -

# Install Java 17 runtime and Node.js with cache mount
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt/lists \
    apt-get update && apt-get install -yq \
    openjdk-17-jre-headless \
    nodejs

# Create app user
RUN adduser --disabled-password --gecos "" app

# Copy built application and frontend from builder stage
COPY --from=builder --chown=app /home/app/build/libs/ /home/app/libs/
COPY --from=builder --chown=app /home/app/frontend/dist/ /home/app/frontend/dist/

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

# Alternative target: OpenJ9 Semeru with container optimization
FROM icr.io/appcafe/ibm-semeru-runtimes:open-17-jre-jammy as semeru

# Install curl for health checks
RUN apt-get update && apt-get install -yq curl && rm -rf /var/lib/apt/lists/*

# Create app user
RUN adduser --disabled-password --gecos "" app

# Copy built application and frontend from builder stage
COPY --from=builder --chown=app /home/app/build/libs/ /home/app/libs/
COPY --from=builder --chown=app /home/app/frontend/dist/ /home/app/frontend/dist/

# Set working directory and user
WORKDIR /home/app
USER app

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:7000/v2/ || exit 1

# Run with OpenJ9 container-optimized JVM options
CMD ["java", \
     "-XX:+UseContainerSupport", \
     "-XX:MaxRAMPercentage=75.0", \
     "-XX:InitialRAMPercentage=50.0", \
     "-XX:+IdleTuningGcOnIdle", \
     "-XX:+IdleTuningCompactOnIdle", \
     "-Xtune:virtualized", \
     "-Xcompressedrefs", \
     "-Xcodecachetotal64m", \
     "-Xshareclasses", \
     "-XX:SharedCacheHardLimit=200m", \
     "-Xscmx60m", \
     "-Xgcpolicy:gencon", \
     "-Xgc:concurrentScavenge", \
     "-jar", "/home/app/libs/nscr-1.0-SNAPSHOT-all.jar"]