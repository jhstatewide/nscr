# Registry Torture Test

The Registry Torture Test is a comprehensive correctness test that randomly performs operations (push, delete, query) on a running NSCR registry and validates the registry state after each operation. This serves as a correctness torture test to catch edge cases and ensure data integrity.

## Overview

The torture test performs the following operations randomly:

- **Push**: Pulls container images from Docker Hub and pushes them to the registry
- **Delete**: Deletes entire repositories from the registry
- **Query**: Queries repository information and tags
- **List Repos**: Lists all repositories in the registry
- **List Tags**: Lists tags for a specific repository

After each operation, the test validates that:
- Repository counts are correct
- Manifest counts are correct
- Blob counts are correct
- No data corruption occurred

## Prerequisites

1. **Running NSCR Registry**: The registry must be running on `localhost:7000` (or specify custom URL)
2. **Container Runtime**: Docker must be running and accessible
3. **Network Access**: Internet access to pull images from Docker Hub

## Available Gradle Tasks

### Basic Torture Test
```bash
./gradlew tortureTest
```
- **Default**: 50 operations with 2-second delays
- **Duration**: ~2-3 minutes
- **Output**: Console output

### Extended Torture Test
```bash
./gradlew tortureTestExtended
```
- **Default**: 200 operations with 1-second delays
- **Duration**: ~5-10 minutes
- **Output**: `torture-test-extended-report.txt`

### Quick Torture Test (CI/CD)
```bash
./gradlew tortureTestQuick
```
- **Default**: 20 operations with 500ms delays
- **Duration**: ~30 seconds
- **Output**: `torture-test-quick-report.txt`

### Concurrent Torture Test
```bash
./gradlew tortureTestConcurrent
```
- **Default**: 4 workers, 25 operations each, 500ms delays, max 8 concurrent operations
- **Duration**: ~3-5 minutes
- **Output**: `torture-test-concurrent-report.txt`
- **Purpose**: Test registry under concurrent load with multiple workers

### High-Intensity Concurrent Torture Test
```bash
./gradlew tortureTestConcurrentIntense
```
- **Default**: 8 workers, 50 operations each, 200ms delays, max 16 concurrent operations
- **Duration**: ~5-10 minutes
- **Output**: `torture-test-concurrent-intense-report.txt`
- **Purpose**: High-intensity concurrent testing with many workers

### Maximum Stress Concurrent Torture Test
```bash
./gradlew tortureTestConcurrentStress
```
- **Default**: 16 workers, 100 operations each, 100ms delays, max 32 concurrent operations
- **Duration**: ~10-20 minutes
- **Output**: `torture-test-concurrent-stress-report.txt`
- **Purpose**: Maximum stress testing - use with caution

## Customization

You can customize the torture test parameters using Gradle properties:

### Sequential Torture Test Customization
```bash
# Custom registry URL
./gradlew tortureTest -Ptorture.registryUrl=localhost:8080

# Custom number of operations
./gradlew tortureTest -Ptorture.maxOperations=100

# Custom delay between operations (milliseconds)
./gradlew tortureTest -Ptorture.operationDelayMs=1500

# Custom output file
./gradlew tortureTest -Ptorture.outputFile=my-test-report.txt

# Combine multiple properties
./gradlew tortureTest -Ptorture.registryUrl=localhost:8080 -Ptorture.maxOperations=75 -Ptorture.operationDelayMs=1000
```

### Concurrent Torture Test Customization
```bash
# Custom number of workers
./gradlew tortureTestConcurrent -Ptorture.numWorkers=8

# Custom operations per worker
./gradlew tortureTestConcurrent -Ptorture.operationsPerWorker=50

# Custom delay between operations (milliseconds)
./gradlew tortureTestConcurrent -Ptorture.operationDelayMs=200

# Custom maximum concurrent operations
./gradlew tortureTestConcurrent -Ptorture.maxConcurrentOperations=16

# Custom registry URL
./gradlew tortureTestConcurrent -Ptorture.registryUrl=localhost:8080

# Custom output file
./gradlew tortureTestConcurrent -Ptorture.outputFile=my-concurrent-test-report.txt

# Combine multiple properties
./gradlew tortureTestConcurrent -Ptorture.numWorkers=6 -Ptorture.operationsPerWorker=30 -Ptorture.operationDelayMs=300 -Ptorture.maxConcurrentOperations=12
```

## Test Images

The torture test uses a comprehensive set of container images with multiple tags:

### Base Images
- **alpine**: `latest`, `3.18`, `3.17`, `3.16`
- **debian**: `latest`, `bookworm`, `bullseye`, `buster`
- **ubuntu**: `latest`, `22.04`, `20.04`, `18.04`

### Application Images
- **nginx**: `latest`, `alpine`, `1.25`, `1.24`
- **redis**: `latest`, `alpine`, `7.2`, `7.0`
- **postgres**: `latest`, `alpine`, `15`, `14`
- **node**: `latest`, `alpine`, `20`, `18`
- **python**: `latest`, `alpine`, `3.11`, `3.10`

### Utility Images
- **busybox**: `latest`, `1.36`, `1.35`
- **hello-world**: `latest`
- **httpd**: `latest`, `alpine`, `2.4`
- **memcached**: `latest`, `alpine`, `1.6`

## Validation Logic

### Push Operation Validation
After pushing an image, the test validates:
- Repository count increases (if new repository) or stays same (if existing)
- Manifest count increases
- Blob count increases (new layers)

### Delete Operation Validation
After deleting a repository, the test validates:
- Repository count decreases
- Manifest count decreases
- Blob count stays same or decreases (blobs might be shared)

### Query Operation Validation
After querying, the test validates:
- Registry state remains unchanged (no side effects)
- Returned data is consistent

## Report Format

The torture test generates a comprehensive report including:

```
=== Registry Torture Test Report ===
Total Operations: 50
Successful Operations: 48
Failed Operations: 2
Validation Passed: 47
Validation Failed: 3

Operation Breakdown:
  push: 15 total, 15 successful, 15 validated
  delete: 10 total, 9 successful, 8 validated
  query: 10 total, 10 successful, 10 validated
  list_repos: 10 total, 10 successful, 10 validated
  list_tags: 5 total, 4 successful, 4 validated

Failed Operations:
  delete: Failed to delete repository alpine: 404 Not Found
  list_tags: Failed to list tags for repository nonexistent: 404 Not Found

Final Registry State:
  Repositories: 8
  Manifests: 12
  Blobs: 45
```

## Usage Examples

### Development Testing
```bash
# Quick test during development
./gradlew tortureTestQuick

# Standard test before committing
./gradlew tortureTest
```

### CI/CD Pipeline
```bash
# Quick test in CI
./gradlew tortureTestQuick

# Extended test in nightly builds
./gradlew tortureTestExtended
```

### Stress Testing
```bash
# High-intensity sequential test
./gradlew tortureTest -Ptorture.maxOperations=500 -Ptorture.operationDelayMs=100

# Long-running sequential test
./gradlew tortureTest -Ptorture.maxOperations=1000 -Ptorture.operationDelayMs=5000

# High-intensity concurrent test
./gradlew tortureTestConcurrentIntense

# Maximum stress concurrent test
./gradlew tortureTestConcurrentStress

# Custom high-concurrency test
./gradlew tortureTestConcurrent -Ptorture.numWorkers=12 -Ptorture.operationsPerWorker=75 -Ptorture.operationDelayMs=150 -Ptorture.maxConcurrentOperations=20
```

### Custom Registry Testing
```bash
# Test against staging registry
./gradlew tortureTest -Ptorture.registryUrl=staging-registry.company.com:5000

# Test against remote registry
./gradlew tortureTest -Ptorture.registryUrl=registry.example.com:443
```

## Troubleshooting

### Common Issues

1. **Registry Not Running**
   ```
   Error: Registry is not healthy
   ```
   **Solution**: Start the NSCR registry with `./gradlew run`

2. **Container Runtime Not Running**
   ```
   Exception: Container runtime is not running
   ```
   **Solution**: Start container runtime daemon

3. **Network Issues**
   ```
   Exception: Failed to pull image
   ```
   **Solution**: Check internet connectivity and Docker Hub access

4. **Permission Issues**
   ```
   Exception: Permission denied
   ```
   **Solution**: Ensure container runtime daemon is accessible (user in docker group)

### Debug Mode

For detailed debugging, you can run the torture test with increased logging:

```bash
# Enable debug logging
./gradlew tortureTest --debug

# Or run with custom JVM args
./gradlew tortureTest -Dorg.slf4j.simpleLogger.defaultLogLevel=debug
```

## Concurrent Testing Features

The concurrent torture test provides advanced testing capabilities:

### Key Features
- **Multiple Workers**: N concurrent workers performing operations simultaneously
- **Thread-Safe Operations**: All operations are properly synchronized
- **Concurrency Control**: Semaphore-based limiting of concurrent operations
- **Worker Statistics**: Per-worker performance tracking
- **Race Condition Detection**: Designed to catch concurrency-related bugs
- **Load Testing**: Simulates real-world concurrent usage patterns

### When to Use Concurrent Testing
- **Race Condition Testing**: Detect deadlocks, race conditions, and synchronization issues
- **Load Testing**: Test registry performance under concurrent load
- **Stress Testing**: Push the registry to its limits with high concurrency
- **Real-World Simulation**: Mimic actual usage patterns with multiple clients

### Concurrency Parameters
- **numWorkers**: Number of concurrent worker threads
- **operationsPerWorker**: Operations each worker performs
- **maxConcurrentOperations**: Maximum operations running simultaneously
- **operationDelayMs**: Delay between operations (shorter = more intense)

## Integration with Existing Tests

The torture test complements existing unit and integration tests:

- **Unit Tests**: Test individual components in isolation
- **Integration Tests**: Test component interactions
- **Sequential Torture Test**: Test system behavior under random load and validate correctness
- **Concurrent Torture Test**: Test system behavior under concurrent load and detect race conditions

## Performance Considerations

### Sequential Torture Test
- **Operation Delay**: Adjust `operationDelayMs` based on system performance
- **Sequential Operations**: The test runs operations sequentially to ensure deterministic validation
- **Resource Usage**: Each push operation downloads and uploads images, consuming bandwidth and disk space
- **Cleanup**: The test automatically cleans up local container images after pushing

### Concurrent Torture Test
- **Worker Count**: More workers increase concurrency but also resource usage
- **Concurrent Operations**: Limited by `maxConcurrentOperations` to prevent overwhelming the registry
- **Operation Delay**: Shorter delays increase intensity but may cause timeouts
- **Resource Usage**: Multiple workers downloading/uploading simultaneously increases bandwidth and disk usage
- **Thread Safety**: All operations are thread-safe with proper synchronization
- **Registry Load**: High concurrency may stress the registry - monitor system resources

## Contributing

To extend the torture test:

1. **Add New Operations**: Implement new operation types in `RegistryTortureTest.kt`
2. **Add New Images**: Extend the `testImages` list with additional container images
3. **Improve Validation**: Enhance validation logic for better correctness checking
4. **Add Metrics**: Include additional metrics in the report generation

## License

This torture test is part of the NSCR project and follows the same license terms.
