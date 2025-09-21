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
    images=$(docker images --filter "reference=$pattern" --format "{{.Repository}}:{{.Tag}}" 2>/dev/null || true)
    
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

# Clean up nscr-test-* images (new test format)
cleanup_images "nscr-test-*" "nscr-test-* images"

# Clean up localhost:* images (old test format)
cleanup_images "localhost:*" "localhost:* images"

echo ""
echo "✅ All test Docker image cleanup completed!"
echo ""
echo "💡 Note: This only removes test-related images."
echo "   Your real Docker images are safe and untouched."
