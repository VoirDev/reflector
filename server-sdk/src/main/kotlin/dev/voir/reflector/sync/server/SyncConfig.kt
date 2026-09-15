package dev.voir.reflector.sync.server

import dev.voir.reflector.sync.protocol.CollectionId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Configuration of the module.
 *
 * The limits are part of the contract rather than protection from careless clients: without a bound
 * on a group, a client returning after a long offline period sends a request that fits neither in
 * memory nor in a timeout, and the failure lands where nothing can be done about it.
 *
 * @property collections Collections the module serves, keyed by identifier.
 * @property retention How long the change log and its tombstones are kept. It is the same number as
 *   the lifetime of a cursor: a client whose cursor fell out of the window has to bootstrap anyway,
 *   so keeping history longer buys nothing and keeping it shorter loses changes.
 * @property maxOperationsPerGroup Largest number of operations accepted in one group.
 * @property maxChangesPageSize Largest page the module serves when reading the log or a snapshot.
 * @property blobs Configuration of files, or `null` when this deployment serves none. Its absence is
 *   published to clients rather than kept private: an application configured to synchronise files
 *   against a server that has no storage behind it has a misconfiguration worth learning about at
 *   start-up instead of on the first photograph a user attaches.
 */
public data class SyncConfig(
    public val collections: Map<CollectionId, CollectionSpec>,
    public val retention: Duration = DEFAULT_RETENTION,
    public val maxOperationsPerGroup: Int = DEFAULT_MAX_OPERATIONS_PER_GROUP,
    public val maxChangesPageSize: Int = DEFAULT_MAX_PAGE_SIZE,
    public val blobs: BlobConfig? = null,
) {
    init {
        require(collections.isNotEmpty()) { "at least one collection has to be registered" }
        require(retention.isPositive()) { "retention has to be positive" }
        require(maxOperationsPerGroup > 0) { "group limit has to be positive" }
        require(maxChangesPageSize > 0) { "page limit has to be positive" }
        collections.forEach { (id, spec) ->
            require(id == spec.id) { "collection ${spec.id.value} is registered under the key ${id.value}" }
        }
    }

    /**
     * Returns the specification of a collection.
     *
     * @param collection Collection to look up.
     * @return Registered specification.
     * @throws UnknownCollectionException When the collection is not registered.
     */
    public fun require(collection: CollectionId): CollectionSpec =
        collections[collection] ?: throw UnknownCollectionException(collection)

    public companion object {
        /** Long enough to cover a holiday offline, short enough to keep the log bounded. */
        public val DEFAULT_RETENTION: Duration = 30.days

        /** Beyond this a group stops being an envelope and becomes a migration. */
        public const val DEFAULT_MAX_OPERATIONS_PER_GROUP: Int = 500

        /** Page size that keeps one response bounded without making catch-up chatty. */
        public const val DEFAULT_MAX_PAGE_SIZE: Int = 500
    }
}

/**
 * Builds a configuration from a set of collection specifications.
 *
 * @param collections Collections to serve.
 * @param retention How long history is kept.
 * @param maxOperationsPerGroup Largest number of operations in one group.
 * @param maxChangesPageSize Largest page served.
 * @param blobs Configuration of files, or `null` to serve none.
 * @return Configuration keyed by collection identifier.
 */
public fun syncConfig(
    collections: Set<CollectionSpec>,
    retention: Duration = SyncConfig.DEFAULT_RETENTION,
    maxOperationsPerGroup: Int = SyncConfig.DEFAULT_MAX_OPERATIONS_PER_GROUP,
    maxChangesPageSize: Int = SyncConfig.DEFAULT_MAX_PAGE_SIZE,
    blobs: BlobConfig? = null,
): SyncConfig =
    SyncConfig(
        collections = collections.associateBy { it.id },
        retention = retention,
        maxOperationsPerGroup = maxOperationsPerGroup,
        maxChangesPageSize = maxChangesPageSize,
        blobs = blobs,
    )
