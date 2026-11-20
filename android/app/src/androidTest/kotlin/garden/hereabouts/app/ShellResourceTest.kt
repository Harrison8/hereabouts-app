// Checks run inside the app on a device: the brand fonts draw at the weights the type scale asks for, the shipped data
// is where the app reads it (and the test-only expected answers are not shipped), and the three kept things come back
// as they were kept. The location keeps only its county and a rounded position, and the county's details are looked
// up again on load.
@file:OptIn(ExperimentalTextApi::class)

package garden.hereabouts.app

import android.content.Context
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import garden.hereabouts.core.AppData
import garden.hereabouts.core.LocationLookup
import garden.hereabouts.core.LocationResult
import garden.hereabouts.core.Tables
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
class ShellResourceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private companion object {
        val shipped: Pair<AppData, Tables> by lazy { AppModel.loadShipped(ApplicationProvider.getApplicationContext()) }
    }

    /** A store of its own for one test, empty at the start, so the tests never touch the app's own store. */
    private fun freshStore(name: String): DataStore<Preferences> {
        val file = File(context.cacheDir, "test-$name.preferences_pb")
        file.delete()
        return PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) { file }
    }

    /** A new model on `store`, from what the store holds now, as at a fresh launch. */
    private fun model(store: DataStore<Preferences>, now: () -> ZonedDateTime = { ZonedDateTime.now() }): AppModel {
        val (data, tables) = shipped
        return AppModel(data, tables, store, runBlocking { store.data.first() }, now)
    }

    @Test
    fun figtreeDrawsAtRegularAndSemiBoldNotItsDefaultLight() {
        val measurer = TextMeasurer(createFontFamilyResolver(context), Density(context), LayoutDirection.Ltr)
        fun width(family: FontFamily, weight: FontWeight) = measurer.measure(
            "Purple coneflower grows hereabouts",
            TextStyle(fontFamily = family, fontWeight = weight, fontSize = 20.sp),
        ).size.width

        val light = FontFamily(
            Font(R.font.figtree, FontWeight.Light, variationSettings = FontVariation.Settings(FontVariation.weight(300))),
        )
        val lightWidth = width(light, FontWeight.Light)
        val regularWidth = width(Figtree, FontWeight.Normal)
        val semiBoldWidth = width(Figtree, FontWeight.SemiBold)
        // Figtree's letters widen as they get heavier, so each weight that really draws is wider than the one before.
        assertTrue("Regular ($regularWidth) is no wider than Light ($lightWidth)", regularWidth > lightWidth)
        assertTrue("SemiBold ($semiBoldWidth) is no wider than Regular ($regularWidth)", semiBoldWidth > regularWidth)

        val serifWidth = width(YoungSerif, FontWeight.Normal)
        assertNotEquals("Young Serif draws like the phone's own font", width(FontFamily.Default, FontWeight.Normal), serifWidth)
    }

    @Test
    fun dataShipsWithoutTheExpectedAnswers() {
        val assets = context.assets
        for (path in listOf("data/plants.json", "data/version.json", "tables/zip-county.json", "tables/care-rules.json")) {
            assets.open(path).close()
        }
        assertTrue("images/ is empty", assets.list("images").orEmpty().isNotEmpty())
        val top = assets.list("").orEmpty().toList()
        assertFalse("expected/ is in the app, but only the logic tests need it. Assets: $top", "expected" in top)
        // The fonts ship once, as font resources; only their licences stay in the assets.
        val fonts = assets.list("fonts").orEmpty().toList()
        assertTrue("A font file is in the assets as well as the resources: $fonts", fonts.none { it.endsWith(".ttf") })

        val (data, _) = shipped
        assertTrue(data.plants.isNotEmpty())
    }

    @Test
    fun theModelLoadsAndKeepsWhatItShould() = runBlocking<Unit> {
        val store = freshStore("model")
        val model = model(store)
        assertNull(model.location.value)

        val fairfax = (LocationLookup.resolve("51059", null, null, model.tables) as LocationResult.Found).location
        model.setLocation(fairfax).join()
        model.toggleSaved("echinacea-purpurea").join()
        model.toggleSaved("asclepias-tuberosa").join()
        model.toggleSaved("rudbeckia-hirta").join()
        model.toggleSaved("asclepias-tuberosa").join()
        model.toggleTick("cut-back").join()

        val again = model(store)
        assertEquals(fairfax, again.location.value)
        assertEquals("Fairfax County", again.location.value?.countyName)
        assertEquals(listOf("echinacea-purpurea", "rudbeckia-hirta"), again.saved.value)
        assertTrue(again.isTicked("cut-back"))
    }

    @Test
    fun theLocationKeepsOnlyTheCountyAndARoundedPosition() = runBlocking<Unit> {
        val store = freshStore("location")
        val model = model(store)
        val fairfax = (LocationLookup.resolve("51059", 38.84567, -77.30612, model.tables) as LocationResult.Found).location
        model.setLocation(fairfax).join()
        assertEquals(38.85, model.location.value?.lat)
        assertEquals(-77.31, model.location.value?.lon)

        // Only the county code and the rounded position are written to the phone.
        val written = assertNotNull(store.data.first()[LocationStore.key])
        val o = Json.parseToJsonElement(written) as JsonObject
        assertEquals(setOf("countyFips", "lat", "lon"), o.keys)
        assertEquals("51059", (o["countyFips"] as JsonPrimitive).content)
        assertEquals(38.85, (o["lat"] as JsonPrimitive).double, 0.0)
        assertEquals(-77.31, (o["lon"] as JsonPrimitive).double, 0.0)

        val again = model(store)
        assertEquals(38.85, again.location.value?.lat)
        assertEquals(-77.31, again.location.value?.lon)

        // The county's name, state and ecoregion come from the tables on load, not from what was written.
        store.edit {
            it[LocationStore.key] = """{"countyFips":"51059","countyName":"Old name","stateName":"Old state",""" +
                """"stateUsps":"XX","ecoregion":"00","lat":38.85,"lon":-77.31}"""
        }
        val reloaded = assertNotNull(model(store).location.value)
        assertEquals("Fairfax County", reloaded.countyName)
        assertEquals("VA", reloaded.stateUsps)
        assertEquals(fairfax.stateName, reloaded.stateName)
        assertEquals(model.tables.countyEcoregion["51059"], reloaded.ecoregion)
        assertEquals(38.85, reloaded.lat)

        // A kept county the tables no longer serve, or one that does not read, loads as no county.
        store.edit { it[LocationStore.key] = """{"countyFips":"99999","lat":null,"lon":null}""" }
        assertNull(model(store).location.value)
        store.edit { it[LocationStore.key] = "not json" }
        assertNull(model(store).location.value)
    }

    @Test
    fun theWeekIsNamedByItsSaturday() {
        val zone = ZoneId.of("America/New_York")
        fun day(y: Int, m: Int, d: Int, h: Int = 12) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, zone)
        assertEquals("20261114", TicksStore.weekId(day(2026, 11, 14))) // Saturday
        assertEquals("20261114", TicksStore.weekId(day(2026, 11, 19))) // Thursday
        assertEquals("20261114", TicksStore.weekId(day(2026, 11, 20, 23))) // Friday night
        assertEquals("20261121", TicksStore.weekId(day(2026, 11, 21, 0))) // Saturday
        assertEquals("20261226", TicksStore.weekId(day(2027, 1, 1))) // across the year
    }

    @Test
    fun ticksFromEarlierWeeksAreDropped() = runBlocking<Unit> {
        val store = freshStore("ticks")
        store.edit { it[TicksStore.key] = """{"20261107":["water"],"20261114":["cut-back"],"junk":["x"]}""" }
        val thursday = ZonedDateTime.of(2026, 11, 19, 12, 0, 0, 0, ZoneId.systemDefault())

        val model = model(store) { thursday }
        assertEquals(mapOf("20261114" to setOf("cut-back")), model.ticks.value)
        // What was dropped is forgotten, not only hidden.
        assertNotNull(model.cleanup).join()
        assertEquals("""{"20261114":["cut-back"]}""", store.data.first()[TicksStore.key])

        // Ticks that do not read at all count as none and are left as they are, as on the iPhone.
        store.edit { it[TicksStore.key] = "not json" }
        val unread = model(store) { thursday }
        assertEquals(emptyMap<String, Set<String>>(), unread.ticks.value)
        assertNull(unread.cleanup)
        assertEquals("not json", store.data.first()[TicksStore.key])
    }

    private fun <T : Any> assertNotNull(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }
}
