#!/bin/bash

# Cleanup script for Docker images created by NSCR tests
# This script safely removes only test-related images

set -e

echo "🐳 NSCR Docker Image Cleanup"
echo "============================"

# Function to clean up images with a specific pattern
cleanup_images() {
    local pattern="$1"
    local description="$2"
    
    echo "🗑️  Cleaning up $description..."
    
    # Get list of images matching the pattern
    local images
    images=$(docker images --format "{{.Repository}}:{{.Tag}}" | grep -E "$pattern" 2>/dev/null || true)
    
    if [ -z "$images" ]; then
        echo "   No $description found"
        return 0
    fi
    
    # Count images
    local count
    count=$(echo "$images" | wc -l)
    echo "   Found $count $description to clean up"
    
    # Remove each image
    echo "$images" | while read -r image; do
        if [ -n "$image" ]; then
            echo "   Removing: $image"
            if docker rmi -f "$image" 2>/dev/null; then
                echo "   ✓ Successfully removed: $image"
            else
                echo "   ⚠️  Could not remove: $image (may be in use)"
            fi
        fi
    done
    
    echo "   ✅ $description cleanup completed"
}

# Clean up nscr-test-registry-* images (new test format)
cleanup_images "nscr-test-registry-*" "nscr-test-registry-* images"

# Clean up localhost:* images that contain nscr-test-* (old test format)
# This is safer than cleaning all localhost:* images
cleanup_images "localhost:.*nscr-test-" "localhost:*nscr-test-* images"

echo ""
echo "✅ All test Docker image cleanup completed!"
echo ""
echo "💡 Note: This only removes test-related images."
echo "   Your real Docker images are safe and untouched."
