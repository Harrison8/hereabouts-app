// The opening, "Your map fills in": on a fresh launch it plays once over the first screen and goes within a second,
// leaving the first screen tappable. It does not play again when the app comes back from the background, when the
// screen is made again, or in a second screen in the same process. It does not play with the phone's animations off,
// when a tapped reminder opened the app, or when a test turns it off; a launch from the recent apps list is not a
// reminder tap, so it plays there. Touches are held back until the circle starts at 250 ms and reach the screen from
// then on. Its first frame sits exactly where the phone's own loading screen put the tag, at the same size.
//
// The test phones run with animations off, so these tests count them as on. Every other screen test turns the opening
// off. Screenshots of the loading screen and of the frames at 150 ms and 350 ms are saved on the device, where the
// workflow collects them, with the times the screen took to draw its first frame with and without the opening.
package garden.hereabouts.app

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import garden.hereabouts.app.opening.Opening
import garden.hereabouts.app.opening.OpeningMap
import garden.hereabouts.app.plant.MapShapes
import garden.hereabouts.app.plant.MapShapesStore
import garden.hereabouts.app.reminders.OPEN_GARDEN
import garden.hereabouts.app.services.HOLD_OPENING_MS
import garden.hereabouts.app.services.SKIP_OPENING
import garden.hereabouts.app.services.TestSwitches
import garden.hereabouts.core.Location
import garden.hereabouts.core.LocationLookup
import garden.hereabouts.core.LocationResult
import garden.hereabouts.core.OpeningStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class OpeningScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: StubServer

    private val fairfax: Location by lazy {
        val (_, tables) = AppModel.loadShipped(context)
        (LocationLookup.resolve("51059", 38.8462, -77.3064, tables) as LocationResult.Found).location
    }

    @Before
    fun start() {
        server = StubServer()
        TestSwitches.site = server.baseUrl
        TestSwitches.animations = true
    }

    @After
    fun stop() {
        server.stop()
        TestSwitches.site = InstrumentationRegistry.getArguments().getString(HereaboutsTestRunner.BASE_URL_ARGUMENT)
        TestSwitches.animations = null
        TestSwitches.holdFirstFrame = false
    }

    // Opening the app

    /** Keeps Fairfax County, or no county at all, with nothing else kept. */
    private fun keep(location: Location?) {
        AppProcess.forget()
        runBlocking {
            context.keptStore.edit {
                it.clear()
                if (location != null) LocationStore.save(it, location)
            }
        }
    }

    /** The launcher's intent for the app's screen, with the opening playing unless the extras say otherwise. */
    private fun intent(): Intent = Intent.makeMainActivity(ComponentName(context, MainActivity::class.java))

    // Reading the screen

    private fun exists(tag: String): Boolean =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    private fun tabsShowing(): Boolean = exists("tabs")

    private fun gardenShowing(): Boolean =
        compose.onAllNodes(hasTestTag("title") and hasText("My garden"), useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    /** What the opening's mark reads: "Opening played" or "Opening held", or null when it never came up. */
    private fun mark(): String? {
        val nodes = compose.onAllNodes(hasTestTag("openingShown"), useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
        return nodes.firstOrNull()?.config?.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
    }

    private fun waitFor(millis: Long, condition: () -> Boolean): Boolean = try {
        compose.waitUntil(millis) { condition() }
        true
    } catch (e: Throwable) {
        condition()
    }

    /** Waits `millis` with nothing to wait for, so anything that would happen has time to. */
    private fun pause(millis: Long) {
        waitFor(millis) { false }
    }

    // Plays once

    /**
     * On a fresh launch from the app icon the opening plays, leaves its mark, and goes within 1 s of the app's own
     * clock; the first screen is then tappable. Coming back from the background, the screen made again (as when the
     * phone is turned) and a second screen in the same process never show it again.
     */
    @Test
    fun itPlaysOnceOnAFreshLaunchAndTheFirstScreenIsTappableWithin1s() {
        keep(fairfax)
        ActivityScenario.launch<MainActivity>(intent()).use { scenario ->
            assertTrue("The opening never came up", waitFor(30_000) { mark() != null })
            assertEquals("Opening played", mark())
            val shownAt = compose.mainClock.currentTime
            assertTrue("The opening did not go", waitFor(10_000) { !exists("opening") })
            val took = compose.mainClock.currentTime - shownAt
            assertTrue("The opening lasted $took ms", took <= 1_000)
            assertTrue("No tab bar under the opening", tabsShowing())
            compose.onNodeWithTag("tab-Garden").performClick()
            assertTrue("The Garden tab did not open after the opening", waitFor(10_000) { gardenShowing() })
            assertEquals("The mark went with the opening", "Opening played", mark())

            // Back from the background.
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            pause(1_000)
            assertFalse("The opening came back after the background", exists("opening"))

            // The screen made again.
            scenario.recreate()
            assertTrue("No tab bar after the screen was made again", waitFor(30_000) { tabsShowing() })
            pause(1_000)
            assertFalse("The opening played again when the screen was made again", exists("opening"))
            assertEquals("The opening played again when the screen was made again", null, mark())
        }

        // A second screen in the same process, opened from the app icon again.
        ActivityScenario.launch<MainActivity>(intent()).use {
            assertTrue("No tab bar on the second screen", waitFor(30_000) { tabsShowing() })
            pause(1_000)
            assertEquals("The opening played again on a second screen", null, mark())
        }
    }

    /** With no county yet the opening plays too, and opens onto the first-open screen. */
    @Test
    fun withNoCountyItOpensOntoTheFirstOpenScreen() {
        keep(null)
        ActivityScenario.launch<MainActivity>(intent()).use {
            assertTrue("The opening never came up", waitFor(30_000) { mark() != null })
            assertEquals("Opening played", mark())
            assertTrue("The opening did not go", waitFor(10_000) { !exists("opening") })
            assertTrue("No ZIP field after the opening", waitFor(10_000) { exists("zipField") })
            assertFalse("The tab bar shows with no county", tabsShowing())
        }
    }

    // Does not play

    /** With the phone's animations off, the first screen shows straight away. */
    @Test
    fun itDoesNotPlayWithAnimationsOff() {
        TestSwitches.animations = false
        keep(fairfax)
        ActivityScenario.launch<MainActivity>(intent()).use {
            assertTrue("No tab bar", waitFor(30_000) { tabsShowing() })
            pause(1_000)
            assertEquals("The opening played with animations off", null, mark())
            assertFalse(exists("opening"))
        }
    }

    /** The other screen tests' switch turns it off. */
    @Test
    fun theTestsSwitchTurnsItOff() {
        keep(fairfax)
        ActivityScenario.launch<MainActivity>(intent().putExtra(SKIP_OPENING, true)).use {
            assertTrue("No tab bar", waitFor(30_000) { tabsShowing() })
            pause(1_000)
            assertEquals("The opening played with the tests' switch on", null, mark())
            assertFalse(exists("opening"))
        }
    }

    /**
     * A tapped reminder opens the app straight onto the Garden tab with no opening: the screen starts with the same
     * intent the reminder's tap gives it.
     */
    @Test
    fun itDoesNotPlayWhenAReminderOpenedTheApp() {
        keep(fairfax)
        val reminder = Intent(context, MainActivity::class.java).setAction(OPEN_GARDEN)
        ActivityScenario.launch<MainActivity>(reminder).use {
            assertTrue("The reminder did not open the Garden tab", waitFor(30_000) { gardenShowing() })
            pause(1_000)
            assertEquals("The opening played for a reminder's tap", null, mark())
            assertFalse(exists("opening"))
        }
    }

    /**
     * A fresh launch from the recent apps list repeats the intent that last started the screen, which may be a
     * reminder's. It is not a reminder tap: the opening plays, the app opens on the Plants tab, and this_week_opened is
     * not sent.
     */
    @Test
    fun aLaunchFromTheRecentAppsListPlaysItAndIsNotAReminderTap() {
        keep(fairfax)
        val recents = Intent(context, MainActivity::class.java)
            .setAction(OPEN_GARDEN)
            .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        ActivityScenario.launch<MainActivity>(recents).use { scenario ->
            scenario.onActivity {
                assertTrue(
                    "The screen was not started as from the recent apps list",
                    it.intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0,
                )
            }
            assertTrue("The opening never came up", waitFor(30_000) { mark() != null })
            assertEquals("Opening played", mark())
            assertTrue("The opening did not go", waitFor(10_000) { !exists("opening") })
            assertTrue("No tab bar", tabsShowing())
            pause(2_000)
            assertFalse("A launch from the recent apps list opened the Garden tab", gardenShowing())
            StubServer.waitFor(8) { server.postedEvents.any { it.name == "this_week_opened" } }
            assertFalse(
                "A launch from the recent apps list sent this_week_opened",
                server.postedEvents.any { it.name == "this_week_opened" },
            )
        }
    }

    // Touches, and the frames held still

    /**
     * Held at 150 ms, before the circle starts: a tap on a tab does not reach it. The frame is photographed as
     * opening-150.
     */
    @Test
    fun before250msTouchesDoNotReachTheScreen() {
        keep(fairfax)
        ActivityScenario.launch<MainActivity>(intent().putExtra(HOLD_OPENING_MS, 150.0)).use {
            assertTrue("The held opening never came up", waitFor(30_000) { mark() != null })
            assertEquals("Opening held", mark())
            assertTrue("The held opening is not on screen", exists("opening"))
            screenshot("opening-150")
            compose.onNodeWithTag("tab-Garden").performClick()
            pause(1_000)
            assertFalse("A tap reached the Garden tab through the opening at 150 ms", gardenShowing())
        }
    }

    /**
     * Held at 350 ms, after the circle has started: a tap on a tab reaches it. The frame is photographed as
     * opening-350.
     */
    @Test
    fun from250msTouchesReachTheScreen() {
        keep(fairfax)
        ActivityScenario.launch<MainActivity>(intent().putExtra(HOLD_OPENING_MS, 350.0)).use {
            assertTrue("The held opening never came up", waitFor(30_000) { mark() != null })
            assertEquals("Opening held", mark())
            assertTrue("The held opening is not on screen", exists("opening"))
            screenshot("opening-350")
            compose.onNodeWithTag("tab-Garden").performClick()
            assertTrue("A tap did not reach the Garden tab at 350 ms", waitFor(10_000) { gardenShowing() })
            assertTrue("The held opening went", exists("opening"))
        }
    }

    /**
     * The opening's first frame puts the tag exactly where the phone's own loading screen put it, at the same size:
     * on Android 12 and later the phone's splash screen, before that the window's background. The app is started as
     * the launcher starts it, and its first frame is held back to photograph the loading screen (opening-loading),
     * then shown and photographed held at 0 ms (opening-first-frame).
     */
    @Test
    fun theFirstFrameMatchesTheLoadingScreen() {
        keep(fairfax)
        TestSwitches.holdFirstFrame = true
        val start = intent()
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(HOLD_OPENING_MS, 0.0)
        // Android 13 and later show the app's icon on the splash screen only for a launch from the launcher, or when
        // asked to.
        val options = if (Build.VERSION.SDK_INT >= 33) {
            ActivityOptions.makeBasic().setSplashScreenStyle(android.window.SplashScreen.SPLASH_SCREEN_STYLE_ICON).toBundle()
        } else {
            null
        }
        context.startActivity(start, options)
        try {
            assertTrue("The screen did not start", waitFor(20_000) { screens() > 0 })
            Thread.sleep(2_000)
            val loading = takeScreenshot("opening-loading")
            TestSwitches.holdFirstFrame = false
            assertTrue("The held opening never came up", waitFor(30_000) { mark() != null })
            assertEquals("Opening held", mark())
            Thread.sleep(1_000)
            val first = takeScreenshot("opening-first-frame")

            val density = context.resources.displayMetrics.density
            val a = mossBox(loading)
            val b = mossBox(first)
            assertNotNull("No moss tag on the loading screen", a)
            assertNotNull("No moss tag in the first frame", b)
            a!!
            b!!
            val where = "loading screen $a, first frame $b, density $density, screen ${first.width} x ${first.height}"
            // The tag's drawing is 176 by 212 of its 256 units, at 160 dp for 256 units.
            val width = 176 / 256f * 160 * density
            val height = 212 / 256f * 160 * density
            assertTrue("The tag is not ${width}px wide: $where", abs(b.width() - width) <= 3 && abs(a.width() - width) <= 3)
            assertTrue("The tag is not ${height}px tall: $where", abs(b.height() - height) <= 3 && abs(a.height() - height) <= 3)
            assertTrue("The tag moved between the loading screen and the first frame: $where", abs(a.left - b.left) <= 2 && abs(a.top - b.top) <= 2)
            assertTrue("The tag is not centred across the screen: $where", abs(b.exactCenterX() - first.width / 2f) <= 2)
            // The drawing's middle is 2 units above the tag's middle.
            val middleY = first.height / 2f - 2 / 256f * 160 * density
            assertTrue("The tag is not centred down the whole screen: $where", abs(b.exactCenterY() - middleY) <= 2)
        } finally {
            TestSwitches.holdFirstFrame = false
            finishScreens()
        }
    }

    // How long the loading screen stays up

    /**
     * Not a pass-or-fail check of the time: measures how long the screen takes from being made to drawing its first
     * frame, with the opening and without it, each launch reading the data and the map's outlines afresh. The first
     * pair warms the test process and is left out. It also times one cold read of the map's outlines and one map
     * build on their own. The times are saved as the name of an empty file beside the screenshots, where the workflow
     * collects them. The test process is already running, so starting a process is not in these times.
     */
    @Test
    fun timeToTheFirstFrameWithAndWithoutTheOpening() {
        val withoutOpening = ArrayList<Long>()
        val withOpening = ArrayList<Long>()
        repeat(6) { round ->
            for (playing in listOf(false, true)) {
                keep(fairfax)
                MapShapesStore.forget()
                TestSwitches.firstFrameAfterMs = null
                val start = if (playing) intent() else intent().putExtra(SKIP_OPENING, true)
                ActivityScenario.launch<MainActivity>(start).use {
                    assertTrue("The first frame was never drawn", waitFor(30_000) { TestSwitches.firstFrameAfterMs != null })
                    val took = TestSwitches.firstFrameAfterMs!!
                    if (playing) assertTrue("The opening did not go", waitFor(10_000) { !exists("opening") })
                    if (round > 0) (if (playing) withOpening else withoutOpening).add(took)
                }
            }
        }
        MapShapesStore.forget()
        val readStart = SystemClock.elapsedRealtime()
        val shapes = runBlocking { MapShapesStore.get(context) }
        val readMs = SystemClock.elapsedRealtime() - readStart
        assertNotNull("The map's outlines did not read", shapes)
        val (data, _) = AppModel.loadShipped(context)
        val buildStart = SystemClock.elapsedRealtime()
        OpeningMap.build(shapes!!, data.geo, fairfax)
        val buildMs = SystemClock.elapsedRealtime() - buildStart
        val name = "opening-timing_api-${Build.VERSION.SDK_INT}" +
            "_without-ms_${withoutOpening.joinToString("-")}_with-ms_${withOpening.joinToString("-")}" +
            "_outlines-read-ms_${readMs}_map-build-ms_$buildMs.txt"
        shell("mkdir -p ${PlantsTest.SCREENSHOTS}")
        shell("touch ${PlantsTest.SCREENSHOTS}/$name")
        println(name)
    }

    /** A map that fails to read or build leaves the tag on kraft: the screen still draws, and nothing crashes. */
    @Test
    fun aMapThatFailsLeavesTheTagOnKraft() {
        keep(fairfax)
        ActivityScenario.launch<MainActivity>(intent().putExtra(SKIP_OPENING, true)).use { scenario ->
            lateinit var launch: Launch
            scenario.onActivity { launch = ViewModelProvider(it)[Launch::class.java] }
            assertTrue("The app did not load", waitFor(30_000) { launch.model.value != null })
            val opening = Opening(OpeningStart.Play)
            val broken = CompletableDeferred<MapShapes?>().apply { completeExceptionally(IllegalStateException("broken")) }
            runBlocking { opening.buildMap(broken, launch.model.value!!) }
            assertTrue("A failed map kept the first frame waiting", opening.mapSettled)
            assertNull("A failed map left a map to draw", opening.map)
        }
    }

    // Photographs

    /** The whole screen, saved as SCREENSHOTS/<name>.png on the device, where the workflow collects it. */
    private fun screenshot(name: String) {
        compose.waitForIdle()
        // The screen capture reads the last frame the phone drew; give a frame that was just drawn time to reach it.
        Thread.sleep(300)
        shell("mkdir -p ${PlantsTest.SCREENSHOTS}")
        shell("screencap -p ${PlantsTest.SCREENSHOTS}/$name.png")
    }

    /** The whole screen as a picture to read, also saved like `screenshot`. */
    private fun takeScreenshot(name: String): Bitmap {
        shell("mkdir -p ${PlantsTest.SCREENSHOTS}")
        shell("screencap -p ${PlantsTest.SCREENSHOTS}/$name.png")
        val shot: Bitmap? = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertNotNull("The phone gave no screenshot", shot)
        shot!!
        return if (shot.config == Bitmap.Config.ARGB_8888) shot else shot.copy(Bitmap.Config.ARGB_8888, false)
    }

    /**
     * The smallest box holding every moss pixel, leaving out the top and bottom tenth of the screen, where the status
     * bar and the gesture bar are.
     */
    private fun mossBox(shot: Bitmap): Rect? {
        val w = shot.width
        val h = shot.height
        val pixels = IntArray(w * h)
        shot.getPixels(pixels, 0, w, 0, 0, w, h)
        var left = w
        var top = h
        var right = -1
        var bottom = -1
        for (y in h / 10 until h - h / 10) {
            for (x in 0 until w) {
                val p = pixels[y * w + x]
                if (abs(Color.red(p) - 0x3B) <= 12 && abs(Color.green(p) - 0x4A) <= 12 && abs(Color.blue(p) - 0x2A) <= 12) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        return if (right < 0) null else Rect(left, top, right + 1, bottom + 1)
    }

    /** Runs a shell command on the device. Reading the output to its end waits for the command to finish. */
    private fun shell(command: String): String {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        return ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).use {
            it.readBytes().decodeToString()
        }
    }

    /** How many of the app's screens exist now, whatever state each is in. */
    private fun screens(): Int {
        var count = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            for (stage in listOf(Stage.PRE_ON_CREATE, Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED)) {
                count += monitor.getActivitiesInStage(stage).count { it is MainActivity }
            }
        }
        return count
    }

    /** Closes every screen of the app, for a test that started one without ActivityScenario. */
    private fun finishScreens() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            for (stage in listOf(Stage.PRE_ON_CREATE, Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED)) {
                monitor.getActivitiesInStage(stage).filterIsInstance<MainActivity>().forEach { it.finish() }
            }
        }
        waitFor(10_000) { screens() == 0 }
    }
}
