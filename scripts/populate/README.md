# NSCR Registry Population Scripts

This directory contains scripts to populate your NSCR registry with sample Docker images for testing and demonstration purposes.

## Scripts Overview

### `populate_registry.sh`
**Full Registry Population Script**

Pulls and pushes 10 popular small Docker images to your NSCR registry:
- Alpine Linux, Hello World, Nginx, Redis, PostgreSQL
- Node.js, Python, BusyBox, Apache HTTP Server, Memcached

**Features:**
- ✅ Colored output with status indicators
- ✅ Registry connectivity validation
- ✅ Automatic cleanup of local images
- ✅ Registry status display
- ✅ Comprehensive error handling

**Usage:**
```bash
./scripts/populate/populate_registry.sh
```

### `populate_registry_simple.sh`
**Quick Registry Population Script**

Pulls and pushes 3 essential images for basic testing:
- Alpine Linux (latest)
- Hello World (latest)  
- Nginx (Alpine variant)

**Features:**
- ✅ Minimal dependencies
- ✅ Fast execution
- ✅ Perfect for quick testing

**Usage:**
```bash
./scripts/populate/populate_registry_simple.sh
```

## Prerequisites

Both scripts require:
- **Docker** must be running
- **NSCR registry** must be running on `localhost:7000`
- **Internet connection** to pull images from Docker Hub

Optional:
- **jq** for formatted JSON output in the full script

## Quick Start

1. **Start your NSCR registry:**
   ```bash
   ./gradlew run
   ```

2. **Run the simple population script:**
   ```bash
   ./scripts/populate/populate_registry_simple.sh
   ```

3. **Or run the full population script:**
   ```bash
   ./scripts/populate/populate_registry.sh
   ```

4. **View your populated registry:**
   Open http://localhost:7000 in your browser

## What These Scripts Do

1. **Check Registry Status** - Verify NSCR is running
2. **Pull Images** - Download images from Docker Hub
3. **Tag Images** - Tag them for your local registry
4. **Push Images** - Upload to your NSCR registry
5. **Clean Up** - Remove local tags to save space
6. **Show Status** - Display final registry statistics

## Troubleshooting

**Registry not running:**
```
Error: NSCR registry is not running at http://localhost:7000
```
→ Start the registry with `./gradlew run`

**Docker not running:**
```
Error: Docker is not running
```
→ Start Docker Desktop or Docker daemon

**Permission denied:**
```
Permission denied: ./scripts/populate/populate_registry.sh
```
→ Make scripts executable: `chmod +x scripts/populate/*.sh`

## Customization

You can modify the scripts to:
- Change the registry URL (default: `localhost:7000`)
- Add or remove images from the population list
- Adjust the temporary directory location
- Modify output formatting and colors
