# NSCR Docker Setup

This directory contains Docker configuration files for running NSCR (New and Shiny Container Registry) in a containerized environment.

## Files

- `Dockerfile` - Multi-stage build for NSCR
- `docker-compose.yml` - Full setup with bind mount for data persistence
- `docker-compose.simple.yml` - Simple setup with named volume
- `README.md` - This documentation

## Quick Start

### Option 1: Simple Setup (Recommended for most users)

```bash
# Build and run with named volume
docker-compose -f docker/docker-compose.simple.yml up -d

# Check status
docker-compose -f docker/docker-compose.simple.yml ps

# View logs
docker-compose -f docker/docker-compose.simple.yml logs -f
```

### Option 2: Advanced Setup (For production-like environments)

```bash
# Create data directory
mkdir -p docker/data

# Build and run with bind mount
docker-compose -f docker/docker-compose.yml up -d

# Check status
docker-compose -f docker/docker-compose.yml ps
```

## Data Persistence

### Named Volume (Simple)
- **File**: `docker-compose.simple.yml`
- **Storage**: Docker-managed named volume
- **Location**: Managed by Docker (usually in `/var/lib/docker/volumes/`)
- **Pros**: Simple, portable, Docker handles everything
- **Cons**: Less control over exact location

### Bind Mount (Advanced)
- **File**: `docker-compose.yml`
- **Storage**: Host directory bind mount
- **Location**: `./docker/data/` (relative to docker-compose.yml)
- **Pros**: Direct access to data files, easy backup
- **Cons**: Path-dependent, less portable

## Usage Examples

### Test the Registry

```bash
# Test basic functionality
curl http://localhost:7000/v2

# List repositories
curl http://localhost:7000/v2/_catalog

# Use the management tool
./nscr status
```

### Push an Image

```bash
# Pull an image from Docker Hub
docker pull alpine:latest

# Tag for local registry
docker tag alpine:latest localhost:7000/alpine:latest

# Push to NSCR
docker push localhost:7000/alpine:latest
```

### Management

```bash
# List repositories
./nscr list-repos

# List tags
./nscr list-tags alpine

# Delete an image
./nscr delete-image alpine latest

# Run garbage collection
./nscr garbage-collect
```

## Configuration

### Environment Variables

- `JAVA_OPTS` - JVM options (default: `-Xmx512m -Xms256m`)

### Ports

- `7000` - NSCR registry API

### Volumes

- `/app/data` - Registry data storage (H2 database files)

## Building from Source

```bash
# Build the Docker image
docker build -f docker/Dockerfile -t nscr:latest .

# Run the container
docker run -d \
  --name nscr-registry \
  -p 7000:7000 \
  -v nscr_data:/app/data \
  nscr:latest
```

## Troubleshooting

### Check Container Status

```bash
docker-compose -f docker/docker-compose.simple.yml ps
```

### View Logs

```bash
docker-compose -f docker/docker-compose.simple.yml logs -f nscr
```

### Access Container Shell

```bash
docker-compose -f docker/docker-compose.simple.yml exec nscr /bin/bash
```

### Reset Data

```bash
# Stop and remove containers
docker-compose -f docker/docker-compose.simple.yml down

# Remove volume (WARNING: This deletes all data!)
docker volume rm docker_nscr_data

# Start fresh
docker-compose -f docker/docker-compose.simple.yml up -d
```

## Production Considerations

1. **Security**: The container runs as non-root user
2. **Health Checks**: Built-in health checks monitor registry availability
3. **Resource Limits**: Consider adding memory/CPU limits in production
4. **Backup**: Regular backups of the data volume are recommended
5. **Monitoring**: Add logging and monitoring as needed

## Data Backup

### Named Volume Backup

```bash
# Create backup
docker run --rm -v docker_nscr_data:/data -v $(pwd):/backup alpine tar czf /backup/nscr-backup.tar.gz -C /data .

# Restore backup
docker run --rm -v docker_nscr_data:/data -v $(pwd):/backup alpine tar xzf /backup/nscr-backup.tar.gz -C /data
```

### Bind Mount Backup

```bash
# Simple file system backup
tar czf nscr-backup.tar.gz docker/data/
```
