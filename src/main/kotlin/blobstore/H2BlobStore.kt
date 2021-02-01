package blobstore

import SessionID
import org.h2.jdbcx.JdbcDataSource
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.HandleConsumer
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.Exception


class H2BlobStore: Blobstore {
    private val dataSource: JdbcDataSource = JdbcDataSource()
    private val jdbi: Jdbi
    private val logger = LoggerFactory.getLogger("H2BlobStore")

    init {
        dataSource.setURL("jdbc:h2:./data/blobstore")
        dataSource.user = "sa"
        dataSource.password = "sa"
        this.jdbi = Jdbi.create(dataSource)
        provisionTables()
    }

    private fun provisionTables() {
        jdbi.useTransaction<RuntimeException> { handle: Handle ->
            handle.execute("CREATE TABLE IF NOT EXISTS blobs(sessionID varchar(256), blobNumber int, digest varchar(256), content blob, CONSTRAINT unique_digest UNIQUE (digest));")
            handle.commit()
            logger.info("H2 Blobstore initialized!")
        }
    }

    private val uploadedUUIDs = mutableSetOf<Digest>()

    override fun nextSessionLocation(sessionID: SessionID): String {
        // count # of blobs with matching session ID
        // return session ID + the sequence...
        val sessionBlobCount = jdbi.withHandle<Int, Exception> { handle ->
            handle.createQuery("SELECT COUNT(*) as blobCount from blobs where sessionID = :sessionID")
                .bind("sessionID", sessionID.id).map { rs, ctx -> rs.getInt("blobCount")}.first() ?: 0
        }
        return sessionID.id + "/" + sessionBlobCount
    }

    override fun hasBlob(digest: Digest): Boolean {
        val query = "SELECT COUNT(*) as matching_blob_count FROM blobs where digest = :digest;"
        return jdbi.withHandle<Boolean, Exception> { handle ->
            val statement = handle.createQuery(query).bind("digest", digest.digestString)
            statement.map { rs, ctx ->
                rs.getInt("matching_blob_count") > 0
            }.first()
        }
    }

    fun addBlob(digest: Digest, bytes: ByteArray) {
        addBlob(digest, ByteArrayInputStream(bytes))
    }

    override fun addBlob(digest: Digest, inputStream: InputStream) {
        // let's slurp the stream to a variable and see how big it is...
        val slurped = inputStream.readAllBytes()
        logger.info("Size of blob in addBlob: ${slurped.size}")

        jdbi.useTransaction<RuntimeException> { handle ->
            val statement = handle.connection.prepareStatement("INSERT INTO blobs(digest, content) values (?, ?)")
            statement.setString(1, digest.digestString)
            statement.setBytes(2, slurped)
            val result = statement.executeUpdate()
            handle.commit()
            logger.info("Blob inserted for ${digest.digestString}. Result: $result")
        }
        uploadedUUIDs.add(digest)
    }

    override fun addBlob(sessionID: SessionID, blobNumber: Int?, bodyAsInputStream: InputStream) {
        // let's slurp the stream to a variable and see how big it is...
        val slurped = bodyAsInputStream.readAllBytes()
        logger.info("Size of blob in addBlob: ${slurped.size}")

        jdbi.useTransaction<RuntimeException> { handle ->
            val statement = handle.connection.prepareStatement("INSERT INTO blobs(sessionID, blobNumber, content) values (?, ?, ?)")
            statement.setString(1, sessionID.id)
            if (blobNumber != null) {
                statement.setInt(2, blobNumber)
            }
            statement.setBinaryStream(3, bodyAsInputStream)
            val result = statement.executeUpdate()
            handle.commit()
            logger.info("Blob inserted for ${sessionID.id}/${blobNumber}. Result: $result")
        }
    }

    override fun removeBlob(digest: Digest) {
        TODO("Not yet implemented")
    }

    override fun buildBlob(sessionID: SessionID, digest: Digest) {
        TODO("Not yet implemented")
    }
}