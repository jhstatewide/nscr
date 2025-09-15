#!/bin/bash

# Cleanup script for test data
# This script removes all test data directories and files

echo "Cleaning up test data..."

# Remove all test data directories
if [ -d "./tmp/test-data" ]; then
    echo "Removing ./tmp/test-data directory..."
    rm -rf ./tmp/test-data
    echo "Test data cleanup completed."
else
    echo "No test data directory found."
fi

# Remove any other temporary files
if [ -d "./tmp" ]; then
    echo "Checking for other temporary files..."
    find ./tmp -name "*.db" -o -name "*.trace.db" -o -name "*.mv.db" | while read file; do
        echo "Removing: $file"
        rm -f "$file"
    done
fi

echo "Cleanup completed."
