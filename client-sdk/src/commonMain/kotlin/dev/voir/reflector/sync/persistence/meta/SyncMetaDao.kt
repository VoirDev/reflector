package dev.voir.reflector.sync.persistence.meta

import androidx.room3.ColumnTypeConverters
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import dev.voir.reflector.sync.persistence.SyncColumnConverters

/** Access to the per-scope identity of this installation. */
@Dao
@ColumnTypeConverters(SyncColumnConverters::class)
public interface SyncMetaDao {
    /**
     * Reads the identity of a scope.
     *
     * @param scopeId Scope to read.
     * @return Stored identity, or `null` when the scope has never been opened on this installation.
     */
    @Query("SELECT * FROM sync_meta WHERE scope_id = :scopeId")
    public suspend fun find(scopeId: String): SyncMetaEntity?

    /**
     * Stores a freshly generated identity.
     *
     * Inserted, never replaced: overwriting the client identifier of a scope silently breaks echo
     * suppression, so a change has to fail loudly and be followed by a bootstrap.
     *
     * @param meta Identity to store.
     */
    @Insert
    public suspend fun insert(meta: SyncMetaEntity)

    /**
     * Removes the identity of a scope, used when the scope's data is wiped.
     *
     * @param scopeId Scope to wipe.
     */
    @Query("DELETE FROM sync_meta WHERE scope_id = :scopeId")
    public suspend fun delete(scopeId: String)
}
