#!/bin/bash

# Concurrent Torture Test Example Script
# This script demonstrates how to run the new concurrent torture test with different configurations

set -e

echo "=== Concurrent Registry Torture Test Examples ==="
echo

# Check if registry is running
echo "Checking if registry is running..."
if ! curl -s http://localhost:7000/api/web/status > /dev/null; then
    echo "ERROR: Registry is not running on localhost:7000"
    echo "Please start the registry first with: ./gradlew run"
    exit 1
fi
echo "✓ Registry is running"
echo

# Example 1: Basic concurrent test
echo "=== Example 1: Basic Concurrent Test ==="
echo "Running 4 workers with 10 operations each..."
./gradlew tortureTestConcurrent \
    -Ptorture.numWorkers=4 \
    -Ptorture.operationsPerWorker=10 \
    -Ptorture.operationDelayMs=1000 \
    -Ptorture.maxConcurrentOperations=4 \
    -Ptorture.outputFile=concurrent-basic-report.txt
echo "✓ Basic concurrent test completed"
echo

# Example 2: Medium intensity test
echo "=== Example 2: Medium Intensity Test ==="
echo "Running 6 workers with 20 operations each..."
./gradlew tortureTestConcurrent \
    -Ptorture.numWorkers=6 \
    -Ptorture.operationsPerWorker=20 \
    -Ptorture.operationDelayMs=500 \
    -Ptorture.maxConcurrentOperations=8 \
    -Ptorture.outputFile=concurrent-medium-report.txt
echo "✓ Medium intensity test completed"
echo

# Example 3: High intensity test
echo "=== Example 3: High Intensity Test ==="
echo "Running 8 workers with 30 operations each..."
./gradlew tortureTestConcurrent \
    -Ptorture.numWorkers=8 \
    -Ptorture.operationsPerWorker=30 \
    -Ptorture.operationDelayMs=200 \
    -Ptorture.maxConcurrentOperations=12 \
    -Ptorture.outputFile=concurrent-high-report.txt
echo "✓ High intensity test completed"
echo

# Example 4: Using predefined intense task
echo "=== Example 4: Predefined Intense Test ==="
echo "Running predefined intense concurrent test..."
./gradlew tortureTestConcurrentIntense
echo "✓ Predefined intense test completed"
echo

# Example 5: Custom stress test
echo "=== Example 5: Custom Stress Test ==="
echo "Running custom stress test with 10 workers..."
./gradlew tortureTestConcurrent \
    -Ptorture.numWorkers=10 \
    -Ptorture.operationsPerWorker=25 \
    -Ptorture.operationDelayMs=100 \
    -Ptorture.maxConcurrentOperations=16 \
    -Ptorture.outputFile=concurrent-stress-report.txt
echo "✓ Custom stress test completed"
echo

echo "=== All Concurrent Torture Tests Completed ==="
echo
echo "Generated reports:"
ls -la *concurrent*report.txt 2>/dev/null || echo "No report files found"
echo
echo "To view a report:"
echo "cat concurrent-basic-report.txt"
echo
echo "To run the maximum stress test (use with caution):"
echo "./gradlew tortureTestConcurrentStress"
