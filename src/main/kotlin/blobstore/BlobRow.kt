package blobstore

import java.io.InputStream

// this represents a row in the blobs table
// it has the columns sessionID, blobNumber, digest, and content
class BlobRow(
    var sessionID: String,
    var blobNumber: Int,
    var digest: String?,
    var content: ByteArray
) {

    // this is a convenience method to get the content as an input stream
    fun contentAsInputStream(): InputStream {
        return content.inputStream()
    }

    companion object {
        fun fromResultSet(rs: java.sql.ResultSet): BlobRow {
            return BlobRow(
                rs.getString("sessionID"),
                rs.getInt("blobNumber"),
                rs.getString("digest"),
                rs.getBytes("content")
            )
        }
    }
}
