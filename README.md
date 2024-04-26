# New and Shiny Container Registry

## Why?

There are only a few implementations of container registries out there. I basically want the SQLite of container registries. A simple, easy to use, and easy to understand container registry.

## Example walkthrough when pushing an image

This registry is designed to be a valid target for the official Docker daemon to be able to push
and pull. What actually happens when we call `docker push`?

To push an image, we will be pushing at least two things: a manifest and a blob. The manifest is a JSON file that describes the image, and the blob is the actual image data. The manifest will contain a list of layers, which are the blobs that make up the image.

We first need to get a session ID. We get this by doing a POST to `/v2/<image-name>/blobs/uploads/`. This will return a session ID that we can use to upload the blob.

TODO: check that the endpoint actually exists