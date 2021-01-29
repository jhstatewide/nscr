package blobstore

import org.h2.jdbcx.JdbcDataSource
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.HandleConsumer
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
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
            handle.execute("CREATE TABLE IF NOT EXISTS blobs(digest varchar(256), content blob, CONSTRAINT unique_digest UNIQUE (digest));")
            handle.commit()
            logger.info("H2 Blobstore initialized!")
        }
    }

    private val uploadedUUIDs = mutableSetOf<Digest>()

    override fun hasBlob(digest: Digest): Boolean {
        val query = "SELECT COUNT(*) as matching_blob_count FROM blobs where digest = :digest;"
        return jdbi.withHandle<Boolean, Exception> { handle ->
            val statement = handle.createQuery(query).bind("digest", digest.digestString)
            statement.map { rs, ctx ->
                rs.getInt("matching_blob_count") > 0
            }.first()
        }
    }

    override fun addBlob(digest: Digest, inputStream: InputStream) {
        jdbi.useTransaction<RuntimeException> { handle ->
            val statement = handle.connection.prepareStatement("INSERT INTO blobs(digest, content) values (?, ?)")
            statement.setString(1, digest.digestString)
            statement.setBinaryStream(2, inputStream)
            val result = statement.executeUpdate()
            logger.info("Blob inserted for ${digest.digestString}. Result: $result")
            handle.commit()
        }
        uploadedUUIDs.add(digest)
    }

    override fun removeBlob(digest: Digest) {
        TODO("Not yet implemented")
    }
}