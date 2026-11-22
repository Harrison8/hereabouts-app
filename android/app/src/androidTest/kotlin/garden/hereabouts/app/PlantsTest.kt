// The Plants tab, tapped through like a person, compared with the website's own answers in expected/list.json (made by
// running the website's code, and carried in the test app's assets): Fairfax County lists the same plants in the same
// order, a search for "oak" shows the same plants and the same "Also matching" lines, the height sort and the shade
// filter give the website's order, the save button saves without opening the plant, the county has its map pin,
// heights read "1.5 to 2.5 ft", and with no signal the banner shows with its cloud. The same checks as the iPhone
// app's. Each launch keeps the county exactly as the website's answers have it, position included, so nearby shops
// count the same. Events go to a stand-in server on 127.0.0.1 run by the test; nothing reaches an outside address.
package garden.hereabouts.app

import android.content.Context
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import garden.hereabouts.app.services.ServiceSettings
import garden.hereabouts.app.services.TestSwitches
import garden.hereabouts.core.Location
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlantsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val density = context.resources.displayMetrics.density
    private lateinit var server: StubServer
    private lateinit var settings: ServiceSettings
    private var fontChanged = false

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
        server.stop()
        TestSwitches.site = InstrumentationRegistry.getArguments().getString(HereaboutsTestRunner.BASE_URL_ARGUMENT)
        TestSwitches.offline = false
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

    // Launching and reading the screen

    /** The app with `location` kept as the county, as if it had been chosen on the first-open screen. */
    private fun launch(location: Location, offline: Boolean = false): ActivityScenario<MainActivity> {
        TestSwitches.offline = offline
        // The model lasts as long as the process, so the app is started afresh to read the county kept below.
        AppProcess.forget()
        runBlocking {
            context.keptStore.edit {
                it.clear()
                LocationStore.save(it, location)
            }
        }
        val scenario = launchApp()
        assertTrue("The app did not open on its tabs", waitFor(30_000) { exists(hasTestTag("tabs")) })
        assertTrue("The app did not open on Plants", waitFor(10_000) { exists(hasTestTag("title") and hasText("Plants")) })
        assertTrue("The Plants tab has no list", waitFor(20_000) { exists(hasTestTag("plantList")) })
        // On a small screen, or at the largest text size, the heading, search and chips can fill the screen, and the
        // list only builds what is on it. So this moves to the count and sort menu just above the rows first. The
        // list counts as there once a plant row is built, or, for a county with no native plant, the "Also
        // matching" line or the empty-results text.
        compose.onNodeWithTag("plantList").performScrollToKey("head-count")
        compose.waitForIdle()
        assertTrue("The Plants tab shows no plant rows, and no line saying why", waitFor(20_000) {
            plantRows().isNotEmpty() || exists(hasTestTag("alsoCounty")) || exists(hasTestTag("emptyResults"))
        })
        compose.onNodeWithTag("plantList").performScrollToIndex(0)
        compose.waitForIdle()
        return scenario
    }

    private val plantRow = SemanticsMatcher("a plant row") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("plant-") == true
    }

    /** Every plant row the list has built, top to bottom. Each row is marked "plant-<id>". */
    private fun plantRows(): List<SemanticsNode> =
        compose.onAllNodes(plantRow).fetchSemanticsNodes().sortedBy { it.boundsInRoot.top }

    private fun exists(matcher: SemanticsMatcher): Boolean =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun node(tag: String, unmerged: Boolean = false): SemanticsNode =
        compose.onNodeWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNode("No $tag on the screen")

    /** Everything a node reads out: its words, its spoken label and its state. */
    private fun words(node: SemanticsNode): String = listOfNotNull(
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "),
        node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text },
        node.config.getOrNull(SemanticsProperties.StateDescription),
    ).joinToString(" ")

    private fun text(tag: String): String =
        node(tag).config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    private fun selected(node: SemanticsNode): Boolean =
        node.config.getOrNull(SemanticsProperties.Selected) == true ||
            node.config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On

    /**
     * The plant ids of the first `count` rows, top to bottom. The list only builds the rows near the screen, so this
     * moves down the list until it has seen enough, or the end of the list, then goes back to the top.
     */
    private fun shownIds(count: Int): List<String> {
        val ids = mutableListOf<String>()
        val list = compose.onNodeWithTag("plantList")
        var index = 0
        var unchanged = 0
        while (index < 400) {
            val before = ids.size
            for (row in plantRows()) {
                val id = row.config[SemanticsProperties.TestTag].removePrefix("plant-")
                if (id !in ids) ids.add(id)
            }
            if (ids.size >= count) break
            unchanged = if (ids.size == before) unchanged + 1 else 0
            if (unchanged >= 3) break
            index += 3
            try {
                list.performScrollToIndex(index)
            } catch (e: Exception) {
                break
            }
            compose.waitForIdle()
        }
        list.performScrollToIndex(0)
        compose.waitForIdle()
        return ids.take(count)
    }

    private fun search(query: String) {
        compose.onNodeWithTag("search").performTextInput(query)
        compose.waitForIdle()
    }

    private fun waitFor(millis: Long, condition: () -> Boolean): Boolean = try {
        compose.waitUntil(millis) { condition() }
        true
    } catch (e: Throwable) {
        condition()
    }

    /** The events of one name that reached the stand-in server, as their properties. */
    private fun posted(name: String, since: Int = 0): List<JsonObject> =
        server.postedEvents.drop(since).filter { it.name == name }.map { it.properties }

    private fun number(element: JsonElement?): Double? = (element as? JsonPrimitive)?.doubleOrNull

    private fun string(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** The whole screen, saved as SCREENSHOTS/<name>.png on the device, where the workflow collects it. */
    private fun screenshot(name: String) {
        compose.waitForIdle()
        shell("mkdir -p $SCREENSHOTS")
        shell("screencap -p $SCREENSHOTS/$name.png")
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

    // The website's answers

    private val list: JsonObject by lazy {
        val text = try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("list.json").use {
                it.readBytes().decodeToString()
            }
        } catch (e: Exception) {
            throw AssertionError("list.json could not be read: it is not in the test app's assets", e)
        }
        Json.parseToJsonElement(text).jsonObject
    }

    /** The list.json case for a county: the given sort and search, every set empty unless `extra` gives it, any height. */
    private fun listCase(
        county: String,
        sort: String,
        search: String = "",
        extra: Map<String, List<Any>> = emptyMap(),
    ): JsonObject {
        val cases = list["cases"]?.jsonArray ?: throw AssertionError("list.json could not be read: no cases")
        return cases.map { it.jsonObject }.firstOrNull { c ->
            val loc = c["location"] as? JsonObject
            val st = c["state"] as? JsonObject
            if (loc == null || st == null) return@firstOrNull false
            if (string(loc["countyFips"]) != county || string(st["sort"]) != sort || string(st["search"]) != search) {
                return@firstOrNull false
            }
            if (number(st["maxHeight"]) != 100.0) return@firstOrNull false
            listOf("sun", "moisture", "category", "wildlife", "zones", "bloomMonth", "bloomColor", "buy").all { field ->
                val have = (st[field] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
                val want = extra[field].orEmpty().map { "$it" }
                have == want
            }
        } ?: throw AssertionError("list.json has no case for county $county, sort $sort, search \"$search\", $extra")
    }

    private fun ids(c: JsonObject): List<String> = (c["ids"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }

    private fun also(c: JsonObject, field: String): List<String> =
        ((c["also"] as? JsonObject)?.get(field) as? JsonArray).orEmpty().map { it.jsonPrimitive.content }

    /** The county a case was made for, exactly as the website's answers have it. */
    private fun location(c: JsonObject): Location {
        val loc = c["location"]?.jsonObject ?: throw AssertionError("The case has no location")
        return Location(
            countyFips = string(loc["countyFips"]).orEmpty(),
            countyName = string(loc["countyName"]).orEmpty(),
            stateFips = string(loc["stateFips"]).orEmpty(),
            stateName = string(loc["stateName"]).orEmpty(),
            stateUsps = string(loc["stateUsps"]).orEmpty(),
            ecoregion = string(loc["ecoregion"]).orEmpty(),
            lat = number(loc["lat"]),
            lon = number(loc["lon"]),
        )
    }

    /** Plant id to common name, from the app's own data. */
    private val names: Map<String, String> by lazy {
        AppModel.loadShipped(context).first.plants.associate { it.id to it.common }
    }

    // The tests

    /** Fairfax County shows the website's first ten plants in its order, and results_shown is sent once with the count. */
    @Test
    fun fairfaxListsTheWebsitesPlantsInItsOrder() {
        val expected = listCase("51059", "suggested")
        launch(location(expected)).use {
            screenshot("plants-fairfax")
            assertEquals("Fairfax County, Virginia", text("plantsCounty"))
            val pin = node("county-pin", unmerged = true)
            val county = node("plantsCounty")
            assertTrue(
                "The map pin is not before the county",
                pin.boundsInRoot.right <= county.boundsInRoot.left + 1,
            )
            assertFalse("The no-signal banner shows with signal", exists(hasTestTag("offlineBanner")))
            assertEquals(ids(expected).take(10), shownIds(10))

            assertTrue(
                "No results_shown event reached the server. Requests: ${server.receivedPaths}",
                StubServer.waitFor(30) { posted("results_shown").isNotEmpty() },
            )
            Thread.sleep(2_000)
            val shown = posted("results_shown")
            assertEquals("results_shown should be sent once for the county", 1, shown.size)
            assertEquals(ids(expected).size.toDouble(), number(shown.first()["count"]))
            assertEquals("45", string(shown.first()["ecoregion"]))
            assertTrue("empty_results was sent for a county with plants", posted("empty_results").isEmpty())
        }
    }

    /**
     * A search for "oak" shows the website's plants, and its "Also matching" lines when the website has them:
     * Louisville has three oaks, Portland, Oregon has none native but three in its "not native here" line.
     */
    @Test
    fun searchingOakShowsTheWebsitesOaksAndAlsoMatching() {
        for (county in listOf("21111", "41051")) {
            val expected = listCase(county, "suggested", search = "oak")
            val loc = location(expected)
            // Only this launch's events count: the server keeps the ones the last county's launch sent.
            val start = server.postedEvents.size
            launch(loc).use {
                search("oak")
                val want = ids(expected)
                val count = "${want.size} native ${if (want.size == 1) "plant" else "plants"}"
                assertTrue(
                    "$county: the list did not settle on $count after searching oak; it says ${words(node("plantCount"))}",
                    waitFor(10_000) { text("plantCount") == count },
                )
                assertEquals("$county: the oak results differ from the website's", want, shownIds(50))
                // The lines below the rows may be off a small screen, which the list does not build.
                compose.onNodeWithTag("plantList").performScrollToKey("also")
                compose.waitForIdle()

                val countyAlso = also(expected, "county")
                val filtersAlso = also(expected, "filters")
                if (countyAlso.isEmpty()) {
                    assertFalse("$county: an \"Also matching\" line the website does not have", exists(hasTestTag("alsoCounty")))
                } else {
                    assertTrue("$county: no \"Also matching, but not native\" line", waitFor(5_000) { exists(hasTestTag("alsoCounty")) })
                    assertEquals(
                        "Also matching, but not native to ${loc.countyName}, ${loc.stateName}: " +
                            countyAlso.take(12).joinToString(", ") { names[it] ?: it },
                        text("alsoCounty"),
                    )
                }
                assertEquals("$county: the hidden-by-filters line", filtersAlso.isNotEmpty(), exists(hasTestTag("alsoFilters")))
                // The website hides its empty-results text when the search has matches elsewhere.
                assertEquals(
                    "$county: the empty-results text",
                    want.isEmpty() && countyAlso.isEmpty() && filtersAlso.isEmpty(),
                    exists(hasTestTag("emptyResults")),
                )
                screenshot("plants-oak-$county")

                // plant_search: the two counts only, never the typed text. It goes one second after typing stops. Under test
                // the screen's waits run on the test's own clock, which only moves while the screen is busy, so this moves
                // it on by that second.
                compose.mainClock.advanceTimeBy(1_100)
                compose.waitForIdle()
                // The compose wait moves the test's clock a frame at a time while it polls, so it works on either clock.
                val searchSent = waitFor(15_000) { posted("plant_search", since = start).isNotEmpty() }
                assertTrue(
                    "$county: no plant_search event. Events since launch: " +
                        "${server.postedEvents.drop(start).map { it.name }}. Requests: ${server.receivedPaths}",
                    searchSent,
                )
                val sent = posted("plant_search", since = start).last()
                assertEquals(want.size.toDouble(), number(sent["count"]))
                assertEquals((countyAlso.size + filtersAlso.size).toDouble(), number(sent["hidden_count"]))
                val leftovers = sent.keys - setOf("count", "hidden_count", "platform", "distinct_id", "\$process_person_profile")
                assertTrue("plant_search carries $leftovers", leftovers.isEmpty())
                for (event in server.postedEvents) {
                    val json = event.event.toString()
                    assertFalse("An event carries the search text: $json", json.lowercase().contains("\"oak\""))
                }
            }
        }
    }

    /** Choosing "Height (high to low)" (the website's tallest-first sort) puts the plants in the website's order for it. */
    @Test
    fun tallestFirstReordersAsTheWebsiteDoes() {
        val suggested = listCase("51059", "suggested")
        val tallest = listCase("51059", "height-desc")
        assertNotEquals("The two sorts start the same", ids(suggested).take(10), ids(tallest).take(10))
        launch(location(suggested)).use {
            compose.onNodeWithTag("sortMenu").performClick()
            assertTrue(
                "The sort menu has no \"Height (high to low)\"",
                waitFor(5_000) { exists(hasText("Height (high to low)")) },
            )
            screenshot("plants-sort-menu")
            compose.onNodeWithText("Height (high to low)").performScrollTo().performClick()
            assertTrue(
                "The sort menu does not show the chosen sort",
                waitFor(5_000) { words(node("sortMenu")).contains("Height (high to low)") },
            )
            assertEquals(ids(tallest).take(10), shownIds(10))
            screenshot("plants-tallest")
        }
    }

    /**
     * The save button saves and unsaves without opening the plant, and sends plant_saved and plant_unsaved. (The plant
     * page comes later; until then tapping the rest of the row does nothing to check.)
     */
    @Test
    fun theSaveButtonTogglesWithoutOpeningThePlant() {
        val expected = listCase("51059", "suggested")
        val first = ids(expected).first()
        val name = names[first] ?: throw AssertionError("No plant $first in the app's data")
        launch(location(expected)).use {
            val tag = "save-$first"
            assertTrue("The first row has no save button", waitFor(10_000) { exists(hasTestTag(tag)) })
            val save = node(tag)
            assertFalse("The save button starts saved", selected(save))
            assertTrue(
                "The save button is not labelled \"Save $name\": ${words(save)}",
                save.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("Save $name"),
            )
            assertTrue("The save button is narrower than 48 dp", save.size.width >= 48 * density - 1)
            assertTrue("The save button is shorter than 48 dp", save.size.height >= 48 * density - 1)

            compose.onNodeWithTag(tag).performClick()
            assertTrue("The save button did not save", waitFor(5_000) { selected(node(tag)) })
            assertTrue("Saving opened the plant", exists(hasTestTag("title") and hasText("Plants")))
            screenshot("plants-saved")
            assertTrue("No plant_saved event", StubServer.waitFor(20) { posted("plant_saved").isNotEmpty() })

            compose.onNodeWithTag(tag).performClick()
            assertTrue("The save button did not unsave", waitFor(5_000) { !selected(node(tag)) })
            assertTrue("Unsaving opened the plant", exists(hasTestTag("title") and hasText("Plants")))
            assertTrue("No plant_unsaved event", StubServer.waitFor(20) { posted("plant_unsaved").isNotEmpty() })
        }
    }

    /**
     * The Filters chip opens the panel; choosing "Full shade" makes its button count the website's shade list, and the
     * list then shows the website's shade plants.
     */
    @Test
    fun theFiltersPanelCountsAndFiltersAsTheWebsiteDoes() {
        val shade = listCase("51059", "suggested", extra = mapOf("sun" to listOf("shade")))
        launch(location(shade)).use {
            compose.onNodeWithTag("filtersChip").performClick()
            assertTrue("The filters panel did not open", waitFor(5_000) { exists(hasTestTag("showPlants")) })
            screenshot("plants-filters-panel")
            // Each bloom colour takes taps over at least 48 dp, even on a narrow phone.
            for (name in listOf("White", "Yellow", "Orange", "Red", "Pink", "Purple", "Blue", "Green")) {
                val swatch = compose.onNodeWithContentDescription(name).fetchSemanticsNode("No $name swatch")
                assertTrue("The $name swatch is narrower than 48 dp", swatch.size.width >= 48 * density - 1)
            }
            compose.onNodeWithText("Full shade").performClick()
            val count = ids(shade).size
            val label = "Show $count ${if (count == 1) "plant" else "plants"}"
            assertTrue(
                "The button does not say $label",
                waitFor(5_000) { words(node("showPlants")).contains(label) },
            )
            compose.onNodeWithTag("showPlants").performClick()
            assertTrue("The panel did not close", waitFor(5_000) { !exists(hasTestTag("showPlants")) })
            assertEquals(ids(shade).take(10), shownIds(10))
            screenshot("plants-full-shade")
        }
    }

    /**
     * A chosen type chip stays on screen and chosen when a search leaves none of that type, so there is still a chip to
     * turn off; and the Filters chip shows as chosen, with how many choices are on, once a panel filter is on.
     */
    @Test
    fun chosenChipsStayVisibleAndFiltersShowsWhenOn() {
        launch(location(listCase("51059", "suggested"))).use {
            assertTrue("No Filters chip", waitFor(10_000) { exists(hasTestTag("filtersChip")) })
            assertFalse("The Filters chip shows as chosen with nothing chosen in the panel", selected(node("filtersChip")))
            compose.onNodeWithTag("filtersChip").performClick()
            assertTrue("The filters panel did not open", waitFor(5_000) { exists(hasTestTag("showPlants")) })
            compose.onNodeWithText("Full shade").performClick()
            compose.onNodeWithTag("showPlants").performClick()
            assertTrue("The panel did not close", waitFor(5_000) { !exists(hasTestTag("showPlants")) })
            assertTrue("The Filters chip is not shown as chosen", waitFor(5_000) { selected(node("filtersChip")) })
            assertEquals("1 chosen", node("filtersChip").config.getOrNull(SemanticsProperties.StateDescription))
            // The chip is drawn 32 dp tall, as Material draws it, and Material widens the area that takes the tap to
            // 48 dp around it. That tap area is what has to reach 48 dp.
            assertTrue(
                "The Filters chip takes taps over less than 48 dp",
                node("filtersChip").size.height >= 48 * density - 1 ||
                    runCatching { compose.onNodeWithTag("filtersChip").assertTouchHeightIsEqualTo(48.dp) }.isSuccess,
            )

            // Fairfax has three full-shade trees. Choose Trees, then search a name no tree has: the Trees chip stays,
            // chosen, counting 0. (Turned off at 0 it would go, which is right, so the test ends here.)
            assertTrue("No Trees chip", waitFor(10_000) { exists(hasTestTag("chip-tree")) })
            compose.onNodeWithTag("chip-tree").performClick()
            assertTrue("The Trees chip is not shown as chosen", waitFor(5_000) { selected(node("chip-tree")) })
            screenshot("plants-chips")
            search("milkweed")
            assertTrue(
                "Trees and \"milkweed\" still show plants",
                waitFor(10_000) { text("plantCount") == "0 native plants" && plantRows().isEmpty() },
            )
            assertTrue("The chosen Trees chip vanished when no trees matched", exists(hasTestTag("chip-tree")))
            assertTrue("The Trees chip is no longer shown as chosen", selected(node("chip-tree")))
            val treeWords = node("chip-tree").config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            assertTrue("The Trees chip should count 0 trees: $treeWords", "0" in treeWords)
            assertTrue("The Filters chip lost its chosen look", selected(node("filtersChip")))
            screenshot("plants-trees-milkweed")
        }
    }

    /** With no signal the banner shows above the chips, and the list still works. */
    @Test
    fun noSignalShowsTheBanner() {
        launch(location(listCase("51059", "suggested")), offline = true).use {
            assertTrue("No banner with no signal", waitFor(10_000) { exists(hasTestTag("offlineBanner")) })
            assertEquals(
                "No signal. Every plant and photo still works. Seller links open once you have signal again.",
                text("offlineBanner"),
            )
            val icon = node("no-signal-icon", unmerged = true)
            val banner = node("offlineBanner")
            assertTrue(
                "The no-signal icon is not before the words",
                icon.boundsInRoot.right <= banner.boundsInRoot.left + 1,
            )
            assertEquals("The list is empty with no signal", 3, shownIds(3).size)
            screenshot("plants-no-signal")
        }
    }

    /** A height in the list reads with "to", not a dash: Butterfly Weed is "1.5 to 2.5 ft", and no row shows an en dash. */
    @Test
    fun heightsInTheListReadTo() {
        launch(location(listCase("51059", "suggested"))).use {
            assertEquals("The list is empty", 3, shownIds(3).size)
            for (row in plantRows()) {
                assertFalse("A row shows an en dash: ${words(row)}", words(row).contains('–'))
            }
            search("Butterfly Weed")
            assertTrue(
                "No row for Butterfly Weed",
                waitFor(10_000) { exists(hasTestTag("plant-asclepias-tuberosa")) },
            )
            val label = node("plant-asclepias-tuberosa").config.getOrNull(SemanticsProperties.ContentDescription)
                .orEmpty().joinToString(" ")
            assertTrue("Butterfly Weed's row reads: $label", label.endsWith("1.5 to 2.5 ft"))
        }
    }

    /** At the largest text size every row still has its save button in reach, and the panel opens. */
    @Test
    fun theLargestTextSize() {
        val expected = listCase("51059", "suggested")
        val first = ids(expected).first()
        fontChanged = true
        shell("settings put system font_scale 2.0")
        assertTrue("The text size did not change", waitFor(10_000) { fontScale() > 1.9f })
        launch(location(expected)).use { scenario ->
            var scale = 0f
            scenario.onActivity { scale = it.resources.configuration.fontScale }
            assertTrue("The app is not at the largest text size: $scale", scale > 1.9f)
            screenshot("plants-largest-text")
            compose.onNodeWithTag("plantList").performScrollToKey(first)
            compose.waitForIdle()
            compose.onNodeWithTag("save-$first").assertIsDisplayed()
            screenshot("plants-largest-text-first-row")
            compose.onNodeWithTag("plantList").performScrollToKey("head-chips")
            compose.waitForIdle()
            compose.onNodeWithTag("filtersChip").performClick()
            assertTrue("The filters panel did not open", waitFor(5_000) { exists(hasTestTag("showPlants")) })
            screenshot("plants-filters-largest-text")
        }
    }

    companion object {
        /** A folder the shell can write to and the workflow can read from. */
        const val SCREENSHOTS = "/data/local/tmp/hereabouts-screenshots"
    }
}
