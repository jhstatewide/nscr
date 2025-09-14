package blobstore

import SessionID
import org.h2.jdbcx.JdbcDataSource
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.Exception
import kotlin.io.path.Path

class H2BlobStore(dataDirectory: Path = Path("./data/")): Blobstore {
    private val dataSource: JdbcDataSource = JdbcDataSource()
    private val jdbi: Jdbi
    private val logger = LoggerFactory.getLogger("H2BlobStore")
    private val cleanupExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "blobstore-cleanup").apply { isDaemon = true }
    }

    init {
        dataSource.setURL("jdbc:h2:file:${dataDirectory.toAbsolutePath()}/blobstore")
        dataSource.user = "sa"
        dataSource.password = "sa"
        // Configure connection pooling
        dataSource.maxConnections = 10
        dataSource.minConnections = 2
        this.jdbi = Jdbi.create(dataSource)
        provisionTables()
        
        // Register shutdown hook for proper cleanup
        Runtime.getRuntime().addShutdownHook(Thread {
            logger.info("Shutting down H2BlobStore...")
            cleanup()
        })
    }

    @Throws(Exception::class)
    private fun provisionTables() {
        jdbi.useTransaction<RuntimeException> { handle: Handle ->
            handle.execute("CREATE TABLE IF NOT EXISTS blobs(sessionID varchar(256), blobNumber int, digest varchar(256), content blob, CONSTRAINT unique_digest UNIQUE (digest));")
            handle.execute("CREATE TABLE IF NOT EXISTS manifests(name varchar(256), tag varchar(256), manifest clob, digest varchar(256), constraint unique_image_version unique (name, tag));")
            handle.commit()
            logger.info("H2 Blobstore initialized!")
        }
    }

    private val uploadedUUIDs = mutableSetOf<Digest>()

    override fun nextSessionLocation(sessionID: SessionID): String {
        // count # of blobs with matching session ID
        // return session ID + the sequence...
        val sessionBlobCount = blobCountForSession(sessionID)
        return sessionID.id + "/" + sessionBlobCount
    }

    @Throws(Exception::class)
    override fun blobCountForSession(sessionID: SessionID): Int = jdbi.withHandle<Int, Exception> { handle ->
        // Count only blobs without digests (chunk blobs), not the final stitched blob
        handle.createQuery("SELECT COUNT(*) as blobCount from blobs where sessionID = :sessionID AND digest IS NULL")
            .bind("sessionID", sessionID.id).map { rs, _ -> rs.getInt("blobCount") }.first() ?: 0
    }

    @Throws(Exception::class)
    override fun hasBlob(digest: Digest): Boolean {
        val query = "SELECT COUNT(*) as matching_blob_count FROM blobs where digest = :digest;"
        return jdbi.withHandle<Boolean, Exception> { handle ->
            val statement = handle.createQuery(query).bind("digest", digest.digestString)
            statement.map { rs, _ ->
                rs.getInt("matching_blob_count") > 0
            }.first()
        }
    }

    @Throws(Exception::class)
    override fun addBlob(sessionID: SessionID, blobNumber: Int?, bodyAsInputStream: InputStream): Long {
        // we cannot go over the input stream twice...
        // so we need to copy it to a temp file
        val tempFile = File.createTempFile("blobstore", "blob")
        try {
            bodyAsInputStream.use {
                it.copyTo(tempFile.outputStream())
                // now log the name of the tempfile
                logger.info("Uploaded blob to temp file: ${tempFile.absolutePath}")
            }
            // get the size of the temp file
            val size = tempFile.length()

            // log the size of the blob
            logger.info("Uploading blob of size $size bytes")

            // get an input stream for the file
            tempFile.inputStream().use { fileInputStream ->
                jdbi.useTransaction<RuntimeException> { handle ->
                    val statement = handle.connection.prepareStatement("INSERT INTO blobs(sessionID, blobNumber, content) values (?, ?, ?)")
                    statement.setString(1, sessionID.id)
                    if (blobNumber != null) {
                        statement.setInt(2, blobNumber)
                    }
                    statement.setBinaryStream(3, fileInputStream, size)
                    val result = statement.executeUpdate()
                    handle.commit()
                    logger.info("Blob inserted for ${sessionID.id}/${blobNumber}. Result: $result")
                }
            }
            return size
        } finally {
            if (tempFile.exists()) {
                logger.info("Deleting temp file: ${tempFile.absolutePath}")
                tempFile.delete()
            }
        }
    }

    override fun removeBlob(digest: Digest) {
        jdbi.useTransaction<Exception> { handle ->
            val deletedRows = handle.createUpdate("DELETE FROM blobs WHERE digest = :digest")
                .bind("digest", digest.digestString)
                .execute()
            logger.info("Removed blob with digest ${digest.digestString}, deleted $deletedRows rows")
            handle.commit()
        }
    }

    @Throws(Exception::class)
    override fun associateBlobWithSession(sessionID: SessionID, digest: Digest) {
        val blobCount = blobCountForSession(sessionID)
        if (blobCount == 1) {
            // we only have a single blob
            val query = "update blobs set digest = ? where sessionID = ?"
            jdbi.useTransaction<Exception> { handle ->
                handle.createUpdate(query).bind(0, digest.digestString)
                    .bind(1, sessionID.id).execute()
            }
            logger.info("Session ID ${sessionID.id} blob tagged with ${digest.digestString}!")
        } else {
            // Handle multi-part uploads by stitching chunks together
            logger.info("Stitching $blobCount blob chunks for session ${sessionID.id}")
            stitchMultiPartBlob(sessionID, digest)
        }
    }

    @Throws(Exception::class)
    private fun stitchMultiPartBlob(sessionID: SessionID, digest: Digest) {
        jdbi.useTransaction<Exception> { handle ->
            // Get all blob chunks for this session, ordered by blobNumber
            val blobChunks = handle.createQuery("""
                SELECT blobNumber, content 
                FROM blobs 
                WHERE sessionID = :sessionID 
                ORDER BY blobNumber
            """)
                .bind("sessionID", sessionID.id)
                .map { rs, _ ->
                    Pair(rs.getInt("blobNumber"), rs.getBytes("content"))
                }
                .list()

            if (blobChunks.isEmpty()) {
                throw IllegalStateException("No blob chunks found for session ${sessionID.id}")
            }

            // Verify we have consecutive blob numbers starting from 0
            val expectedNumbers = (0 until blobChunks.size).toList()
            val actualNumbers = blobChunks.map { it.first }.sorted()
            if (actualNumbers != expectedNumbers) {
                throw IllegalStateException("Non-consecutive blob numbers for session ${sessionID.id}: expected $expectedNumbers, got $actualNumbers")
            }

            // Calculate total size
            val totalSize = blobChunks.sumOf { it.second.size }
            logger.info("Stitching ${blobChunks.size} chunks totaling $totalSize bytes for session ${sessionID.id}")

            // Create a temporary file to hold the stitched blob
            val tempFile = File.createTempFile("stitched_blob", "tmp")
            try {
                // Write all chunks to the temp file in order
                tempFile.outputStream().use { outputStream ->
                    blobChunks.forEach { (blobNumber, content) ->
                        outputStream.write(content)
                        logger.debug("Wrote chunk $blobNumber (${content.size} bytes) to stitched blob")
                    }
                }

                // Verify the stitched blob size
                val stitchedSize = tempFile.length()
                if (stitchedSize != totalSize.toLong()) {
                    throw IllegalStateException("Stitched blob size mismatch: expected $totalSize, got $stitchedSize")
                }

                // Calculate and verify the digest of the stitched blob
                val calculatedDigest = calculateSHA256(tempFile)
                val expectedDigest = if (digest.digestString.startsWith("sha256:")) {
                    digest.digestString.substring(7) // Remove "sha256:" prefix
                } else {
                    digest.digestString
                }
                
                if (calculatedDigest != expectedDigest) {
                    throw IllegalStateException("Digest mismatch for session ${sessionID.id}: expected $expectedDigest, calculated $calculatedDigest")
                }
                
                logger.info("Digest verification successful for session ${sessionID.id}: $calculatedDigest")

                // Read the stitched blob and insert it as a new blob with the digest
                tempFile.inputStream().use { stitchedInputStream ->
                    val insertStatement = handle.connection.prepareStatement(
                        "INSERT INTO blobs(sessionID, blobNumber, digest, content) VALUES (?, ?, ?, ?)"
                    )
                    insertStatement.setString(1, sessionID.id)
                    insertStatement.setInt(2, 0) // Use 0 as the blob number for the final stitched blob
                    insertStatement.setString(3, digest.digestString)
                    insertStatement.setBinaryStream(4, stitchedInputStream, stitchedSize)
                    insertStatement.executeUpdate()
                }

                // Delete the original chunk blobs
                val deleteStatement = handle.connection.prepareStatement(
                    "DELETE FROM blobs WHERE sessionID = ? AND digest IS NULL"
                )
                deleteStatement.setString(1, sessionID.id)
                val deletedChunks = deleteStatement.executeUpdate()

                logger.info("Successfully stitched $deletedChunks chunks into final blob with digest ${digest.digestString}")
                handle.commit()

            } finally {
                // Clean up temp file
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
        }
    }

    @Throws(Exception::class)
    override fun addManifest(image: ImageVersion, digest: Digest, manifestJson: String) {
        jdbi.useTransaction<Exception> { handle ->
            // see if the manifest already exists
            if (hasManifest(image)) {
                logger.info("Manifest already exists for $image, updating...")
                handle.createUpdate("DELETE FROM MANIFESTS WHERE name = :name and tag = :tag")
                    .bind("name", image.name)
                    .bind("tag", image.tag)
                    .execute()
            } else {
                logger.info("Manifest does not exist for $image, inserting...")
            }
            handle.createUpdate("INSERT INTO MANIFESTS (name, tag, manifest, digest) values (:name, :tag, :manifest, :digest);")
                .bind("name", image.name)
                .bind("tag", image.tag)
                .bind("manifest", manifestJson)
                .bind("digest", "sha256:${digest.digestString}")
                .execute()
            logger.info("Manifest added for $image with digest: sha256:${digest.digestString}")
            handle.commit()
        }
    }

    @Throws(Exception::class)
    override fun getManifest(image: ImageVersion): String {
        return jdbi.withHandle<String?, Exception> { handle ->
            if (image.tag.startsWith("sha256:")) {
                logger.debug("Looking up by digest!")
                val query = "select manifest from manifests where name = :name and digest = :digest;"
                handle.createQuery(query)
                    .bind("name", image.name)
                    .bind("digest", image.tag)
                    .map { rs, _ ->
                        rs.getString("manifest")
                    }.firstOrNull()
            } else {
                val query = "select manifest from manifests where name = :name and tag = :tag;"
                handle.createQuery(query)
                    .bind("name", image.name)
                    .bind("tag", image.tag)
                    .map { rs, _ ->
                        rs.getString("manifest")
                    }.firstOrNull()
            }
        } ?: error("Cannot find manifest for $image!")
    }

    @Throws(Exception::class)
    override fun hasManifest(image: ImageVersion): Boolean {
        return jdbi.withHandle<Int, Exception> { handle ->
            handle.createQuery("SELECT count(*) as count from manifests where name = :name and tag = :tag")
                .bind("name", image.name)
                .bind("tag", image.tag)
                .map { rs, _ ->
                    rs.getInt("count")
                }.first()
        } > 0
    }

    @kotlin.jvm.Throws(Exception::class)
    override fun digestForManifest(image: ImageVersion): Digest {
        return jdbi.withHandle<Digest?, Exception> { handle ->
            val query = "select digest from manifests where name = :name and tag = :tag;"
            handle.createQuery(query)
                .bind("name", image.name)
                .bind("tag", image.tag)
                .map { rs, _ ->
                    Digest(rs.getString("digest"))
                }.firstOrNull()
        } ?: error("Cannot find manifest for $image!")
    }

    @Throws(Exception::class)
    override fun eachBlob(function: (BlobRow) -> Unit) {
        jdbi.useHandle<Exception> { handle ->
            handle.createQuery("SELECT * FROM BLOBS").map { rs, _ ->
                BlobRow.fromResultSet(rs)
            }.forEach { function(it) }
        }
    }

    override fun getBlob(imageVersion: ImageVersion, handler: (InputStream, Handle) -> Unit) {
        jdbi.useHandle<Exception> { handle ->
            val stream = handle.createQuery("select * from blobs where digest = :digest")
                .bind("digest", imageVersion.tag)
                .map { rs, _ ->
                    rs.getBinaryStream("content")
                }.first()
            handler(stream, handle)
        }
    }

    override fun countBlobs(): Long {
        return jdbi.withHandle<Long, Exception> { handle ->
            handle.createQuery("select count(*) as count from blobs")
                .map { rs, _ ->
                    rs.getLong("count")
                }.first()
        }
    }

    override fun removeManifest(image: ImageVersion) {
        jdbi.useTransaction<Exception> { handle ->
            val deletedRows = handle.createUpdate("DELETE FROM manifests WHERE name = :name AND tag = :tag")
                .bind("name", image.name)
                .bind("tag", image.tag)
                .execute()
            logger.info("Removed manifest for $image, deleted $deletedRows rows")
            handle.commit()
        }
    }

    override fun listRepositories(): List<String> {
        return jdbi.withHandle<List<String>, Exception> { handle ->
            handle.createQuery("SELECT DISTINCT name FROM manifests ORDER BY name")
                .map { rs, _ -> rs.getString("name") }
                .list()
        }
    }

    override fun listTags(repository: String): List<String> {
        return jdbi.withHandle<List<String>, Exception> { handle ->
            handle.createQuery("SELECT tag FROM manifests WHERE name = :name ORDER BY tag")
                .bind("name", repository)
                .map { rs, _ -> rs.getString("tag") }
                .list()
        }
    }

    override fun garbageCollect(): GarbageCollectionResult {
        return jdbi.inTransaction<GarbageCollectionResult, Exception> { handle ->
            var blobsRemoved = 0
            var spaceFreed = 0L
            var manifestsRemoved = 0
            
            try {
                // For now, implement a simple garbage collection that removes blobs without digests
                // (these are typically from failed uploads or incomplete sessions)
                val unreferencedBlobs = handle.createQuery("SELECT digest FROM blobs WHERE digest IS NULL")
                    .map { rs, _ -> rs.getString("digest") }.list()
                
                for (digest in unreferencedBlobs) {
                    val blobSize = handle.createQuery("SELECT LENGTH(content) as size FROM blobs WHERE digest IS NULL LIMIT 1")
                        .map { rs, _ -> rs.getLong("size") }.firstOrNull() ?: 0L
                    
                    val deleted = handle.createUpdate("DELETE FROM blobs WHERE digest IS NULL LIMIT 1")
                        .execute()
                    
                    if (deleted > 0) {
                        blobsRemoved++
                        spaceFreed += blobSize
                        logger.info("Garbage collected unreferenced blob (${blobSize} bytes)")
                    }
                }
                
                logger.info("Garbage collection completed: $blobsRemoved blobs removed, $spaceFreed bytes freed, $manifestsRemoved manifests removed")
                
            } catch (e: Exception) {
                logger.error("Error during garbage collection: ${e.message}")
                // Return empty result on error
            }
            
            GarbageCollectionResult(blobsRemoved, spaceFreed, manifestsRemoved)
        }
    }

    private fun calculateSHA256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { inputStream ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                md.update(buffer, 0, bytesRead)
            }
        }
        val digest = md.digest()
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Clean up resources and close connections
     */
    fun cleanup() {
        try {
            logger.info("Starting H2BlobStore cleanup...")
            
            // Shutdown cleanup executor
            cleanupExecutor.shutdown()
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("Cleanup executor did not terminate gracefully, forcing shutdown")
                cleanupExecutor.shutdownNow()
            }
            
            // Close database connections
            try {
                dataSource.close()
                logger.info("Database connections closed successfully")
            } catch (e: Exception) {
                logger.error("Error closing database connections: ${e.message}")
            }
            
            logger.info("H2BlobStore cleanup completed")
        } catch (e: Exception) {
            logger.error("Error during H2BlobStore cleanup: ${e.message}")
        }
    }

    /**
     * Schedule cleanup of temporary files
     */
    private fun scheduleCleanup(cleanupTask: () -> Unit) {
        cleanupExecutor.submit {
            try {
                cleanupTask()
            } catch (e: Exception) {
                logger.error("Error during scheduled cleanup: ${e.message}")
            }
        }
    }
}