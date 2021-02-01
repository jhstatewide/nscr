package blobstore

import SessionID
import java.io.InputStream

inline class Digest(val digestString: String)

interface Blobstore {
    fun hasBlob(digest: Digest): Boolean
    fun addBlob(digest: Digest, inputStream: InputStream)
    fun nextSessionLocation(sessionID: SessionID): String
    fun removeBlob(digest: Digest)
}