package dev.voir.reflector.sync.core.conflict

import kotlin.jvm.JvmInline
import kotlin.uuid.Uuid

/**
 * Identifier of one open conflict.
 *
 * Generated locally when the conflict is detected and stored with it, so that a decision made by
 * the user survives a process restart and cannot be applied to a different conflict of the same
 * entity.
 *
 * @property value Locally generated UUID value. Conflicts are read by identity and ordered by
 *   their detection time, so its version is immaterial and none is required.
 */
@JvmInline
public value class ConflictId(
    public val value: Uuid,
)
