package dev.voir.reflector.sync.protocol.config

import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Limits and retention window published by the server.
 *
 * The client fetches them once at start-up and caches them. Knowing the limits before an envelope
 * is assembled is a requirement, not a convenience: a group cannot be split without breaking its
 * atomicity, so an oversized group has to be detected locally and surfaced to the application
 * instead of failing on the wire after a long offline period.
 *
 * @property maxOperationsPerGroup Largest number of operations the server accepts in one group.
 * @property maxDocumentBytes Largest serialised entity document the server accepts.
 * @property maxChangesPageSize Largest page the server serves when reading the change log.
 * @property retentionDays History window in whole days. A cursor older than the window is refused
 *   with a stale-cursor error, so a client that has been offline longer can go to a bootstrap
 *   directly instead of trying a doomed pull first.
 */
@Serializable
public data class SyncLimits(
    public val maxOperationsPerGroup: Int,
    public val maxDocumentBytes: Int,
    public val maxChangesPageSize: Int,
    public val retentionDays: Int,
) {
    /** Retention window as a duration, for comparisons against locally measured offline time. */
    public val retention: Duration
        get() = retentionDays.days
}
