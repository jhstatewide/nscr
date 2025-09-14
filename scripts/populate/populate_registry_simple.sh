#!/bin/bash

# Simple NSCR Registry Population Script
# This script pulls just a few essential small Docker images

set -e

REGISTRY_URL="localhost:7000"

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

print_status() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

# Check if registry is running
print_status "Checking if NSCR registry is running..."
if ! curl -s "http://$REGISTRY_URL/api/web/status" > /dev/null 2>&1; then
    echo "Error: NSCR registry is not running at http://$REGISTRY_URL"
    echo "Please start the registry first with: ./gradlew run"
    exit 1
fi

print_success "NSCR registry is running!"

# Simple images to pull
images=("alpine:latest" "hello-world:latest" "nginx:alpine")

for image in "${images[@]}"; do
    image_name=$(echo "$image" | cut -d':' -f1)
    tag=$(echo "$image" | cut -d':' -f2)
    local_name=$(echo "$image_name" | sed 's|/|-|g')
    
    print_status "Processing: $image_name:$tag"
    
    # Pull image
    docker pull "$image_name:$tag"
    
    # Tag for local registry
    docker tag "$image_name:$tag" "$REGISTRY_URL/$local_name:$tag"
    
    # Push to registry
    docker push "$REGISTRY_URL/$local_name:$tag"
    
    # Clean up local tag
    docker rmi "$REGISTRY_URL/$local_name:$tag"
    
    print_success "Completed: $image_name:$tag"
done

print_success "Registry population completed!"
echo "Check your registry at: http://$REGISTRY_URL"
