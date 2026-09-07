package dev.voir.reflector.sync.core.adapter

import dev.voir.reflector.sync.core.conflict.Conflict
import dev.voir.reflector.sync.core.conflict.Resolution
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import kotlinx.serialization.json.JsonObject

/**
 * Bridge between the library and the application's own tables, implemented by the application.
 *
 * The library stores synchronisation metadata only. It never reads or writes business rows itself,
 * because the schema of those rows belongs to the application and its UI queries them relationally.
 * Everything that touches them goes through this adapter.
 *
 * Every method is called inside the library's transaction, together with the metadata changes it
 * belongs to. That is the whole point of keeping both in one database: a crash cannot leave applied
 * data with an unadvanced cursor, or the other way round. An implementation must therefore not
 * start its own transaction, perform network calls, or block on anything outside the database.
 */
public interface CollectionAdapter {
    /**
     * Shape of the application's tables behind this collection, or `null` to declare none.
     *
     * Opt-in, and the whole of the opting in: an application that returns a fingerprint here has the
     * library rebuild the collection from a snapshot whenever the value changes between two runs,
     * and one that leaves this `null` keeps today's behaviour, where a migration that invalidates
     * the synchronised rows is followed by an explicit
     * [dev.voir.reflector.sync.core.CollectionHandle.requestResync].
     *
     * Both are supported because they fail differently. Calling `requestResync` after a migration
     * works and costs nothing — until the day somebody forgets, and then nothing at all happens: the
     * cursor moves on over tables that no longer hold what the library thinks they hold, silently
     * and for as long as the installation lives. A fingerprint cannot be forgotten, at the price of
     * the library holding one opinion about a schema it does not own — that a change to it means the
     * synchronised data has to be fetched again.
     *
     * The first run after an application starts declaring a fingerprint stores it without rebuilding
     * anything: nothing is known to have changed, and forcing every installation through a bootstrap
     * on the upgrade that introduced the declaration would be a large lie about a small fact.
     */
    public val schema: SchemaFingerprint?
        get() = null

    /**
     * Returns the current state of an entity for sending to the server.
     *
     * Called at push time, not at mutation time: the library materialises bodies lazily and sends
     * the state the entity has now. An entity edited twice locally is therefore sent once, in its
     * latest state.
     *
     * The result must be a JSON object, not an arbitrary element, because the server merges it into
     * the stored document by the keys present at the top level. Nulls must be written explicitly:
     * an omitted key keeps the stored value, while an explicit `null` clears it.
     *
     * @param entityType Type of the entity to serialise.
     * @param id Identifier of the entity to serialise.
     * @return Current state of the entity, or `null` when the row no longer exists locally, which
     *   the library treats as a deletion.
     */
    public suspend fun snapshot(
        entityType: EntityType,
        id: EntityId,
    ): JsonObject?

    /**
     * Applies incoming changes to the application's tables.
     *
     * The operations of one call belong to one server transaction and must be applied together.
     * A sweep after a bootstrap arrives here as well, as a list of deletions.
     *
     * @param ops Changes to apply, in the order the server committed them.
     */
    public suspend fun applyRemote(ops: List<RemoteOp>)

    /**
     * Reports a change the server refused permanently.
     *
     * The collection's queue is blocked until the application reacts, which is deliberate: the
     * alternative is dropping the user's change silently. A reaction usually means correcting the
     * data in a new mutation, or deleting the offending entity.
     *
     * @param entityType Type of the refused entity, or `null` when the refusal concerns the whole
     *   group.
     * @param id Identifier of the refused entity, or `null` when the refusal concerns the whole
     *   group.
     * @param rejection Reason the server gave.
     */
    public suspend fun onRejected(
        entityType: EntityType?,
        id: EntityId?,
        rejection: SyncRejection,
    )

    /**
     * Decides what to do with a conflict, if the application can decide without the user.
     *
     * Called for every conflict as soon as it is detected. Returning `null` leaves the conflict open
     * and publishes it through the collection's conflict flow, which is the path for decisions that
     * need a person; returning a resolution applies it immediately in the same transaction.
     *
     * There is no common ancestor to merge against. Storing one would mean the library keeps a copy
     * of the business data, which is exactly what it avoids; an application that needs an ancestor
     * can keep it itself.
     *
     * @param conflict Conflict to decide on, carrying both sides as they are known now.
     * @return Decision to apply, or `null` to hand the conflict to the application's user interface.
     */
    public suspend fun resolve(conflict: Conflict): Resolution?
}
