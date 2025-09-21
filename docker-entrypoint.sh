#!/bin/bash
set -e

# Ensure data directory has correct permissions
echo "Initializing NSCR data directory..."

# Create data directory if it doesn't exist
mkdir -p /home/app/data

# Check if we can write to the data directory
if [ ! -w "/home/app/data" ]; then
    echo "Data directory is not writable. Attempting to fix permissions..."
    
    # Try to fix permissions (this will work if the volume is empty or we have the right permissions)
    chown -R app:app /home/app/data 2>/dev/null || {
        echo "Warning: Could not fix data directory permissions automatically."
        echo "If you're using a Docker volume, you may need to:"
        echo "1. Remove the volume: docker volume rm <volume-name>"
        echo "2. Recreate it: docker volume create <volume-name>"
        echo "3. Or run the container with: --user root"
        echo ""
        echo "Continuing anyway - the application may fail if it cannot write to the data directory."
    }
fi

echo "Data directory initialized successfully."
echo "Starting NSCR..."

# Execute the original command
exec "$@"
