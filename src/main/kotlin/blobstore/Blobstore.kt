package blobstore

import SessionID
import java.io.InputStream

inline class Digest(val digestString: String)

interface Blobstore {
    fun hasBlob(digest: Digest): Boolean
    fun nextSessionLocation(sessionID: SessionID): String
    fun removeBlob(digest: Digest)
    fun addBlob(sessionID: SessionID, blobNumber: Int?, bodyAsInputStream: InputStream): Int
    fun buildBlob(sessionID: SessionID, digest: Digest)
}