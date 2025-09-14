package blobstore

import SessionID
import org.h2.jdbcx.JdbcDataSource
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream
import java.nio.file.Path
import kotlin.Exception
import kotlin.io.path.Path

class H2BlobStore(dataDirectory: Path = Path("./data/")): Blobstore {
    private val dataSource: JdbcDataSource = JdbcDataSource()
    private val jdbi: Jdbi
    private val logger = LoggerFactory.getLogger("H2BlobStore")

    init {
        dataSource.setURL("jdbc:h2:file:${dataDirectory.toAbsolutePath()}/blobstore")
        dataSource.user = "sa"
        dataSource.password = "sa"
        this.jdbi = Jdbi.create(dataSource)
        provisionTables()
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
    private fun blobCountForSession(sessionID: SessionID): Int = jdbi.withHandle<Int, Exception> { handle ->
        handle.createQuery("SELECT COUNT(*) as blobCount from blobs where sessionID = :sessionID")
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
            val fileInputStream = tempFile.inputStream()
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
            TODO("Not yet able to stitch multi-part uploads! BlobCount for ${sessionID.id} is $blobCount")
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
        val handle = jdbi.open()
        val stream = handle.createQuery("select * from blobs where digest = :digest")
            .bind("digest", imageVersion.tag)
            .map { rs, _ ->
                rs.getBinaryStream("content")
            }.first()
        handler(stream, handle)
    }

    override fun countBlobs(): Long {
        val handle = jdbi.open()
        val count = handle.createQuery("select count(*) as count from blobs")
            .map { rs, _ ->
                rs.getLong("count")
            }.first()
        return count
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
}