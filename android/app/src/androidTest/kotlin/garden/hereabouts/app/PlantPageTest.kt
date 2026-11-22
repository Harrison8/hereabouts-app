// The plant page and its Where to buy sheet, tapped through like a person and compared with the website's own answers
// in expected/sellers.json (made by running the website's code, and carried in the test app's assets): Spicebush in
// Fairfax County shows its three photos with the counter, its facts with ranges read "to", the season chart and the
// USDA link; the sheet lists the sellers in the website's order, and Garden for Wildlife's link is the website's own
// with the app's "__app" tag; Bur Oak in Jefferson County, Kentucky has a shop 15 miles away with Directions; Staghorn
// Sumac has no seller in Virginia and says so; Purple Lovegrass has no photo and shows the plate. The same checks as
// the iPhone app's. Under test the app hands no address to another app: it only notes it, and the tests read the note.
// Events go to a stand-in server on 127.0.0.1 run by the test; nothing reaches an outside address.
package garden.hereabouts.app

import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlantPageTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: StubServer
    private lateinit var settings: ServiceSettings
    private var fontChanged = false

    @Before
    fun start() {
        server = StubServer()
        TestSwitches.site = server.baseUrl
        TestSwitches.offline = false
        TestSwitches.opened.clear()
        settings = ServiceSettings.forLaunch(context)
        clearFiles()
    }

    @After
    fun stop() {
        server.stop()
        TestSwitches.site = InstrumentationRegistry.getArguments().getString(HereaboutsTestRunner.BASE_URL_ARGUMENT)
        TestSwitches.offline = false
        TestSwitches.opened.clear()
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

    // Launching, opening a plant and reading the screen

    /** The app with `location` kept as the county, open on the Plants tab with its list drawn. */
    private fun launch(location: Location): ActivityScenario<MainActivity> {
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
        assertTrue("The Plants tab has no list", waitFor(20_000) { exists(hasTestTag("plantList")) })
        assertTrue("The Plants tab has no search field", waitFor(10_000) { exists(hasTestTag("search")) })
        return scenario
    }

    /** Searches the list for `search`, as a person would, and taps the plant's row. */
    private fun openPlant(search: String, id: String) {
        compose.onNodeWithTag("search").performTextInput(search)
        // The Search key closes the keyboard, so it does not cover the plant page.
        compose.onNodeWithTag("search").performImeAction()
        compose.waitForIdle()
        compose.onNodeWithTag("plantList").performScrollToKey(id)
        assertTrue("No row for $id after searching \"$search\"", waitFor(10_000) { exists(hasTestTag("plant-$id")) })
        compose.onNodeWithTag("plant-$id").performClick()
        assertTrue("The plant page for $id did not open", waitFor(10_000) { exists(hasTestTag("plantPage")) })
        compose.waitForIdle()
    }

    /** Opens the Where to buy sheet from the button under the plant's name. */
    private fun openWhereToBuy() {
        compose.onNodeWithTag("whereToBuy").performScrollTo().performClick()
        assertTrue("The Where to buy sheet did not open", waitFor(10_000) { exists(hasTestTag("whereToBuyDone")) })
        compose.waitForIdle()
    }

    private fun exists(matcher: SemanticsMatcher): Boolean =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun node(tag: String, unmerged: Boolean = false): SemanticsNode =
        compose.onNodeWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNode("No $tag on the screen")

    /** Everything a node reads out: its spoken label, its words and its state. */
    private fun words(node: SemanticsNode): String = listOfNotNull(
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "),
        node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text },
        node.config.getOrNull(SemanticsProperties.StateDescription),
    ).joinToString(" ")

    private fun text(tag: String, unmerged: Boolean = false): String =
        node(tag, unmerged).config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    private fun label(tag: String): String =
        node(tag).config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()

    /**
     * The seller rows of the open sheet, top to bottom, each read as its words. Sorted by where each row is laid out,
     * not by its visible bounds: a row scrolled below the sheet's edge has empty visible bounds at the top.
     */
    private fun sellerRows(): List<SemanticsNode> =
        compose.onAllNodes(hasTestTag("seller")).fetchSemanticsNodes().sortedBy { it.positionInRoot.y }

    /** The first line a seller row shows: the seller's name. */
    private fun sellerName(row: SemanticsNode): String =
        row.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text.orEmpty()

    private fun waitFor(millis: Long, condition: () -> Boolean): Boolean = try {
        compose.waitUntil(millis) { condition() }
        true
    } catch (e: Throwable) {
        condition()
    }

    /** The events of one name that reached the stand-in server, as their properties. */
    private fun posted(name: String): List<JsonObject> =
        server.postedEvents.filter { it.name == name }.map { it.properties }

    private fun string(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun bool(element: JsonElement?): Boolean? = (element as? JsonPrimitive)?.booleanOrNull

    private fun number(element: JsonElement?): Double? = (element as? JsonPrimitive)?.doubleOrNull

    /** The addresses the app handed out, oldest first, each checked to be a plain "view this" with no app chosen. */
    private fun openedLinks(): List<String> = TestSwitches.opened.map { intent ->
        assertEquals("A link was not handed out to be viewed", Intent.ACTION_VIEW, intent.action)
        assertNull("A link names the app to open it, so the phone's default could not take it", intent.`package`)
        assertNull("A link names the app to open it, so the phone's default could not take it", intent.component)
        intent.dataString.orEmpty()
    }

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

    // The website's answers

    private val sellers: JsonObject by lazy {
        val text = try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("sellers.json").use {
                it.readBytes().decodeToString()
            }
        } catch (e: Exception) {
            throw AssertionError("sellers.json could not be read: it is not in the test app's assets", e)
        }
        Json.parseToJsonElement(text).jsonObject
    }

    /** The sellers.json case for a plant in a county, as the app's channel makes it. */
    private fun sellersCase(plant: String, county: String): JsonObject {
        val cases = sellers["cases"]?.jsonArray ?: throw AssertionError("sellers.json could not be read: no cases")
        return cases.map { it.jsonObject }.firstOrNull { c ->
            string(c["channel"]) == "app" && string(c["plant"]) == plant &&
                string((c["location"] as? JsonObject)?.get("countyFips")) == county
        } ?: throw AssertionError("sellers.json has no app case for $plant in county $county")
    }

    private fun view(c: JsonObject): JsonObject = c["view"]?.jsonObject ?: throw AssertionError("The case has no view")

    private fun rows(c: JsonObject): List<JsonObject> = (view(c)["rows"] as? JsonArray).orEmpty().map { it.jsonObject }

    /** The county a case was made for, exactly as the website's answers have it, position included. */
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

    // The tests

    /**
     * Spicebush in Fairfax: three photos with the counter, swiped through; the name, the Latin name and "Shrub · Native
     * to Fairfax County, Virginia"; the facts with ranges read "to"; the season chart's sentence; the map; the USDA
     * link handed to the browser; Save; and Back to the list.
     */
    @Test
    fun spicebushShowsItsPhotosFactsChartMapAndUsdaLink() {
        launch(location(sellersCase("lindera-benzoin", "51059"))).use {
            openPlant("Spicebush", "lindera-benzoin")
            assertTrue("No photo counter", waitFor(10_000) { exists(hasTestTag("photoCounter")) })
            assertEquals("In bloom · 1 of 3", text("photoCounter"))
            screenshot("plant-page-top")
            compose.onNodeWithTag("photoPager").performTouchInput { swipeLeft() }
            assertTrue("The second photo did not show", waitFor(5_000) { text("photoCounter") == "In fruit · 2 of 3" })
            compose.onNodeWithTag("photoPager").performTouchInput { swipeLeft() }
            assertTrue("The third photo did not show", waitFor(5_000) { text("photoCounter") == "In fall · 3 of 3" })

            assertEquals("Spicebush", text("plantName"))
            assertEquals("Lindera benzoin", text("plantSci"))
            assertEquals("Shrub · Native to Fairfax County, Virginia", text("plantMeta"))
            assertTrue("Height reads: ${words(node("fact-Height"))}", words(node("fact-Height")).contains("6 to 12 ft"))

            compose.onNodeWithTag("seasonChart").performScrollTo()
            assertTrue(
                "The season chart reads: ${label("seasonChart")}",
                label("seasonChart").startsWith("Through the year. In leaf: March to October; Bloom: March to April"),
            )
            compose.onNodeWithTag("fact-Flowers").performScrollTo()
            assertTrue("Flowers reads: ${words(node("fact-Flowers"))}", words(node("fact-Flowers")).contains("Mar to Apr"))
            val growth = words(node("fact-Growth"))
            assertTrue(
                "Growth reads: $growth",
                growth.contains(
                    "Slow to moderate (about 1 to 1.5 ft a year); 6 to 12 ft in about 6 to 8 years; fruit in 3 to 6 years",
                ),
            )
            screenshot("plant-page-scrolled")

            // No line on the page writes a range with a dash.
            for (n in compose.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()) {
                assertFalse("A line shows an en dash: ${words(n)}", words(n).contains('–'))
            }

            assertEquals("Where to buy", sellerCardTitle())
            compose.onNodeWithTag("rangeMap").performScrollTo().assertIsDisplayed()
            assertTrue(
                "The map reads: ${label("rangeMap")}",
                label("rangeMap") == "The shaded parts of this map of the lower 48 states are where Spicebush grows wild. " +
                    "Fairfax County, Virginia is marked with a dot.",
            )
            screenshot("plant-page-map")
            compose.onNodeWithTag("usdaLink").performScrollTo().performClick()
            assertTrue("The USDA link was not handed to the browser", waitFor(5_000) { TestSwitches.opened.isNotEmpty() })
            assertEquals("https://plants.sc.egov.usda.gov/plant-profile/LIBE3", openedLinks().last())

            // Save on the page saves the plant, and the bar's bookmark says so too.
            compose.onNodeWithTag("pageSave").performScrollTo().performClick()
            assertTrue("Save did not save", waitFor(5_000) { words(node("pageSave")).contains("Saved") })
            assertTrue("The bar's bookmark does not say Saved", words(node("toolbarSave")).contains("Saved"))
            assertTrue("No plant_saved event", StubServer.waitFor(15) { posted("plant_saved").isNotEmpty() })

            // The back arrow returns to the list, which still shows Spicebush.
            compose.onNodeWithContentDescription("Back").performClick()
            assertTrue("Back did not return to the list", waitFor(10_000) { exists(hasTestTag("plantList")) })
            assertFalse("The plant page is still open", exists(hasTestTag("plantPage")))
        }
    }

    /** The words on the kraft Where to buy row's first line. */
    private fun sellerCardTitle(): String {
        compose.onNodeWithTag("whereToBuyCard").performScrollTo()
        return node("whereToBuyCard").config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text.orEmpty()
    }

    /**
     * Spicebush's Where to buy in Fairfax: the summary, the sellers in the website's order, the commission note, and
     * Garden for Wildlife's own link with the "__app" tag handed to the browser, with affiliate_click. buy_panel_open
     * is sent once, when the page opens, and not again when the sheet does.
     */
    @Test
    fun whereToBuyListsTheWebsitesSellersAndOpensTheAppLink() {
        val case = sellersCase("lindera-benzoin", "51059")
        val expected = rows(case)
        launch(location(case)).use {
            openPlant("Spicebush", "lindera-benzoin")
            assertTrue("No buy_panel_open when the page opened", StubServer.waitFor(15) { posted("buy_panel_open").isNotEmpty() })
            assertEquals("lindera-benzoin", string(posted("buy_panel_open").first()["plant"]))
            assertEquals("${expected.size} sellers ship to Virginia", text("whereToBuySubtitle", unmerged = true))

            openWhereToBuy()
            assertEquals(
                "${expected.size} sellers ship Spicebush to Virginia. None has a shop near you.",
                text("whereToBuySummary"),
            )
            assertTrue("No commission note", exists(hasTestTag("commissionNote")))
            assertEquals(
                "The sellers are not in the website's order",
                expected.map { string(it["name"]) },
                sellerRows().map { sellerName(it) },
            )
            assertEquals("Garden for Wildlife", sellerName(sellerRows().first()))
            screenshot("where-to-buy")

            val first = expected.first()
            compose.onAllNodes(hasTestTag("seller"))[0].performClick()
            assertTrue("The seller's link was not handed to the browser", waitFor(5_000) { TestSwitches.opened.isNotEmpty() })
            val link = openedLinks().last()
            assertEquals(string(first["href"]), link)
            assertTrue("Garden for Wildlife's link has no __app tag: $link", link.endsWith("__app"))

            assertTrue("No affiliate_click", StubServer.waitFor(15) { posted("affiliate_click").isNotEmpty() })
            val click = posted("affiliate_click").first()
            assertEquals("lindera-benzoin", string(click["plant"]))
            assertEquals("Garden for Wildlife", string(click["vendor"]))
            assertEquals(false, bool(click["local"]))
            assertEquals(true, bool(click["affiliate"]))
            assertTrue("local_vendor_click was sent for a seller with no shop nearby", posted("local_vendor_click").isEmpty())
            assertEquals("buy_panel_open was sent again when the sheet opened", 1, posted("buy_panel_open").size)

            compose.onNodeWithTag("whereToBuyDone").performClick()
            assertTrue("Done did not close the sheet", waitFor(10_000) { !exists(hasTestTag("whereToBuyDone")) })
        }
    }

    /**
     * Bur Oak in Jefferson County, Kentucky: the first seller is a shop 15 miles away. Its row says "Pick up · 15 mi ·
     * Goshen, KY", and Directions asks the phone's maps app for the same place as the website's Google Maps link, then
     * hands that link to the browser when no maps app takes it. local_vendor_click is sent, affiliate_click is not.
     */
    @Test
    fun aNearbyShopHasDirections() {
        val case = sellersCase("quercus-macrocarpa", "21111")
        val first = rows(case).first()
        assertTrue("The website's first Bur Oak seller is not a shop nearby", bool(first["local"]) == true)
        launch(location(case)).use {
            openPlant("Bur Oak", "quercus-macrocarpa")
            openWhereToBuy()
            val row = sellerRows().first()
            assertEquals(string(first["name"]), sellerName(row))
            assertTrue("The shop's row reads: ${words(row)}", words(row).contains("Pick up · 15 mi · Goshen, KY"))
            screenshot("where-to-buy-nearby")

            val directions = compose.onAllNodes(hasTestTag("directions"))[0]
            assertEquals(
                "Directions to ${string(first["name"])}",
                directions.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull(),
            )
            directions.performClick()
            assertTrue("Directions handed nothing out", waitFor(5_000) { TestSwitches.opened.size >= 2 })
            val links = openedLinks()
            val maps = string(first["directions"]).orEmpty()
            val place = android.net.Uri.parse(maps).getQueryParameter("destination").orEmpty()
            assertTrue("The website's Directions link has no place: $maps", place.isNotEmpty())
            assertTrue(
                "Directions did not ask a maps app for $place first: $links",
                links[links.size - 2].startsWith("geo:") && links[links.size - 2].contains(place),
            )
            assertEquals("With no maps app, Directions did not open the website's link", maps, links.last())

            assertTrue("No local_vendor_click", StubServer.waitFor(15) { posted("local_vendor_click").isNotEmpty() })
            val click = posted("local_vendor_click").first()
            assertEquals("quercus-macrocarpa", string(click["plant"]))
            assertEquals(string(first["name"]), string(click["vendor"]))
            assertTrue("affiliate_click was sent for Directions", posted("affiliate_click").isEmpty())
        }
    }

    /**
     * Staghorn Sumac has no seller that ships to Virginia: the row says "No seller listed yet", the sheet shows the
     * website's line and no commission note, and no_seller_shown is sent once, when the page opens.
     */
    @Test
    fun aPlantWithNoSellerSaysSo() {
        val case = sellersCase("rhus-typhina", "51059")
        val none = string(view(case)["noSellerText"])
        assertTrue("The website has a seller for Staghorn Sumac in Virginia now", rows(case).isEmpty() && none != null)
        launch(location(case)).use {
            openPlant("Staghorn", "rhus-typhina")
            assertTrue("No no_seller_shown when the page opened", StubServer.waitFor(15) { posted("no_seller_shown").isNotEmpty() })
            val shown = posted("no_seller_shown").first()
            assertEquals("rhus-typhina", string(shown["plant"]))
            assertEquals("VA", string(shown["state"]))
            assertEquals("No seller listed yet", text("whereToBuySubtitle", unmerged = true))

            openWhereToBuy()
            assertEquals(none, text("noSeller"))
            assertFalse("The sheet has a commission note with no seller", exists(hasTestTag("commissionNote")))
            assertTrue("The sheet lists a seller", sellerRows().isEmpty())
            screenshot("where-to-buy-none")
            assertEquals("no_seller_shown was sent again", 1, posted("no_seller_shown").size)
            assertEquals("buy_panel_open was sent more than once", 1, posted("buy_panel_open").size)
        }
    }

    /** Purple Lovegrass has no photo yet: the page shows the plate in the photo's place, and no counter. */
    @Test
    fun aPlantWithNoPhotoShowsThePlate() {
        launch(location(sellersCase("lindera-benzoin", "51059"))).use {
            openPlant("Lovegrass", "eragrostis-spectabilis")
            assertTrue("No plate", waitFor(10_000) { exists(hasTestTag("noPhoto")) })
            assertEquals("Purple Lovegrass: no photograph yet", label("noPhoto"))
            assertFalse("A counter shows with no photo", exists(hasTestTag("photoCounter")))
            screenshot("plant-page-no-photo")
        }
    }

    /** At the largest text size the page and the sheet still work: Save and Where to buy are in reach, and the sheet lists Garden for Wildlife first. */
    @Test
    fun theLargestTextSize() {
        fontChanged = true
        shell("settings put system font_scale 2.0")
        assertTrue("The text size did not change", waitFor(10_000) { fontScale() > 1.9f })
        launch(location(sellersCase("lindera-benzoin", "51059"))).use { scenario ->
            var scale = 0f
            scenario.onActivity { scale = it.resources.configuration.fontScale }
            assertTrue("The app is not at the largest text size: $scale", scale > 1.9f)
            openPlant("Spicebush", "lindera-benzoin")
            screenshot("plant-page-largest-text")
            compose.onNodeWithTag("pageSave").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("whereToBuy").performScrollTo().assertIsDisplayed()
            screenshot("plant-page-largest-text-buttons")
            compose.onNodeWithTag("seasonChart").performScrollTo()
            screenshot("plant-page-largest-text-chart")
            openWhereToBuy()
            assertEquals("Garden for Wildlife", sellerName(sellerRows().first()))
            screenshot("where-to-buy-largest-text")
        }
    }

    /** The phone's Back closes the plant page and returns to the list. */
    @Test
    fun thePhonesBackReturnsToTheList() {
        launch(location(sellersCase("lindera-benzoin", "51059"))).use { scenario ->
            openPlant("Spicebush", "lindera-benzoin")
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            assertTrue("Back did not return to the list", waitFor(10_000) { exists(hasTestTag("plantList")) })
            assertFalse("The plant page is still open", exists(hasTestTag("plantPage")))
        }
    }
}
