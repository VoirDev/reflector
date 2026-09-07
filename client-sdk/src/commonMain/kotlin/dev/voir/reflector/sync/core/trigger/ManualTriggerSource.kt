package dev.voir.reflector.sync.core.trigger

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Trigger source the application pushes into.
 *
 * This is where platform integration lands: an Android lifecycle observer, an iOS foreground
 * notification, a network callback or a background task all end in one call to [fire]. Keeping that
 * translation in the application is what lets the library stay free of platform APIs — and lets an
 * application that knows better about its own lifecycle do something smarter.
 *
 * ```kotlin
 * val triggers = ManualTriggerSource()
 * lifecycleOwner.lifecycle.addObserver(
 *     LifecycleEventObserver { _, event ->
 *         if (event == Lifecycle.Event.ON_START) triggers.fire(SyncTrigger.FOREGROUND)
 *     },
 * )
 * ```
 */
public class ManualTriggerSource : SyncTriggerSource {
    // One trigger is replayed: an application that fires while the engine is still starting must
    // not lose it, and an extra synchronisation costs nothing.
    private val signals = MutableSharedFlow<SyncTrigger>(replay = 1, extraBufferCapacity = BUFFER)

    override fun triggers(): Flow<SyncTrigger> = signals.asSharedFlow()

    /**
     * Asks for synchronisation.
     *
     * Never suspends and never fails: a dropped trigger means the next one — or the timer — does the
     * work, and blocking a lifecycle callback would be worse than being a little late.
     *
     * @param trigger Reason to synchronise.
     */
    public fun fire(trigger: SyncTrigger) {
        signals.tryEmit(trigger)
    }

    private companion object {
        /** More than enough for lifecycle events, which arrive in ones and twos. */
        const val BUFFER = 16
    }
}
