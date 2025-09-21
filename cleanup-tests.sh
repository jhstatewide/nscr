#!/bin/bash

# Simple wrapper script for test cleanup
# This script provides an easy way to clean up test artifacts

echo "🧹 NSCR Test Cleanup"
echo "==================="

# Check if we're in the right directory
if [ ! -f "build.gradle.kts" ]; then
    echo "❌ Error: This script must be run from the NSCR project root directory"
    echo "   (where build.gradle.kts is located)"
    exit 1
fi

# Set JAVA_HOME if not already set
if [ -z "$JAVA_HOME" ]; then
    if [ -d "/home/josh/.asdf/installs/java/adoptopenjdk-17.0.16+8" ]; then
        export JAVA_HOME="/home/josh/.asdf/installs/java/adoptopenjdk-17.0.16+8"
        echo "🔧 Set JAVA_HOME to: $JAVA_HOME"
    else
        echo "⚠️  Warning: JAVA_HOME not set and default path not found"
        echo "   You may need to set JAVA_HOME manually"
    fi
fi

echo "🚀 Running Gradle cleanup tasks..."
./gradlew cleanupTestData cleanupAllTestDockerImages

echo ""
echo "✅ Cleanup completed!"
echo ""
echo "💡 Tips:"
echo "   - Run this script after running tests to clean up artifacts"
echo "   - The .keep file is always preserved to maintain directory structure"
echo "   - You can also run: ./gradlew cleanupTestData"
