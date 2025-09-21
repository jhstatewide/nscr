# New and Shiny Container Registry

## Why?

There are only a few implementations of container registries out there. I basically want the SQLite of container registries. A simple, easy to use, and easy to understand container registry.

## Example walkthrough when pushing an image

This registry is designed to be a valid target for the official Docker daemon to be able to push
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

- ✅ **Push/Pull Images** - Full Docker Registry API v2 compatibility
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

## Docker Support

NSCR can be built and run as a Docker container:

```bash
# Build the Docker image (with BuildKit cache mounts for faster builds)
./gradlew dockerBuild

# Run the container
docker run -p 7000:7000 nscr:latest

# Or use the convenient Gradle task
./gradlew dockerRun
```

The Docker image includes:
- Multi-stage build for optimized size
- BuildKit cache mounts for faster builds
- Java 17 runtime
- Node.js for frontend support
- Health checks

## Scripts

See `scripts/README.md` for detailed information about available management and test scripts.