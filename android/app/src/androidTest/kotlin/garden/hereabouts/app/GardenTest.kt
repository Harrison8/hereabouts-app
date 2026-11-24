// The Garden tab, tapped through like a person: this week's jobs for the saved plants with their tick circles and the
// "N of M done" line, the job sheet (when, what to do, the sources, which open in the phone's browser), the map pin
// before the county, the saved plants with the red Remove button a swipe to the left shows (a long swipe removes the
// plant at once, pressing and holding opens no menu, a swipe the phone calls off slides back, a swipe moves only the
// row it started on, and swiping on a plant page opened from here removes nothing), the empty state, and the Saturday
// reminders. The same checks as the iPhone app's.
//
// The tests set the clock, and the phone's answer to the notification question, through TestSwitches, so no system
// question covers the screen. Each reminder the app arms is noted there too, and the tests compare it with
// expected/care.json, the website's own answers. Events and links go to a stand-in server on 127.0.0.1 run by the
// test; nothing reaches an outside address.
package garden.hereabouts.app

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.service.notification.StatusBarNotification
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import garden.hereabouts.app.reminders.ReminderReceiver
import garden.hereabouts.app.reminders.ReminderScheduler
import garden.hereabouts.app.reminders.RescheduleReceiver
import garden.hereabouts.app.services.DataUpdater
import garden.hereabouts.app.services.SKIP_OPENING
import garden.hereabouts.app.services.ServiceSettings
import garden.hereabouts.app.services.TestSwitches
import garden.hereabouts.core.Location
import garden.hereabouts.core.LocationLookup
import garden.hereabouts.core.LocationResult
import garden.hereabouts.core.Reminder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class GardenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: StubServer
    private lateinit var settings: ServiceSettings
    private var fontChanged = false

    /** The time zone of care.json's cases for Fairfax County. */
    private val zone: ZoneId = ZoneId.of("America/New_York")

    private val fourPlants = listOf("asclepias-tuberosa", "schizachyrium-scoparium", "quercus-alba", "lindera-benzoin")

    private val fairfax: Location by lazy {
        val (_, tables) = AppModel.loadShipped(context)
        (LocationLookup.resolve("51059", 38.8462, -77.3064, tables) as LocationResult.Found).location
    }

    @Before
    fun start() {
        server = StubServer()
        TestSwitches.site = server.baseUrl
        TestSwitches.offline = false
        TestSwitches.opened.clear()
        settings = ServiceSettings.forLaunch(context)
        clearFiles()
        notifications().cancelAll()
    }

    @After
    fun stop() {
        server.stop()
        TestSwitches.site = InstrumentationRegistry.getArguments().getString(HereaboutsTestRunner.BASE_URL_ARGUMENT)
        TestSwitches.opened.clear()
        if (fontChanged) {
            shell("settings put system font_scale 1.0")
            waitFor(10_000) { fontScale() < 1.1f }
        }
        clearFiles()
        notifications().cancelAll()
    }

    private fun clearFiles() {
        settings.dataFolder.deleteRecursively()
        settings.queueFile.delete()
        settings.photoFolder.deleteRecursively()
    }

    // Launching and reading the screen

    /** `local` ("YYYY-MM-DDTHH:mm") in Fairfax County's time zone. */
    private fun at(local: String): ZonedDateTime = LocalDateTime.parse(local).atZone(zone)

    /**
     * Keeps `location` and `saved` the way the app keeps them, with nothing else kept. When the phone does not allow
     * notifications (no answer set for the test), the app's own record says it asked before, so that is a refusal.
     */
    private fun keep(saved: List<String>, location: Location = fairfax) {
        // The model lasts as long as the process, so the app is started afresh to read what is kept below.
        AppProcess.forget()
        runBlocking {
            context.keptStore.edit {
                it.clear()
                LocationStore.save(it, location)
                if (saved.isNotEmpty()) SavedStore.save(it, saved)
                if (TestSwitches.notifications == null) it[ReminderScheduler.askedKey] = true
            }
        }
    }

    /** Whether the app's own record says it asked the phone about notifications. */
    private fun askedRecorded(): Boolean = runBlocking { context.keptStore.data.first()[ReminderScheduler.askedKey] == true }

    /** Android 13 and later ask the person about notifications. Before that the phone allows them without a question. */
    private val phoneAsks = Build.VERSION.SDK_INT >= 33

    /**
     * The app with Fairfax County chosen, the clock at `now` (local time, or the phone's clock when null), `saved`
     * already saved and the phone's answer about notifications set, then on the Garden tab.
     */
    private fun launch(
        now: String? = null,
        saved: List<String> = emptyList(),
        notifications: String? = null,
        openGarden: Boolean = true,
    ): ActivityScenario<MainActivity> {
        TestSwitches.now = now?.let(::at)
        TestSwitches.notifications = notifications
        keep(saved)
        val scenario = launchApp()
        assertTrue("No tab bar after launch", waitFor(30_000) { exists(hasTestTag("tabs")) })
        if (openGarden) openGardenTab()
        return scenario
    }

    private fun openGardenTab() {
        compose.onNodeWithTag("tab-Garden").performClick()
        assertTrue("The Garden tab is not titled My garden", waitFor(10_000) { gardenShowing() })
    }

    private fun gardenShowing(): Boolean = exists(hasTestTag("title") and hasText("My garden"))

    private fun exists(matcher: SemanticsMatcher): Boolean =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun exists(tag: String): Boolean = exists(hasTestTag(tag))

    private fun node(tag: String, unmerged: Boolean = false): SemanticsNode =
        compose.onNodeWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNode("No $tag on the screen")

    /** Everything a node reads out: its spoken label, its words and its state. */
    private fun words(node: SemanticsNode): String = listOfNotNull(
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "),
        node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text },
        node.config.getOrNull(SemanticsProperties.StateDescription),
    ).joinToString(" ")

    private fun text(tag: String): String =
        node(tag).config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    private fun label(tag: String): String =
        node(tag).config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()

    private fun state(tag: String): String? = node(tag).config.getOrNull(SemanticsProperties.StateDescription)

    private fun switchState(): ToggleableState? = node("remindersSwitch").config.getOrNull(SemanticsProperties.ToggleableState)

    private fun hasTestTagStarting(prefix: String) = SemanticsMatcher("test tag starts with $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    private fun anyRemoveButton(): Boolean = exists(hasTestTagStarting("remove-"))

    /** A node's size in dp. */
    private fun sizeDp(tag: String): Pair<Float, Float> {
        val size = node(tag).size
        val density = context.resources.displayMetrics.density
        return size.width / density to size.height / density
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

    /**
     * Drags a saved plant's row to the left by `amount` (or by `fraction` of its width), from near its right edge, slowly,
     * and holds a moment before letting go, so the row stops where the finger stopped. With `callOff` the phone calls
     * the swipe off instead of the finger lifting.
     */
    private fun dragLeft(tag: String, amount: Dp? = null, fraction: Float? = null, callOff: Boolean = false) {
        compose.onNodeWithTag(tag).performScrollTo()
        compose.onNodeWithTag(tag).performTouchInput {
            val distance = amount?.toPx() ?: (width * (fraction ?: 0.5f))
            down(Offset(width * 0.9f, centerY))
            val steps = 24
            repeat(steps) { moveBy(Offset(-distance / steps, 0f), delayMillis = 16) }
            advanceEventTime(200)
            if (callOff) cancel() else up()
        }
        compose.waitForIdle()
    }

    /** The events of one name that reached the stand-in server, as their properties. */
    private fun posted(name: String): List<JsonObject> =
        server.postedEvents.filter { it.name == name }.map { it.properties }

    private fun string(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** The whole screen, saved as SCREENSHOTS/<name>.png on the device, where the workflow collects it. */
    private fun screenshot(name: String) {
        compose.waitForIdle()
        // The screen capture reads the last frame the phone drew; give a frame that was just drawn time to reach it.
        Thread.sleep(300)
        shell("mkdir -p ${PlantsTest.SCREENSHOTS}")
        shell("screencap -p ${PlantsTest.SCREENSHOTS}/$name.png")
    }

    /** Runs a shell command on the device. Reading the output to its end waits for the command to finish. */
    private fun shell(command: String): String {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        return ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).use {
            it.readBytes().decodeToString()
        }
    }

    private fun fontScale(): Float =
        Settings.System.getFloat(context.contentResolver, Settings.System.FONT_SCALE, 1f)

    private fun notifications(): NotificationManager = context.getSystemService(NotificationManager::class.java)

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

    // Reminders

    /** One reminder: its id, its time as "YYYY-MM-DDTHH:mm" in the phone's time zone, its title and its body. */
    private data class Waiting(val id: Int, val at: String, val title: String, val body: String) {
        override fun toString() = "$id $at $title: ${body.replace("\n", " | ")}"
    }

    private fun waiting(reminder: Reminder?): Waiting? = reminder?.let {
        Waiting(it.id, it.at.toLocalDateTime().toString(), it.title, it.body)
    }

    /** The reminder the app armed last, or null when it cancelled the one waiting or armed none. */
    private fun lastArmed(): Waiting? = waiting(TestSwitches.armed.lastOrNull())

    private fun alarmWaiting(): Boolean = ReminderScheduler.pendingAlarm(context, create = false) != null

    // The website's answers

    private val care: JsonObject by lazy {
        val text = try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("care.json").use { it.readBytes().decodeToString() }
        } catch (e: Exception) {
            throw AssertionError("care.json could not be read: it is not in the test app's assets", e)
        }
        Json.parseToJsonElement(text).jsonObject
    }

    /** The care.json reminders for a county, a time and a list of saved plants, in Fairfax County's time zone. */
    private fun careCase(county: String, now: String, saved: List<String>): List<Waiting> {
        val cases = care["cases"]?.jsonArray ?: throw AssertionError("care.json could not be read: no cases")
        val found = cases.map { it.jsonObject }.firstOrNull { c ->
            string(c["county"]) == county && string(c["now"]) == now && string(c["tz"]) == zone.id &&
                (c["savedIds"] as? JsonArray)?.map { string(it) } == saved
        } ?: throw AssertionError("care.json has no case for county $county at $now with $saved")
        return found.getValue("result").jsonArray.map { it.jsonObject }.map {
            Waiting(
                id = it.getValue("id").jsonPrimitive.int,
                at = string(it["at"]).orEmpty(),
                title = string(it["title"]).orEmpty(),
                body = string(it["body"]).orEmpty(),
            )
        }
    }

    // The tests

    /** With nothing saved, the Garden tab says so, and its button opens the Plants tab. */
    @Test
    fun theEmptyStateShowsWithNothingSavedAndOpensPlants() {
        launch(notifications = "willAllow").use {
            assertTrue("No empty state with nothing saved", waitFor(10_000) { exists("gardenEmpty") })
            assertEquals("No plants saved yet", text("gardenEmpty"))
            assertTrue("The empty state has no Browse plants button", exists("browsePlants"))
            assertEquals("Browse plants", words(node("browsePlants")))
            val (_, height) = sizeDp("browsePlants")
            assertTrue("Browse plants is less than 48 dp tall ($height)", height >= 47.5f)
            // Not asked yet, or allowed without a question, and the switch is on, so the empty state says when the reminder comes.
            assertTrue(
                "The empty state does not say when the reminder comes: ${label("gardenEmptyNote")}",
                label("gardenEmptyNote") == "Tap the save button on any plant to save it here. " +
                    "Each Saturday at 8:00 you’ll get the week’s garden jobs for the plants you saved.",
            )
            assertEquals("The question was asked at first open", 0, TestSwitches.asks.get())
            screenshot("garden-empty")
            compose.onNodeWithTag("browsePlants").performClick()
            assertTrue("Browse plants did not open the Plants tab", waitFor(10_000) { exists("plantList") })
        }
    }

    /**
     * Purple coneflower saved in Fairfax County on a Thursday in November: This week lists the tidy-garden stems job
     * (shown once with its pair) and the cold-winter seed job, so ticking one shows "1 of 2 done". The job's sheet
     * says when to do it, what to do with its ranges read "to", and opens its sources in the browser.
     */
    @Test
    fun thisWeekListsTheStemsJobAndTickingItCountsOneDone() {
        launch(now = "2027-11-18T09:00", saved = listOf("echinacea-purpurea")).use {
            assertTrue("This week does not list the stems job", waitFor(10_000) { exists("job-stems-tidy") })
            val row = words(node("job-stems-tidy"))
            assertTrue("The stems row reads $row", row.contains("Cut back dead stems for a tidy garden"))
            assertTrue("The stems row reads $row", row.contains("Purple Coneflower"))
            assertTrue("This week does not list the cold-winter seed job", exists("job-sow-cold-seed"))
            assertFalse("The pair shows twice", exists("job-stems-pollinator"))
            assertEquals("0 of 2 done", text("doneCount"))
            assertEquals("Nov 13 to 19", text("thisWeekDates"))
            // The phone refused notifications before, so no reminder is promised.
            assertEquals("From Fairfax County’s frost dates.", text("thisWeekFooter"))

            val (width, height) = sizeDp("tick-stems-tidy")
            assertTrue("The tick circle is less than 48 dp wide ($width)", width >= 47.5f)
            assertTrue("The tick circle is less than 48 dp tall ($height)", height >= 47.5f)
            assertEquals("Not done", state("tick-stems-tidy"))
            assertTrue(
                "The tick circle reads ${label("tick-stems-tidy")}",
                label("tick-stems-tidy") == "Tick off: Cut back dead stems for a tidy garden",
            )
            compose.onNodeWithTag("tick-stems-tidy").performClick()
            assertTrue("Ticking the job shows ${text("doneCount")}", waitFor(5_000) { text("doneCount") == "1 of 2 done" })
            assertEquals("Done", state("tick-stems-tidy"))
            screenshot("garden-one-job-done")

            // The job sheet.
            compose.onNodeWithTag("job-stems-tidy").performClick()
            assertTrue("The job sheet did not open", waitFor(10_000) { exists("jobWhen-stems-tidy") })
            assertEquals("Now until about April 14.", text("jobWhen-stems-tidy"))
            // The sheet opens at full height: its Done button sits in the top quarter of the screen.
            val top = node("jobSheetDone").positionOnScreen.y
            assertTrue(
                "The job sheet did not open at full height (Done at $top)",
                top < context.resources.displayMetrics.heightPixels / 4f,
            )
            assertTrue(
                "The sheet does not list what to do",
                exists(hasText("Last season's stems are still empty. Cutting them does not harm nesting bees.")),
            )
            assertTrue("The sheet does not name the saved plant", exists(hasText("Your Purple Coneflower")))
            assertTrue("A range does not read \"to\"", exists(hasText("12 to 24 in.", substring = true)))
            for (n in compose.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()) {
                assertFalse("A line shows an en dash: ${words(n)}", words(n).contains('–'))
            }
            val (_, markHeight) = sizeDp("markDone")
            assertTrue("Mark as done is less than 48 dp tall ($markHeight)", markHeight >= 47.5f)
            // The job was ticked above, so the sheet's button unticks it.
            assertEquals("Mark as not done", text("markDone"))
            compose.onNodeWithTag("markDone").performClick()
            assertTrue("Mark as not done reads ${text("markDone")}", waitFor(5_000) { text("markDone") == "Mark as done" })
            screenshot("job-sheet")
            // The pair's other job shows below the first, since either way will do.
            val other = hasText("Or: Cut stems later for a pollinator-friendly garden")
            assertTrue("The sheet does not show the pair's other job", exists(other))
            compose.onNode(other, useUnmergedTree = true).performScrollTo()
            screenshot("job-sheet-other-way")
            compose.onNodeWithTag("source-0").performScrollTo()
            screenshot("job-sheet-sources")
            compose.onNodeWithTag("source-0").performClick()
            assertTrue("The source did not open in the browser", waitFor(10_000) { TestSwitches.opened.isNotEmpty() })
            assertEquals(
                "https://content.ces.ncsu.edu/garden-cleanup-for-pollinators-trim-perennial-stems-in-their-first-winter",
                TestSwitches.opened.last().dataString,
            )
            // Done closes the sheet, and the job is no longer ticked. It scrolls with the sheet, as on the iPhone, so the
            // sheet goes back to the top first.
            compose.onNodeWithTag("jobSheetDone").performScrollTo().performClick()
            assertTrue("Done did not close the sheet", waitFor(10_000) { !exists("jobSheetDone") })
            assertEquals("0 of 2 done", text("doneCount"))
        }
    }

    /** At the largest text size the jobs, their tick circles and the job sheet can all be reached. */
    @Test
    fun atTheLargestTextTheJobsAndTheSheetCanBeReached() {
        fontChanged = true
        shell("settings put system font_scale 2.0")
        assertTrue("The phone did not take the largest text size", waitFor(10_000) { fontScale() > 1.9f })
        launch(now = "2027-11-18T09:00", saved = listOf("echinacea-purpurea", "asclepias-tuberosa", "lindera-benzoin")).use {
            assertTrue("No stems job at the largest text", waitFor(10_000) { exists("tick-stems-tidy") })
            screenshot("garden-largest-text")
            compose.onNodeWithTag("tick-stems-tidy").performScrollTo().performClick()
            assertTrue("The tick circle cannot be ticked at the largest text", waitFor(5_000) { state("tick-stems-tidy") == "Done" })
            compose.onNodeWithTag("saved-lindera-benzoin").performScrollTo()
            screenshot("garden-saved-largest-text")
            compose.onNodeWithTag("job-stems-tidy").performScrollTo().performClick()
            assertTrue("The job sheet did not open at the largest text", waitFor(10_000) { exists("markDone") })
            screenshot("job-sheet-largest-text")
            compose.onNodeWithTag("source-0").performScrollTo()
            screenshot("job-sheet-sources-largest-text")
        }
    }

    /**
     * With four plants saved in Fairfax County on Saturday October 2, 2027 at noon, the reminder armed is the website's
     * first, with the same id, time, title and body. Coming back to the app arms it again. Turning Saturday reminders
     * off in Settings leaves none waiting.
     */
    @Test
    fun theReminderMatchesTheWebsitesAndTurningThemOffLeavesNone() {
        val expected = careCase("51059", "2027-10-02T12:00", fourPlants)
        assertTrue("The care.json case has no reminders to compare", expected.isNotEmpty())
        launch(now = "2027-10-02T12:00", saved = fourPlants, notifications = "allowed").use { scenario ->
            assertTrue(
                "The reminder armed differs from care.json.\nApp: ${lastArmed()}\nWebsite: ${expected.first()}",
                waitFor(20_000) { lastArmed() == expected.first() },
            )
            assertTrue("No alarm is set for the reminder", alarmWaiting())
            assertTrue("The saved plants are not listed", waitFor(5_000) { exists("saved-quercus-alba") })
            // The map pin sits before the county under the title.
            assertTrue("No map pin before the county", exists("county-pin"))
            val pin = node("county-pin", unmerged = true).boundsInRoot
            val county = node("gardenCounty", unmerged = true).boundsInRoot
            assertTrue("The map pin is not before the county ($pin, $county)", pin.right <= county.left + 1)
            assertEquals("Fairfax County, Virginia", text("gardenCounty"))
            // The phone allows notifications and the switch is on, so the line under This week says when they come.
            assertTrue(
                "The line under This week does not say when the reminder comes: ${text("thisWeekFooter")}",
                waitFor(10_000) { text("thisWeekFooter").contains("A reminder comes every Saturday at 8:00.") },
            )
            screenshot("garden-four-plants")

            // Leaving the app and coming back arms the reminder again, still the website's.
            pause(2_000)
            val before = TestSwitches.armed.size
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertTrue("The app did not come back to the Garden tab", waitFor(20_000) { gardenShowing() })
            assertTrue(
                "Coming back to the app did not arm the reminder again: ${TestSwitches.armed}",
                waitFor(15_000) { TestSwitches.armed.size > before && lastArmed() == expected.first() },
            )

            compose.onNodeWithTag("tab-Settings").performClick()
            assertTrue("No Saturday reminders switch", waitFor(10_000) { exists("remindersSwitch") })
            compose.onNodeWithTag("remindersSwitch").performScrollTo()
            assertEquals("Saturday reminders start off", ToggleableState.On, switchState())
            compose.onNodeWithTag("remindersSwitch").performClick()
            assertTrue("The switch did not turn off", waitFor(5_000) { switchState() == ToggleableState.Off })
            assertTrue(
                "Turning reminders off left ${lastArmed()} armed",
                waitFor(15_000) { TestSwitches.armed.lastOrNull() == null && !alarmWaiting() },
            )
        }
    }

    /** With plants saved, the reminders switch in Settings answers every tap, however often the Garden tab opens between. */
    @Test
    fun theRemindersSwitchAnswersEachTimeAfterTheGardenTab() {
        launch(now = "2027-10-02T12:00", saved = listOf("asclepias-tuberosa", "lindera-benzoin"), notifications = "allowed").use {
            assertTrue("The saved plants are not listed", waitFor(10_000) { exists("saved-lindera-benzoin") })
            for ((round, want) in listOf(ToggleableState.Off, ToggleableState.On, ToggleableState.Off).withIndex()) {
                compose.onNodeWithTag("tab-Settings").performClick()
                assertTrue("No Saturday reminders switch", waitFor(10_000) { exists("remindersSwitch") })
                compose.onNodeWithTag("remindersSwitch").performScrollTo().performClick()
                assertTrue(
                    "Tap ${round + 1} on the switch did nothing: it still reads ${switchState()}",
                    waitFor(5_000) { switchState() == want },
                )
                val armed = want == ToggleableState.On
                assertTrue(
                    "After tap ${round + 1} the reminder armed is ${lastArmed()}",
                    waitFor(10_000) { (TestSwitches.armed.lastOrNull() != null) == armed && alarmWaiting() == armed },
                )
                openGardenTab()
            }
        }
    }

    /**
     * Swiping a saved plant to the left shows a red Remove button and leaves the Garden tab open; tapping the button
     * unsaves the plant, and the count of saved plants drops by one.
     */
    @Test
    fun swipingASavedPlantLeftShowsRemoveAndTappingItRemovesIt() {
        launch(now = "2027-11-18T09:00", saved = listOf("lindera-benzoin", "asclepias-tuberosa")).use {
            assertTrue("Spicebush is not listed", waitFor(10_000) { exists("saved-lindera-benzoin") })
            assertEquals("2 plants", label("savedCount"))
            assertTrue(
                "The row reads ${label("saved-lindera-benzoin")}",
                label("saved-lindera-benzoin").startsWith("Spicebush, Lindera benzoin, "),
            )
            assertFalse("A Remove button shows before any swipe", anyRemoveButton())

            dragLeft("saved-lindera-benzoin", amount = 120.dp)
            assertTrue("Swiping left showed no Remove button", waitFor(5_000) { exists("remove-lindera-benzoin") })
            assertEquals("Remove Spicebush", label("remove-lindera-benzoin"))
            val (width, height) = sizeDp("remove-lindera-benzoin")
            assertTrue("The Remove button is narrower than 48 dp ($width)", width >= 47.5f)
            assertTrue("The Remove button is less than 48 dp tall ($height)", height >= 47.5f)
            assertTrue("Swiping opened the plant's page", gardenShowing() && !exists("plantPage"))
            screenshot("garden-saved-swiped")

            compose.onNodeWithTag("remove-lindera-benzoin").performClick()
            assertTrue("Remove did not unsave Spicebush", waitFor(10_000) { !exists("saved-lindera-benzoin") })
            assertTrue("The count reads ${label("savedCount")} after Remove", waitFor(5_000) { label("savedCount") == "1 plant" })
            assertTrue("Remove took Butterfly Weed too", exists("saved-asclepias-tuberosa"))
            assertFalse("A Remove button still shows", anyRemoveButton())
        }
    }

    /**
     * A swipe the phone calls off slides the row back and removes nothing. A swipe that wanders onto the next row
     * still moves only the row it started on. TalkBack's Remove action unsaves the plant it is on.
     */
    @Test
    fun aCalledOffSwipeSlidesBackAndASwipeMovesOnlyItsOwnRow() {
        val saved = listOf("lindera-benzoin", "asclepias-tuberosa", "echinacea-purpurea")
        launch(now = "2027-11-18T09:00", saved = saved).use {
            assertTrue("Spicebush is not listed", waitFor(10_000) { exists("saved-lindera-benzoin") })
            compose.onNodeWithTag("saved-lindera-benzoin").performScrollTo()
            val home = node("saved-lindera-benzoin").positionInRoot.x

            dragLeft("saved-lindera-benzoin", amount = 120.dp, callOff = true)
            assertTrue(
                "A swipe the phone called off did not slide back",
                waitFor(5_000) { abs(node("saved-lindera-benzoin").positionInRoot.x - home) < 1f },
            )
            assertFalse("A swipe the phone called off left the Remove button", anyRemoveButton())
            assertEquals("A swipe the phone called off removed a plant", "3 plants", label("savedCount"))

            // Sideways first, then down onto the next row while still moving left.
            val rowHeight = node("saved-lindera-benzoin").size.height.toFloat()
            compose.onNodeWithTag("saved-lindera-benzoin").performTouchInput {
                val step = 60.dp.toPx() / 12
                down(Offset(width * 0.9f, centerY))
                repeat(12) { moveBy(Offset(-step, 0f), delayMillis = 16) }
                repeat(12) { moveBy(Offset(-step, rowHeight * 1.2f / 12), delayMillis = 16) }
                advanceEventTime(200)
                up()
            }
            assertTrue("The swipe did not open the row it started on", waitFor(5_000) { exists("remove-lindera-benzoin") })
            assertFalse("The swipe moved the row below it", exists("remove-asclepias-tuberosa"))
            assertEquals("3 plants", label("savedCount"))
            screenshot("garden-saved-one-row-open")

            // A tap on the open row only closes it.
            compose.onNodeWithTag("saved-lindera-benzoin").performClick()
            assertTrue("A tap did not close the open row", waitFor(5_000) { !anyRemoveButton() })
            assertFalse("A tap on the open row opened the plant", exists("plantPage"))

            // TalkBack's Remove action, on the second row.
            val remove = node("saved-asclepias-tuberosa").config.getOrNull(SemanticsActions.CustomActions)
                ?.firstOrNull { it.label == "Remove" }
            assertNotNull("The row has no Remove action for TalkBack", remove)
            compose.runOnUiThread { remove!!.action() }
            assertTrue("The Remove action did not unsave Butterfly Weed", waitFor(10_000) { !exists("saved-asclepias-tuberosa") })
            assertTrue("The Remove action took another plant", exists("saved-lindera-benzoin") && exists("saved-echinacea-purpurea"))
            assertEquals("2 plants", label("savedCount"))
        }
    }

    /**
     * Removing a plant that is no longer saved does nothing. If the swipe's Remove and TalkBack's Remove both fire, or
     * one fires twice, the plant stays unsaved and plant_saved is not sent.
     */
    @Test
    fun removingAPlantTwiceLeavesItUnsaved() {
        launch(now = "2027-11-18T09:00", saved = listOf("lindera-benzoin", "asclepias-tuberosa")).use {
            assertTrue("Butterfly Weed is not listed", waitFor(10_000) { exists("saved-asclepias-tuberosa") })
            val remove = node("saved-asclepias-tuberosa").config.getOrNull(SemanticsActions.CustomActions)
                ?.firstOrNull { it.label == "Remove" }
            assertNotNull("The row has no Remove action for TalkBack", remove)
            // Both before the screen has redrawn, as two removals that fire together would be.
            compose.runOnUiThread {
                remove!!.action()
                remove.action()
            }
            assertTrue("Remove did not unsave Butterfly Weed", waitFor(10_000) { !exists("saved-asclepias-tuberosa") })
            pause(2_000)
            assertFalse("Removing twice saved Butterfly Weed again", exists("saved-asclepias-tuberosa"))
            assertEquals("1 plant", label("savedCount"))
            assertTrue("No plant_unsaved event", StubServer.waitFor(20) { posted("plant_unsaved").isNotEmpty() })
            StubServer.waitFor(3) { posted("plant_saved").isNotEmpty() || posted("plant_unsaved").size > 1 }
            assertEquals("Removing twice sent plant_saved", 0, posted("plant_saved").size)
            assertEquals("Removing twice sent plant_unsaved twice", 1, posted("plant_unsaved").size)
        }
    }

    /**
     * Swiping through a plant's photos on its page, opened from the Garden tab, moves only the photos: both saved
     * plants are still saved when the page closes, and no Remove button shows.
     */
    @Test
    fun swipingThePhotosOfAPlantOpenedFromTheGardenKeepsEveryPlantSaved() {
        launch(now = "2027-11-18T09:00", saved = listOf("lindera-benzoin", "asclepias-tuberosa")).use {
            assertTrue("Spicebush is not listed", waitFor(10_000) { exists("saved-lindera-benzoin") })
            assertEquals("2 plants", label("savedCount"))
            compose.onNodeWithTag("saved-lindera-benzoin").performScrollTo().performClick()
            assertTrue("Tapping Spicebush did not open its page", waitFor(10_000) { exists("plantPage") })
            assertTrue("Spicebush's page has no photo counter", waitFor(10_000) { exists("photoCounter") })
            assertEquals("In bloom · 1 of 3", text("photoCounter"))
            compose.onNodeWithTag("photoPager").performTouchInput { swipeLeft() }
            assertTrue("Swiping did not show the fruit photo", waitFor(5_000) { text("photoCounter") == "In fruit · 2 of 3" })
            compose.onNodeWithTag("photoPager").performTouchInput { swipeLeft() }
            assertTrue("Swiping did not show the fall photo", waitFor(5_000) { text("photoCounter") == "In fall · 3 of 3" })

            compose.onNodeWithContentDescription("Back").performClick()
            assertTrue("Could not go back to the Garden tab", waitFor(10_000) { gardenShowing() && !exists("plantPage") })
            assertTrue("The count reads ${label("savedCount")} after swiping the photos", waitFor(5_000) { label("savedCount") == "2 plants" })
            assertTrue("Swiping the photos unsaved Spicebush", exists("saved-lindera-benzoin"))
            assertTrue("Swiping the photos unsaved Butterfly Weed", exists("saved-asclepias-tuberosa"))
            assertFalse("A Remove button shows after swiping the photos", anyRemoveButton())
        }
    }

    /**
     * A saved plant that is not native to a newly chosen county stays saved and stays listed: purple coneflower grows
     * wild in Fairfax County but not in Portland, Oregon. Pressing and holding it opens no menu; a long swipe to the
     * left removes it.
     */
    @Test
    fun changingTheCountyKeepsASavedPlantThatIsNotNativeThere() {
        launch(now = "2027-11-18T09:00", saved = listOf("echinacea-purpurea")).use {
            assertTrue("Purple coneflower is not listed in Fairfax County", waitFor(10_000) { exists("saved-echinacea-purpurea") })

            compose.onNodeWithTag("tab-Settings").performClick()
            assertTrue("No Change button in Settings", waitFor(10_000) { exists("changeLocation") })
            compose.onNodeWithTag("changeLocation").performScrollTo().performClick()
            assertTrue("Change did not open the location screen", waitFor(10_000) { exists("zipField") })
            compose.onNodeWithTag("zipField").performTextInput("97201")
            assertTrue("97201 did not show Multnomah County", waitFor(10_000) { exists(hasText("Multnomah County, Oregon", substring = true)) })
            compose.onNodeWithTag("continue").performClick()
            assertTrue("The location screen did not close", waitFor(10_000) { !exists("zipField") })

            openGardenTab()
            assertTrue(
                "Purple coneflower left the saved plants after the county changed",
                waitFor(10_000) { exists("saved-echinacea-purpurea") },
            )
            assertTrue(
                "The Garden tab does not show the new county: ${text("gardenCounty")}",
                text("gardenCounty").contains("Multnomah County, Oregon"),
            )
            screenshot("garden-after-moving")

            // Pressing and holding opens no menu. A press this long may count as a tap and open the plant's page; if
            // so, go back.
            compose.onNodeWithTag("saved-echinacea-purpurea").performScrollTo().performTouchInput { longClick() }
            pause(1_500)
            assertFalse("Pressing and holding showed Remove", anyRemoveButton())
            assertFalse("Pressing and holding opened a menu", exists(hasText("Remove")))
            if (exists("plantPage")) {
                compose.onNodeWithContentDescription("Back").performClick()
                assertTrue("Could not go back to the Garden tab", waitFor(10_000) { gardenShowing() && !exists("plantPage") })
            }

            dragLeft("saved-echinacea-purpurea", fraction = 0.85f)
            assertTrue("A long swipe did not unsave the plant", waitFor(10_000) { exists("gardenEmpty") })
        }
    }

    /**
     * On Android 13 and later, the first save with a county set asks the phone whether reminders may be sent, once,
     * records that it asked, and sends the answer. Before Android 13 the phone has no such question, so none is asked.
     */
    @Test
    fun theFirstSaveAsksForNotificationsOnceAndSendsTheAnswer() {
        launch(notifications = "willAllow", openGarden = false).use {
            assertTrue("No save button on the Plants tab", waitFor(20_000) { exists(hasTestTagStarting("save-")) })
            assertEquals("The question was asked before anything was saved", 0, TestSwitches.asks.get())
            assertFalse("The app records a question before anything was saved", askedRecorded())
            val saves = compose.onAllNodes(hasTestTagStarting("save-"), useUnmergedTree = true)
            saves[0].performClick()
            if (!phoneAsks) {
                assertTrue("No plant_saved event", StubServer.waitFor(20) { posted("plant_saved").isNotEmpty() })
                // Time for an answer to arrive, if a question were asked.
                StubServer.waitFor(5) { posted("notifications_answer").isNotEmpty() }
                assertTrue("An answer was sent before Android 13", posted("notifications_answer").isEmpty())
                assertEquals("The phone was asked before Android 13", 0, TestSwitches.asks.get())
                assertFalse("The app records a question before Android 13", askedRecorded())
                return@use
            }
            assertTrue("No notifications_answer event", StubServer.waitFor(20) { posted("notifications_answer").isNotEmpty() })
            val answer = posted("notifications_answer").first()
            assertEquals(true, (answer["granted"] as? JsonPrimitive)?.booleanOrNull)
            assertEquals(1, TestSwitches.asks.get())
            assertTrue("The app did not record that it asked", askedRecorded())
            saves[1].performClick()
            // Time for a second answer to arrive, if one were sent.
            StubServer.waitFor(5) { posted("notifications_answer").size > 1 }
            assertEquals("The question was asked twice", 1, posted("notifications_answer").size)
            assertEquals("The question was asked twice", 1, TestSwitches.asks.get())
        }
    }

    /**
     * When the person refuses notifications at the first save, the Garden tab promises no Saturday reminder: not under
     * This week, and not in the empty state once the plant is removed again. Saving again does not ask again: the app
     * records that it asked, because the phone itself does not say so. Before Android 13 the phone has no question,
     * and a phone that does not allow notifications promises nothing in the same way.
     */
    @Test
    fun whenThePhoneRefusesNotificationsNoReminderIsPromised() {
        launch(now = "2027-11-18T09:00", notifications = "willDeny", openGarden = false).use {
            assertTrue("No save button on the Plants tab", waitFor(20_000) { exists(hasTestTagStarting("save-")) })
            val first = compose.onAllNodes(hasTestTagStarting("save-"), useUnmergedTree = true)[0]
            val plantId = first.fetchSemanticsNode().config[SemanticsProperties.TestTag].removePrefix("save-")
            first.performClick()
            if (phoneAsks) {
                assertTrue("No notifications_answer event", StubServer.waitFor(20) { posted("notifications_answer").isNotEmpty() })
                assertEquals(false, (posted("notifications_answer").first()["granted"] as? JsonPrimitive)?.booleanOrNull)
                assertTrue("The app did not record that it asked", askedRecorded())
            } else {
                assertTrue("No plant_saved event", StubServer.waitFor(20) { posted("plant_saved").isNotEmpty() })
                StubServer.waitFor(5) { posted("notifications_answer").isNotEmpty() }
                assertTrue("An answer was sent before Android 13", posted("notifications_answer").isEmpty())
            }
            assertNull("A reminder was armed after the phone refused", TestSwitches.armed.lastOrNull())

            openGardenTab()
            assertTrue("No line under This week after saving $plantId", waitFor(10_000) { exists("thisWeekFooter") })
            assertTrue(
                "With notifications refused the line under This week reads: ${text("thisWeekFooter")}",
                waitFor(5_000) { text("thisWeekFooter") == "From Fairfax County’s frost dates." },
            )
            screenshot("garden-notifications-refused")

            dragLeft("saved-$plantId", amount = 120.dp)
            assertTrue("Swiping left showed no Remove button", waitFor(5_000) { exists("remove-$plantId") })
            compose.onNodeWithTag("remove-$plantId").performClick()
            assertTrue("Remove did not unsave the plant", waitFor(10_000) { exists("gardenEmptyNote") })
            assertTrue(
                "With notifications refused the empty state reads: ${label("gardenEmptyNote")}",
                waitFor(5_000) { label("gardenEmptyNote") == "Tap the save button on any plant to save it here." },
            )
            screenshot("garden-empty-notifications-refused")

            // A refusal is final: saving again does not ask again.
            compose.onNodeWithTag("browsePlants").performClick()
            assertTrue("Browse plants did not open the Plants tab", waitFor(10_000) { exists("plantList") })
            compose.onNodeWithTag("save-$plantId", useUnmergedTree = true).performClick()
            StubServer.waitFor(5) { posted("notifications_answer").size > 1 }
            assertEquals("The phone was asked again after a refusal", if (phoneAsks) 1 else 0, TestSwitches.asks.get())
            assertEquals("An answer was sent again after a refusal", if (phoneAsks) 1 else 0, posted("notifications_answer").size)
        }
    }

    /**
     * After a refusal, turning the Saturday reminders switch on in Settings asks the phone once more, and once the
     * person allows them the Garden tab promises the reminder again. Turning the switch off and on again does not ask
     * a third time. Before Android 13 there is no question, so none is asked.
     */
    @Test
    fun turningRemindersOnAfterARefusalAsksOnce() {
        launch().use {
            assertTrue("No empty state with nothing saved", waitFor(10_000) { exists("gardenEmptyNote") })
            assertEquals("Tap the save button on any plant to save it here.", label("gardenEmptyNote"))
            compose.onNodeWithTag("tab-Settings").performClick()
            assertTrue("No Saturday reminders switch", waitFor(10_000) { exists("remindersSwitch") })
            compose.onNodeWithTag("remindersSwitch").performScrollTo().performClick()
            assertTrue("The switch did not turn off", waitFor(5_000) { switchState() == ToggleableState.Off })
            assertEquals("Turning the switch off asked the phone", 0, TestSwitches.asks.get())

            // The person will allow notifications if asked again.
            if (phoneAsks) TestSwitches.notifications = "willAllow"
            compose.onNodeWithTag("remindersSwitch").performScrollTo().performClick()
            assertTrue("The switch did not turn on", waitFor(5_000) { switchState() == ToggleableState.On })
            if (!phoneAsks) {
                pause(3_000)
                assertEquals("The phone was asked before Android 13", 0, TestSwitches.asks.get())
                return@use
            }
            assertTrue("Turning the switch on did not ask the phone", waitFor(10_000) { TestSwitches.asks.get() == 1 })

            openGardenTab()
            assertTrue(
                "Once allowed, the empty state reads: ${label("gardenEmptyNote")}",
                waitFor(10_000) {
                    label("gardenEmptyNote") == "Tap the save button on any plant to save it here. " +
                        "Each Saturday at 8:00 you’ll get the week’s garden jobs for the plants you saved."
                },
            )

            compose.onNodeWithTag("tab-Settings").performClick()
            assertTrue("No Saturday reminders switch", waitFor(10_000) { exists("remindersSwitch") })
            for (want in listOf(ToggleableState.Off, ToggleableState.On)) {
                compose.onNodeWithTag("remindersSwitch").performScrollTo().performClick()
                assertTrue("The switch did not turn $want", waitFor(5_000) { switchState() == want })
            }
            pause(3_000)
            assertEquals("The phone was asked again once it allowed notifications", 1, TestSwitches.asks.get())
        }
    }

    /**
     * Saturday's reminder at 08:00: the app posts the website's reminder for the week, arms the next one, and tapping
     * the reminder brings the open app forward on the Garden tab (one screen, not a second) and sends this_week_opened
     * from the notification.
     *
     * The app is started here as the phone's launcher starts it, in its own task. A screen started by ActivityScenario
     * sits in the test app's task, where the reminder's tap would rightly start a second screen in the app's own task.
     */
    @Test
    fun aRemindersTapOpensTheGardenInTheScreenAlreadyOpen() {
        val expected = careCase("51059", "2027-10-02T12:00", fourPlants)
        assertTrue("The care.json case has too few reminders to compare", expected.size >= 2)
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        TestSwitches.now = at("2027-10-09T08:00")
        TestSwitches.notifications = "allowed"
        keep(fourPlants)
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(SKIP_OPENING, true),
        )
        try {
            assertTrue("No tab bar after launch", waitFor(30_000) { exists(hasTestTag("tabs")) })
            assertTrue("No plant list on the Plants tab", waitFor(20_000) { exists("plantList") })
            context.sendBroadcast(Intent(context, ReminderReceiver::class.java).setAction(ReminderScheduler.REMIND))

            var shown: StatusBarNotification? = null
            assertTrue(
                "The reminder was not posted",
                StubServer.waitFor(15) {
                    shown = notifications().activeNotifications.firstOrNull { it.id == expected[0].id }
                    shown != null
                },
            )
            val notification = shown!!.notification
            assertEquals(expected[0].title, notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
            assertEquals(expected[0].body, notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())
            assertTrue(
                "The next reminder armed differs from care.json.\nApp: ${lastArmed()}\nWebsite: ${expected[1]}",
                waitFor(10_000) { lastArmed() == expected[1] },
            )

            val options = if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle()
            } else {
                null
            }
            notification.contentIntent.send(context, 0, null, null, null, null, options)
            assertTrue("The reminder did not open the Garden tab", waitFor(20_000) { gardenShowing() })
            pause(2_000)
            assertEquals("The reminder opened a second screen", 1, screens())
            assertTrue("No this_week_opened event", StubServer.waitFor(20) { posted("this_week_opened").isNotEmpty() })
            assertEquals("notification", string(posted("this_week_opened").first()["from"]))
            screenshot("garden-from-reminder")
        } finally {
            finishScreens()
        }
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

    /**
     * After the phone restarts, or its clock or time zone changes, the reminder is armed again without the app being
     * opened. Any other message arms nothing.
     */
    @Test
    fun theReminderIsArmedAgainAfterARestartWithoutTheAppOpen() {
        val expected = careCase("51059", "2027-10-02T12:00", fourPlants)
        TestSwitches.now = at("2027-10-02T12:00")
        TestSwitches.notifications = "allowed"
        keep(fourPlants)
        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) {
            TestSwitches.armed.clear()
            RescheduleReceiver().onReceive(context, Intent(action))
            assertTrue(
                "$action did not arm the website's reminder.\nApp: ${lastArmed()}\nWebsite: ${expected.first()}",
                StubServer.waitFor(15) { lastArmed() == expected.first() },
            )
            assertTrue("$action set no alarm", alarmWaiting())
        }
        assertNull("Arming the reminder loaded the whole app", AppProcess.loaded())

        TestSwitches.armed.clear()
        RescheduleReceiver().onReceive(context, Intent("garden.hereabouts.app.SOMETHING_ELSE"))
        StubServer.waitFor(3) { TestSwitches.armed.isNotEmpty() }
        assertTrue("Another message armed a reminder", TestSwitches.armed.isEmpty())
    }

    /**
     * A downloaded plant whose two seasons share one photo opens from the Garden tab and shows both: In leaf, then In
     * fall.
     */
    @Test
    fun twoSeasonsSharingOnePhotoBothShow() {
        val shared = StubData.make(
            """
            [{"id":"test-plant-one","common":"Test Plant One","sci":"Testus unus","category":"perennial"},
             {"id":"test-plant-two","common":"Test Plant Two","sci":"Testus duo","category":"shrub",
              "phasePhotos":{"green":{"url":"images/test-plant-two-green.jpg","alt":"Test","credit":"Test"},
                             "fall":{"url":"images/test-plant-two-green.jpg","alt":"Test","credit":"Test"}}}]
            """.trimIndent(),
        )
        DataUpdater.keep(shared.byName, settings.dataFolder)
        launch(now = "2027-11-18T09:00", saved = listOf("test-plant-two")).use {
            assertTrue("The plant from the downloaded set is not listed", waitFor(10_000) { exists("saved-test-plant-two") })
            compose.onNodeWithTag("saved-test-plant-two").performScrollTo().performClick()
            assertTrue("The plant's page did not open", waitFor(10_000) { exists("photoCounter") })
            assertEquals("In leaf · 1 of 2", text("photoCounter"))
            compose.onNodeWithTag("photoPager").performTouchInput { swipeLeft() }
            assertTrue("Swiping did not show the second season", waitFor(5_000) { text("photoCounter") == "In fall · 2 of 2" })
        }
    }
}
