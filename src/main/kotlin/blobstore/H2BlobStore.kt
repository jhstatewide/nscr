package blobstore

import java.io.InputStream

class H2BlobStore: Blobstore {
    override fun hasBlob(digest: Digest): Boolean {
        return false
    }

    override fun addBlob(digest: Digest, inputStream: InputStream) {
        TODO("Not yet implemented")
    }

    override fun removeBlob(digest: Digest) {
        TODO("Not yet implemented")
    }
}