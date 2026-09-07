package dev.voir.reflector.sample.ledger.android

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * The two callbacks this sample cares about, with the other five left empty.
 *
 * `ActivityLifecycleCallbacks` has no default implementations, and a class overriding all seven to
 * use two of them hides which two those are.
 */
abstract class SimpleActivityLifecycleCallbacks : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
