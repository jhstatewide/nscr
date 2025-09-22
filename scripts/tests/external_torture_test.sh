#!/bin/bash

# External Registry Torture Test Script
# This script demonstrates how to use the NSCR external API for comprehensive
# registry torture testing using simple bash and curl commands.

set -e

# Configuration
REGISTRY_URL="${REGISTRY_URL:-http://localhost:7000}"
USERNAME="${USERNAME:-}"
PASSWORD="${PASSWORD:-}"
DURATION="${DURATION:-60}"
CONCURRENT_WORKERS="${CONCURRENT_WORKERS:-5}"
TEST_TYPE="${TEST_TYPE:-all}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Counters
OPERATION_COUNT=0
ERROR_COUNT=0
STATE_SNAPSHOTS=0

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

# Function to make API calls with optional authentication
api_call() {
    local endpoint="$1"
    local method="${2:-GET}"
    
    # Build curl command with optional authentication
    local curl_cmd="curl -s"
    
    # Add authentication if credentials are provided
    if [ -n "$USERNAME" ] && [ -n "$PASSWORD" ]; then
        curl_cmd="$curl_cmd -u $USERNAME:$PASSWORD"
    fi
    
    # Add SSL options for HTTPS
    if [[ "$REGISTRY_URL" == https://* ]]; then
        curl_cmd="$curl_cmd -k"  # Allow self-signed certificates
    fi
    
    # Add method and URL
    curl_cmd="$curl_cmd -X $method $REGISTRY_URL$endpoint"
    
    # Execute the command
    eval "$curl_cmd"
}

# Function to record operation result
record_operation() {
    local success="$1"
    OPERATION_COUNT=$((OPERATION_COUNT + 1))
    if [ "$success" = "false" ]; then
        ERROR_COUNT=$((ERROR_COUNT + 1))
    fi
}

# Function to get registry state
get_registry_state() {
    local response
    response=$(api_call "/api/registry/state")
    
    if [ $? -eq 0 ] && echo "$response" | jq -e '.summary' >/dev/null 2>&1; then
        echo "$response"
        record_operation "true"
        return 0
    else
        print_error "Failed to get registry state"
        record_operation "false"
        return 1
    fi
}

# Function to get health status
get_health() {
    local response
    response=$(api_call "/api/registry/health")
    
    if [ $? -eq 0 ] && echo "$response" | jq -e '.status' >/dev/null 2>&1; then
        echo "$response"
        record_operation "true"
        return 0
    else
        print_error "Failed to get health status"
        record_operation "false"
        return 1
    fi
}

# Function to get repository details
get_repository_details() {
    local repo_name="$1"
    local response
    response=$(api_call "/api/registry/repositories/$repo_name")
    
    if [ $? -eq 0 ] && echo "$response" | jq -e '.name' >/dev/null 2>&1; then
        echo "$response"
        record_operation "true"
        return 0
    else
        print_error "Failed to get repository details for $repo_name"
        record_operation "false"
        return 1
    fi
}

# Function to get active sessions
get_active_sessions() {
    local response
    response=$(api_call "/api/registry/sessions")
    
    if [ $? -eq 0 ] && echo "$response" | jq -e '.activeSessions' >/dev/null 2>&1; then
        echo "$response"
        record_operation "true"
        return 0
    else
        print_error "Failed to get active sessions"
        record_operation "false"
        return 1
    fi
}

# Function to monitor registry state
monitor_registry_state() {
    local duration="$1"
    print_status "Starting registry state monitoring for $duration seconds"
    
    local start_time=$(date +%s)
    local end_time=$((start_time + duration))
    
    while [ $(date +%s) -lt $end_time ]; do
        local state
        state=$(get_registry_state)
        
        if [ $? -eq 0 ]; then
            local repos=$(echo "$state" | jq -r '.summary.totalRepositories')
            local manifests=$(echo "$state" | jq -r '.summary.totalManifests')
            local blobs=$(echo "$state" | jq -r '.summary.totalBlobs')
            local sessions=$(echo "$state" | jq -r '.activeSessions.count')
            local health=$(echo "$state" | jq -r '.health.status')
            
            print_status "State: repos=$repos, manifests=$manifests, blobs=$blobs, sessions=$sessions, health=$health"
            STATE_SNAPSHOTS=$((STATE_SNAPSHOTS + 1))
        fi
        
        sleep 5
    done
}

# Function to perform health checks
perform_health_checks() {
    local duration="$1"
    print_status "Starting health checks for $duration seconds"
    
    local start_time=$(date +%s)
    local end_time=$((start_time + duration))
    
    while [ $(date +%s) -lt $end_time ]; do
        local health
        health=$(get_health)
        
        if [ $? -eq 0 ]; then
            local status=$(echo "$health" | jq -r '.status')
            if [ "$status" != "healthy" ]; then
                print_warning "Registry health degraded: $status"
                echo "$health" | jq '.'
            else
                print_status "Registry health: $status"
            fi
        fi
        
        sleep 10
    done
}

# Function to perform repository consistency checks
perform_consistency_checks() {
    local duration="$1"
    print_status "Starting repository consistency checks for $duration seconds"
    
    local start_time=$(date +%s)
    local end_time=$((start_time + duration))
    
    while [ $(date +%s) -lt $end_time ]; do
        local state
        state=$(get_registry_state)
        
        if [ $? -eq 0 ]; then
            # Get repository names
            local repos
            repos=$(echo "$state" | jq -r '.repositories[].name')
            
            while IFS= read -r repo_name; do
                if [ -n "$repo_name" ]; then
                    local repo_details
                    repo_details=$(get_repository_details "$repo_name")
                    
                    if [ $? -eq 0 ]; then
                        # Check for consistency issues
                        local tag_count_state
                        tag_count_state=$(echo "$state" | jq -r --arg repo "$repo_name" '.repositories[] | select(.name == $repo) | .tagCount')
                        local tag_count_details
                        tag_count_details=$(echo "$repo_details" | jq -r '.tagCount')
                        
                        if [ "$tag_count_state" != "$tag_count_details" ]; then
                            print_error "Inconsistent tag count for $repo_name: state=$tag_count_state, details=$tag_count_details"
                        fi
                        
                        # Check for manifests without digests
                        local tags_without_digest
                        tags_without_digest=$(echo "$repo_details" | jq -r '.tags[] | select(.hasManifest == true and .digest == null) | .tag')
                        
                        if [ -n "$tags_without_digest" ]; then
                            print_error "Manifest without digest for $repo_name: $tags_without_digest"
                        fi
                    fi
                fi
            done <<< "$repos"
        fi
        
        sleep 15
    done
}

# Function to perform session monitoring
monitor_sessions() {
    local duration="$1"
    print_status "Starting session monitoring for $duration seconds"
    
    local start_time=$(date +%s)
    local end_time=$((start_time + duration))
    
    while [ $(date +%s) -lt $end_time ]; do
        local sessions
        sessions=$(get_active_sessions)
        
        if [ $? -eq 0 ]; then
            local total_sessions
            total_sessions=$(echo "$sessions" | jq -r '.totalActiveSessions')
            print_status "Active sessions: $total_sessions"
            
            # Check for long-running sessions
            local long_sessions
            long_sessions=$(echo "$sessions" | jq -r '.activeSessions[] | select(.duration > 300000) | .id')
            
            if [ -n "$long_sessions" ]; then
                print_warning "Long-running sessions detected: $long_sessions"
            fi
        fi
        
        sleep 20
    done
}

# Function to perform stress test
perform_stress_test() {
    local duration="$1"
    local workers="$2"
    print_status "Starting stress test for $duration seconds with $workers workers"
    
    local start_time=$(date +%s)
    local end_time=$((start_time + duration))
    
    # Start background workers
    for i in $(seq 1 "$workers"); do
        stress_worker "$i" "$end_time" &
    done
    
    # Wait for all workers to complete
    wait
}

# Function for individual stress test worker
stress_worker() {
    local worker_id="$1"
    local end_time="$2"
    
    while [ $(date +%s) -lt $end_time ]; do
        # Randomly choose an operation
        local operations=("get_state" "get_health" "get_sessions" "get_repository_details")
        local operation="${operations[$RANDOM % ${#operations[@]}]}"
        
        case "$operation" in
            "get_state")
                get_registry_state >/dev/null
                ;;
            "get_health")
                get_health >/dev/null
                ;;
            "get_sessions")
                get_active_sessions >/dev/null
                ;;
            "get_repository_details")
                local state
                state=$(get_registry_state)
                if [ $? -eq 0 ]; then
                    local repos
                    repos=$(echo "$state" | jq -r '.repositories[].name' | head -1)
                    if [ -n "$repos" ]; then
                        get_repository_details "$repos" >/dev/null
                    fi
                fi
                ;;
        esac
        
        # Random delay between operations
        sleep "$(echo "scale=2; $RANDOM/32767 * 0.9 + 0.1" | bc)"
    done
}

# Function to print summary
print_summary() {
    print_status "=== Torture Test Summary ==="
    print_status "Total operations: $OPERATION_COUNT"
    print_status "Errors: $ERROR_COUNT"
    
    if [ $OPERATION_COUNT -gt 0 ]; then
        local success_rate
        success_rate=$(echo "scale=2; ($OPERATION_COUNT - $ERROR_COUNT) * 100 / $OPERATION_COUNT" | bc)
        print_status "Success rate: ${success_rate}%"
    else
        print_status "Success rate: 0%"
    fi
    
    print_status "State snapshots collected: $STATE_SNAPSHOTS"
}

# Function to check prerequisites
check_prerequisites() {
    print_status "Checking prerequisites..."
    
    # Check if curl is available
    if ! command -v curl >/dev/null 2>&1; then
        print_error "curl is required but not installed"
        exit 1
    fi
    
    # Check if jq is available
    if ! command -v jq >/dev/null 2>&1; then
        print_error "jq is required but not installed"
        exit 1
    fi
    
    # Check if bc is available
    if ! command -v bc >/dev/null 2>&1; then
        print_error "bc is required but not installed"
        exit 1
    fi
    
    # Test registry connectivity
    print_status "Testing registry connectivity..."
    if ! api_call "/api/registry/health" >/dev/null 2>&1; then
        print_error "Cannot connect to registry at $REGISTRY_URL"
        print_error "Please ensure the registry is running and accessible"
        exit 1
    fi
    
    print_success "Prerequisites check passed"
}

# Main function
main() {
    print_status "Starting NSCR External Torture Test"
    print_status "Registry URL: $REGISTRY_URL"
    
    # Log authentication status
    if [ -n "$USERNAME" ] && [ -n "$PASSWORD" ]; then
        print_status "Username: $USERNAME"
    else
        print_warning "No authentication configured - using anonymous access"
    fi
    
    print_status "Test Type: $TEST_TYPE"
    print_status "Duration: $DURATION seconds"
    print_status "Concurrent Workers: $CONCURRENT_WORKERS"
    echo
    
    check_prerequisites
    echo
    
    # Run tests based on type
    case "$TEST_TYPE" in
        "monitor")
            monitor_registry_state "$DURATION"
            ;;
        "consistency")
            perform_consistency_checks "$DURATION"
            ;;
        "stress")
            perform_stress_test "$DURATION" "$CONCURRENT_WORKERS"
            ;;
        "all")
            # Run all tests concurrently
            monitor_registry_state "$DURATION" &
            perform_health_checks "$DURATION" &
            perform_consistency_checks "$DURATION" &
            monitor_sessions "$DURATION" &
            perform_stress_test "$DURATION" "$CONCURRENT_WORKERS" &
            
            # Wait for all tests to complete
            wait
            ;;
        *)
            print_error "Unknown test type: $TEST_TYPE"
            exit 1
            ;;
    esac
    
    echo
    print_summary
}

# Run main function
main "$@"
