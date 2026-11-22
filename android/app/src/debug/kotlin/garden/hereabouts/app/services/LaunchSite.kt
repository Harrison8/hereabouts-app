// Debug builds only. The tests run inside the app's own process, so they choose the services' address here before
// they open the app. The test runner sets underTest, and with it the services reach no address at all unless a test
// names one, such as a stand-in server on 127.0.0.1. So no test ever fetches the real data or sends a real event.
// A test can also make the app act as if the phone had no signal, to see the no-signal line.
// Under test the app hands no link to another app either: it notes the link in `opened` instead, so no browser or maps
// app covers the screen, and the tests read what would have opened.
// A test can also set the clock, give the phone's answer about notifications so no system question covers the screen,
// and read each reminder the app arms.
// The opening, the half second of motion on a fresh launch, can be turned off or held still at one moment by an extra
// on the intent that starts the screen. A test can also count the phone's animations as on or off whatever the phone
// says, and keep the phone's loading screen up, to photograph it. It can read how long the screen took from being made
// to drawing its first frame.
// A Release build has a twin of this file that always gives the website and has no switches.
package garden.hereabouts.app.services

import android.content.Intent
import android.os.Build
import garden.hereabouts.core.Reminder
import java.time.ZonedDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

object TestSwitches {
    /** True while the instrumented tests run. */
    @Volatile
    var underTest: Boolean = false

    /** Under test, the address the services talk to (an http or https address), or null for none. */
    @Volatile
    var site: String? = null

    /** Under test, true to make the app act as if the phone had no signal. Read when the app opens. */
    @Volatile
    var offline: Boolean = false

    /** Under test, every link the app would have handed to another app, oldest first. */
    val opened: MutableList<Intent> = CopyOnWriteArrayList()

    /** Under test, the time the app takes as now, time zone included, or null for the phone's own clock. */
    @Volatile
    var now: ZonedDateTime? = null

    /**
     * Under test, what the phone says about notifications: "allowed"; "willAllow" or "willDeny" for a phone not
     * asked yet, which gives that answer when asked; or null for one that does not allow them. Whether the app asked
     * before is the app's own kept record, which a test sets like any other kept value.
     */
    @Volatile
    var notifications: String? = null

    /** Under test, how many times the app asked the phone whether it may send notifications. */
    val asks = AtomicInteger(0)

    /** Under test, every reminder the app armed, oldest first; null where it cancelled the one waiting. */
    val armed: MutableList<Reminder?> = CopyOnWriteArrayList()

    /**
     * Under test, whether the phone's animations count as on, or null to ask the phone. The test phones run with
     * animations off, so the opening's own tests turn them on here. Read when a screen is made.
     */
    @Volatile
    var animations: Boolean? = null

    /** Under test, true to keep the phone's loading screen up: the app draws its first frame once this is false. */
    @Volatile
    var holdFirstFrame: Boolean = false

    /** Under test, how long the last screen made took, in milliseconds, from being made to drawing its first frame. */
    @Volatile
    var firstFrameAfterMs: Long? = null
}

/** The intent extra (true) that turns the opening off, so the tests that are not about it do not wait for it. */
const val SKIP_OPENING = "garden.hereabouts.app.SKIP_OPENING"

/** The intent extra (a Double, in milliseconds) that holds the opening still at that moment, to photograph it. */
const val HOLD_OPENING_MS = "garden.hereabouts.app.HOLD_OPENING_MS"

/** The address the services talk to in this build: the website, or under test what the tests chose. */
internal fun launchSite(): String? {
    if (!TestSwitches.underTest) return WEBSITE
    val site = TestSwitches.site ?: return null
    return site.takeIf { it.startsWith("http://") || it.startsWith("https://") }
}

/** Whether this build acts as if it had no signal: only under test, when a test asks. */
internal fun forcedOffline(): Boolean = TestSwitches.underTest && TestSwitches.offline

/**
 * Under test, notes `intent` in TestSwitches.opened instead of starting it, and says whether an app would have taken
 * it. The test phone counts as having a browser and no maps app, so a geo: link is refused and Directions falls back
 * to the Google Maps link the same way on every Android version. Null when not under test: start it for real.
 */
internal fun openedUnderTest(intent: Intent): Boolean? {
    if (!TestSwitches.underTest) return null
    TestSwitches.opened.add(intent)
    return intent.data?.scheme != "geo"
}

/** Under test, the time the test chose, if it chose one. Null otherwise: read the phone's clock. */
internal fun clockUnderTest(): ZonedDateTime? = if (TestSwitches.underTest) TestSwitches.now else null

/**
 * Under test, whether the phone allows the app's notifications now, in place of asking the phone. Before Android 13
 * there is no question and the phone allows them until the person turns them off, so there a phone that would allow
 * them already does. Null otherwise: ask the phone.
 */
internal fun phoneAllowsUnderTest(): Boolean? {
    if (!TestSwitches.underTest) return null
    val answer = TestSwitches.notifications
    return answer == "allowed" || (Build.VERSION.SDK_INT < 33 && answer == "willAllow")
}

/**
 * Under test, counts the question and gives the answer the test chose in place of the phone's question, which the
 * phone then keeps, so no system question covers the screen. Null otherwise: ask the phone.
 */
internal fun askedUnderTest(): Boolean? {
    if (!TestSwitches.underTest) return null
    TestSwitches.asks.incrementAndGet()
    val granted = TestSwitches.notifications == "willAllow" || TestSwitches.notifications == "allowed"
    TestSwitches.notifications = if (granted) "allowed" else null
    return granted
}

/** Under test, notes the reminder armed, or null for none. The alarm itself is still set. */
internal fun armedUnderTest(reminder: Reminder?) {
    if (TestSwitches.underTest) TestSwitches.armed.add(reminder)
}

/** Under test, whether the phone's animations count as on, if a test chose. Null otherwise: ask the phone. */
internal fun animationsUnderTest(): Boolean? = if (TestSwitches.underTest) TestSwitches.animations else null

/** Under test, whether the intent that started the screen turns the opening off. */
internal fun openingSkippedForTests(intent: Intent?): Boolean =
    TestSwitches.underTest && intent?.getBooleanExtra(SKIP_OPENING, false) == true

/** Under test, the moment the intent that started the screen holds the opening at, if it names one. */
internal fun openingHeldForTests(intent: Intent?): Double? {
    if (!TestSwitches.underTest || intent?.hasExtra(HOLD_OPENING_MS) != true) return null
    return intent.getDoubleExtra(HOLD_OPENING_MS, 0.0)
}

/** Under test, whether a test keeps the phone's loading screen up for now. */
internal fun firstFrameHeldForTests(): Boolean = TestSwitches.underTest && TestSwitches.holdFirstFrame

/** Under test, notes how long the screen took from being made to drawing its first frame. */
internal fun firstFrameDrawnForTests(afterMs: Long) {
    if (TestSwitches.underTest) TestSwitches.firstFrameAfterMs = afterMs
}

/** Whether the opening leaves a mark the tests can read once it has been on screen: only under test. */
internal fun openingMarkForTests(): Boolean = TestSwitches.underTest
