// The screen check: every screen and sheet a person can reach, photographed at the normal text size and at the
// largest one (the phone's font scale 2.0), with an accessibility check on each main screen. The pictures are saved
// on the device as <size>-<screen>.png, where the workflow collects them.
//
// Nothing here fails the run for what the screens look like. A step that cannot be done is written down and the test
// goes on, so one missing control does not cost the pictures after it. Each test saves its notes as
// report-<test>.b64 next to the pictures (base64, so any characters survive the device's shell); the workflow turns
// them back into text. The Compose accessibility checks need Android 14 (API 34) or later; on older phones the notes say
// they were not run.
// Events go to a stand-in server on 127.0.0.1 run by the test; nothing reaches an outside address.
package garden.hereabouts.app

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.compose.ui.geometry.Offset
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.disableAccessibilityChecks
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import garden.hereabouts.app.reminders.ReminderScheduler
import garden.hereabouts.app.services.ServiceSettings
import garden.hereabouts.app.services.TestSwitches
import garden.hereabouts.core.Location
import garden.hereabouts.core.LocationLookup
import garden.hereabouts.core.LocationResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class ScreenCheckTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val testName = TestName()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: StubServer
    private lateinit var settings: ServiceSettings
    private var fontChanged = false
    private var screenChanged = false
    private var size = "normal"
    private val notes = mutableListOf<String>()

    private val zone: ZoneId = ZoneId.of("America/New_York")

    private val fairfax: Location by lazy {
        val (_, tables) = AppModel.loadShipped(context)
        (LocationLookup.resolve("51059", 38.8462, -77.3064, tables) as LocationResult.Found).location
    }

    private val fourPlants = listOf("echinacea-purpurea", "asclepias-tuberosa", "lindera-benzoin", "quercus-alba")

    @Before
    fun start() {
        server = StubServer()
        TestSwitches.site = server.baseUrl
        TestSwitches.offline = false
        settings = ServiceSettings.forLaunch(context)
        clearFiles()
    }

    @After
    fun stop() {
        writeNotes()
        server.stop()
        TestSwitches.site = InstrumentationRegistry.getArguments().getString(HereaboutsTestRunner.BASE_URL_ARGUMENT)
        TestSwitches.offline = false
        TestSwitches.now = null
        TestSwitches.notifications = null
        if (screenChanged) {
            shell("wm size reset")
            shell("wm density reset")
        }
        if (fontChanged) {
            shell("settings put system font_scale 1.0")
            waitFor(10_000) { fontScale() < 1.1f }
        }
        clearFiles()
    }

    private fun clearFiles() {
        settings.dataFolder.deleteRecursively()
        settings.queueFile.delete()
        settings.photoFolder.deleteRecursively()
    }

    // Sizes

    private fun useSize(largest: Boolean) {
        size = if (largest) "largest" else "normal"
        if (largest) {
            fontChanged = true
            shell("settings put system font_scale 2.0")
            waitFor(10_000) { fontScale() > 1.9f }
        }
    }

    // Launching

    private fun at(local: String) = LocalDateTime.parse(local).atZone(zone)

    /** The app with Fairfax County kept and `saved` saved, opened on its tabs. Nothing is kept when `location` is null. */
    private fun launch(
        saved: List<String> = emptyList(),
        now: String? = null,
        offline: Boolean = false,
        location: Location? = fairfax,
    ): ActivityScenario<MainActivity> {
        TestSwitches.offline = offline
        TestSwitches.now = now?.let(::at)
        TestSwitches.notifications = null
        AppProcess.forget()
        runBlocking {
            context.keptStore.edit {
                it.clear()
                if (location != null) {
                    LocationStore.save(it, location)
                    if (saved.isNotEmpty()) SavedStore.save(it, saved)
                    it[ReminderScheduler.askedKey] = true
                }
            }
        }
        val scenario = launchApp()
        if (location != null) {
            if (!waitFor(30_000) { exists(hasTestTag("tabs")) }) note("The app did not open on its tabs")
        } else if (!waitFor(30_000) { exists(hasTestTag("zipField")) }) {
            note("The app did not open on the first-open screen")
        }
        return scenario
    }

    private fun waitForPlants() {
        if (!waitFor(20_000) { exists(hasTestTag("plantList")) }) note("No Plants list")
        step("Plants rows") {
            compose.onNodeWithTag("plantList").performScrollToKey("head-count")
            compose.waitForIdle()
            waitFor(20_000) { plantRows().isNotEmpty() || exists(hasTestTag("alsoCounty")) || exists(hasTestTag("emptyResults")) }
            compose.onNodeWithTag("plantList").performScrollToIndex(0)
            compose.waitForIdle()
        }
    }

    private fun openGarden() {
        step("open Garden") {
            compose.onNodeWithTag("tab-Garden").performClick()
            waitFor(10_000) { exists(hasTestTag("title") and hasText("My garden")) }
        }
    }

    private fun openSettings() {
        step("open Settings") {
            compose.onNodeWithTag("tab-Settings").performClick()
            waitFor(10_000) { exists(hasTestTag("title") and hasText("Settings")) }
        }
    }

    private fun search(query: String) {
        compose.onNodeWithTag("search").performTextInput(query)
        compose.onNodeWithTag("search").performImeAction()
        compose.waitForIdle()
    }

    private fun openPlant(query: String, id: String) {
        step("open plant $id") {
            search(query)
            compose.onNodeWithTag("plantList").performScrollToKey(id)
            waitFor(10_000) { exists(hasTestTag("plant-$id")) }
            compose.onNodeWithTag("plant-$id").performClick()
            if (!waitFor(10_000) { exists(hasTestTag("plantPage")) }) note("The plant page for $id did not open")
            compose.waitForIdle()
        }
    }

    // Reading the screen

    private fun exists(matcher: SemanticsMatcher): Boolean =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun exists(tag: String): Boolean = exists(hasTestTag(tag))

    private val plantRow = SemanticsMatcher("a plant row") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("plant-") == true
    }

    private fun plantRows(): List<SemanticsNode> =
        compose.onAllNodes(plantRow).fetchSemanticsNodes().sortedBy { it.boundsInRoot.top }

    private fun waitFor(millis: Long, condition: () -> Boolean): Boolean = try {
        compose.waitUntil(millis) { condition() }
        true
    } catch (e: Throwable) {
        condition()
    }

    private fun fontScale(): Float =
        Settings.System.getFloat(context.contentResolver, Settings.System.FONT_SCALE, 1f)

    // Taking notes, pictures and checks

    private fun note(line: String) {
        notes.add(line)
    }

    /** Runs one step; if it cannot be done, says so in the notes and goes on. */
    private fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            note("STEP FAILED [$size] $name: ${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull()?.take(200)}")
        }
    }

    /** The whole screen, saved as <size>-<screen>.png. */
    private fun shot(screen: String) {
        try {
            compose.waitForIdle()
            Thread.sleep(300)
            shell("mkdir -p ${PlantsTest.SCREENSHOTS}")
            shell("screencap -p ${PlantsTest.SCREENSHOTS}/$size-$screen.png")
        } catch (e: Throwable) {
            note("SHOT FAILED [$size] $screen: ${e.message?.take(200)}")
        }
    }

    /** The Compose accessibility checks on what is on the screen now. Findings are noted, never thrown. */
    private fun check(screen: String) {
        if (Build.VERSION.SDK_INT < 34) {
            note("A11Y [$size] $screen: not run, the Compose accessibility checks need Android 14 (API 34)")
            return
        }
        try {
            compose.enableAccessibilityChecks()
            try {
                compose.onRoot().tryPerformAccessibilityChecks()
                note("A11Y [$size] $screen: no findings")
            } catch (e: Throwable) {
                note("A11Y [$size] $screen: FINDINGS ${e.message?.replace('\n', ' ')?.take(1500)}")
            } finally {
                compose.disableAccessibilityChecks()
            }
        } catch (e: Throwable) {
            note("A11Y [$size] $screen: the check could not run: ${e.javaClass.simpleName}: ${e.message?.take(200)}")
        }
    }

    private fun writeNotes() {
        if (notes.isEmpty()) return
        try {
            val file = "${PlantsTest.SCREENSHOTS}/report-${testName.methodName}.b64"
            val encoded = Base64.getEncoder().encodeToString(notes.joinToString("\n").toByteArray())
            shell("mkdir -p ${PlantsTest.SCREENSHOTS}")
            shell("rm -f $file")
            // executeShellCommand splits on spaces and has no shell, so ">>" would be passed to printf as text.
            // One "sh -c" word with ${IFS} for spaces lets the shell do the redirect.
            encoded.chunked(2000).forEach { shell("sh -c printf\${IFS}%s\${IFS}$it>>$file") }
        } catch (e: Throwable) {
            // Nothing more to do: the pictures are still saved.
        }
    }

    private fun shell(command: String): String {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        return ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).use {
            it.readBytes().decodeToString()
        }
    }

    private fun dragLeft(tag: String, amount: Dp) {
        compose.onNodeWithTag(tag).performScrollTo()
        compose.onNodeWithTag(tag).performTouchInput {
            val distance = amount.toPx()
            down(Offset(width * 0.9f, centerY))
            val steps = 24
            repeat(steps) { moveBy(Offset(-distance / steps, 0f), delayMillis = 16) }
            advanceEventTime(200)
            up()
        }
        compose.waitForIdle()
    }

    private fun boundsOfFirstOpen(): String {
        fun bounds(m: SemanticsMatcher) =
            compose.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.toString()
        return "field ${bounds(hasTestTag("zipField"))}, county ${bounds(hasText("Fairfax County, Virginia"))}, " +
            "button ${bounds(hasTestTag("continue"))}"
    }

    /** The ZIP field and the county line are both fully on screen and end above the pinned button's top edge. */
    private fun zipFieldAndCountyAboveButton(): Boolean {
        fun node(m: SemanticsMatcher) = compose.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
        val field = node(hasTestTag("zipField"))?.boundsInRoot ?: return false
        val county = node(hasText("Fairfax County, Virginia"))?.boundsInRoot ?: return false
        val button = node(hasTestTag("continue"))?.boundsInRoot ?: return false
        return field.top >= 0f && field.bottom <= button.top && county.top >= 0f && county.bottom <= button.top
    }

    // The flows

    private fun firstOpenFlow() {
        launch(location = null).use {
            shot("first-open")
            check("first-open")
            step("ZIP found") {
                compose.onNodeWithTag("zipField").performTextInput("22030")
                waitFor(10_000) { exists(hasText("Fairfax County, Virginia")) }
                // The form scrolls the field and the county above the pinned button, and the number pad is up: wait
                // for that scroll to settle, and fail if either is still under the button.
                val above = waitFor(5_000) { zipFieldAndCountyAboveButton() }
                shot("first-open-zip-found")
                assertTrue("The ZIP field and the county are not both above the Show button: ${boundsOfFirstOpen()}", above)
            }
        }
        launch(location = null).use {
            step("outside the lower 48") {
                compose.onNodeWithTag("zipField").performTextInput("99501")
                waitFor(10_000) { exists(hasText("We only cover the lower 48 states for now.")) }
                shot("first-open-outside")
            }
        }
        launch(location = null).use {
            step("town found") {
                compose.onNodeWithText("Use a town or street address instead").performClick()
                waitFor(10_000) { exists(hasTestTag("placeField")) }
                compose.onNodeWithTag("placeField").performTextInput("Dayton, OH")
                compose.onNodeWithTag("placeField").performImeAction()
                waitFor(10_000) { exists(hasText("Montgomery County, Ohio")) }
                shot("first-open-town")
            }
        }
    }

    private fun plantsFlow() {
        launch().use {
            waitForPlants()
            shot("plants-top")
            check("plants")
            step("scrolled") {
                compose.onNodeWithTag("plantList").performScrollToIndex(8)
                shot("plants-scrolled")
                compose.onNodeWithTag("plantList").performScrollToIndex(0)
            }
            step("sort menu") {
                // No performScrollTo: the menu and its items are on screen (a popup is not inside a scrolling parent).
                compose.onNodeWithTag("sortMenu").performClick()
                waitFor(5_000) { exists(hasText("Height (high to low)")) }
                shot("plants-sort-menu")
                compose.onNodeWithText("Height (high to low)").performClick()
                shot("plants-tallest")
            }
            step("filters") {
                compose.onNodeWithTag("filtersChip").performClick()
                waitFor(5_000) { exists(hasTestTag("showPlants")) }
                shot("plants-filters")
                compose.onNodeWithText("Full shade").performScrollTo().performClick()
                shot("plants-filters-shade")
                compose.onNodeWithTag("showPlants").performClick()
                waitFor(5_000) { !exists(hasTestTag("showPlants")) }
                shot("plants-filtered")
            }
        }
        launch().use {
            waitForPlants()
            step("search and no results") {
                search("oak")
                shot("plants-search")
                search("zzzz")
                waitFor(5_000) { exists(hasTestTag("emptyResults")) }
                shot("plants-no-results")
            }
        }
        launch(offline = true).use {
            step("no signal") {
                waitFor(10_000) { exists(hasTestTag("offlineBanner")) }
                waitForPlants()
                shot("plants-no-signal")
            }
        }
    }

    private fun plantPageFlow() {
        launch().use {
            waitForPlants()
            openPlant("Spicebush", "lindera-benzoin")
            shot("plant-top")
            check("plant-page")
            step("photo pager") {
                compose.onNodeWithTag("photoPager").performTouchInput { swipeLeft() }
                waitFor(5_000) { exists(hasText("In fruit · 2 of 3")) }
                shot("plant-photo-pager")
            }
            step("scrolled") {
                compose.onNodeWithTag("fact-Flowers").performScrollTo()
                shot("plant-scrolled")
            }
            step("map") {
                compose.onNodeWithTag("rangeMap").performScrollTo()
                shot("plant-map")
            }
            step("where to buy with sellers") {
                compose.onNodeWithTag("whereToBuy").performScrollTo().performClick()
                waitFor(10_000) { exists(hasTestTag("whereToBuyDone")) }
                shot("where-to-buy-sellers")
                compose.onNodeWithTag("whereToBuyDone").performClick()
            }
        }
        launch().use {
            waitForPlants()
            openPlant("Staghorn", "rhus-typhina")
            step("where to buy with none") {
                compose.onNodeWithTag("whereToBuy").performScrollTo().performClick()
                waitFor(10_000) { exists(hasTestTag("whereToBuyDone")) }
                shot("where-to-buy-none")
            }
        }
        launch().use {
            waitForPlants()
            openPlant("Lovegrass", "eragrostis-spectabilis")
            shot("plant-no-photo")
        }
    }

    private fun gardenFlow() {
        launch(saved = fourPlants, now = "2027-11-18T09:00").use {
            openGarden()
            waitFor(10_000) { exists(hasTestTag("job-stems-tidy")) }
            shot("garden-top")
            check("garden")
            step("saved plants") {
                compose.onNodeWithTag("saved-lindera-benzoin").performScrollTo()
                shot("garden-saved-spicebush")
            }
            step("swipe to remove") {
                dragLeft("saved-lindera-benzoin", 120.dp)
                waitFor(5_000) { exists(hasTestTag("remove-lindera-benzoin")) }
                shot("garden-saved-swiped")
            }
        }
        launch(saved = fourPlants, now = "2027-11-18T09:00").use {
            openGarden()
            waitFor(10_000) { exists(hasTestTag("job-stems-tidy")) }
            step("job sheet") {
                compose.onNodeWithTag("job-stems-tidy").performScrollTo().performClick()
                waitFor(10_000) { exists(hasTestTag("markDone")) }
                shot("job-sheet")
                compose.onNodeWithTag("source-0").performScrollTo()
                shot("job-sheet-sources")
                compose.onNodeWithTag("jobSheetDone").performScrollTo().performClick()
            }
        }
        launch(saved = listOf("echinacea-purpurea"), now = "2027-11-18T09:00").use {
            openGarden()
            step("all done") {
                waitFor(10_000) { exists(hasTestTag("tick-stems-tidy")) }
                compose.onNodeWithTag("tick-stems-tidy").performScrollTo().performClick()
                compose.onNodeWithTag("tick-sow-cold-seed").performScrollTo().performClick()
                compose.waitForIdle()
                compose.onNodeWithTag("tick-stems-tidy").performScrollTo()
                shot("garden-done")
            }
        }
        launch(now = "2027-11-18T09:00").use {
            openGarden()
            waitFor(10_000) { exists(hasTestTag("gardenEmpty")) }
            shot("garden-empty")
        }
    }

    private fun settingsFlow() {
        launch().use {
            openSettings()
            shot("settings")
            check("settings")
            step("photo credits") {
                compose.onNodeWithText("Photo credits and licenses").performScrollTo().performClick()
                waitFor(10_000) { exists(hasTestTag("title") and hasText("Photo credits and licenses")) }
                shot("photo-credits")
                repeat(3) { n ->
                    compose.onRoot().performTouchInput { swipeUp() }
                    shot("photo-credits-${n + 2}")
                }
                compose.onNodeWithContentDescription("Back").performClick()
            }
            step("change location") {
                compose.onNodeWithTag("changeLocation").performScrollTo().performClick()
                waitFor(10_000) { exists(hasTestTag("zipField")) }
                compose.onNodeWithTag("zipField").performTextInput("44101")
                waitFor(10_000) { exists(hasText("Cuyahoga County, Ohio")) }
                shot("change-location")
            }
        }
    }

    /** Plants on a phone 360 by 640 dp, before the large title collapses: how many rows show. */
    private fun smallPhoneFlow() {
        screenChanged = true
        shell("wm size 720x1280")
        shell("wm density 320")
        launch().use { scenario ->
            waitForPlants()
            shot("plants-small-phone")
            val windowHeight = compose.onRoot().fetchSemanticsNode().size.height
            var navBar = 0
            scenario.onActivity { activity ->
                navBar = ViewCompat.getRootWindowInsets(activity.window.decorView)
                    ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
            }
            // Rows are counted only above the navigation bar, and only the part of the window the list can use.
            val height = windowHeight - navBar
            val whole = plantRows().count {
                // boundsInRoot is cut to the list's edge, so a row cut off by the tab bar reports a short height. A row
                // is whole only when that height equals the row's own height.
                it.boundsInRoot.height >= it.size.height - 1 && it.boundsInRoot.top >= 0 && it.boundsInRoot.bottom <= height
            }
            val any = plantRows().count { it.boundsInRoot.bottom > 0 && it.boundsInRoot.top < height }
            note("SMALLPHONE [$size] 360x640 dp: $whole plant rows fully above the navigation bar, $any at least partly (window ${windowHeight}px, navigation bar ${navBar}px)")
        }
    }

    // The tests, one per area and size

    @Test fun firstOpenNormal() { useSize(false); firstOpenFlow() }

    @Test fun firstOpenLargest() { useSize(true); firstOpenFlow() }

    @Test fun plantsNormal() { useSize(false); plantsFlow() }

    @Test fun plantsLargest() { useSize(true); plantsFlow() }

    @Test fun plantPageNormal() { useSize(false); plantPageFlow() }

    @Test fun plantPageLargest() { useSize(true); plantPageFlow() }

    @Test fun gardenNormal() { useSize(false); gardenFlow() }

    @Test fun gardenLargest() { useSize(true); gardenFlow() }

    @Test fun settingsNormal() { useSize(false); settingsFlow() }

    @Test fun settingsLargest() { useSize(true); settingsFlow() }

    @Test fun smallPhoneNormal() { useSize(false); smallPhoneFlow() }

    @Test fun smallPhoneLargest() { useSize(true); smallPhoneFlow() }
}
