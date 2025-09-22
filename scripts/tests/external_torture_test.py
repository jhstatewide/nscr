#!/usr/bin/env python3
"""
External Registry Torture Test Client

This script demonstrates how to use the NSCR external API for comprehensive
registry torture testing. It performs concurrent operations while monitoring
registry state to ensure consistency.

Usage:
    python3 external_torture_test.py --registry-url http://localhost:7000 --username admin --password admin
"""

import argparse
import asyncio
import aiohttp
import json
import time
import random
import logging
import ssl
from typing import Dict, List, Any
from dataclasses import dataclass
from concurrent.futures import ThreadPoolExecutor
import threading

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

@dataclass
class RegistryState:
    """Represents the current state of the registry"""
    timestamp: int
    total_repositories: int
    total_manifests: int
    total_blobs: int
    active_sessions: int
    health_status: str
    repositories: List[Dict[str, Any]]

class NSCRTortureTest:
    """External torture test client for NSCR registry"""
    
    def __init__(self, registry_url: str, username: str = None, password: str = None):
        self.registry_url = registry_url.rstrip('/')
        self.auth = aiohttp.BasicAuth(username, password) if username and password else None
        self.session = None
        self.state_history: List[RegistryState] = []
        self.operation_count = 0
        self.error_count = 0
        self.lock = threading.Lock()
        
        # Log authentication status
        if self.auth:
            logger.info(f"Using authentication: {username}")
        else:
            logger.info("No authentication configured - using anonymous access")
        
    async def __aenter__(self):
        # Configure SSL context to allow self-signed certificates
        ssl_context = ssl.create_default_context()
        ssl_context.check_hostname = False
        ssl_context.verify_mode = ssl.CERT_NONE
        
        connector = aiohttp.TCPConnector(ssl=ssl_context)
        self.session = aiohttp.ClientSession(auth=self.auth, connector=connector)
        return self
        
    async def __aexit__(self, exc_type, exc_val, exc_tb):
        if self.session:
            await self.session.close()
    
    async def get_registry_state(self) -> RegistryState:
        """Get comprehensive registry state"""
        try:
            async with self.session.get(f"{self.registry_url}/api/registry/state") as response:
                if response.status == 200:
                    data = await response.json()
                    return RegistryState(
                        timestamp=data['timestamp'],
                        total_repositories=data['summary']['totalRepositories'],
                        total_manifests=data['summary']['totalManifests'],
                        total_blobs=data['summary']['totalBlobs'],
                        active_sessions=data['activeSessions']['count'],
                        health_status=data['health']['status'],
                        repositories=data['repositories']
                    )
                else:
                    logger.error(f"Failed to get registry state: {response.status}")
                    return None
        except Exception as e:
            logger.error(f"Error getting registry state: {e}")
            return None
    
    async def get_health(self) -> Dict[str, Any]:
        """Get registry health status"""
        try:
            async with self.session.get(f"{self.registry_url}/api/registry/health") as response:
                if response.status == 200:
                    return await response.json()
                else:
                    logger.error(f"Failed to get health: {response.status}")
                    return None
        except Exception as e:
            logger.error(f"Error getting health: {e}")
            return None
    
    async def get_repository_details(self, repo_name: str) -> Dict[str, Any]:
        """Get detailed repository information"""
        try:
            async with self.session.get(f"{self.registry_url}/api/registry/repositories/{repo_name}") as response:
                if response.status == 200:
                    return await response.json()
                else:
                    logger.error(f"Failed to get repository details for {repo_name}: {response.status}")
                    return None
        except Exception as e:
            logger.error(f"Error getting repository details for {repo_name}: {e}")
            return None
    
    async def get_active_sessions(self) -> Dict[str, Any]:
        """Get active session information"""
        try:
            async with self.session.get(f"{self.registry_url}/api/registry/sessions") as response:
                if response.status == 200:
                    return await response.json()
                else:
                    logger.error(f"Failed to get active sessions: {response.status}")
                    return None
        except Exception as e:
            logger.error(f"Error getting active sessions: {e}")
            return None
    
    def record_operation(self, success: bool):
        """Record operation result"""
        with self.lock:
            self.operation_count += 1
            if not success:
                self.error_count += 1
    
    def get_success_rate(self) -> float:
        """Calculate success rate"""
        with self.lock:
            if self.operation_count == 0:
                return 0.0
            return (self.operation_count - self.error_count) / self.operation_count * 100
    
    async def monitor_registry_state(self, duration: int = 60):
        """Monitor registry state for specified duration"""
        logger.info(f"Starting registry state monitoring for {duration} seconds")
        start_time = time.time()
        
        while time.time() - start_time < duration:
            state = await self.get_registry_state()
            if state:
                self.state_history.append(state)
                logger.info(f"State: repos={state.total_repositories}, "
                          f"manifests={state.total_manifests}, "
                          f"blobs={state.total_blobs}, "
                          f"sessions={state.active_sessions}, "
                          f"health={state.health_status}")
            
            await asyncio.sleep(5)  # Monitor every 5 seconds
    
    async def perform_health_checks(self, duration: int = 60):
        """Perform continuous health checks"""
        logger.info(f"Starting health checks for {duration} seconds")
        start_time = time.time()
        
        while time.time() - start_time < duration:
            health = await self.get_health()
            if health:
                status = health.get('status', 'unknown')
                if status != 'healthy':
                    logger.warning(f"Registry health degraded: {status}")
                    logger.warning(f"Health details: {json.dumps(health, indent=2)}")
                else:
                    logger.debug(f"Registry health: {status}")
            
            await asyncio.sleep(10)  # Check every 10 seconds
    
    async def perform_repository_consistency_checks(self, duration: int = 60):
        """Perform repository consistency checks"""
        logger.info(f"Starting repository consistency checks for {duration} seconds")
        start_time = time.time()
        
        while time.time() - start_time < duration:
            state = await self.get_registry_state()
            if state:
                for repo in state.repositories:
                    repo_name = repo['name']
                    repo_details = await self.get_repository_details(repo_name)
                    
                    if repo_details:
                        # Check for consistency issues
                        tag_count_from_state = repo['tagCount']
                        tag_count_from_details = repo_details['tagCount']
                        
                        if tag_count_from_state != tag_count_from_details:
                            logger.error(f"Inconsistent tag count for {repo_name}: "
                                       f"state={tag_count_from_state}, details={tag_count_from_details}")
                            self.record_operation(False)
                        else:
                            self.record_operation(True)
                        
                        # Check for manifests without digests
                        for tag in repo_details.get('tags', []):
                            if tag.get('hasManifest', False) and not tag.get('digest'):
                                logger.error(f"Manifest without digest for {repo_name}:{tag['tag']}")
                                self.record_operation(False)
                            else:
                                self.record_operation(True)
            
            await asyncio.sleep(15)  # Check every 15 seconds
    
    async def perform_session_monitoring(self, duration: int = 60):
        """Monitor active sessions"""
        logger.info(f"Starting session monitoring for {duration} seconds")
        start_time = time.time()
        
        while time.time() - start_time < duration:
            sessions = await self.get_active_sessions()
            if sessions:
                active_sessions = sessions.get('activeSessions', [])
                total_sessions = sessions.get('totalActiveSessions', 0)
                
                logger.info(f"Active sessions: {total_sessions}")
                
                for session in active_sessions:
                    session_id = session['id']
                    duration_ms = session['duration']
                    blob_count = session['blobCount']
                    
                    logger.debug(f"Session {session_id}: duration={duration_ms}ms, blobs={blob_count}")
                    
                    # Check for long-running sessions
                    if duration_ms > 300000:  # 5 minutes
                        logger.warning(f"Long-running session detected: {session_id} ({duration_ms}ms)")
            
            await asyncio.sleep(20)  # Check every 20 seconds
    
    async def perform_stress_test(self, duration: int = 60, concurrent_requests: int = 10):
        """Perform stress test with concurrent API requests"""
        logger.info(f"Starting stress test for {duration} seconds with {concurrent_requests} concurrent requests")
        start_time = time.time()
        
        async def stress_worker(worker_id: int):
            """Individual stress test worker"""
            while time.time() - start_time < duration:
                try:
                    # Randomly choose an operation
                    operation = random.choice([
                        'get_state',
                        'get_health', 
                        'get_sessions',
                        'get_repository_details'
                    ])
                    
                    if operation == 'get_state':
                        state = await self.get_registry_state()
                        if state:
                            self.record_operation(True)
                        else:
                            self.record_operation(False)
                    
                    elif operation == 'get_health':
                        health = await self.get_health()
                        if health:
                            self.record_operation(True)
                        else:
                            self.record_operation(False)
                    
                    elif operation == 'get_sessions':
                        sessions = await self.get_active_sessions()
                        if sessions:
                            self.record_operation(True)
                        else:
                            self.record_operation(False)
                    
                    elif operation == 'get_repository_details':
                        state = await self.get_registry_state()
                        if state and state.repositories:
                            repo_name = random.choice(state.repositories)['name']
                            details = await self.get_repository_details(repo_name)
                            if details:
                                self.record_operation(True)
                            else:
                                self.record_operation(False)
                        else:
                            self.record_operation(False)
                    
                    # Random delay between operations
                    await asyncio.sleep(random.uniform(0.1, 1.0))
                    
                except Exception as e:
                    logger.error(f"Stress worker {worker_id} error: {e}")
                    self.record_operation(False)
        
        # Start concurrent workers
        workers = [stress_worker(i) for i in range(concurrent_requests)]
        await asyncio.gather(*workers)
    
    def analyze_state_history(self):
        """Analyze state history for anomalies"""
        logger.info("Analyzing state history for anomalies...")
        
        if len(self.state_history) < 2:
            logger.warning("Insufficient state history for analysis")
            return
        
        # Check for sudden changes in metrics
        for i in range(1, len(self.state_history)):
            prev_state = self.state_history[i-1]
            curr_state = self.state_history[i]
            
            # Check for sudden drops in repositories (potential data loss)
            if curr_state.total_repositories < prev_state.total_repositories:
                logger.warning(f"Repository count decreased: {prev_state.total_repositories} -> {curr_state.total_repositories}")
            
            # Check for sudden drops in manifests
            if curr_state.total_manifests < prev_state.total_manifests:
                logger.warning(f"Manifest count decreased: {prev_state.total_manifests} -> {curr_state.total_manifests}")
            
            # Check for health status changes
            if prev_state.health_status == 'healthy' and curr_state.health_status != 'healthy':
                logger.warning(f"Health status degraded: {prev_state.health_status} -> {curr_state.health_status}")
    
    def print_summary(self):
        """Print test summary"""
        logger.info("=== Torture Test Summary ===")
        logger.info(f"Total operations: {self.operation_count}")
        logger.info(f"Errors: {self.error_count}")
        logger.info(f"Success rate: {self.get_success_rate():.2f}%")
        logger.info(f"State snapshots collected: {len(self.state_history)}")
        
        if self.state_history:
            first_state = self.state_history[0]
            last_state = self.state_history[-1]
            logger.info(f"Initial state: repos={first_state.total_repositories}, "
                       f"manifests={first_state.total_manifests}, blobs={first_state.total_blobs}")
            logger.info(f"Final state: repos={last_state.total_repositories}, "
                       f"manifests={last_state.total_manifests}, blobs={last_state.total_blobs}")

async def main():
    parser = argparse.ArgumentParser(description='NSCR External Torture Test')
    parser.add_argument('--registry-url', default='http://localhost:7000',
                       help='Registry URL (default: http://localhost:7000)')
    parser.add_argument('--username', help='Registry username (optional)')
    parser.add_argument('--password', help='Registry password (optional)')
    parser.add_argument('--duration', type=int, default=60,
                       help='Test duration in seconds (default: 60)')
    parser.add_argument('--concurrent-requests', type=int, default=10,
                       help='Number of concurrent requests for stress test (default: 10)')
    parser.add_argument('--test-type', choices=['monitor', 'consistency', 'stress', 'all'],
                       default='all', help='Type of test to run (default: all)')
    
    args = parser.parse_args()
    
    # Authentication is optional - warn if not provided
    if not args.username or not args.password:
        print("Warning: No authentication provided - using anonymous access")
        print("If the registry requires authentication, use --username and --password")
    
    async with NSCRTortureTest(args.registry_url, args.username, args.password) as tester:
        logger.info(f"Starting {args.test_type} torture test for {args.duration} seconds")
        
        if args.test_type in ['monitor', 'all']:
            await tester.monitor_registry_state(args.duration)
        
        if args.test_type in ['consistency', 'all']:
            await tester.perform_repository_consistency_checks(args.duration)
        
        if args.test_type in ['stress', 'all']:
            await tester.perform_stress_test(args.duration, args.concurrent_requests)
        
        # Always run health checks and session monitoring
        await asyncio.gather(
            tester.perform_health_checks(args.duration),
            tester.perform_session_monitoring(args.duration)
        )
        
        # Analyze results
        tester.analyze_state_history()
        tester.print_summary()

if __name__ == '__main__':
    asyncio.run(main())
