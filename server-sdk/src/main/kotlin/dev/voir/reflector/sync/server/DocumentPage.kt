package dev.voir.reflector.sync.server

import dev.voir.reflector.sync.protocol.PageToken

/**
 * One page of stored documents.
 *
 * @property documents Documents of this page, ordered by their identifier so that paging is stable
 *   while other clients keep writing.
 * @property nextPage Token of the next page, or `null` when this was the last one.
 */
public data class DocumentPage(
    public val documents: List<StoredDocument>,
    public val nextPage: PageToken?,
)
