package dev.voir.reflector.sync.core.blob

/**
 * Whether this device fetches a file as soon as it hears of it, or waits to be asked.
 *
 * The second editorial decision about a file, and the mirror of [BlobBinding]: that one is about
 * publishing a record without its file, this one is about holding a record without its file. Both
 * are the application's because only it knows what the file is for — a thumbnail in a list is
 * wanted before anybody asks for it, and the forty-megabyte original beside it is not.
 *
 * It decides whether a **download** starts on its own, and nothing else. A file this device created
 * is always uploaded whatever is declared here: the bytes are already local, the server is the one
 * side that does not have them, and a library that declined to send them would be losing data
 * rather than saving a transfer.
 */
public enum class BlobFetch {
    /**
     * The bytes are fetched as soon as a document names them.
     *
     * The default, and what an integration that thinks about none of this gets: every file a
     * document points at is on the device shortly after the record is, so a screen that draws one
     * never has to draw its absence.
     */
    EAGER,

    /**
     * The bytes are fetched only once the application asks, through
     * [dev.voir.reflector.sync.core.CollectionHandle.fetch].
     *
     * For a file that is large, or that most records never show: the record arrives, the reference
     * is known, and the state published for the file says the server has bytes this device does not
     * — which is a screen offering to download rather than an error. Once fetched, the file stays
     * until [dev.voir.reflector.sync.core.CollectionHandle.evict] gives it up, so the second time
     * that record is opened its file is simply there.
     */
    ON_DEMAND,
}
