# Test Cleanup Guide

This document explains how to clean up test artifacts that may be left behind after running tests.

## Problem

Tests sometimes leave temporary directories and files in `tmp/test-data/` that accumulate over time, cluttering the repository. However, we need to preserve the `.keep` file to maintain the directory structure in git.

## Solutions

### 1. Gradle Task (Recommended)

The easiest way to clean up test artifacts is using the Gradle task:

```bash
./gradlew cleanupTestData
```

This task:
- ✅ Removes all test directories and files
- ✅ Preserves the `.keep` file
- ✅ Shows progress and confirmation
- ✅ Runs automatically as part of `./gradlew clean`

### 2. Convenience Script

For even easier cleanup, use the convenience script:

```bash
./cleanup-tests.sh
```

This script:
- ✅ Sets up the environment automatically
- ✅ Runs both test data and Docker image cleanup
- ✅ Provides helpful tips and feedback

### 3. Docker Image Cleanup

For Docker-specific cleanup, you have several options:

```bash
# Clean up only new test images (nscr-test-* prefix)
./gradlew cleanupTestDockerImages

# Clean up all test images (nscr-test-* and localhost:* from old runs)
./gradlew cleanupAllTestDockerImages

# General Docker cleanup (removes unused images)
./gradlew cleanupDockerImages
```

### 4. Manual Cleanup

You can also clean up manually:

```bash
# Remove all directories except .keep
find tmp/test-data -mindepth 1 -maxdepth 1 -type d -exec rm -rf {} +

# Or use the detailed script
./scripts/cleanup-test-data.sh
```

## Automatic Cleanup

The cleanup task is automatically integrated with the main `clean` task:

```bash
./gradlew clean  # This now includes test data cleanup
```

## What Gets Cleaned

The cleanup removes:
- All test directories (e.g., `basic-auth-test-*`, `server-lifecycle-test-*`)
- All temporary files created by tests
- H2 database files (`.mv.db`, `.trace.db`)
- **Docker images with `nscr-test-*` prefix** (new test images)
- **Docker images with `localhost:*` tags** (from old test runs)

The cleanup preserves:
- The `.keep` file (essential for git)
- The `tmp/test-data/` directory structure
- **All other Docker images** (your real images are safe!)

## Integration with Tests

The `RegistryTortureTestTest` class includes a `cleanupTestData()` method that can be called programmatically:

```kotlin
RegistryTortureTestTest.cleanupTestData()
```

## Best Practices

1. **Run cleanup regularly**: After running tests, especially integration tests
2. **Use the Gradle task**: It's the most reliable method
3. **Check before committing**: Ensure no test artifacts are left behind
4. **Preserve .keep**: Never delete the `.keep` file manually

## Troubleshooting

### Permission Issues
If you get permission errors, ensure the files aren't locked by running processes:
```bash
# Kill any hanging Java processes
pkill -f java
./gradlew cleanupTestData
```

### Directory Not Empty
If directories can't be deleted, they might contain locked files:
```bash
# Force remove (use with caution)
sudo rm -rf tmp/test-data/*
# Then recreate .keep if needed
echo "# Keep file" > tmp/test-data/.keep
```

### Gradle Task Not Found
If the cleanup task isn't available, ensure you're in the project root:
```bash
# Check you're in the right directory
ls build.gradle.kts
# Run the task
./gradlew tasks --group verification
```
