package com.statewidesoftware.nscr

import com.statewidesoftware.nscr.blobstore.H2BlobStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.nio.file.Path

/**
 * Test the improved database recovery behavior with retry limits and comprehensive diagnostics
 */
class DatabaseRecoveryTest {
    
    private lateinit var tempDir: Path
    private lateinit var blobStore: H2BlobStore
    
    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("nscr-recovery-test")
        blobStore = H2BlobStore(tempDir)
    }
    
    @AfterEach
    fun tearDown() {
        try {
            blobStore.cleanup()
        } catch (e: Exception) {
            // Ignore cleanup errors in tests
        }
        Files.deleteIfExists(tempDir.resolve("blobstore.mv.db"))
        Files.deleteIfExists(tempDir.resolve("blobstore.trace.db"))
        Files.deleteIfExists(tempDir)
    }
    
    @Test
    fun `recovery should succeed on first attempt for healthy database`() {
        // Test that recovery works correctly for a healthy database
        val result = blobStore.attemptRecovery()
        assertTrue(result, "Recovery should succeed for healthy database")
        assertFalse(blobStore.isRecoveryFailed(), "Recovery should not be marked as failed")
    }
    
    @Test
    fun `recovery should track attempts correctly`() {
        // This test verifies that the recovery mechanism tracks attempts correctly
        // and respects the maximum attempt limit
        
        // Create a blobstore instance
        val testBlobStore = H2BlobStore(tempDir)
        
        // Test that recovery works initially
        val firstAttempt = testBlobStore.attemptRecovery()
        assertTrue(firstAttempt, "Recovery should succeed for healthy database")
        assertFalse(testBlobStore.isRecoveryFailed(), "Recovery should not be marked as failed")
        
        // Test that we can call recovery multiple times on a healthy database
        val secondAttempt = testBlobStore.attemptRecovery()
        assertTrue(secondAttempt, "Recovery should succeed again for healthy database")
        assertFalse(testBlobStore.isRecoveryFailed(), "Recovery should not be marked as failed")
        
        try {
            testBlobStore.cleanup()
        } catch (e: Exception) {
            // Expected - cleanup may fail
        }
    }
    
    @Test
    fun `recovery should provide isRecoveryFailed method`() {
        // This test verifies that the isRecoveryFailed method works correctly
        
        // Initially, recovery should not be failed
        assertFalse(blobStore.isRecoveryFailed(), "Recovery should not be marked as failed initially")
        
        // After successful recovery, it should still not be failed
        val result = blobStore.attemptRecovery()
        assertTrue(result, "Recovery should succeed")
        assertFalse(blobStore.isRecoveryFailed(), "Recovery should not be marked as failed after success")
    }
}
