# NSCR Scripts

This directory contains utility scripts for managing and testing the NSCR (New and Shiny Container Registry).

## Directory Structure

```
scripts/
├── management/          # Management and operational scripts
│   └── nscr            # Main registry management CLI tool
└── tests/              # Test and validation scripts
    ├── test_registry.sh    # Full registry functionality test
    ├── test_deletion.sh    # Image deletion and garbage collection test
    └── simple_test.sh      # Basic functionality verification
```

## Management Scripts

### `management/nscr`

The main command-line tool for managing your NSCR registry.

**Usage:**
```bash
./scripts/management/nscr <command> [options]
```

**Commands:**
- `list-repos` - List all repositories
- `list-tags <repo>` - List all tags for a repository
- `delete-image <repo> <tag>` - Delete an image by repository and tag
- `garbage-collect` - Run garbage collection to free space
- `gc-stats` - Show garbage collection statistics
- `blob-count` - Show current blob count
- `status` - Show registry status

**Examples:**
```bash
# List all repositories
./scripts/management/nscr list-repos

# List tags for debian repository
./scripts/management/nscr list-tags debian

# Delete an image
./scripts/management/nscr delete-image debian bullseye-slim

# Check garbage collection statistics
./scripts/management/nscr gc-stats

# Run garbage collection
./scripts/management/nscr garbage-collect

# Check registry status
./scripts/management/nscr status
```

## Test Scripts

### `tests/test_registry.sh`

Comprehensive test that validates the complete registry workflow:
- Builds the NSCR project
- Starts the registry server
- Pulls a Debian image from Docker Hub
- Pushes it to the local NSCR registry
- Verifies the push was successful
- Tests manifest retrieval
- Cleans up resources

**Usage:**
```bash
./scripts/tests/test_registry.sh
```

### `tests/test_deletion.sh`

Advanced test that validates image deletion and garbage collection:
- Pushes multiple images (Debian and Alpine)
- Deletes one image
- Runs garbage collection
- Verifies space is freed
- Confirms remaining images still work
- Tests all new deletion and management APIs

**Usage:**
```bash
./scripts/tests/test_deletion.sh
```

### `tests/simple_test.sh`

Basic functionality verification test:
- Builds the project
- Starts the server
- Tests basic API endpoints
- Verifies garbage collection works
- Minimal test for quick validation

**Usage:**
```bash
./scripts/tests/simple_test.sh
```

### `tests/test_multipart.sh`

Multi-part upload functionality test:
- Builds the NSCR project
- Starts the registry server
- Pulls a large Ubuntu image from Docker Hub
- Pushes it to the local NSCR registry (tests multi-part uploads)
- Verifies the push was successful
- Tests manifest and blob retrieval
- Validates repository listing
- Comprehensive test for large image handling

**Usage:**
```bash
./scripts/tests/test_multipart.sh
```

## Prerequisites

All scripts require:
- Docker to be running
- Java 17 installed and configured
- The NSCR project to be buildable with `./gradlew build`

## Configuration

Scripts use the following default configuration:
- Registry URL: `localhost:7000`
- Test blobstore directory: `/tmp/nscr_*_test`

You can modify these values in the scripts if needed for your environment.
