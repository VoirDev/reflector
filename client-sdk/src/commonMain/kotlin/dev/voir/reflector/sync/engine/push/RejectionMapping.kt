package dev.voir.reflector.sync.engine.push

import dev.voir.reflector.sync.core.adapter.SyncRejection
import dev.voir.reflector.sync.protocol.push.RejectCode
import dev.voir.reflector.sync.protocol.push.RejectError

/**
 * Translates a refusal from the wire into the closed set the application reacts to.
 *
 * [RejectCode.DEPENDENCY] deliberately has no case here: it never reaches the application, because
 * the library answers it by merging groups and retrying. An unknown code becomes
 * [SyncRejection.Unknown] and is treated as permanent, which is the safe default — the alternative
 * is retrying forever a change the server has already decided about.
 *
 * @receiver Refusal as the server reported it.
 * @return Reason in the application's terms.
 */
internal fun RejectError.toRejection(): SyncRejection =
    when (code) {
        RejectCode.VALIDATION -> SyncRejection.Validation(message)
        RejectCode.UNKNOWN_ENTITY_TYPE -> SyncRejection.UnknownEntityType(message)
        RejectCode.TOO_LARGE -> SyncRejection.TooLarge(message)
        else -> SyncRejection.Unknown(code.value, message)
    }
