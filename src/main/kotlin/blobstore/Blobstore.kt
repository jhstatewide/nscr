package blobstore

import SessionID
import java.io.InputStream

inline class Digest(val digestString: String)

data class ImageVersion(val name: String, val tag: String)

interface Blobstore {
    fun hasBlob(digest: Digest): Boolean
    fun nextSessionLocation(sessionID: SessionID): String
    fun removeBlob(digest: Digest)
    fun addBlob(sessionID: SessionID, blobNumber: Int?, bodyAsInputStream: InputStream): Int
    fun buildBlob(sessionID: SessionID, digest: Digest)
    fun addManifest(image: ImageVersion, digest: Digest, manifestJson: String)
    fun getManifest(image: ImageVersion): String
    fun hasManifest(image: ImageVersion): Boolean
    fun digestForManifest(image: ImageVersion): Digest
}