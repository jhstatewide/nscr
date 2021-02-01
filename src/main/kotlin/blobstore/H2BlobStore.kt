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
        val sessionBlobCount = blobCountForSession(sessionID)
        return sessionID.id + "/" + sessionBlobCount
    }

    private fun blobCountForSession(sessionID: SessionID): Int = jdbi.withHandle<Int, Exception> { handle ->
        handle.createQuery("SELECT COUNT(*) as blobCount from blobs where sessionID = :sessionID")
            .bind("sessionID", sessionID.id).map { rs, _ -> rs.getInt("blobCount") }.first() ?: 0
    }

    override fun hasBlob(digest: Digest): Boolean {
        val query = "SELECT COUNT(*) as matching_blob_count FROM blobs where digest = :digest;"
        return jdbi.withHandle<Boolean, Exception> { handle ->
            val statement = handle.createQuery(query).bind("digest", digest.digestString)
            statement.map { rs, _ ->
                rs.getInt("matching_blob_count") > 0
            }.first()
        }
    }

    override fun addBlob(sessionID: SessionID, blobNumber: Int?, bodyAsInputStream: InputStream): Int {
        // TODO: eliminate slurp! use a filterInputSTream to count bytes???
        val content = bodyAsInputStream.readAllBytes()
        jdbi.useTransaction<RuntimeException> { handle ->
            val statement = handle.connection.prepareStatement("INSERT INTO blobs(sessionID, blobNumber, content) values (?, ?, ?)")
            statement.setString(1, sessionID.id)
            if (blobNumber != null) {
                statement.setInt(2, blobNumber)
            }
            statement.setBytes(3, content)
            val result = statement.executeUpdate()
            handle.commit()
            logger.info("Blob inserted for ${sessionID.id}/${blobNumber}. Result: $result")
        }
        return content.size
    }

    override fun removeBlob(digest: Digest) {
        TODO("Not yet implemented")
    }

    override fun buildBlob(sessionID: SessionID, digest: Digest) {
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
}