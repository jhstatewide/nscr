# NSCR Docker Registry Setup

This document explains how to build, tag, and push NSCR container images to various container registries.

## Repository Names

Based on your configuration, your images will be available at:

- **Default JDK**: `docker.io/statewide/nscr`
- **Semeru OpenJ9**: `docker.io/statewide/nscr-semeru`

## Quick Start

### 1. Build Both Variants
```bash
./gradlew dockerBuildAll
```

### 2. Push Both Variants
```bash
./gradlew dockerPushAll
```

## Available Gradle Tasks

### Building Tasks
- `dockerBuild` - Build local development image
- `dockerBuildForRegistry` - Build and tag default JDK for registry
- `dockerBuildSemeruForRegistry` - Build and tag Semeru variant for registry
- `dockerBuildAll` - Build both variants for registry

### Pushing Tasks
- `dockerPushDefault` - Push default JDK image to registry
- `dockerPushSemeru` - Push Semeru image to registry
- `dockerPushAll` - Push both variants to registry

## Versioning Strategy

### Automatic Versioning
- **Version tags**: Based on `build.gradle.kts` version (currently `1.0-SNAPSHOT`)
- **Latest tag**: Always points to the most recent build
- **Branch tags**: For development branches
- **Semantic versioning**: For releases (e.g., `v1.0.0`, `v1.1.0`)

### Tag Examples
```
docker.io/statewide/nscr:1.0-SNAPSHOT
docker.io/statewide/nscr:latest
docker.io/statewide/nscr:v1.0.0
docker.io/statewide/nscr-semeru:1.0-SNAPSHOT
docker.io/statewide/nscr-semeru:latest
docker.io/statewide/nscr-semeru:v1.0.0
```

## Configuration

### Custom Registry and Namespace
You can override the default registry and namespace using Gradle properties:

```bash
# Use GitHub Container Registry
./gradlew dockerBuildAll -PdockerRegistry=ghcr.io -PdockerNamespace=yourusername

# Use Quay.io
./gradlew dockerBuildAll -PdockerRegistry=quay.io -PdockerNamespace=yourusername

# Use custom registry
./gradlew dockerBuildAll -PdockerRegistry=your-registry.com -PdockerNamespace=yourorg
```

### Environment Variables
Set these in your CI/CD environment:
- `DOCKER_USERNAME` - Your registry username
- `DOCKER_PASSWORD` - Your registry password/token

## Registry Setup

### Docker Hub
1. Create two repositories:
   - `statewide/nscr` (for default JDK)
   - `statewide/nscr-semeru` (for Semeru OpenJ9)

2. Set up access tokens in Docker Hub settings

3. Configure secrets in your CI/CD:
   ```
   DOCKER_USERNAME=your-dockerhub-username
   DOCKER_PASSWORD=your-dockerhub-token
   ```

### GitHub Container Registry (ghcr.io)
1. Enable GitHub Packages in your repository settings

2. Create a Personal Access Token with `write:packages` permission

3. Configure secrets:
   ```
   DOCKER_USERNAME=your-github-username
   DOCKER_PASSWORD=your-github-token
   ```

## Automated CI/CD

### GitHub Actions
The repository includes a GitHub Actions workflow (`.github/workflows/docker-publish.yml`) that:
- Builds both variants on every push to main/master
- Pushes to registry on tags and main branch
- Uses build cache for faster builds
- Handles PR builds without pushing

### Manual Release Process
1. Update version in `build.gradle.kts`
2. Create and push a git tag:
   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```
3. GitHub Actions will automatically build and push the tagged version

## Image Variants

### Default JDK (OpenJDK 17)
- **Target**: `default`
- **Base**: Ubuntu 22.04
- **JVM**: OpenJDK 17
- **Size**: ~334MB
- **Use case**: General purpose, standard performance

### Semeru OpenJ9
- **Target**: `semeru`
- **Base**: IBM Semeru OpenJ9
- **JVM**: OpenJ9 with container optimizations
- **Size**: ~334MB
- **Use case**: Memory-constrained environments, container-optimized

## Usage Examples

### Pull and Run
```bash
# Default JDK
docker pull docker.io/statewide/nscr:latest
docker run -p 7000:7000 docker.io/statewide/nscr:latest

# Semeru OpenJ9
docker pull docker.io/statewide/nscr-semeru:latest
docker run -p 7000:7000 docker.io/statewide/nscr-semeru:latest
```

### Docker Compose
```yaml
version: '3.8'
services:
  nscr-default:
    image: docker.io/statewide/nscr:latest
    ports:
      - "7000:7000"
    volumes:
      - nscr-data:/home/app/data

  nscr-semeru:
    image: docker.io/statewide/nscr-semeru:latest
    ports:
      - "7001:7000"
    volumes:
      - nscr-data-semeru:/home/app/data

volumes:
  nscr-data:
  nscr-data-semeru:
```

## Troubleshooting

### Build Issues
- Ensure Docker BuildKit is enabled: `export DOCKER_BUILDKIT=1`
- Check available disk space (builds require ~2GB)
- Verify all dependencies are installed

### Push Issues
- Verify registry credentials are correct
- Check if you have push permissions to the repository
- Ensure the repository exists in the target registry

### Authentication Issues
- For Docker Hub: Use Personal Access Token, not password
- For GitHub: Use Personal Access Token with `write:packages` scope
- For other registries: Check their specific authentication requirements

## Security Considerations

- Use specific version tags in production, not `latest`
- Regularly update base images for security patches
- Scan images for vulnerabilities using tools like Trivy or Snyk
- Use minimal base images when possible
- Implement proper secret management for registry credentials
