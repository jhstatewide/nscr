# New and Shiny Container Registry

## Why?

There are only a few implementations of container registries out there. I basically want the SQLite of container registries. A simple, easy to use, and easy to understand container registry.

I had wanted to try making my own container registry for a while now, and this is the result. One of my frustrations with the official registry is that it doesn't seem very well behaved when wanting to delete images, leaving garbage and bloating your disk space forever. This is especially annoying for using it in a CI/CD pipeline, where lots of images are being pushed and pulled constantly.

This registry is implemented on top of an ACID database and we are able to safely delete images and reclaim disk space transactionally.

I also wanted something that's really easy to set up and doesn't have a huge footprint.

This is probably best suited for running behind your firewall and not exposed to the public internet, but in theory it could be used as as a public registry if you really wanted to.

## Obligatory Screenshot

<img width="3840" height="4706" alt="nscr_webui_screenshot" src="https://github.com/user-attachments/assets/b4130d59-95b2-4d57-bb89-d7949255ee36" />

## Key Features

- **Safe Image Deletion** - ACID database ensures images can be safely deleted and disk space reclaimed
- **Easy to Run** - Docker images are available for both default JDK and Semeru OpenJ9, making it easy to get started
- **Real-time Web UI** - Live monitoring with server-sent events for logs, repositories, and throughput
- **OCI Registry API v2** - Full compatibility with Docker and other OCI-compliant runtimes
- **Garbage Collection** - Automatic cleanup of orphaned blobs and manifests
- **Throughput Monitoring** - Real-time performance tracking with historical data
- **Torture Testing** - Built-in comprehensive testing framework for validation
- **Lightweight** - Small footprint, easy setup, minimal resource usage
- **Memory Optimized** - Semeru OpenJ9 variant uses 75% less memory

## Quick Start with Docker

The easiest way to run NSCR is using the official Docker images.

### Default JDK Version
```bash
# Pull and run the default JDK version
docker pull docker.io/statewide/nscr:latest
docker run -p 7000:7000 docker.io/statewide/nscr:latest
```

### Semeru OpenJ9 Version (Memory Optimized)
```bash
# Pull and run the Semeru OpenJ9 version (75% less memory usage)
docker pull docker.io/statewide/nscr-semeru:latest
docker run -p 7000:7000 docker.io/statewide/nscr-semeru:latest
```

**What is Semeru OpenJ9?** IBM Semeru Runtime combines OpenJDK class libraries with the Eclipse OpenJ9 JVM, providing faster startup times, improved performance, and significantly reduced memory usage compared to standard OpenJDK. This makes it ideal for containerized environments where resource efficiency is critical.

### With Persistent Data
```bash
# Run with persistent data storage
docker run -p 7000:7000 -v nscr-data:/home/app/data docker.io/statewide/nscr:latest
```

### Using Docker Compose
```yaml
version: '3.8'
services:
  nscr:
    image: docker.io/statewide/nscr:latest
    ports:
      - "7000:7000"
    volumes:
      - nscr-data:/home/app/data

volumes:
  nscr-data:
```

**Registry will be available at:** http://localhost:7000

## Example walkthrough when pushing an image

This registry is designed to be a valid target for OCI-compliant container runtimes to be able to push
and pull. What actually happens when we call `docker push`?

To push an image, we will be pushing at least two things: a manifest and a blob. The manifest is a JSON file that describes the image, and the blob is the actual image data. The manifest will contain a list of layers, which are the blobs that make up the image.

We first need to get a session ID. We get this by doing a POST to `/v2/<image-name>/blobs/uploads/`. This will return a session ID that we can use to upload the blob.

## Quick Start

1. **Build and run the registry:**
   ```bash
   ./gradlew build
   ./gradlew run
   ```

2. **Test the registry:**
   ```bash
   ./scripts/tests/test_registry.sh
   ```

3. **Manage your registry:**
   ```bash
   ./nscr status                    # Check registry status
   ./nscr list-repos               # List repositories
   ./nscr delete-image <repo> <tag> # Delete an image
   ./nscr garbage-collect          # Free up space
   ```

## Features

- ✅ **Push/Pull Images** - Full OCI Registry API v2 compatibility
- ✅ **Image Deletion** - Remove specific images by repository and tag
- ✅ **Garbage Collection** - Automatically clean up unreferenced blobs
- ✅ **Repository Management** - List repositories and tags
- ✅ **Storage Management** - Monitor and reclaim storage space
- ✅ **Management Tools** - Command-line interface for registry operations

## Documentation

- **[doc/](doc/)** - Comprehensive documentation including:
  - [Authentication Guide](doc/AUTHENTICATION.md) - Setting up authentication
  - [Test Cleanup Guide](doc/TEST_CLEANUP.md) - Managing test artifacts
  - [Torture Testing](doc/TORTURE_TEST_README.md) - Advanced testing strategies
  - [AI Development](doc/AGENTS.md) - Development workflow with AI agents

## Container Support

NSCR can be built and run as a container with two JVM options:

### Standard OpenJDK (Default)
```bash
# Build the container image (with BuildKit cache mounts for faster builds)
./gradlew dockerBuild

# Run the container
docker run -p 7000:7000 nscr:latest

# Or use the convenient Gradle task
./gradlew dockerRun
```

### IBM Semeru OpenJ9 (Container-Optimized)
```bash
# Build the Semeru OpenJ9 container image (minimal memory usage)
./gradlew dockerBuildSemeru

# Run the Semeru container
docker run -p 7000:7000 nscr:semeru

# Or use the convenient Gradle task
./gradlew dockerRunSemeru
```

### Container Image Features
- **Multi-stage build** for optimized size
- **BuildKit cache mounts** for faster builds
- **Java 17 runtime** (OpenJDK or Semeru OpenJ9)
- **Node.js** for frontend support
- **Health checks** for container monitoring

### Semeru OpenJ9 Benefits
- **Container-aware memory management** (75% of container memory)
- **Idle memory release** (releases unused memory after 3s idle)
- **Virtualized tuning** for cloud/container environments
- **Compressed references** for reduced memory footprint
- **Class data sharing** for faster startup
- **Optimized garbage collection** (generational concurrent GC)

## Scripts

See `scripts/README.md` for detailed information about available management and test scripts.
