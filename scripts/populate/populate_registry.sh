#!/bin/bash

# NSCR Registry Population Script
# This script pulls popular small Docker images and pushes them to the local NSCR registry

set -e

REGISTRY_URL="localhost:7000"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEMP_DIR="$SCRIPT_DIR/temp_images"

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

# Function to check if registry is running
check_registry() {
    print_status "Checking if NSCR registry is running..."
    if curl -s "http://$REGISTRY_URL/api/web/status" > /dev/null 2>&1; then
        print_success "NSCR registry is running at http://$REGISTRY_URL"
    else
        print_error "NSCR registry is not running at http://$REGISTRY_URL"
        print_error "Please start the registry first with: ./gradlew run"
        exit 1
    fi
}

# Function to create temp directory
setup_temp_dir() {
    print_status "Setting up temporary directory..."
    rm -rf "$TEMP_DIR"
    mkdir -p "$TEMP_DIR"
    print_success "Created temporary directory: $TEMP_DIR"
}

# Function to pull and tag image
pull_and_tag() {
    local image_name="$1"
    local tag="$2"
    local local_name="$3"
    
    print_status "Pulling $image_name:$tag..."
    if docker pull "$image_name:$tag"; then
        print_success "Successfully pulled $image_name:$tag"
        
        print_status "Tagging as $REGISTRY_URL/$local_name:$tag..."
        if docker tag "$image_name:$tag" "$REGISTRY_URL/$local_name:$tag"; then
            print_success "Successfully tagged $REGISTRY_URL/$local_name:$tag"
        else
            print_error "Failed to tag $image_name:$tag"
            return 1
        fi
    else
        print_error "Failed to pull $image_name:$tag"
        return 1
    fi
}

# Function to push image to registry
push_to_registry() {
    local local_name="$1"
    local tag="$2"
    
    print_status "Pushing $REGISTRY_URL/$local_name:$tag to registry..."
    if docker push "$REGISTRY_URL/$local_name:$tag"; then
        print_success "Successfully pushed $REGISTRY_URL/$local_name:$tag"
    else
        print_error "Failed to push $REGISTRY_URL/$local_name:$tag"
        return 1
    fi
}

# Function to clean up local images
cleanup_local() {
    local local_name="$1"
    local tag="$2"
    
    print_status "Cleaning up local image $REGISTRY_URL/$local_name:$tag..."
    docker rmi "$REGISTRY_URL/$local_name:$tag" 2>/dev/null || true
}

# Function to show registry status
show_registry_status() {
    print_status "Checking registry status..."
    if response=$(curl -s "http://$REGISTRY_URL/api/web/status"); then
        echo "$response" | jq '.' 2>/dev/null || echo "$response"
    else
        print_error "Failed to get registry status"
    fi
}

# Main execution
main() {
    print_status "Starting NSCR Registry Population Script"
    print_status "=========================================="
    
    # Check if docker is running
    if ! docker info > /dev/null 2>&1; then
        print_error "Docker is not running. Please start Docker first."
        exit 1
    fi
    
    # Check if jq is available
    if ! command -v jq > /dev/null 2>&1; then
        print_warning "jq is not installed. JSON output will not be formatted."
    fi
    
    check_registry
    setup_temp_dir
    
    # Define images to pull (small, popular images)
    declare -a images=(
        "alpine:latest"
        "hello-world:latest"
        "nginx:alpine"
        "redis:alpine"
        "postgres:alpine"
        "node:alpine"
        "python:alpine"
        "busybox:latest"
        "httpd:alpine"
        "memcached:alpine"
    )
    
    print_status "Will pull and push ${#images[@]} images to the registry"
    print_status "Images: ${images[*]}"
    echo
    
    # Process each image
    for image in "${images[@]}"; do
        image_name=$(echo "$image" | cut -d':' -f1)
        tag=$(echo "$image" | cut -d':' -f2)
        local_name=$(echo "$image_name" | sed 's|/|-|g')  # Replace / with - for local naming
        
        print_status "Processing: $image_name:$tag"
        
        if pull_and_tag "$image_name" "$tag" "$local_name"; then
            if push_to_registry "$local_name" "$tag"; then
                cleanup_local "$local_name" "$tag"
                print_success "Completed processing $image_name:$tag"
            else
                print_error "Failed to push $image_name:$tag"
            fi
        else
            print_error "Failed to pull $image_name:$tag"
        fi
        
        echo "----------------------------------------"
    done
    
    # Clean up temp directory
    print_status "Cleaning up temporary directory..."
    rm -rf "$TEMP_DIR"
    
    print_success "Registry population completed!"
    echo
    show_registry_status
    
    print_status "You can now view the populated registry at: http://$REGISTRY_URL"
}

# Run main function
main "$@"
