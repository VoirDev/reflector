package dev.voir.reflector.sync.server

/**
 * What one pass of the blob collector found, and what it let go of.
 *
 * The report exists for the same reason [PurgeReport] does: a removal nobody can evidence afterwards
 * is the one an administrator is later asked about.
 *
 * It says nothing about what became of the objects themselves, and that absence is deliberate. The
 * module hands released blobs to [BlobStorage.onReleased] and forms no opinion about disposal —
 * deleting at once and keeping an object for a regulator's retention period are both correct, and
 * only the host can tell which applies. Whatever happened to the bytes is recorded where it
 * happened, in the host's own storage log, which is also the only evidence an auditor would accept.
 *
 * @property candidates Blobs found to have been unreferenced for longer than the retention window.
 *   In a dry run this is the whole of the answer.
 * @property released Blobs whose rows were dropped and whose keys were handed to the host.
 * @property dryRun Whether anything was actually removed.
 */
public data class BlobSweepReport(
    public val candidates: Int,
    public val released: Int,
    public val dryRun: Boolean,
) {
    /** Whether the pass found nothing to do. */
    public val isEmpty: Boolean
        get() = candidates == 0

    /**
     * Adds up two passes, so that a sweep can be reported as the sum of its collections.
     *
     * @param other Report to add to this one.
     * @return Totals of both.
     */
    internal operator fun plus(other: BlobSweepReport): BlobSweepReport =
        BlobSweepReport(
            candidates = candidates + other.candidates,
            released = released + other.released,
            dryRun = dryRun || other.dryRun,
        )

    public companion object {
        /** Report of a pass that found nothing. */
        public val Empty: BlobSweepReport = BlobSweepReport(0, 0, dryRun = false)
    }
}
