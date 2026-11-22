// A Release build always talks to the website. The Debug build's twin of this file lets the tests choose instead.
package garden.hereabouts.app.services

import android.content.Intent
import garden.hereabouts.core.Reminder
import java.time.ZonedDateTime

/** The address the services talk to in this build. */
internal fun launchSite(): String? = WEBSITE

/** Whether this build acts as if it had no signal. Never in a Release build. */
internal fun forcedOffline(): Boolean = false

/** A Release build always hands a link to the phone's own app for it. */
@Suppress("UNUSED_PARAMETER")
internal fun openedUnderTest(intent: Intent): Boolean? = null

/** A Release build always reads the phone's clock. */
internal fun clockUnderTest(): ZonedDateTime? = null

/** A Release build always asks the phone whether it allows notifications. */
internal fun phoneAllowsUnderTest(): Boolean? = null

/** A Release build always asks the phone itself. */
internal fun askedUnderTest(): Boolean? = null

/** A Release build notes nothing. */
@Suppress("UNUSED_PARAMETER")
internal fun armedUnderTest(reminder: Reminder?) {}

/** A Release build always asks the phone whether its animations are on. */
internal fun animationsUnderTest(): Boolean? = null

/** A Release build always plays the opening when the rules say it plays. */
@Suppress("UNUSED_PARAMETER")
internal fun openingSkippedForTests(intent: Intent?): Boolean = false

/** A Release build never holds the opening still. */
@Suppress("UNUSED_PARAMETER")
internal fun openingHeldForTests(intent: Intent?): Double? = null

/** A Release build draws its first frame as soon as it is ready. */
internal fun firstFrameHeldForTests(): Boolean = false

/** A Release build notes no times for tests. */
@Suppress("UNUSED_PARAMETER")
internal fun firstFrameDrawnForTests(afterMs: Long) {}

/** A Release build leaves no mark for tests. */
internal fun openingMarkForTests(): Boolean = false
