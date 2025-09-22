#!/usr/bin/env ruby

# Example usage of the Ruby torture test client
# This script demonstrates various ways to use the NSCR external API client

require_relative 'external_torture_test'

# Example 1: Basic monitoring (with optional authentication)
puts "=== Example 1: Basic Registry Monitoring ==="
# Try with authentication first, fall back to anonymous if not provided
tester = NSCRTortureTest.new('http://localhost:7000', 'admin', 'admin')

# Get current registry state
state = tester.get_registry_state
if state
  puts "Current registry state:"
  puts "  Repositories: #{state.total_repositories}"
  puts "  Manifests: #{state.total_manifests}"
  puts "  Blobs: #{state.total_blobs}"
  puts "  Active Sessions: #{state.active_sessions}"
  puts "  Health: #{state.health_status}"
else
  puts "Failed to get registry state"
end

# Example 2: Health check
puts "\n=== Example 2: Health Check ==="
health = tester.get_health
if health
  puts "Registry health: #{health['status']}"
  puts "Uptime: #{health['uptime']}ms"
  puts "Checks: #{health['checks']}"
else
  puts "Failed to get health status"
end

# Example 3: Repository details
puts "\n=== Example 3: Repository Details ==="
if state && !state.repositories.empty?
  repo_name = state.repositories.first['name']
  puts "Getting details for repository: #{repo_name}"
  
  repo_details = tester.get_repository_details(repo_name)
  if repo_details
    puts "  Tag count: #{repo_details['tagCount']}"
    puts "  Tags: #{repo_details['tags'].map { |t| t['tag'] }.join(', ')}"
  else
    puts "Failed to get repository details"
  end
end

# Example 4: Active sessions
puts "\n=== Example 4: Active Sessions ==="
sessions = tester.get_active_sessions
if sessions
  puts "Total active sessions: #{sessions['totalActiveSessions']}"
  sessions['activeSessions'].each do |session|
    puts "  Session #{session['id']}: #{session['duration']}ms, #{session['blobCount']} blobs"
  end
else
  puts "Failed to get active sessions"
end

# Example 5: Blob information
puts "\n=== Example 5: Blob Information ==="
blob_info = tester.get_blob_info
if blob_info
  puts "Total blobs: #{blob_info['totalBlobs']}"
  puts "Sample blobs:"
  blob_info['blobs'].first(3).each do |blob|
    puts "  #{blob['digest']}: #{blob['size']} bytes"
  end
else
  puts "Failed to get blob information"
end

# Example 6: Quick stress test
puts "\n=== Example 6: Quick Stress Test (10 seconds) ==="
puts "Running stress test with 5 concurrent workers for 10 seconds..."
tester.run_tests('stress', 10, 5)
tester.print_summary

puts "\n=== All Examples Complete ==="
