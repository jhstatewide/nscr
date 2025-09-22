#!/usr/bin/env ruby

# External Registry Torture Test Client (Ruby)
# This script demonstrates how to use the NSCR external API for comprehensive
# registry torture testing using Ruby fibers for concurrency.

require 'net/http'
require 'uri'
require 'json'
require 'time'
require 'optparse'
require 'logger'
require 'thread'

# Configure logging
logger = Logger.new(STDOUT)
logger.level = Logger::INFO
logger.formatter = proc do |severity, datetime, progname, msg|
  "#{datetime.strftime('%Y-%m-%d %H:%M:%S')} - #{severity} - #{msg}\n"
end

class NSCRTortureTest
  attr_reader :registry_url, :username, :password, :logger
  attr_accessor :operation_count, :error_count, :state_history

  def initialize(registry_url, username = nil, password = nil)
    @registry_url = registry_url.chomp('/')
    @username = username
    @password = password
    @logger = logger
    @operation_count = 0
    @error_count = 0
    @state_history = []
    @mutex = Mutex.new
    
    # Log authentication status
    if @username && @password && !@username.empty? && !@password.empty?
      logger.info("Using authentication: #{@username}")
    else
      logger.info("No authentication configured - using anonymous access")
    end
  end

  # Make HTTP request with optional authentication
  def make_request(endpoint, method = 'GET')
    uri = URI("#{@registry_url}#{endpoint}")
    http = Net::HTTP.new(uri.host, uri.port)
    
    # Configure SSL/TLS for HTTPS
    if uri.scheme == 'https'
      http.use_ssl = true
      http.verify_mode = OpenSSL::SSL::VERIFY_NONE # Allow self-signed certificates
    end
    
    http.read_timeout = 30
    http.open_timeout = 10

    request = case method.upcase
              when 'GET'
                Net::HTTP::Get.new(uri)
              when 'POST'
                Net::HTTP::Post.new(uri)
              when 'DELETE'
                Net::HTTP::Delete.new(uri)
              else
                Net::HTTP::Get.new(uri)
              end

    # Add authentication only if credentials are provided
    if @username && @password && !@username.empty? && !@password.empty?
      request.basic_auth(@username, @password)
    end
    
    request['Accept'] = 'application/json'
    request['Content-Type'] = 'application/json'

    response = http.request(request)
    
    if response.code.to_i == 200
      JSON.parse(response.body)
    else
      logger.error("HTTP #{response.code}: #{response.message}")
      nil
    end
  rescue => e
    logger.error("Request failed: #{e.message}")
    nil
  end

  # Record operation result
  def record_operation(success)
    @mutex.synchronize do
      @operation_count += 1
      @error_count += 1 unless success
    end
  end

  # Get comprehensive registry state
  def get_registry_state
    data = make_request('/api/registry/state')
    if data
      record_operation(true)
      RegistryState.new(
        timestamp: data['timestamp'],
        total_repositories: data.dig('summary', 'totalRepositories') || 0,
        total_manifests: data.dig('summary', 'totalManifests') || 0,
        total_blobs: data.dig('summary', 'totalBlobs') || 0,
        active_sessions: data.dig('activeSessions', 'count') || 0,
        health_status: data.dig('health', 'status') || 'unknown',
        repositories: data['repositories'] || []
      )
    else
      record_operation(false)
      nil
    end
  end

  # Get registry health status
  def get_health
    data = make_request('/api/registry/health')
    if data
      record_operation(true)
      data
    else
      record_operation(false)
      nil
    end
  end

  # Get repository details
  def get_repository_details(repo_name)
    data = make_request("/api/registry/repositories/#{repo_name}")
    if data
      record_operation(true)
      data
    else
      record_operation(false)
      nil
    end
  end

  # Get active sessions
  def get_active_sessions
    data = make_request('/api/registry/sessions')
    if data
      record_operation(true)
      data
    else
      record_operation(false)
      nil
    end
  end

  # Get blob information
  def get_blob_info
    data = make_request('/api/registry/blobs')
    if data
      record_operation(true)
      data
    else
      record_operation(false)
      nil
    end
  end

  # Calculate success rate
  def success_rate
    return 0.0 if @operation_count == 0
    ((@operation_count - @error_count).to_f / @operation_count * 100).round(2)
  end

  # Monitor registry state using fibers
  def monitor_registry_state(duration)
    logger.info("Starting registry state monitoring for #{duration} seconds")
    start_time = Time.now

    Fiber.new do
      while Time.now - start_time < duration
        state = get_registry_state
        if state
          @state_history << state
          logger.info("State: repos=#{state.total_repositories}, " \
                     "manifests=#{state.total_manifests}, " \
                     "blobs=#{state.total_blobs}, " \
                     "sessions=#{state.active_sessions}, " \
                     "health=#{state.health_status}")
        end

        # Yield control and sleep
        Fiber.yield
        sleep(5)
      end
    end
  end

  # Perform health checks using fibers
  def perform_health_checks(duration)
    logger.info("Starting health checks for #{duration} seconds")
    start_time = Time.now

    Fiber.new do
      while Time.now - start_time < duration
        health = get_health
        if health
          status = health['status']
          if status != 'healthy'
            logger.warn("Registry health degraded: #{status}")
            logger.warn("Health details: #{health.to_json}")
          else
            logger.debug("Registry health: #{status}")
          end
        end

        Fiber.yield
        sleep(10)
      end
    end
  end

  # Perform repository consistency checks using fibers
  def perform_consistency_checks(duration)
    logger.info("Starting repository consistency checks for #{duration} seconds")
    start_time = Time.now

    Fiber.new do
      while Time.now - start_time < duration
        state = get_registry_state
        if state
          state.repositories.each do |repo|
            repo_name = repo['name']
            repo_details = get_repository_details(repo_name)

            if repo_details
              # Check for consistency issues
              tag_count_state = repo['tagCount']
              tag_count_details = repo_details['tagCount']

              if tag_count_state != tag_count_details
                logger.error("Inconsistent tag count for #{repo_name}: " \
                           "state=#{tag_count_state}, details=#{tag_count_details}")
              end

              # Check for manifests without digests
              repo_details['tags']&.each do |tag|
                if tag['hasManifest'] && !tag['digest']
                  logger.error("Manifest without digest for #{repo_name}:#{tag['tag']}")
                end
              end
            end
          end
        end

        Fiber.yield
        sleep(15)
      end
    end
  end

  # Monitor active sessions using fibers
  def monitor_sessions(duration)
    logger.info("Starting session monitoring for #{duration} seconds")
    start_time = Time.now

    Fiber.new do
      while Time.now - start_time < duration
        sessions = get_active_sessions
        if sessions
          active_sessions = sessions['activeSessions'] || []
          total_sessions = sessions['totalActiveSessions'] || 0

          logger.info("Active sessions: #{total_sessions}")

          active_sessions.each do |session|
            session_id = session['id']
            duration_ms = session['duration']
            blob_count = session['blobCount']

            logger.debug("Session #{session_id}: duration=#{duration_ms}ms, blobs=#{blob_count}")

            # Check for long-running sessions
            if duration_ms > 300_000 # 5 minutes
              logger.warn("Long-running session detected: #{session_id} (#{duration_ms}ms)")
            end
          end
        end

        Fiber.yield
        sleep(20)
      end
    end
  end

  # Perform stress test using fibers
  def perform_stress_test(duration, concurrent_requests)
    logger.info("Starting stress test for #{duration} seconds with #{concurrent_requests} concurrent requests")
    start_time = Time.now

    # Create multiple stress worker fibers
    workers = (1..concurrent_requests).map do |worker_id|
      stress_worker(worker_id, start_time, duration)
    end

    workers
  end

  # Individual stress test worker fiber
  def stress_worker(worker_id, start_time, duration)
    Fiber.new do
      while Time.now - start_time < duration
        begin
          # Randomly choose an operation
          operations = %w[get_state get_health get_sessions get_repository_details get_blob_info]
          operation = operations.sample

          case operation
          when 'get_state'
            state = get_registry_state
            logger.debug("Worker #{worker_id}: get_state #{state ? 'success' : 'failed'}")

          when 'get_health'
            health = get_health
            logger.debug("Worker #{worker_id}: get_health #{health ? 'success' : 'failed'}")

          when 'get_sessions'
            sessions = get_active_sessions
            logger.debug("Worker #{worker_id}: get_sessions #{sessions ? 'success' : 'failed'}")

          when 'get_repository_details'
            state = get_registry_state
            if state && !state.repositories.empty?
              repo_name = state.repositories.sample['name']
              details = get_repository_details(repo_name)
              logger.debug("Worker #{worker_id}: get_repository_details #{details ? 'success' : 'failed'}")
            end

          when 'get_blob_info'
            blob_info = get_blob_info
            logger.debug("Worker #{worker_id}: get_blob_info #{blob_info ? 'success' : 'failed'}")
          end

          # Random delay between operations
          Fiber.yield
          sleep(rand(0.1..1.0))

        rescue => e
          logger.error("Stress worker #{worker_id} error: #{e.message}")
          record_operation(false)
        end
      end
    end
  end

  # Analyze state history for anomalies
  def analyze_state_history
    logger.info("Analyzing state history for anomalies...")

    return logger.warn("Insufficient state history for analysis") if @state_history.length < 2

    # Check for sudden changes in metrics
    (1...@state_history.length).each do |i|
      prev_state = @state_history[i - 1]
      curr_state = @state_history[i]

      # Check for sudden drops in repositories (potential data loss)
      if curr_state.total_repositories < prev_state.total_repositories
        logger.warn("Repository count decreased: #{prev_state.total_repositories} -> #{curr_state.total_repositories}")
      end

      # Check for sudden drops in manifests
      if curr_state.total_manifests < prev_state.total_manifests
        logger.warn("Manifest count decreased: #{prev_state.total_manifests} -> #{curr_state.total_manifests}")
      end

      # Check for health status changes
      if prev_state.health_status == 'healthy' && curr_state.health_status != 'healthy'
        logger.warn("Health status degraded: #{prev_state.health_status} -> #{curr_state.health_status}")
      end
    end
  end

  # Print test summary
  def print_summary
    logger.info("=== Torture Test Summary ===")
    logger.info("Total operations: #{@operation_count}")
    logger.info("Errors: #{@error_count}")
    logger.info("Success rate: #{success_rate}%")
    logger.info("State snapshots collected: #{@state_history.length}")

    if @state_history.any?
      first_state = @state_history.first
      last_state = @state_history.last
      logger.info("Initial state: repos=#{first_state.total_repositories}, " \
                 "manifests=#{first_state.total_manifests}, blobs=#{first_state.total_blobs}")
      logger.info("Final state: repos=#{last_state.total_repositories}, " \
                 "manifests=#{last_state.total_manifests}, blobs=#{last_state.total_blobs}")
    end
  end

  # Run all tests using fiber scheduler
  def run_tests(test_type, duration, concurrent_requests)
    logger.info("Starting #{test_type} torture test for #{duration} seconds")

    fibers = []

    case test_type
    when 'monitor'
      fibers << monitor_registry_state(duration)

    when 'consistency'
      fibers << perform_consistency_checks(duration)

    when 'stress'
      fibers.concat(perform_stress_test(duration, concurrent_requests))

    when 'all'
      # Run all test types concurrently
      fibers << monitor_registry_state(duration)
      fibers << perform_health_checks(duration)
      fibers << perform_consistency_checks(duration)
      fibers << monitor_sessions(duration)
      fibers.concat(perform_stress_test(duration, concurrent_requests))
    end

    # Run all fibers using a simple scheduler
    run_fiber_scheduler(fibers, duration)
  end

  # Simple fiber scheduler
  def run_fiber_scheduler(fibers, duration)
    start_time = Time.now
    active_fibers = fibers.dup

    while Time.now - start_time < duration && active_fibers.any?
      active_fibers.each do |fiber|
        begin
          fiber.resume if fiber.alive?
        rescue FiberError
          # Fiber completed or error occurred
        end
      end

      # Remove completed fibers
      active_fibers.reject! { |fiber| !fiber.alive? }

      # Small delay to prevent busy waiting
      sleep(0.01)
    end

    # Ensure all fibers are properly terminated
    active_fibers.each do |fiber|
      fiber.resume while fiber.alive?
    rescue FiberError
      # Fiber completed
    end
  end
end

# Registry state data class
class RegistryState
  attr_reader :timestamp, :total_repositories, :total_manifests, :total_blobs,
              :active_sessions, :health_status, :repositories

  def initialize(timestamp:, total_repositories:, total_manifests:, total_blobs:,
                 active_sessions:, health_status:, repositories:)
    @timestamp = timestamp
    @total_repositories = total_repositories
    @total_manifests = total_manifests
    @total_blobs = total_blobs
    @active_sessions = active_sessions
    @health_status = health_status
    @repositories = repositories
  end
end

# Main execution
def main
  options = {
    registry_url: 'http://localhost:7000',
    username: nil,
    password: nil,
    duration: 60,
    concurrent_requests: 10,
    test_type: 'all',
    log_level: 'INFO'
  }

  OptionParser.new do |opts|
    opts.banner = "Usage: #{$0} [options]"

    opts.on('--registry-url URL', 'Registry URL (default: http://localhost:7000)') do |url|
      options[:registry_url] = url
    end

    opts.on('--username USERNAME', 'Registry username (optional)') do |username|
      options[:username] = username
    end

    opts.on('--password PASSWORD', 'Registry password (optional)') do |password|
      options[:password] = password
    end

    opts.on('--duration SECONDS', Integer, 'Test duration in seconds (default: 60)') do |duration|
      options[:duration] = duration
    end

    opts.on('--concurrent-requests COUNT', Integer, 'Number of concurrent requests for stress test (default: 10)') do |count|
      options[:concurrent_requests] = count
    end

    opts.on('--test-type TYPE', %w[monitor consistency stress all], 'Type of test to run (default: all)') do |type|
      options[:test_type] = type
    end

    opts.on('--log-level LEVEL', %w[DEBUG INFO WARN ERROR], 'Log level (default: INFO)') do |level|
      options[:log_level] = level
    end

    opts.on('-h', '--help', 'Show this help message') do
      puts opts
      exit
    end
  end.parse!

  # Authentication is optional - warn if not provided
  if options[:username].nil? || options[:password].nil?
    puts "Warning: No authentication provided - using anonymous access"
    puts "If the registry requires authentication, use --username and --password"
  end

  # Set log level
  logger.level = case options[:log_level]
                 when 'DEBUG' then Logger::DEBUG
                 when 'INFO' then Logger::INFO
                 when 'WARN' then Logger::WARN
                 when 'ERROR' then Logger::ERROR
                 else Logger::INFO
                 end

  # Create and run torture test
  tester = NSCRTortureTest.new(
    options[:registry_url],
    options[:username],
    options[:password]
  )

  begin
    tester.run_tests(
      options[:test_type],
      options[:duration],
      options[:concurrent_requests]
    )

    # Analyze results
    tester.analyze_state_history
    tester.print_summary

  rescue Interrupt
    logger.info("Test interrupted by user")
    tester.print_summary
  rescue => e
    logger.error("Test failed: #{e.message}")
    logger.error(e.backtrace.join("\n"))
    exit 1
  end
end

# Run main function if script is executed directly
if __FILE__ == $0
  main
end
