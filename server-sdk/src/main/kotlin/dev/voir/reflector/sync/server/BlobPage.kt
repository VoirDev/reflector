package dev.voir.reflector.sync.server

import dev.voir.reflector.sync.protocol.PageToken

/**
 * One page of the files a collection holds.
 *
 * @property blobs Files of this page, ordered by identifier so that paging is stable while clients
 *   keep registering more.
 * @property nextPage Token of the next page, or `null` when this was the last one.
 */
public data class BlobPage(
    public val blobs: List<StoredBlob>,
    public val nextPage: PageToken?,
)
