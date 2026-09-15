package dev.voir.reflector.sync.server

/**
 * What one purge physically removed.
 *
 * Every number is a count of rows deleted, not of entities the host would recognise: one entity
 * that was written five times contributes one row to [entities] and five to [changes]. The report
 * exists so that a host can put the erasure in its own audit log — a deletion that cannot be
 * evidenced afterwards is the one kind of deletion an administrator is asked about.
 *
 * A purge that found nothing returns [Empty] rather than failing: a scope that was never written to
 * and a scope that was purged twice are the same situation, and neither is an error.
 *
 * @property collections Collection rows removed, which is how many collections the scope had.
 * @property batches Log batches removed.
 * @property changes Individual changes removed, across every batch.
 * @property entities Entity rows removed, tombstones included.
 * @property pushResults Stored push outcomes removed, which is the idempotency history.
 */
public data class PurgeReport(
    public val collections: Int,
    public val batches: Int,
    public val changes: Int,
    public val entities: Int,
    public val pushResults: Int,
) {
    /** Whether nothing was there to remove. */
    public val isEmpty: Boolean
        get() = collections == 0 && batches == 0 && changes == 0 && entities == 0 && pushResults == 0

    /**
     * Adds up two reports, so that a scope can be reported as the sum of its collections.
     *
     * @param other Report to add to this one.
     * @return Totals of both.
     */
    internal operator fun plus(other: PurgeReport): PurgeReport =
        PurgeReport(
            collections = collections + other.collections,
            batches = batches + other.batches,
            changes = changes + other.changes,
            entities = entities + other.entities,
            pushResults = pushResults + other.pushResults,
        )

    public companion object {
        /** Report of a purge that found nothing. */
        public val Empty: PurgeReport = PurgeReport(0, 0, 0, 0, 0)
    }
}
