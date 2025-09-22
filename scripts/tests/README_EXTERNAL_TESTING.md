# External Registry Testing Guide

This guide explains how to use the external API for comprehensive registry torture testing and monitoring.

## Overview

The NSCR registry now provides a comprehensive machine-readable API that enables external actors to:

1. **Monitor registry state** in real-time during concurrent operations
2. **Verify consistency** across all registry components
3. **Perform health checks** to ensure registry stability
4. **Track active sessions** and upload progress
5. **Stress test** the API endpoints with concurrent requests

## Authentication and HTTPS Support

### Authentication
Authentication is **optional** for most API endpoints. The clients support both authenticated and anonymous access:

#### With Authentication
```bash
# Python client
python3 scripts/tests/external_torture_test.py --username admin --password admin

# Ruby client  
ruby scripts/tests/external_torture_test.rb --username admin --password admin

# Bash client
USERNAME=admin PASSWORD=admin ./scripts/tests/external_torture_test.sh
```

#### Without Authentication (Anonymous Access)
```bash
# Python client
python3 scripts/tests/external_torture_test.py

# Ruby client
ruby scripts/tests/external_torture_test.rb

# Bash client
./scripts/tests/external_torture_test.sh
```

**Note**: If your registry is configured to require authentication, you must provide credentials. The clients will warn you if authentication is required but not provided.

### HTTPS Support
All clients support both HTTP and HTTPS connections:

```bash
# HTTP (default)
--registry-url http://localhost:7000

# HTTPS with self-signed certificates
--registry-url https://registry.example.com

# HTTPS with custom port
--registry-url https://registry.example.com:8443
```

The clients automatically handle:
- SSL/TLS connections for HTTPS URLs
- Self-signed certificates (with warnings)
- HTTP/HTTPS protocol detection

## Quick Start

### 1. Start the Registry

```bash
# Build and start the registry
./gradlew build
./gradlew run
```

### 2. Run External Torture Tests

#### Python Client (Recommended)

```bash
# Install dependencies
pip3 install aiohttp

# Run comprehensive torture test (with authentication)
python3 scripts/tests/external_torture_test.py \
    --registry-url http://localhost:7000 \
    --username admin \
    --password admin \
    --duration 120 \
    --test-type all

# Or without authentication (anonymous access)
python3 scripts/tests/external_torture_test.py \
    --registry-url http://localhost:7000 \
    --duration 120 \
    --test-type all
```

#### Ruby Client (Fiber-based Concurrency)

```bash
# Run comprehensive torture test using Ruby fibers (with authentication)
ruby scripts/tests/external_torture_test.rb \
    --registry-url http://localhost:7000 \
    --username admin \
    --password admin \
    --duration 120 \
    --test-type all

# Or without authentication (anonymous access)
ruby scripts/tests/external_torture_test.rb \
    --registry-url http://localhost:7000 \
    --duration 120 \
    --test-type all
```

#### Bash Client (Simple)

```bash
# Run comprehensive torture test (with authentication)
USERNAME=admin PASSWORD=admin ./scripts/tests/external_torture_test.sh

# Or without authentication (anonymous access)
./scripts/tests/external_torture_test.sh
```

### 3. Monitor Results

The test clients will output:
- Real-time registry state information
- Consistency check results
- Health status monitoring
- Success/failure rates
- Detailed summaries

## Test Types

### Monitor Test
Continuously monitors registry state and reports metrics:

```bash
# Python version
python3 scripts/tests/external_torture_test.py --test-type monitor --duration 60

# Ruby version
ruby scripts/tests/external_torture_test.rb --test-type monitor --duration 60 --username admin --password admin
```

### Consistency Test
Performs repository consistency checks:

```bash
# Python version
python3 scripts/tests/external_torture_test.py --test-type consistency --duration 60

# Ruby version
ruby scripts/tests/external_torture_test.rb --test-type consistency --duration 60 --username admin --password admin
```

### Stress Test
Performs concurrent API requests to stress test the registry:

```bash
# Python version
python3 scripts/tests/external_torture_test.py --test-type stress --duration 60 --concurrent-requests 20

# Ruby version
ruby scripts/tests/external_torture_test.rb --test-type stress --duration 60 --concurrent-requests 20 --username admin --password admin
```

### All Tests
Runs all test types concurrently (recommended):

```bash
# Python version
python3 scripts/tests/external_torture_test.py --test-type all --duration 120

# Ruby version
ruby scripts/tests/external_torture_test.rb --test-type all --duration 120 --username admin --password admin
```

## API Endpoints

### Registry State
```bash
# With authentication
curl -u admin:admin http://localhost:7000/api/registry/state | jq '.'

# Without authentication (anonymous access)
curl http://localhost:7000/api/registry/state | jq '.'

# HTTPS with self-signed certificate
curl -k -u admin:admin https://registry.example.com/api/registry/state | jq '.'
```

### Health Check
```bash
# With authentication
curl -u admin:admin http://localhost:7000/api/registry/health | jq '.'

# Without authentication
curl http://localhost:7000/api/registry/health | jq '.'
```

### Repository Details
```bash
# With authentication
curl -u admin:admin http://localhost:7000/api/registry/repositories/nginx | jq '.'

# Without authentication
curl http://localhost:7000/api/registry/repositories/nginx | jq '.'
```

### Active Sessions
```bash
# With authentication
curl -u admin:admin http://localhost:7000/api/registry/sessions | jq '.'

# Without authentication
curl http://localhost:7000/api/registry/sessions | jq '.'
```

### Blob Information
```bash
# With authentication
curl -u admin:admin http://localhost:7000/api/registry/blobs | jq '.'

# Without authentication
curl http://localhost:7000/api/registry/blobs | jq '.'
```

## Integration with Existing Torture Tests

You can run the external torture tests alongside your existing internal torture tests:

```bash
# Terminal 1: Start registry
./gradlew run

# Terminal 2: Run internal torture test
./gradlew tortureTestConcurrent -Ptorture.numWorkers=8 -Ptorture.operationsPerWorker=20

# Terminal 3: Run external monitoring (Python)
python3 scripts/tests/external_torture_test.py --test-type all --duration 300

# Or use Ruby version
ruby scripts/tests/external_torture_test.rb --test-type all --duration 300 --username admin --password admin
```

## Custom Test Scenarios

### Scenario 1: High Concurrency Monitoring
Monitor registry state during high-concurrency operations:

```bash
# Start high-concurrency internal test
./gradlew tortureTestConcurrentStress &

# Monitor with external client (Python)
python3 scripts/tests/external_torture_test.py --test-type monitor --duration 300

# Or use Ruby version
ruby scripts/tests/external_torture_test.rb --test-type monitor --duration 300 --username admin --password admin
```

### Scenario 2: Consistency Verification
Verify registry consistency after operations:

```bash
# Run operations
./gradlew tortureTestConcurrent -Ptorture.numWorkers=10 -Ptorture.operationsPerWorker=50

# Verify consistency (Python)
python3 scripts/tests/external_torture_test.py --test-type consistency --duration 60

# Or use Ruby version
ruby scripts/tests/external_torture_test.rb --test-type consistency --duration 60 --username admin --password admin
```

### Scenario 3: Stress Testing
Stress test the API endpoints:

```bash
# High-concurrency API stress test (Python)
python3 scripts/tests/external_torture_test.py \
    --test-type stress \
    --duration 300 \
    --concurrent-requests 50

# Or use Ruby version
ruby scripts/tests/external_torture_test.rb \
    --test-type stress \
    --duration 300 \
    --concurrent-requests 50 \
    --username admin \
    --password admin
```

## Monitoring and Alerting

### Real-time Monitoring
```bash
# Monitor registry state every 5 seconds
while true; do
    echo "=== $(date) ==="
    curl -s -u admin:admin http://localhost:7000/api/registry/state | \
        jq '{repos: .summary.totalRepositories, manifests: .summary.totalManifests, blobs: .summary.totalBlobs, health: .health.status}'
    sleep 5
done
```

### Health Alerting
```bash
# Alert on health degradation
while true; do
    health=$(curl -s -u admin:admin http://localhost:7000/api/registry/health)
    status=$(echo "$health" | jq -r '.status')
    if [ "$status" != "healthy" ]; then
        echo "ALERT: Registry health is $status"
        echo "$health" | jq '.'
    fi
    sleep 10
done
```

## Troubleshooting

### Common Issues

1. **Authentication Errors**
   - Ensure correct username/password
   - Check if authentication is enabled in registry config

2. **Connection Refused**
   - Verify registry is running on correct port
   - Check firewall settings

3. **JSON Parsing Errors**
   - Ensure jq is installed for bash scripts
   - Check API response format

### Debug Mode

Enable debug logging in the Python client:

```python
import logging
logging.basicConfig(level=logging.DEBUG)
```

## Performance Considerations

- **API Rate Limiting**: The registry can handle high-frequency requests, but consider appropriate delays
- **Concurrent Requests**: Start with 10-20 concurrent requests and increase based on system capacity
- **Monitoring Frequency**: 5-10 second intervals are recommended for state monitoring
- **Resource Usage**: External monitoring adds minimal overhead to the registry

## Client Comparison

### Python Client (AsyncIO)
- **Concurrency**: Uses asyncio for true async I/O
- **Dependencies**: Requires `aiohttp` package
- **Performance**: Excellent for high-concurrency scenarios
- **Features**: Full async/await support, comprehensive error handling

### Ruby Client (Fibers)
- **Concurrency**: Uses Ruby fibers for lightweight concurrency
- **Dependencies**: Uses only Ruby standard library (no external gems)
- **Performance**: Good for I/O-bound operations with fiber scheduling
- **Features**: Simple fiber scheduler, thread-safe operations

### Bash Client (Process-based)
- **Concurrency**: Uses background processes and subshells
- **Dependencies**: Requires `curl`, `jq`, and `bc` utilities
- **Performance**: Suitable for basic monitoring and testing
- **Features**: Simple to use, no programming language dependencies

## Best Practices

1. **Run tests in isolated environments** to avoid affecting production systems
2. **Monitor system resources** (CPU, memory, disk) during tests
3. **Use appropriate test durations** - longer tests provide better insights
4. **Combine internal and external tests** for comprehensive coverage
5. **Document test results** for regression analysis
6. **Choose the right client** based on your environment and requirements

## Example Test Results

```
=== Torture Test Summary ===
Total operations: 1,247
Errors: 3
Success rate: 99.76%
State snapshots collected: 24

Initial state: repos=5, manifests=23, blobs=67
Final state: repos=5, manifests=23, blobs=67
```

This external testing framework provides comprehensive monitoring and validation capabilities for ensuring registry stability under concurrent load.
