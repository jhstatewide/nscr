#!/bin/bash

# Test script for NSCR (New and Shiny Container Registry)
# This script pulls a Debian image from Docker Hub and pushes it to the local NSCR registry

set -e  # Exit on any error

# Configuration
REGISTRY_PORT=7000
REGISTRY_URL="localhost:${REGISTRY_PORT}"
TEST_IMAGE="debian:bullseye-slim"
LOCAL_IMAGE="${REGISTRY_URL}/debian:bullseye-slim"
BLOBSTORE_DIR="/tmp/nscr_test_blobstore"

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

# Pull the test image from Docker Hub
print_status "Pulling $TEST_IMAGE from Docker Hub..."
if ! docker pull "$TEST_IMAGE"; then
    print_error "Failed to pull $TEST_IMAGE from Docker Hub"
    exit 1
fi
print_success "Successfully pulled $TEST_IMAGE"

# Tag the image for our local registry
print_status "Tagging image for local registry..."
if ! docker tag "$TEST_IMAGE" "$LOCAL_IMAGE"; then
    print_error "Failed to tag image for local registry"
    exit 1
fi
print_success "Successfully tagged image as $LOCAL_IMAGE"

# Push the image to our local registry
print_status "Pushing $LOCAL_IMAGE to NSCR registry..."
if ! docker push "$LOCAL_IMAGE"; then
    print_error "Failed to push image to NSCR registry"
    exit 1
fi
print_success "Successfully pushed $LOCAL_IMAGE to NSCR registry"

# Verify the push by checking if we can pull it back
print_status "Verifying push by pulling image back from NSCR registry..."
if ! docker pull "$LOCAL_IMAGE"; then
    print_error "Failed to pull image back from NSCR registry"
    exit 1
fi
print_success "Successfully pulled image back from NSCR registry"

# Check the registry API to see stored blobs
print_status "Checking stored blobs in registry..."
BLOB_COUNT=$(curl -s "http://${REGISTRY_URL}/api/blobs" | wc -l)
print_success "Registry contains $BLOB_COUNT blobs"

# Test manifest retrieval
print_status "Testing manifest retrieval..."
MANIFEST=$(curl -s "http://${REGISTRY_URL}/v2/debian/manifests/bullseye-slim")
if [ -z "$MANIFEST" ]; then
    print_error "Failed to retrieve manifest"
    exit 1
fi
print_success "Successfully retrieved manifest (${#MANIFEST} characters)"

# Clean up Docker images
print_status "Cleaning up Docker images..."
docker rmi "$LOCAL_IMAGE" 2>/dev/null || true
docker rmi "$TEST_IMAGE" 2>/dev/null || true

print_success "Test completed successfully!"
print_status "NSCR registry is working correctly with Docker Hub images"
