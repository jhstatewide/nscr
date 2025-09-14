#!/bin/bash

# Test script for NSCR deletion and garbage collection features
# This script demonstrates image deletion and garbage collection capabilities

set -e  # Exit on any error

# Configuration
REGISTRY_PORT=7000
REGISTRY_URL="localhost:${REGISTRY_PORT}"
TEST_IMAGE1="debian:bullseye-slim"
TEST_IMAGE2="alpine:latest"
LOCAL_IMAGE1="${REGISTRY_URL}/debian:bullseye-slim"
LOCAL_IMAGE2="${REGISTRY_URL}/alpine:latest"
BLOBSTORE_DIR="/tmp/nscr_deletion_test"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored output
print_status() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Function to cleanup on exit
cleanup() {
    print_status "Cleaning up..."
    if [ ! -z "$SERVER_PID" ]; then
        print_status "Stopping NSCR server (PID: $SERVER_PID)"
        kill $SERVER_PID 2>/dev/null || true
        wait $SERVER_PID 2>/dev/null || true
    fi
    
    if [ -d "$BLOBSTORE_DIR" ]; then
        print_status "Removing test blobstore directory: $BLOBSTORE_DIR"
        rm -rf "$BLOBSTORE_DIR"
    fi
}

# Set up cleanup trap
trap cleanup EXIT

# Check if Docker is running
if ! docker info >/dev/null 2>&1; then
    print_error "Docker is not running. Please start Docker and try again."
    exit 1
fi

# Check if the project builds
print_status "Building NSCR project..."
if ! ./gradlew build -q; then
    print_error "Failed to build the project. Please fix build errors first."
    exit 1
fi
print_success "Project built successfully"

# Clean up any existing test blobstore
if [ -d "$BLOBSTORE_DIR" ]; then
    print_status "Removing existing test blobstore..."
    rm -rf "$BLOBSTORE_DIR"
fi

# Create test blobstore directory
mkdir -p "$BLOBSTORE_DIR"

# Start the NSCR server in the background
print_status "Starting NSCR server on port $REGISTRY_PORT..."
./gradlew run -q &
SERVER_PID=$!

# Wait for server to start
print_status "Waiting for server to start..."
sleep 5

# Check if server is running
if ! curl -s "http://${REGISTRY_URL}/v2" >/dev/null; then
    print_error "Server failed to start or is not responding"
    exit 1
fi
print_success "NSCR server is running"

# Pull and push first image
print_status "Pulling and pushing $TEST_IMAGE1..."
docker pull "$TEST_IMAGE1" >/dev/null
docker tag "$TEST_IMAGE1" "$LOCAL_IMAGE1"
docker push "$LOCAL_IMAGE1" >/dev/null
print_success "Successfully pushed $LOCAL_IMAGE1"

# Pull and push second image
print_status "Pulling and pushing $TEST_IMAGE2..."
docker pull "$TEST_IMAGE2" >/dev/null
docker tag "$TEST_IMAGE2" "$LOCAL_IMAGE2"
docker push "$LOCAL_IMAGE2" >/dev/null
print_success "Successfully pushed $LOCAL_IMAGE2"

# List repositories
print_status "Listing repositories..."
REPOS=$(curl -s "http://${REGISTRY_URL}/v2/_catalog" | jq -r '.repositories[]')
echo "Repositories: $REPOS"

# List tags for debian repository
print_status "Listing tags for debian repository..."
TAGS=$(curl -s "http://${REGISTRY_URL}/v2/debian/tags/list" | jq -r '.tags[]')
echo "Tags for debian: $TAGS"

# Get initial blob count
INITIAL_BLOBS=$(curl -s "http://${REGISTRY_URL}/api/blobs" | wc -l)
print_status "Initial blob count: $INITIAL_BLOBS"

# Delete the debian image
print_status "Deleting debian:bullseye-slim manifest..."
DELETE_RESPONSE=$(curl -s -w "%{http_code}" -X DELETE "http://${REGISTRY_URL}/v2/debian/manifests/bullseye-slim")
HTTP_CODE="${DELETE_RESPONSE: -3}"
if [ "$HTTP_CODE" = "202" ]; then
    print_success "Successfully deleted debian:bullseye-slim manifest"
else
    print_error "Failed to delete manifest. HTTP code: $HTTP_CODE"
    exit 1
fi

# Verify the image is gone
print_status "Verifying image deletion..."
if ! docker pull "$LOCAL_IMAGE1" 2>/dev/null; then
    print_success "Image successfully deleted - cannot pull it back"
else
    print_warning "Image still exists after deletion"
fi

# Check blob count after deletion (should be the same - blobs not yet garbage collected)
BLOBS_AFTER_DELETE=$(curl -s "http://${REGISTRY_URL}/api/blobs" | wc -l)
print_status "Blob count after deletion: $BLOBS_AFTER_DELETE (should be same as before)"

# Run garbage collection
print_status "Running garbage collection..."
GC_RESPONSE=$(curl -s "http://${REGISTRY_URL}/api/garbage-collect")
BLOBS_REMOVED=$(echo "$GC_RESPONSE" | jq -r '.blobsRemoved')
SPACE_FREED=$(echo "$GC_RESPONSE" | jq -r '.spaceFreed')
MANIFESTS_REMOVED=$(echo "$GC_RESPONSE" | jq -r '.manifestsRemoved')

print_success "Garbage collection completed:"
echo "  - Blobs removed: $BLOBS_REMOVED"
echo "  - Space freed: $SPACE_FREED bytes"
echo "  - Manifests removed: $MANIFESTS_REMOVED"

# Check final blob count
FINAL_BLOBS=$(curl -s "http://${REGISTRY_URL}/api/blobs" | wc -l)
print_status "Final blob count: $FINAL_BLOBS"

# Verify alpine image still works
print_status "Verifying alpine image still works..."
if docker pull "$LOCAL_IMAGE2" >/dev/null; then
    print_success "Alpine image still accessible after garbage collection"
else
    print_error "Alpine image was incorrectly affected by garbage collection"
    exit 1
fi

# List repositories again
print_status "Listing repositories after deletion..."
REPOS_AFTER=$(curl -s "http://${REGISTRY_URL}/v2/_catalog" | jq -r '.repositories[]')
echo "Remaining repositories: $REPOS_AFTER"

print_success "Deletion and garbage collection test completed successfully!"
print_status "NSCR registry properly handles image deletion and garbage collection"
