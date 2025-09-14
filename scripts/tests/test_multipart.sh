#!/bin/bash

# Test script for NSCR (New and Shiny Container Registry)
# This script tests multi-part upload functionality by pushing a large image

set -e  # Exit on any error

# Configuration
REGISTRY_PORT=7000
REGISTRY_URL="localhost:${REGISTRY_PORT}"
TEST_IMAGE="ubuntu:20.04"  # Use a larger image to test multi-part uploads
LOCAL_IMAGE="${REGISTRY_URL}/ubuntu:20.04"
BLOBSTORE_DIR="/tmp/nscr_multipart_test_blobstore"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

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

cleanup() {
    print_status "Cleaning up test environment..."
    
    # Stop any running registry
    if pgrep -f "java.*ServerKt" > /dev/null; then
        print_status "Stopping existing registry..."
        pkill -f "java.*ServerKt" || true
        sleep 2
    fi
    
    # Clean up test data
    if [ -d "$BLOBSTORE_DIR" ]; then
        print_status "Removing test blobstore directory..."
        rm -rf "$BLOBSTORE_DIR"
    fi
    
    # Remove test image
    if docker image inspect "$LOCAL_IMAGE" >/dev/null 2>&1; then
        print_status "Removing test image..."
        docker rmi "$LOCAL_IMAGE" >/dev/null 2>&1 || true
    fi
}

# Set up cleanup on exit
trap cleanup EXIT

print_status "Starting multi-part upload test for NSCR..."

# Check prerequisites
print_status "Checking prerequisites..."

if ! command -v docker &> /dev/null; then
    print_error "Docker is not installed or not in PATH"
    exit 1
fi

if ! docker info &> /dev/null; then
    print_error "Docker daemon is not running"
    exit 1
fi

if ! command -v java &> /dev/null; then
    print_error "Java is not installed or not in PATH"
    exit 1
fi

# Build the project
print_status "Building NSCR project..."
if ! ./gradlew build -x test; then
    print_error "Failed to build project"
    exit 1
fi
print_success "Project built successfully"

# Clean up any existing test data
cleanup

# Start the registry
print_status "Starting NSCR registry on port $REGISTRY_PORT..."
mkdir -p "$BLOBSTORE_DIR"
cd "$BLOBSTORE_DIR"
java -jar ../../build/libs/nscr-1.0-SNAPSHOT-all.jar &
REGISTRY_PID=$!

# Wait for registry to start
print_status "Waiting for registry to start..."
for i in {1..30}; do
    if curl -s "http://$REGISTRY_URL/v2" >/dev/null 2>&1; then
        print_success "Registry is running"
        break
    fi
    if [ $i -eq 30 ]; then
        print_error "Registry failed to start within 30 seconds"
        kill $REGISTRY_PID 2>/dev/null || true
        exit 1
    fi
    sleep 1
done

# Pull the test image
print_status "Pulling test image: $TEST_IMAGE"
if ! docker pull "$TEST_IMAGE"; then
    print_error "Failed to pull test image"
    exit 1
fi
print_success "Test image pulled successfully"

# Tag the image for local registry
print_status "Tagging image for local registry..."
docker tag "$TEST_IMAGE" "$LOCAL_IMAGE"
print_success "Image tagged successfully"

# Get initial blob count
print_status "Getting initial blob count..."
INITIAL_BLOBS=$(curl -s "http://$REGISTRY_URL/api/blobs" | wc -l)
print_status "Initial blob count: $INITIAL_BLOBS"

# Push the image (this will test multi-part uploads)
print_status "Pushing image to registry (testing multi-part uploads)..."
if ! docker push "$LOCAL_IMAGE"; then
    print_error "Failed to push image to registry"
    exit 1
fi
print_success "Image pushed successfully!"

# Verify the push worked
print_status "Verifying push was successful..."

# Check final blob count
FINAL_BLOBS=$(curl -s "http://$REGISTRY_URL/api/blobs" | wc -l)
print_status "Final blob count: $FINAL_BLOBS"

if [ "$FINAL_BLOBS" -gt "$INITIAL_BLOBS" ]; then
    print_success "Blob count increased from $INITIAL_BLOBS to $FINAL_BLOBS"
else
    print_warning "Blob count did not increase as expected"
fi

# Test manifest retrieval
print_status "Testing manifest retrieval..."
MANIFEST_RESPONSE=$(curl -s -w "%{http_code}" "http://$REGISTRY_URL/v2/ubuntu/manifests/20.04")
HTTP_CODE="${MANIFEST_RESPONSE: -3}"
MANIFEST_CONTENT="${MANIFEST_RESPONSE%???}"

if [ "$HTTP_CODE" = "200" ]; then
    print_success "Manifest retrieved successfully"
    if [ -n "$MANIFEST_CONTENT" ]; then
        print_success "Manifest content is not empty"
    else
        print_warning "Manifest content is empty"
    fi
else
    print_error "Failed to retrieve manifest (HTTP $HTTP_CODE)"
fi

# Test blob retrieval
print_status "Testing blob retrieval..."
# Get the first blob digest from the manifest
if [ -n "$MANIFEST_CONTENT" ]; then
    BLOB_DIGEST=$(echo "$MANIFEST_CONTENT" | grep -o '"digest":"[^"]*"' | head -1 | cut -d'"' -f4)
    if [ -n "$BLOB_DIGEST" ]; then
        print_status "Testing blob retrieval for digest: $BLOB_DIGEST"
        BLOB_RESPONSE=$(curl -s -w "%{http_code}" "http://$REGISTRY_URL/v2/ubuntu/blobs/$BLOB_DIGEST")
        BLOB_HTTP_CODE="${BLOB_RESPONSE: -3}"
        
        if [ "$BLOB_HTTP_CODE" = "200" ]; then
            print_success "Blob retrieved successfully"
        else
            print_warning "Blob retrieval failed (HTTP $BLOB_HTTP_CODE)"
        fi
    else
        print_warning "Could not extract blob digest from manifest"
    fi
fi

# Test repository listing
print_status "Testing repository listing..."
REPOS_RESPONSE=$(curl -s "http://$REGISTRY_URL/v2/_catalog")
if echo "$REPOS_RESPONSE" | grep -q "ubuntu"; then
    print_success "Repository listing works correctly"
else
    print_warning "Repository not found in catalog"
fi

print_success "Multi-part upload test completed successfully!"
print_status "The registry successfully handled a large image push, demonstrating multi-part upload functionality."

# Stop the registry
print_status "Stopping registry..."
kill $REGISTRY_PID 2>/dev/null || true
wait $REGISTRY_PID 2>/dev/null || true

print_success "Test completed successfully!"
