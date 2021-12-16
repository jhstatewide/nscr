package blobstore

import SessionID
import org.jdbi.v3.core.Handle
import org.jdbi.v3.sqlobject.Handler
import java.io.InputStream
import java.lang.StringBuilder

inline class Digest(val digestString: String)

data class ImageVersion(val name: String, val tag: String)

interface Blobstore {
    fun hasBlob(digest: Digest): Boolean
    fun nextSessionLocation(sessionID: SessionID): String
    fun removeBlob(digest: Digest)
    fun addBlob(sessionID: SessionID, blobNumber: Int?, bodyAsInputStream: InputStream): Long
    fun associateBlobWithSession(sessionID: SessionID, digest: Digest)
    fun addManifest(image: ImageVersion, digest: Digest, manifestJson: String)
    fun getManifest(image: ImageVersion): String
    fun hasManifest(image: ImageVersion): Boolean
    fun digestForManifest(image: ImageVersion): Digest
    fun eachBlob(function: (String) -> StringBuilder)
    fun getBlob(imageVersion: ImageVersion, handler: (InputStream, Handle) -> Unit)
}