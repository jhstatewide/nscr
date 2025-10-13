package com.statewidesoftware.nscr

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.synchronized
import mu.KotlinLogging

/**
 * Categories for throughput tracking
 */
enum class ThroughputCategory {
    BLOB_UPLOAD,
    BLOB_DOWNLOAD,
    MANIFEST_UPLOAD,
    MANIFEST_DOWNLOAD
}

/**
 * Time range for peak throughput queries
 */
enum class TimeRange {
    MINUTE,
    HOUR,
    DAY
}

/**
 * Data point for time series
 */
data class TimeSeriesPoint(
    val timestamp: Long,
    val readBytes: Long,
    val writeBytes: Long,
    val peakReadRate: Double,
    val peakWriteRate: Double,
    val operationCount: Int
)

/**
 * Current throughput snapshot
 */
data class ThroughputSnapshot(
    val timestamp: Long,
    val categories: Map<ThroughputCategory, CategoryThroughput>,
    val overall: OverallThroughput
)

/**
 * Throughput data for a specific category
 */
data class CategoryThroughput(
    val current: Double, // bytes per second
    val average: Double, // bytes per second over 5 seconds
    val totalBytes: Long
)

/**
 * Overall throughput summary
 */
data class OverallThroughput(
    val read: CategoryThroughput,
    val write: CategoryThroughput,
    val total: CategoryThroughput
)

/**
 * Thread-safe throughput tracker with multi-tier time aggregation
 */
class ThroughputTracker {
    companion object {
        private val logger = KotlinLogging.logger("ThroughputTracker")
        
        // Singleton instance
        @Volatile
        private var INSTANCE: ThroughputTracker? = null
        
        fun getInstance(): ThroughputTracker {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ThroughputTracker().also { INSTANCE = it }
            }
        }
    }
    
    // Real-time tracking (5-second rolling window)
    private val realTimeBuckets = ArrayDeque<SecondBucket>(5)
    private val realTimeLock = Any()
    
    // Historical tracking
    private val minuteBuckets = ArrayDeque<TimeBucket>(60) // Last 60 minutes
    private val hourBuckets = ArrayDeque<TimeBucket>(48) // Last 48 hours  
    private val dayBuckets = ArrayDeque<TimeBucket>(30) // Last 30 days
    
    private val historicalLock = Any()
    
    // Background scheduler
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2)
    
    // Current second tracking
    private val currentSecond = AtomicLong(System.currentTimeMillis() / 1000)
    private val currentBucket = SecondBucket(currentSecond.get())
    
    init {
        // Initialize real-time buckets
        synchronized(realTimeLock) {
            repeat(5) { realTimeBuckets.add(SecondBucket(currentSecond.get() - it)) }
        }
        
        // Start background tasks
        startRollupTasks()
    }
    
    /**
     * Record bytes transferred for a specific category
     */
    fun recordBytes(category: ThroughputCategory, bytes: Long) {
        logger.debug { "Recording $bytes bytes for $category" }
        val now = System.currentTimeMillis() / 1000
        val currentSecondValue = currentSecond.get()
        
        // Check if we need to roll over to a new second
        if (now > currentSecondValue) {
            synchronized(realTimeLock) {
                if (now > currentSecond.get()) {
                    // Add current bucket to real-time window
                    realTimeBuckets.addLast(currentBucket.copy())
                    
                    // Remove oldest bucket if we exceed 5 seconds
                    if (realTimeBuckets.size > 5) {
                        realTimeBuckets.removeFirst()
                    }
                    
                    // Start new bucket
                    currentSecond.set(now)
                    currentBucket.reset(now)
                }
            }
        }
        
        // Record bytes in current bucket
        currentBucket.recordBytes(category, bytes)
        
        // Notify the broadcaster of activity
        try {
            SseThroughputBroadcaster.recordActivity()
        } catch (e: Exception) {
            // Ignore if broadcaster is not available
        }
    }
    
    /**
     * Get current throughput snapshot
     */
    fun getCurrentThroughput(): ThroughputSnapshot {
        val now = System.currentTimeMillis()
        val buckets = synchronized(realTimeLock) { realTimeBuckets.toList() + currentBucket }
        
        val categories = ThroughputCategory.values().associateWith { category ->
            val current = getCurrentRate(buckets, category)
            val average = getAverageRate(buckets, category)
            // Use current second's bytes for display
            val totalBytes = buckets.lastOrNull()?.getBytes(category) ?: 0L
            
            CategoryThroughput(current, average, totalBytes)
        }
        
        val overall = OverallThroughput(
            read = CategoryThroughput(
                current = categories[ThroughputCategory.BLOB_DOWNLOAD]!!.current + categories[ThroughputCategory.MANIFEST_DOWNLOAD]!!.current,
                average = categories[ThroughputCategory.BLOB_DOWNLOAD]!!.average + categories[ThroughputCategory.MANIFEST_DOWNLOAD]!!.average,
                totalBytes = categories[ThroughputCategory.BLOB_DOWNLOAD]!!.totalBytes + categories[ThroughputCategory.MANIFEST_DOWNLOAD]!!.totalBytes
            ),
            write = CategoryThroughput(
                current = categories[ThroughputCategory.BLOB_UPLOAD]!!.current + categories[ThroughputCategory.MANIFEST_UPLOAD]!!.current,
                average = categories[ThroughputCategory.BLOB_UPLOAD]!!.average + categories[ThroughputCategory.MANIFEST_UPLOAD]!!.average,
                totalBytes = categories[ThroughputCategory.BLOB_UPLOAD]!!.totalBytes + categories[ThroughputCategory.MANIFEST_UPLOAD]!!.totalBytes
            ),
            total = CategoryThroughput(0.0, 0.0, 0L) // Will be calculated
        )
        
        val total = CategoryThroughput(
            current = overall.read.current + overall.write.current,
            average = overall.read.average + overall.write.average,
            totalBytes = overall.read.totalBytes + overall.write.totalBytes
        )
        
        return ThroughputSnapshot(now, categories, overall.copy(total = total))
    }
    
    /**
     * Get average throughput over specified seconds
     */
    fun getAverageThroughput(seconds: Int): ThroughputSnapshot {
        val now = System.currentTimeMillis()
        val buckets = synchronized(realTimeLock) { 
            realTimeBuckets.takeLast(seconds.coerceAtMost(5)) + currentBucket 
        }
        
        return calculateSnapshot(buckets, now)
    }
    
    /**
     * Get minute-level statistics
     */
    fun getMinuteStats(lastNMinutes: Int): List<TimeSeriesPoint> {
        return synchronized(historicalLock) {
            minuteBuckets.takeLast(lastNMinutes.coerceAtMost(60)).map { bucket ->
                TimeSeriesPoint(
                    timestamp = bucket.timestamp,
                    readBytes = bucket.readBytes,
                    writeBytes = bucket.writeBytes,
                    peakReadRate = bucket.peakReadRate,
                    peakWriteRate = bucket.peakWriteRate,
                    operationCount = bucket.operationCount
                )
            }
        }
    }
    
    /**
     * Get hour-level statistics
     */
    fun getHourStats(lastNHours: Int): List<TimeSeriesPoint> {
        return synchronized(historicalLock) {
            hourBuckets.takeLast(lastNHours.coerceAtMost(48)).map { bucket ->
                TimeSeriesPoint(
                    timestamp = bucket.timestamp,
                    readBytes = bucket.readBytes,
                    writeBytes = bucket.writeBytes,
                    peakReadRate = bucket.peakReadRate,
                    peakWriteRate = bucket.peakWriteRate,
                    operationCount = bucket.operationCount
                )
            }
        }
    }
    
    /**
     * Get day-level statistics
     */
    fun getDayStats(lastNDays: Int): List<TimeSeriesPoint> {
        return synchronized(historicalLock) {
            dayBuckets.takeLast(lastNDays.coerceAtMost(30)).map { bucket ->
                TimeSeriesPoint(
                    timestamp = bucket.timestamp,
                    readBytes = bucket.readBytes,
                    writeBytes = bucket.writeBytes,
                    peakReadRate = bucket.peakReadRate,
                    peakWriteRate = bucket.peakWriteRate,
                    operationCount = bucket.operationCount
                )
            }
        }
    }
    
    /**
     * Get peak throughput for a time range
     */
    fun getPeakThroughput(timeRange: TimeRange): ThroughputSnapshot {
        val buckets = when (timeRange) {
            TimeRange.MINUTE -> synchronized(historicalLock) { minuteBuckets.toList() }
            TimeRange.HOUR -> synchronized(historicalLock) { hourBuckets.toList() }
            TimeRange.DAY -> synchronized(historicalLock) { dayBuckets.toList() }
        }
        
        if (buckets.isEmpty()) {
            return getCurrentThroughput()
        }
        
        val maxReadRate = buckets.maxOfOrNull { it.peakReadRate } ?: 0.0
        val maxWriteRate = buckets.maxOfOrNull { it.peakWriteRate } ?: 0.0
        val maxTotalRate = maxReadRate + maxWriteRate
        
        val categories = ThroughputCategory.values().associateWith { category ->
            val totalBytes = buckets.sumOf { bucket ->
                when (category) {
                    ThroughputCategory.BLOB_UPLOAD -> bucket.writeBytes
                    ThroughputCategory.BLOB_DOWNLOAD -> bucket.readBytes
                    ThroughputCategory.MANIFEST_UPLOAD -> 0L // Manifests are small, ignore for now
                    ThroughputCategory.MANIFEST_DOWNLOAD -> 0L
                }
            }
            CategoryThroughput(maxTotalRate / 2, maxTotalRate / 2, totalBytes)
        }
        
        val overall = OverallThroughput(
            read = CategoryThroughput(maxReadRate, maxReadRate, buckets.sumOf { it.readBytes }),
            write = CategoryThroughput(maxWriteRate, maxWriteRate, buckets.sumOf { it.writeBytes }),
            total = CategoryThroughput(maxTotalRate, maxTotalRate, buckets.sumOf { it.readBytes + it.writeBytes })
        )
        
        return ThroughputSnapshot(System.currentTimeMillis(), categories, overall)
    }
    
    private fun startRollupTasks() {
        // Roll up to minute buckets every 60 seconds
        scheduler.scheduleAtFixedRate({
            try {
                performMinuteRollup()
            } catch (e: Exception) {
                logger.error("Error in minute rollup: ${e.message}", e)
            }
        }, 60, 60, TimeUnit.SECONDS)
        
        // Roll up to hour buckets every hour
        scheduler.scheduleAtFixedRate({
            try {
                performHourRollup()
            } catch (e: Exception) {
                logger.error("Error in hour rollup: ${e.message}", e)
            }
        }, 3600, 3600, TimeUnit.SECONDS)
        
        // Roll up to day buckets every day
        scheduler.scheduleAtFixedRate({
            try {
                performDayRollup()
            } catch (e: Exception) {
                logger.error("Error in day rollup: ${e.message}", e)
            }
        }, 86400, 86400, TimeUnit.SECONDS)
    }
    
    private fun performMinuteRollup() {
        val now = System.currentTimeMillis() / 1000
        val minuteStart = (now / 60) * 60
        
        // Aggregate current real-time data into minute bucket
        val buckets = synchronized(realTimeLock) { realTimeBuckets.toList() + currentBucket }
        val minuteBucket = aggregateToMinute(buckets, minuteStart)
        
        synchronized(historicalLock) {
            minuteBuckets.addLast(minuteBucket)
            if (minuteBuckets.size > 60) {
                minuteBuckets.removeFirst()
            }
        }
    }
    
    private fun performHourRollup() {
        val now = System.currentTimeMillis() / 1000
        val hourStart = (now / 3600) * 3600
        
        // Aggregate last 60 minutes into hour bucket
        val minuteData = synchronized(historicalLock) { minuteBuckets.toList() }
        val hourBucket = aggregateToHour(minuteData, hourStart)
        
        synchronized(historicalLock) {
            hourBuckets.addLast(hourBucket)
            if (hourBuckets.size > 48) {
                hourBuckets.removeFirst()
            }
        }
    }
    
    private fun performDayRollup() {
        val now = System.currentTimeMillis() / 1000
        val dayStart = (now / 86400) * 86400
        
        // Aggregate last 24 hours into day bucket
        val hourData = synchronized(historicalLock) { hourBuckets.toList() }
        val dayBucket = aggregateToDay(hourData, dayStart)
        
        synchronized(historicalLock) {
            dayBuckets.addLast(dayBucket)
            if (dayBuckets.size > 30) {
                dayBuckets.removeFirst()
            }
        }
    }
    
    private fun getCurrentRate(buckets: List<SecondBucket>, category: ThroughputCategory): Double {
        val latest = buckets.lastOrNull() ?: return 0.0
        return latest.getBytes(category).toDouble() // This is already bytes per second for the current second
    }
    
    private fun getAverageRate(buckets: List<SecondBucket>, category: ThroughputCategory): Double {
        if (buckets.size < 2) return getCurrentRate(buckets, category)
        
        // Calculate average bytes per second over the rolling window
        val totalBytes = buckets.sumOf { it.getBytes(category) }
        val timeSpan = buckets.size.toDouble()
        return totalBytes / timeSpan
    }
    
    private fun calculateSnapshot(buckets: List<SecondBucket>, timestamp: Long): ThroughputSnapshot {
        val categories = ThroughputCategory.values().associateWith { category ->
            val current = getCurrentRate(buckets, category)
            val average = getAverageRate(buckets, category)
            val totalBytes = buckets.sumOf { it.getBytes(category) }
            
            CategoryThroughput(current, average, totalBytes)
        }
        
        val overall = OverallThroughput(
            read = CategoryThroughput(
                current = categories[ThroughputCategory.BLOB_DOWNLOAD]!!.current + categories[ThroughputCategory.MANIFEST_DOWNLOAD]!!.current,
                average = categories[ThroughputCategory.BLOB_DOWNLOAD]!!.average + categories[ThroughputCategory.MANIFEST_DOWNLOAD]!!.average,
                totalBytes = categories[ThroughputCategory.BLOB_DOWNLOAD]!!.totalBytes + categories[ThroughputCategory.MANIFEST_DOWNLOAD]!!.totalBytes
            ),
            write = CategoryThroughput(
                current = categories[ThroughputCategory.BLOB_UPLOAD]!!.current + categories[ThroughputCategory.MANIFEST_UPLOAD]!!.current,
                average = categories[ThroughputCategory.BLOB_UPLOAD]!!.average + categories[ThroughputCategory.MANIFEST_UPLOAD]!!.average,
                totalBytes = categories[ThroughputCategory.BLOB_UPLOAD]!!.totalBytes + categories[ThroughputCategory.MANIFEST_UPLOAD]!!.totalBytes
            ),
            total = CategoryThroughput(0.0, 0.0, 0L)
        )
        
        val total = CategoryThroughput(
            current = overall.read.current + overall.write.current,
            average = overall.read.average + overall.write.average,
            totalBytes = overall.read.totalBytes + overall.write.totalBytes
        )
        
        return ThroughputSnapshot(timestamp, categories, overall.copy(total = total))
    }
    
    private fun aggregateToMinute(secondBuckets: List<SecondBucket>, minuteStart: Long): TimeBucket {
        val readBytes = secondBuckets.sumOf { it.getBytes(ThroughputCategory.BLOB_DOWNLOAD) }
        val writeBytes = secondBuckets.sumOf { it.getBytes(ThroughputCategory.BLOB_UPLOAD) }
        val peakReadRate = secondBuckets.maxOfOrNull { it.getRate(ThroughputCategory.BLOB_DOWNLOAD) } ?: 0.0
        val peakWriteRate = secondBuckets.maxOfOrNull { it.getRate(ThroughputCategory.BLOB_UPLOAD) } ?: 0.0
        val operationCount = secondBuckets.sumOf { it.operationCount }
        
        return TimeBucket(minuteStart * 1000, readBytes, writeBytes, peakReadRate, peakWriteRate, operationCount)
    }
    
    private fun aggregateToHour(minuteBuckets: List<TimeBucket>, hourStart: Long): TimeBucket {
        val readBytes = minuteBuckets.sumOf { it.readBytes }
        val writeBytes = minuteBuckets.sumOf { it.writeBytes }
        val peakReadRate = minuteBuckets.maxOfOrNull { it.peakReadRate } ?: 0.0
        val peakWriteRate = minuteBuckets.maxOfOrNull { it.peakWriteRate } ?: 0.0
        val operationCount = minuteBuckets.sumOf { it.operationCount }
        
        return TimeBucket(hourStart * 1000, readBytes, writeBytes, peakReadRate, peakWriteRate, operationCount)
    }
    
    private fun aggregateToDay(hourBuckets: List<TimeBucket>, dayStart: Long): TimeBucket {
        val readBytes = hourBuckets.sumOf { it.readBytes }
        val writeBytes = hourBuckets.sumOf { it.writeBytes }
        val peakReadRate = hourBuckets.maxOfOrNull { it.peakReadRate } ?: 0.0
        val peakWriteRate = hourBuckets.maxOfOrNull { it.peakWriteRate } ?: 0.0
        val operationCount = hourBuckets.sumOf { it.operationCount }
        
        return TimeBucket(dayStart * 1000, readBytes, writeBytes, peakReadRate, peakWriteRate, operationCount)
    }
    
    fun shutdown() {
        scheduler.shutdown()
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow()
            }
        } catch (e: InterruptedException) {
            scheduler.shutdownNow()
        }
    }
}

/**
 * Data for a single second
 */
data class SecondBucket(
    val second: Long,
    private val blobUploadBytes: AtomicLong = AtomicLong(0),
    private val blobDownloadBytes: AtomicLong = AtomicLong(0),
    private val manifestUploadBytes: AtomicLong = AtomicLong(0),
    private val manifestDownloadBytes: AtomicLong = AtomicLong(0),
    val operationCount: Int = 0
) {
    fun recordBytes(category: ThroughputCategory, bytes: Long) {
        when (category) {
            ThroughputCategory.BLOB_UPLOAD -> blobUploadBytes.addAndGet(bytes)
            ThroughputCategory.BLOB_DOWNLOAD -> blobDownloadBytes.addAndGet(bytes)
            ThroughputCategory.MANIFEST_UPLOAD -> manifestUploadBytes.addAndGet(bytes)
            ThroughputCategory.MANIFEST_DOWNLOAD -> manifestDownloadBytes.addAndGet(bytes)
        }
    }
    
    fun getBytes(category: ThroughputCategory): Long {
        return when (category) {
            ThroughputCategory.BLOB_UPLOAD -> blobUploadBytes.get()
            ThroughputCategory.BLOB_DOWNLOAD -> blobDownloadBytes.get()
            ThroughputCategory.MANIFEST_UPLOAD -> manifestUploadBytes.get()
            ThroughputCategory.MANIFEST_DOWNLOAD -> manifestDownloadBytes.get()
        }
    }
    
    fun getRate(category: ThroughputCategory): Double {
        return getBytes(category).toDouble() // This is already bytes per second since it's per second bucket
    }
    
    fun reset(newSecond: Long): SecondBucket {
        // Reset all counters to zero for the new second
        blobUploadBytes.set(0)
        blobDownloadBytes.set(0)
        manifestUploadBytes.set(0)
        manifestDownloadBytes.set(0)
        // Note: newSecond parameter is kept for future use if needed
        return this
    }
}

/**
 * Data for a time bucket (minute/hour/day)
 */
data class TimeBucket(
    val timestamp: Long,
    val readBytes: Long,
    val writeBytes: Long,
    val peakReadRate: Double,
    val peakWriteRate: Double,
    val operationCount: Int
)

/**
 * Counting input stream that tracks bytes transferred
 */
class CountingInputStream(
    private val delegate: InputStream,
    private val tracker: ThroughputTracker,
    private val category: ThroughputCategory
) : InputStream() {
    
    override fun read(): Int {
        val byte = delegate.read()
        if (byte != -1) {
            tracker.recordBytes(category, 1)
        }
        return byte
    }
    
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val bytesRead = delegate.read(b, off, len)
        if (bytesRead > 0) {
            tracker.recordBytes(category, bytesRead.toLong())
        }
        return bytesRead
    }
    
    override fun read(b: ByteArray): Int {
        return read(b, 0, b.size)
    }
    
    override fun available(): Int = delegate.available()
    
    override fun close() = delegate.close()
    
    override fun mark(readlimit: Int) = delegate.mark(readlimit)
    
    override fun reset() = delegate.reset()
    
    override fun markSupported(): Boolean = delegate.markSupported()
}

/**
 * Counting output stream that tracks bytes transferred
 */
class CountingOutputStream(
    private val delegate: OutputStream,
    private val tracker: ThroughputTracker,
    private val category: ThroughputCategory
) : OutputStream() {
    
    override fun write(b: Int) {
        delegate.write(b)
        tracker.recordBytes(category, 1)
    }
    
    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        tracker.recordBytes(category, len.toLong())
    }
    
    override fun write(b: ByteArray) {
        delegate.write(b)
        tracker.recordBytes(category, b.size.toLong())
    }
    
    override fun flush() = delegate.flush()
    
    override fun close() = delegate.close()
}
