// Checks the daily data check: when to look for new data, and which downloads are used. Each rejected download below
// is wrong in one way only (its hash is worked out again for the changed files), so it is rejected for that reason
// and no other. Then the SHA-256 the check uses, against the standard published values and the bundle's own hash.
package garden.hereabouts.core

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataUpdateTest {
    private val current = DataVersion(schema = 1, dataDate = "2026-11-09T21:26:41-05:00", dataHash = "a".repeat(64))

    private val goodPlants = """[{"id":"new-plant","common":"New Plant","sci":"Nova planta","category":"perennial"}]""".toByteArray()
    private val goodVendors = """{"Some Nursery":{"name":"Some Nursery"}}""".toByteArray()
    private val goodRegions = """{"schema":1,"plants":{"new-plant":{"eco":["71"],"intro":[]}},"served":["71"]}""".toByteArray()

    private fun hashOf(vararg parts: ByteArray): String = DataUpdate.sha256Hex(parts.fold(ByteArray(0)) { a, b -> a + b })

    private fun version(plants: ByteArray, vendors: ByteArray, regions: ByteArray, schema: Int, date: String, hash: String?): ByteArray {
        val h = hash ?: hashOf(plants, vendors, regions)
        return """{"schema":$schema,"dataDate":"$date","dataHash":"$h"}""".toByteArray()
    }

    private fun accept(
        plants: ByteArray,
        vendors: ByteArray,
        regions: ByteArray,
        schema: Int = 1,
        date: String = "2026-11-12T08:00:00-05:00",
        hash: String? = null,
        current: DataVersion = this.current,
    ): AppData? = DataUpdate.accept(version(plants, vendors, regions, schema, date, hash), plants, vendors, regions, current)

    @Test
    fun goodSetIsUsed() {
        val data = assertNotNull(accept(goodPlants, goodVendors, goodRegions))
        assertEquals(listOf("new-plant"), data.plants.map { it.id })
        assertEquals("Some Nursery", data.vendors["Some Nursery"]?.name)
        assertEquals(listOf("71"), data.geo["new-plant"]?.eco)
        assertEquals(listOf("71"), data.served)
        assertEquals("2026-11-12T08:00:00-05:00", data.version.dataDate)
    }

    @Test
    fun sameDateWithNewHashIsUsed() {
        // A second publish on the same day: same date, different files.
        assertNotNull(accept(goodPlants, goodVendors, goodRegions, date = current.dataDate))
        // The same moment written in UTC.
        assertNotNull(accept(goodPlants, goodVendors, goodRegions, date = "2026-11-10T02:26:41Z"))
        // A hash written in capitals is the same hash.
        assertNotNull(accept(goodPlants, goodVendors, goodRegions, hash = hashOf(goodPlants, goodVendors, goodRegions).uppercase()))
    }

    @Test
    fun cutOffPlantsFileIsRejected() {
        assertNull(accept(goodPlants.copyOf(goodPlants.size - 10), goodVendors, goodRegions))
    }

    @Test
    fun laterSchemaIsRejected() {
        assertNull(accept(goodPlants, goodVendors, goodRegions, schema = 2))
    }

    @Test
    fun vendorsListIsRejected() {
        assertNull(accept(goodPlants, """[{"name":"Some Nursery"}]""".toByteArray(), goodRegions))
    }

    @Test
    fun hashMismatchIsRejected() {
        assertNull(accept(goodPlants, goodVendors, goodRegions, hash = "0".repeat(64)))
    }

    @Test
    fun sameHashAsNowIsRejected() {
        val now = DataVersion(schema = 1, dataDate = current.dataDate, dataHash = hashOf(goodPlants, goodVendors, goodRegions))
        assertNull(accept(goodPlants, goodVendors, goodRegions, current = now))
    }

    @Test
    fun olderDateIsRejected() {
        assertNull(accept(goodPlants, goodVendors, goodRegions, date = "2026-11-01T00:00:00-05:00"))
        // One second before the current moment, written in UTC.
        assertNull(accept(goodPlants, goodVendors, goodRegions, date = "2026-11-10T02:26:40Z"))
        assertNull(accept(goodPlants, goodVendors, goodRegions, date = "not a date"))
        assertNull(accept(goodPlants, goodVendors, goodRegions, date = "2026-11-12T08:00:00"))
    }

    @Test
    fun plantWithoutNameIsRejected() {
        assertNull(accept("""[{"id":"new-plant","sci":"Nova planta","category":"perennial"}]""".toByteArray(), goodVendors, goodRegions))
        assertNull(accept("""[{"id":"","common":"New Plant","sci":"Nova planta","category":"perennial"}]""".toByteArray(), goodVendors, goodRegions))
        assertNull(accept("""[{"id":"new-plant","common":"New Plant","category":"perennial"}]""".toByteArray(), goodVendors, goodRegions))
        assertNull(accept("""[{"id":"new-plant","common":"New Plant","sci":"Nova planta","category":""}]""".toByteArray(), goodVendors, goodRegions))
        // A null name is no name either.
        assertNull(accept("""[{"id":"new-plant","common":null,"sci":"Nova planta","category":"perennial"}]""".toByteArray(), goodVendors, goodRegions))
    }

    @Test
    fun emptyPlantsIsRejected() {
        assertNull(accept("[]".toByteArray(), goodVendors, goodRegions))
    }

    @Test
    fun noServedRegionsIsRejected() {
        val noServed = """{"schema":1,"plants":{"new-plant":{"eco":["71"],"intro":[]}},"served":[]}""".toByteArray()
        assertNull(accept(goodPlants, goodVendors, noServed))
        val noRanges = """{"schema":1,"plants":{},"served":["71"]}""".toByteArray()
        assertNull(accept(goodPlants, goodVendors, noRanges))
    }

    @Test
    fun repeatedPlantIdIsRejected() {
        val twice = """
            [{"id":"new-plant","common":"New Plant","sci":"Nova planta","category":"perennial"},
             {"id":"new-plant","common":"Other Plant","sci":"Alia planta","category":"shrub"}]
        """.trimIndent().toByteArray()
        assertNull(accept(twice, goodVendors, goodRegions))
    }

    @Test
    fun brokenTextIsRejected() {
        // Bytes that are not UTF-8: Kotlin would otherwise read them as U+FFFD and carry on.
        val broken = """[{"id":"new-plant","common":"New Plant","sci":"Nova planta","category":"perennial"}]""".toByteArray()
        broken[30] = 0xFF.toByte()
        assertNull(accept(broken, goodVendors, goodRegions))
    }

    @Test
    fun unreadableCurrentDateSkipsTheDateCheck() {
        // The current dataDate cannot be read: any readable new date is used, as long as the hash differs.
        val unreadable = DataVersion(schema = 1, dataDate = "not a date", dataHash = current.dataHash)
        assertNotNull(accept(goodPlants, goodVendors, goodRegions, date = "2000-01-01T00:00:00Z", current = unreadable))
        val sameHash = DataVersion(schema = 1, dataDate = "not a date", dataHash = hashOf(goodPlants, goodVendors, goodRegions))
        assertNull(accept(goodPlants, goodVendors, goodRegions, current = sameHash))
    }

    @Test
    fun brokenVersionFileIsRejected() {
        assertNull(DataUpdate.accept("{".toByteArray(), goodPlants, goodVendors, goodRegions, current))
    }

    @Test
    fun bundleDataPassesItsOwnCheck() {
        val dir = Expected.bundleDir.resolve("data")
        fun read(name: String) = dir.resolve(name).readBytes()
        val older = DataVersion(schema = 1, dataDate = "2020-01-01T00:00:00Z", dataHash = "")
        val data = assertNotNull(
            DataUpdate.accept(read("version.json"), read("plants.json"), read("vendors.json"), read("plant-regions.json"), older),
        )
        assertTrue(data.plants.isNotEmpty())
    }

    @Test
    fun shouldCheck() {
        val now = Instant.ofEpochSecond(2_000_000_000)
        assertEquals(Duration.ofHours(24), DataUpdate.interval)
        assertTrue(DataUpdate.shouldCheck(null, now))
        assertTrue(DataUpdate.shouldCheck(now.minusSeconds(24 * 3600), now))
        assertTrue(DataUpdate.shouldCheck(now.minusSeconds(30 * 3600), now))
        assertFalse(DataUpdate.shouldCheck(now.minusSeconds(24 * 3600 - 1), now))
        assertFalse(DataUpdate.shouldCheck(now, now))
        // The clock was moved back: check again.
        assertTrue(DataUpdate.shouldCheck(now.plusSeconds(3600), now))
    }

    @Test
    fun instant() {
        assertEquals(0.0, DataUpdate.instant("1970-01-01T00:00:00Z"))
        assertEquals(0.0, DataUpdate.instant("1970-01-01T00:00Z"))
        assertEquals(DataUpdate.instant("2026-11-10T02:26:41Z"), DataUpdate.instant("2026-11-09T21:26:41-05:00"))
        assertEquals(1_794_277_601.0, DataUpdate.instant("2026-11-10T02:26:41Z"))
        assertEquals(951_825_600.5, DataUpdate.instant("2000-02-29T12:00:00.5+00:00"))
        assertNull(DataUpdate.instant("2026-02-29T00:00:00Z"))
        assertNull(DataUpdate.instant("2026-11-10"))
        assertNull(DataUpdate.instant("2026-11-10T02:26:41+0500"))
        assertNull(DataUpdate.instant("2026-11-10T02:26:41+24:00"))
        assertNull(DataUpdate.instant("2026-11-10T02:26:41.Z"))
        assertNull(DataUpdate.instant("2026-11-10T02:26:41Z "))
    }

    // SHA-256

    @Test
    fun sha256StandardValues() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", DataUpdate.sha256Hex(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", DataUpdate.sha256Hex("abc".toByteArray()))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            DataUpdate.sha256Hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".toByteArray()),
        )
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            DataUpdate.sha256Hex(ByteArray(1_000_000) { 'a'.code.toByte() }),
        )
    }

    @Test
    fun bundleFilesGiveTheirDataHash() {
        val dir = Expected.bundleDir.resolve("data")
        val all = listOf("plants.json", "vendors.json", "plant-regions.json").fold(ByteArray(0)) { a, n -> a + dir.resolve(n).readBytes() }
        val version = json.decodeFromString(DataVersion.serializer(), dir.resolve("version.json").readText(Charsets.UTF_8))
        assertEquals(version.dataHash, DataUpdate.sha256Hex(all))
    }
}
