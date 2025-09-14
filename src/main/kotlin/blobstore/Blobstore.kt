package blobstore

import SessionID
import org.jdbi.v3.core.Handle
import java.io.InputStream
import java.sql.ResultSet

@JvmInline
value class Digest(val digestString: String)

data class ImageVersion(val name: String, val tag: String)

data class GarbageCollectionResult(
    val blobsRemoved: Int,
    val spaceFreed: Long,
    val manifestsRemoved: Int
)

// This represents the abstract interface to the blobstore
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
    fun getBlob(imageVersion: ImageVersion, handler: (InputStream, Handle) -> Unit)
    fun countBlobs(): Long
    fun eachBlob(function: (BlobRow) -> Unit)
    fun removeManifest(image: ImageVersion)
    fun listRepositories(): List<String>
    fun listTags(repository: String): List<String>
    fun garbageCollect(): GarbageCollectionResult
    fun blobCountForSession(sessionID: SessionID): Int
}