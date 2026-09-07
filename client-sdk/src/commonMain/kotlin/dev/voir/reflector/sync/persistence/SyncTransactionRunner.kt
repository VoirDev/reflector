package dev.voir.reflector.sync.persistence

/**
 * Runs a block as one transaction of the application's database.
 *
 * The library needs a programmatic transaction rather than annotated DAO methods, because the work
 * inside it spans both sides of the boundary: the application's adapter writes its rows, the
 * library writes its metadata, and the two must commit or roll back together.
 *
 * The interface exists so that the engine does not depend on Room directly, which also lets tests
 * run the same code against a plain in-memory implementation.
 */
public interface SyncTransactionRunner {
    /**
     * Runs [block] in a write transaction, committing when it returns and rolling back when it
     * throws.
     *
     * The block must stay on the calling coroutine: the database connection is confined to it, and
     * dispatching part of the work elsewhere fails at runtime rather than silently escaping the
     * transaction. It must also not perform network calls — a connection is a scarce resource and
     * holding one across a request is how a client ends up dead-locking itself.
     *
     * @param block Work to perform inside the transaction.
     * @return Whatever the block returns.
     */
    public suspend fun <R> transaction(block: suspend () -> R): R
}
