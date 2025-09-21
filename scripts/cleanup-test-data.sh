#!/bin/bash

# Cleanup script for NSCR test data
# Removes all test artifacts while preserving the .keep file

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
TEST_DATA_DIR="$PROJECT_ROOT/tmp/test-data"

echo "🧹 Cleaning up test data in $TEST_DATA_DIR..."

if [ ! -d "$TEST_DATA_DIR" ]; then
    echo "❌ Test data directory does not exist: $TEST_DATA_DIR"
    exit 1
fi

# Count directories before cleanup
DIR_COUNT=$(find "$TEST_DATA_DIR" -maxdepth 1 -type d ! -name ".*" ! -path "$TEST_DATA_DIR" | wc -l)
echo "📊 Found $DIR_COUNT test directories to clean up"

# Check if .keep file exists
if [ ! -f "$TEST_DATA_DIR/.keep" ]; then
    echo "⚠️  Warning: .keep file not found in $TEST_DATA_DIR"
    echo "   Creating .keep file to preserve directory structure..."
    cat > "$TEST_DATA_DIR/.keep" << 'EOF'
# This file ensures the tmp/test-data directory is preserved in git
# Test artifacts are automatically cleaned up but this file remains
EOF
fi

# Remove all directories except hidden ones (like .git, .keep, etc.)
REMOVED_COUNT=0
for dir in "$TEST_DATA_DIR"/*; do
    if [ -d "$dir" ] && [ ! -f "$dir" ]; then
        echo "🗑️  Removing: $(basename "$dir")"
        rm -rf "$dir"
        ((REMOVED_COUNT++))
    fi
done

echo "✅ Cleanup complete! Removed $REMOVED_COUNT test directories"
echo "📁 Preserved .keep file to maintain directory structure"

# Verify .keep file is still there
if [ -f "$TEST_DATA_DIR/.keep" ]; then
    echo "✅ .keep file preserved successfully"
else
    echo "❌ Error: .keep file was accidentally removed!"
    exit 1
fi

echo "🎉 Test data cleanup completed successfully!"