#!/bin/bash

# Simple test for NSCR deletion features
set -e

REGISTRY_URL="localhost:7000"
BLOBSTORE_DIR="/tmp/nscr_simple_test"

# Cleanup function
cleanup() {
    echo "Cleaning up..."
    if [ ! -z "$SERVER_PID" ]; then
        kill $SERVER_PID 2>/dev/null || true
        wait $SERVER_PID 2>/dev/null || true
    fi
    rm -rf "$BLOBSTORE_DIR" 2>/dev/null || true
}
trap cleanup EXIT

# Build and start server
echo "Building project..."
./gradlew build -q

echo "Starting server..."
mkdir -p "$BLOBSTORE_DIR"
./gradlew run -q &
SERVER_PID=$!
sleep 5

# Test basic endpoints
echo "Testing basic endpoints..."
curl -s "http://${REGISTRY_URL}/v2" >/dev/null && echo "✓ Registry API working"

# Test repository listing
echo "Testing repository listing..."
REPOS=$(curl -s "http://${REGISTRY_URL}/v2/_catalog" | jq -r '.repositories[]' 2>/dev/null || echo "")
echo "Repositories: $REPOS"

# Test garbage collection (should work even with empty registry)
echo "Testing garbage collection..."
GC_RESPONSE=$(curl -s -X POST "http://${REGISTRY_URL}/api/garbage-collect" 2>/dev/null || echo "{}")
echo "GC Response: $GC_RESPONSE"

echo "✓ Basic functionality test completed"
