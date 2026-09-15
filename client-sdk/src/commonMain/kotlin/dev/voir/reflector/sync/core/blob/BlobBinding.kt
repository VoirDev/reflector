package dev.voir.reflector.sync.core.blob

/**
 * Whether a record may be published before the file it names.
 *
 * The one genuinely editorial decision in file synchronisation, and it is the application's because
 * only it knows what a record means without its file. A receipt is an improvement on a transaction;
 * an image is the whole of a photo post.
 *
 * It decides whether the **push waits**, and nothing else. The library never edits a document, so a
 * field naming a blob is visible to every device from the first push under either binding — what
 * differs is how long that first push is held back.
 */
public enum class BlobBinding {
    /**
     * The record goes out immediately and the file follows.
     *
     * The default, because the common case is that the record matters more than the file: a
     * transaction must not wait on a photograph stuck behind a hotel's captive portal. The other
     * devices see the record at once and its file when the bytes land, which is the same state they
     * already have to render for a large file that is still downloading.
     */
    DEFERRED,

    /**
     * The record waits in the queue until the file is usable on the server.
     *
     * For a record whose file is its content, where publishing one without the other would mislead.
     * It pays head-of-line blocking for that: the group waits, and because the queue is FIFO
     * everything behind it waits too.
     */
    REQUIRED,
}
