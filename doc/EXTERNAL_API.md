# NSCR External API Documentation

This document describes the machine-readable API endpoints available for external monitoring and torture testing of the NSCR Docker registry.

## Base URL

All API endpoints are available at: `http://localhost:7000/api/`

## Authentication

Most API endpoints require authentication. Use basic authentication with the configured credentials:

```bash
curl -u username:password http://localhost:7000/api/registry/state
```

## API Endpoints

### 1. Comprehensive Registry State

**Endpoint:** `GET /api/registry/state`

Returns a complete snapshot of the registry state, including all repositories, tags, active sessions, and health metrics.

**Response Example:**
```json
{
  "timestamp": 1703123456789,
  "registryVersion": "2.0",
  "summary": {
    "totalRepositories": 3,
    "totalManifests": 15,
    "totalBlobs": 45,
    "unreferencedBlobs": 2,
    "orphanedManifests": 0,
    "estimatedSpaceToFree": 1048576
  },
  "repositories": [
    {
      "name": "nginx",
      "tagCount": 5,
      "tags": ["latest", "1.21", "1.20", "alpine", "stable"]
    },
    {
      "name": "postgres",
      "tagCount": 3,
      "tags": ["latest", "13", "14"]
    }
  ],
  "activeSessions": {
    "count": 2,
    "sessions": [
      {
        "id": "session-123",
        "startTime": 1703123400000,
        "lastActivity": 1703123450000,
        "blobCount": 3
      }
    ]
  },
  "health": {
    "status": "healthy",
    "uptime": 3600000,
    "logStreamClients": 1
  }
}
```

### 2. Repository Details

**Endpoint:** `GET /api/registry/repositories/{name}`

Returns detailed information about a specific repository, including tag details and manifest information.

**Response Example:**
```json
{
  "name": "nginx",
  "tagCount": 5,
  "tags": [
    {
      "tag": "latest",
      "hasManifest": true,
      "digest": "sha256:abc123..."
    },
    {
      "tag": "1.21",
      "hasManifest": true,
      "digest": "sha256:def456..."
    }
  ],
  "timestamp": 1703123456789
}
```

### 3. Blob Information

**Endpoint:** `GET /api/registry/blobs`

Returns information about all blobs stored in the registry.

**Response Example:**
```json
{
  "totalBlobs": 45,
  "blobs": [
    {
      "digest": "sha256:abc123...",
      "size": 1048576,
      "created": 1703123400000
    },
    {
      "digest": "sha256:def456...",
      "size": 2097152,
      "created": 1703123410000
    }
  ],
  "timestamp": 1703123456789
}
```

### 4. Active Sessions

**Endpoint:** `GET /api/registry/sessions`

Returns information about currently active upload sessions.

**Response Example:**
```json
{
  "activeSessions": [
    {
      "id": "session-123",
      "startTime": 1703123400000,
      "lastActivity": 1703123450000,
      "blobCount": 3,
      "duration": 56789
    }
  ],
  "totalActiveSessions": 1,
  "timestamp": 1703123456789
}
```

### 5. Health Check

**Endpoint:** `GET /api/registry/health`

Performs comprehensive health checks and returns detailed status information.

**Response Example:**
```json
{
  "status": "healthy",
  "timestamp": 1703123456789,
  "uptime": 3600000,
  "checks": {
    "repositoryListing": true,
    "statisticsAccess": true,
    "databaseConnectivity": true
  },
  "metrics": {
    "totalRepositories": 3,
    "totalManifests": 15,
    "totalBlobs": 45,
    "activeSessions": 1,
    "logStreamClients": 1
  },
  "warnings": []
}
```

### 6. Garbage Collection Statistics

**Endpoint:** `GET /api/garbage-collect/stats`

Returns garbage collection statistics and space usage information.

**Response Example:**
```json
{
  "totalBlobs": 45,
  "totalManifests": 15,
  "unreferencedBlobs": 2,
  "orphanedManifests": 0,
  "estimatedSpaceToFree": 1048576
}
```

## Torture Testing Use Cases

### 1. State Monitoring During Concurrent Operations

Use the `/api/registry/state` endpoint to monitor registry state during concurrent operations:

```bash
# Monitor state every 5 seconds during torture test
while true; do
  curl -s -u username:password http://localhost:7000/api/registry/state | jq '.summary'
  sleep 5
done
```

### 2. Repository Consistency Checking

Verify repository consistency after operations:

```bash
# Check all repositories for consistency
curl -s -u username:password http://localhost:7000/api/registry/state | \
  jq -r '.repositories[].name' | \
  while read repo; do
    echo "Checking repository: $repo"
    curl -s -u username:password "http://localhost:7000/api/registry/repositories/$repo" | \
      jq '.tags[] | select(.hasManifest == false)'
  done
```

### 3. Session Monitoring

Monitor active upload sessions during concurrent pushes:

```bash
# Monitor active sessions
curl -s -u username:password http://localhost:7000/api/registry/sessions | \
  jq '.activeSessions[] | {id, duration, blobCount}'
```

### 4. Health Monitoring

Continuous health monitoring:

```bash
# Health check with alerts
while true; do
  health=$(curl -s -u username:password http://localhost:7000/api/registry/health)
  status=$(echo "$health" | jq -r '.status')
  if [ "$status" != "healthy" ]; then
    echo "ALERT: Registry status is $status"
    echo "$health" | jq '.'
  fi
  sleep 10
done
```

### 5. Blob Tracking

Monitor blob creation and deletion:

```bash
# Track blob count over time
while true; do
  timestamp=$(date -Iseconds)
  blob_count=$(curl -s -u username:password http://localhost:7000/api/registry/blobs | jq '.totalBlobs')
  echo "$timestamp,$blob_count"
  sleep 5
done
```

## Error Handling

All endpoints return appropriate HTTP status codes:

- `200 OK` - Success
- `401 Unauthorized` - Authentication required
- `404 Not Found` - Resource not found
- `500 Internal Server Error` - Server error

Error responses include detailed error information:

```json
{
  "error": "Failed to get registry state",
  "message": "Database connection failed"
}
```

## Rate Limiting

The API endpoints are designed for monitoring and testing purposes. For high-frequency monitoring, consider:

1. Using appropriate delays between requests
2. Caching responses when possible
3. Using the health check endpoint for basic monitoring
4. Using the comprehensive state endpoint for detailed analysis

## Integration Examples

### Python Client Example

```python
import requests
import json
import time

class NSCRMonitor:
    def __init__(self, base_url, username, password):
        self.base_url = base_url
        self.auth = (username, password)
    
    def get_registry_state(self):
        response = requests.get(f"{self.base_url}/api/registry/state", auth=self.auth)
        response.raise_for_status()
        return response.json()
    
    def get_health(self):
        response = requests.get(f"{self.base_url}/api/registry/health", auth=self.auth)
        response.raise_for_status()
        return response.json()
    
    def monitor_continuously(self, interval=5):
        while True:
            try:
                state = self.get_registry_state()
                health = self.get_health()
                
                print(f"Repositories: {state['summary']['totalRepositories']}")
                print(f"Health: {health['status']}")
                print(f"Active Sessions: {state['activeSessions']['count']}")
                
            except Exception as e:
                print(f"Error: {e}")
            
            time.sleep(interval)

# Usage
monitor = NSCRMonitor("http://localhost:7000", "username", "password")
monitor.monitor_continuously()
```

### Bash Monitoring Script

```bash
#!/bin/bash

REGISTRY_URL="http://localhost:7000"
USERNAME="username"
PASSWORD="password"

monitor_registry() {
    while true; do
        echo "=== Registry Status $(date) ==="
        
        # Get health status
        health=$(curl -s -u "$USERNAME:$PASSWORD" "$REGISTRY_URL/api/registry/health")
        status=$(echo "$health" | jq -r '.status')
        echo "Health: $status"
        
        # Get summary
        state=$(curl -s -u "$USERNAME:$PASSWORD" "$REGISTRY_URL/api/registry/state")
        echo "Repositories: $(echo "$state" | jq -r '.summary.totalRepositories')"
        echo "Manifests: $(echo "$state" | jq -r '.summary.totalManifests')"
        echo "Blobs: $(echo "$state" | jq -r '.summary.totalBlobs')"
        echo "Active Sessions: $(echo "$state" | jq -r '.activeSessions.count')"
        
        # Check for warnings
        warnings=$(echo "$health" | jq -r '.warnings[]?' 2>/dev/null)
        if [ -n "$warnings" ]; then
            echo "Warnings: $warnings"
        fi
        
        echo "---"
        sleep 10
    done
}

monitor_registry
```

This API provides comprehensive monitoring capabilities for external torture testing and ensures that external actors can verify registry state consistency during concurrent operations.
