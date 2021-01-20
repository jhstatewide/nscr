package blobstore

import java.io.InputStream

class H2BlobStore: Blobstore {

    private val uploadedUUIDs = mutableSetOf<Digest>()

    override fun hasBlob(digest: Digest): Boolean {
        return uploadedUUIDs.contains(digest)
    }

    override fun addBlob(digest: Digest, inputStream: InputStream) {
        uploadedUUIDs.add(digest)
    }

    override fun removeBlob(digest: Digest) {
        TODO("Not yet implemented")
    }
}