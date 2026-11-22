// The first-open lookups inside the app: what a typed ZIP code, town, state or address finds, and the website's message
// for each failure; the street-address lookup's question and how it reads the Census reply, against saved replies and
// a stand-in server on 127.0.0.1 (never the Census itself); and the Settings lines for the version and the data date.
// The same checks as the iPhone app's.
package garden.hereabouts.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import garden.hereabouts.app.firstopen.AddressLookup
import garden.hereabouts.app.firstopen.FirstOpenLookup
import garden.hereabouts.app.firstopen.GeocoderPlace
import garden.hereabouts.app.firstopen.LatestLookup
import garden.hereabouts.app.settings.dataDay
import garden.hereabouts.app.settings.versionText
import garden.hereabouts.core.LocationResult
import garden.hereabouts.core.Tables
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirstOpenPartsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val tables: Tables by lazy { AppModel.loadShipped(context).second }

    private fun county(outcome: FirstOpenLookup.Outcome?): String? =
        (outcome as? FirstOpenLookup.Outcome.Found)?.let { "${it.location.countyName}, ${it.location.stateName}" }

    private fun method(outcome: FirstOpenLookup.Outcome?): String? = (outcome as? FirstOpenLookup.Outcome.Found)?.method

    private fun message(outcome: FirstOpenLookup.Outcome?): String? = (outcome as? FirstOpenLookup.Outcome.Message)?.text

    // ZIP codes, as they are typed

    @Test
    fun aZipFindsItsCountyOnlyAtFiveDigits() {
        assertNull(FirstOpenLookup.zip("", tables))
        assertNull(FirstOpenLookup.zip("2203", tables))
        assertEquals("Fairfax County, Virginia", county(FirstOpenLookup.zip("22030", tables)))
        assertEquals("zip", method(FirstOpenLookup.zip("22030", tables)))
        assertEquals("We only cover the lower 48 states for now.", message(FirstOpenLookup.zip("99501", tables)))
        assertEquals(
            "We couldn't find ZIP 00000. Try a nearby ZIP or your street address.",
            message(FirstOpenLookup.zip("00000", tables)),
        )
        // Only the first five digits of what was typed or pasted count.
        assertEquals("22030", FirstOpenLookup.zipDigits("22030-1234"))
        assertEquals("22030", FirstOpenLookup.zipDigits(" 2 2a030"))
        assertEquals("", FirstOpenLookup.zipDigits("２２０３０"))
    }

    // Towns, counties, states and addresses, as the website's lookup takes them

    @Test
    fun typedTextGetsTheWebsitesAnswers() = runBlocking<Unit> {
        val offline = AddressLookup(tables, endpoint = "http://127.0.0.1:9/none")
        suspend fun run(text: String) = FirstOpenLookup.text(text, tables, offline)

        assertEquals("Type your city, county or ZIP code first.", message(run("   ")))
        var outcome = run("Dayton, OH")
        assertEquals("Montgomery County, Ohio", county(outcome))
        // The website's method values: the kind of place found, "city" or "county".
        assertEquals("city", method(outcome))
        outcome = run("Montgomery County, OH")
        assertEquals("Montgomery County, Ohio", county(outcome))
        assertEquals("county", method(outcome))
        outcome = run("22030")
        assertEquals("Fairfax County, Virginia", county(outcome))
        assertEquals("zip", method(outcome))
        assertEquals("We only cover the lower 48 states for now.", message(run("99501")))
        assertEquals("Ohio contains several different ecoregions. Type a city, county or ZIP code.", message(run("Ohio")))
        assertEquals("We only cover the lower 48 states for now.", message(run("Alaska")))
        assertEquals(
            "There's a Springfield in more than one state. Add the state, like Springfield, MO.",
            message(run("Springfield")),
        )
        assertEquals(
            "We couldn't find that place. Try your ZIP code, or a city and state like Dayton, OH.",
            message(run("Nowhereville")),
        )
        // A street address, or an unknown name with a digit in it, goes to the address lookup, which has no answer here.
        assertEquals(
            "The address lookup isn't responding. Try your 5-digit ZIP code instead.",
            message(run("123 Main St, Springfield, IL")),
        )
        assertEquals(
            "The address lookup isn't responding. Try your 5-digit ZIP code instead.",
            message(run("Route 9 Nowhereville")),
        )
    }

    @Test
    fun thePhonesPositionFindsTheNearestZipsCounty() {
        val found = FirstOpenLookup.device(38.94, -77.16, tables)
        assertEquals("Fairfax County, Virginia", county(found))
        assertEquals("device", method(found))
        val location = (found as? FirstOpenLookup.Outcome.Found)?.location
        assertEquals(38.94, location?.lat ?: 0.0, 0.0)
        assertEquals(-77.16, location?.lon ?: 0.0, 0.0)
        assertEquals(
            "We only cover the lower 48 states for now.",
            message(FirstOpenLookup.device(61.2181, -149.9003, tables)),
        )
    }

    // The street-address lookup

    @Test
    fun theAddressQuestionIsTheWebsitesAsJson() {
        val url = AddressLookup.url("4600 Silver Hill Rd, Washington, DC 20233 & co+1")
        assertEquals("https", url.protocol)
        assertEquals("geocoding.geo.census.gov", url.host)
        assertEquals("/geocoder/geographies/onelineaddress", url.path)
        assertEquals(
            "address=4600%20Silver%20Hill%20Rd%2C%20Washington%2C%20DC%2020233%20%26%20co%2B1" +
                "&benchmark=Public_AR_Current&vintage=Current_Current&layers=Counties&format=json",
            url.query,
        )
        // The characters encodeURIComponent leaves alone, and a letter outside ASCII as its UTF-8 bytes.
        assertEquals("address=A-_.!~*'()%C3%A9%2F", AddressLookup.url("A-_.!~*'()é/").query.substringBefore("&"))
    }

    @Test
    fun repliesAreReadAsTheWebsiteReadsThem() {
        fun read(json: String): GeocoderPlace? =
            AddressLookup.countyFromGeocoderReply(runCatching { Json.parseToJsonElement(json) }.getOrNull())

        val fairfax = read(FAIRFAX_REPLY)
        assertNotNull("The Fairfax reply gave no county", fairfax)
        assertEquals("51059", fairfax?.countyFips)
        assertEquals("Fairfax County", fairfax?.countyName)
        assertEquals(38.846016, fairfax?.lat ?: 0.0, 0.000001)
        assertEquals(-77.307487, fairfax?.lon ?: 0.0, 0.000001)
        // The website's own cases.
        assertEquals(
            GeocoderPlace("51059", "Fairfax County", 38.9, -77.1),
            read("""{"result":{"addressMatches":[{"coordinates":{"x":-77.1,"y":38.9},"geographies":{"Counties":[{"GEOID":"51059","NAME":"Fairfax County"}]}}]}}"""),
        )
        assertNull(read(NO_MATCH_REPLY))
        assertNull(read("null"))
        assertNull(read("""{"result":{"addressMatches":[{"geographies":{"Counties":[{"GEOID":"1","NAME":"x"}]}}]}}"""))
        assertNull(read("""{"result":{"addressMatches":[{"coordinates":{"x":1,"y":2},"geographies":{"Counties":[]}}]}}"""))
    }

    @Test
    fun theAddressLookupAgainstAStandIn() = runBlocking<Unit> {
        val server = StubServer()
        try {
            val lookup = AddressLookup(tables, endpoint = server.baseUrl + "/geocoder")
            fun path(text: String): String = lookup.requestUrl(text).file

            server.serve(path("1 Found St"), FAIRFAX_REPLY.toByteArray())
            val found = lookup.county("1 Found St")
            val location = (found as? LocationResult.Found)?.location
            assertNotNull("No county: $found. Requests: ${server.receivedPaths}", location)
            assertEquals("Fairfax County", location?.countyName)
            assertEquals("Virginia", location?.stateName)
            // The matched point is rounded before anything keeps it.
            assertEquals(38.85, location?.lat ?: 0.0, 0.0)
            assertEquals(-77.31, location?.lon ?: 0.0, 0.0)

            server.serve(path("2 Missing St"), NO_MATCH_REPLY.toByteArray())
            assertEquals(LocationResult.NotFound, lookup.county("2 Missing St"))

            server.serve(path("3 Alaska St"), ANCHORAGE_REPLY.toByteArray())
            assertEquals(LocationResult.OutsideUs48, lookup.county("3 Alaska St"))

            server.serve(path("4 Broken St"), "<html>".toByteArray())
            assertFails(lookup, "4 Broken St")
            server.serve(path("5 Error St"), FAIRFAX_REPLY.toByteArray(), status = 500)
            assertFails(lookup, "5 Error St")

            // The website's messages for each answer.
            assertEquals(
                "We couldn't match that address. Try your 5-digit ZIP code instead.",
                message(FirstOpenLookup.text("2 Missing St", tables, lookup)),
            )
            assertEquals("address", method(FirstOpenLookup.text("1 Found St", tables, lookup)))
        } finally {
            server.stop()
        }
    }

    private suspend fun assertFails(lookup: AddressLookup, text: String) {
        val result = try {
            lookup.county(text)
        } catch (e: AddressLookup.NoAnswer) {
            return
        }
        fail("$text gave $result instead of failing")
    }

    // A lookup that was overtaken

    /**
     * The website's superseded(): Cancel, or a change to what was typed, while the Census is asked means the old
     * answer sets nothing; and when a second lookup starts, only its answer is used.
     */
    @Test
    fun aLookupThatWasOvertakenSetsNothing() = runBlocking<Unit> {
        val latest = LatestLookup()
        assertEquals("A lookup nothing overtook lost its answer", 1, latest.run { 1 })

        // Cancel, or the text changed, while the lookup waited.
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cancelled = async {
            latest.run {
                started.complete(Unit)
                release.await()
                2
            }
        }
        started.await()
        latest.supersede()
        release.complete(Unit)
        assertNull("A lookup the screen gave up on still gave its answer", cancelled.await())

        // A second lookup started while the first waited.
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val first = async {
            latest.run {
                firstStarted.complete(Unit)
                releaseFirst.await()
                3
            }
        }
        firstStarted.await()
        val second = latest.run { 4 }
        releaseFirst.complete(Unit)
        assertEquals("The newer lookup lost its answer", 4, second)
        assertNull("The older lookup's answer was used after a newer one started", first.await())
    }

    // Settings

    @Test
    fun theDataDateIsTheDayItWasWritten() {
        assertEquals("November 9, 2026", dataDay("2026-11-09T21:26:41-05:00"))
        assertEquals("January 1, 2099", dataDay("2099-01-01T00:00:00Z"))
        assertEquals("February 28, 2026", dataDay("2026-02-28"))
    }

    @Test
    fun theVersionAndTheShippedDataDate() {
        assertEquals("1.0 (1)", versionText("1.0", "1"))
        assertEquals("1.0", versionText("1.0", ""))
        assertEquals("1.0 (1)", versionText(context))
        val shipped = AppModel.loadShipped(context).first.version.dataDate
        assertTrue(
            "The shipped date $shipped reads as ${dataDay(shipped)}",
            Regex("^[A-Z][a-z]+ \\d{1,2}, \\d{4}$").matches(dataDay(shipped)),
        )
    }

    companion object {
        /** A full reply for an address in Fairfax County, as the Census sends it with layers=Counties and format=json. */
        const val FAIRFAX_REPLY = """
            {"result":{"input":{"address":{"address":"1 Found St"},"vintage":{"isDefault":true,"id":"4","vintageName":"Current_Current",
            "vintageDescription":"Current Vintage - Current Benchmark"},"benchmark":{"isDefault":false,"benchmarkDescription":
            "Public Address Ranges - Current Benchmark","id":"4","benchmarkName":"Public_AR_Current"}},"addressMatches":[{
            "tigerLine":{"side":"L","tigerLineId":"76355984"},"geographies":{"Counties":[{"GEOID":"51059","CENTLAT":"+38.8336500",
            "AREAWATER":24726305,"BASENAME":"Fairfax","OID":27590405339546,"LSADC":"06","FUNCSTAT":"A","INTPTLAT":"+38.8344764",
            "NAME":"Fairfax County","OBJECTID":2318,"CENTLON":"-077.2765473","COUNTYCC":"H1","COUNTYNS":"01480119",
            "AREALAND":1012435806,"INTPTLON":"-077.2765196","MTFCC":"G4020","STATE":"51","COUNTY":"059"}]},
            "coordinates":{"x":-77.30748724230096,"y":38.84601622386617},"addressComponents":{"zip":"22030","streetName":"FOUND",
            "preType":"","city":"FAIRFAX","preDirection":"","suffixDirection":"","fromAddress":"1","state":"VA","suffixType":"ST",
            "toAddress":"99","suffixQualifier":"","preQualifier":""},"matchedAddress":"1 FOUND ST, FAIRFAX, VA, 22030"}]}}
            """

        /** The reply for an address the Census cannot match. */
        const val NO_MATCH_REPLY = """
            {"result":{"input":{"address":{"address":"2 Missing St"},"benchmark":{"id":"4","benchmarkName":"Public_AR_Current"},
            "vintage":{"id":"4","vintageName":"Current_Current"}},"addressMatches":[]}}
            """

        /** An address in Anchorage, Alaska, outside the lower 48 states. */
        const val ANCHORAGE_REPLY = """
            {"result":{"addressMatches":[{"coordinates":{"x":-149.9003,"y":61.2181},"geographies":{"Counties":[
            {"GEOID":"02020","NAME":"Anchorage Municipality","STATE":"02","COUNTY":"020"}]},"matchedAddress":"3 ALASKA ST"}]}}
            """
    }
}
