#!/bin/bash

# NSCR Docker Build Script
# Builds the NSCR Docker image

set -e

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}Building NSCR Docker image...${NC}"

# Build the image
docker build -f docker/Dockerfile -t nscr:latest .

echo -e "${GREEN}✅ NSCR Docker image built successfully!${NC}"
echo ""
echo "To run the container:"
echo "  docker run -d --name nscr-registry -p 7000:7000 -v nscr_data:/app/data nscr:latest"
echo ""
echo "Or use docker-compose:"
echo "  docker-compose -f docker/docker-compose.simple.yml up -d"
