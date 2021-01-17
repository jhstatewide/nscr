package blobstore

import java.io.InputStream

inline class Digest(val digestString: String)

interface Blobstore {
    fun hasBlob(digest: Digest): Boolean
    fun addBlob(digest: Digest, inputStream: InputStream)
    fun removeBlob(digest: Digest)
}