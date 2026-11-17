// Checks RangeShapes against apps/shared/bundle/expected/map.json: where the website's RANGE_SHAPES.point puts a place
// on the map, and for each ecoregion how many separate shapes its outline has and where the first one starts.
package garden.hereabouts.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

class RangeMapTest {
    private fun loadShapes(): RangeShapes = RangeShapes.load(Expected.bundleDir.resolve("tables").resolve("range-shapes.json"))

    private fun numbers(c: Case, key: String): List<Double> {
        val a = c.element(key) as? JsonArray ?: fail("${c.file} case ${c.index} $key is not a list")
        return a.map { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.doubleOrNull ?: fail("${c.file} case ${c.index} $key: $it") }
    }

    @Test
    fun everyExpectedCase() {
        val shapes = loadShapes()
        val cases = Expected.cases("map.json")
        var points = 0
        var regions = 0
        val bad = ArrayList<String>()
        for (c in cases) {
            val keys = c.fields.keys
            if (keys == setOf("lat", "lon", "point")) {
                points++
                val lat = c.double("lat")
                val lon = c.double("lon")
                val want = numbers(c, "point")
                val (x, y) = shapes.point(lon, lat)
                if (want.size != 2 || abs(x - want[0]) > 1e-9 || abs(y - want[1]) > 1e-9) {
                    bad.add("case ${c.index} ($lat, $lon): got ($x, $y), expected $want")
                }
            } else if (keys == setOf("region", "pathCount", "firstPoint")) {
                regions++
                val region = c.string("region")
                val wantCount = c.double("pathCount").toInt()
                val wantFirst = numbers(c, "firstPoint")
                try {
                    val paths = shapes.paths(region)
                    val first = paths.firstOrNull()?.firstOrNull()
                    if (paths.size != wantCount) bad.add("region $region: ${paths.size} shapes, expected $wantCount")
                    if (wantFirst.size != 2 || first == null || first.first != wantFirst[0] || first.second != wantFirst[1]) {
                        bad.add("region $region: first point $first, expected $wantFirst")
                    }
                } catch (e: RangeShapes.PathError) {
                    bad.add("region $region: ${e.message}")
                }
            } else {
                bad.add("case ${c.index}: unexpected fields ${keys.sorted()}")
            }
        }
        assertTrue(points > 0)
        assertEquals(shapes.regions.size, regions, "map.json should have one case per region")
        println("map.json: $points point cases and $regions region cases checked")
        if (bad.isNotEmpty()) fail("${bad.size} mismatches in map.json; first:\n" + bad.take(5).joinToString("\n"))
    }

    @Test
    fun everyShapeIsClosedWithPoints() {
        val shapes = loadShapes()
        assertEquals(640.0, shapes.width)
        assertTrue(shapes.regions.isNotEmpty())
        for (region in shapes.regions.keys) {
            val paths = shapes.paths(region)
            assertTrue(paths.isNotEmpty(), region)
            assertTrue(paths.all { it.isNotEmpty() }, region)
        }
        assertEquals(0, shapes.paths("no-such-region").size)
    }

    @Test
    fun pathsRejectUnknownCommands() {
        val projection = RangeShapes.Projection(n = 1.0, C = 1.0, r0 = 1.0, x0 = 0.0, y0 = 0.0, scale = 1.0, lon0 = 0.0)
        val shapes = RangeShapes(
            width = 10.0, height = 10.0, projection = projection,
            regions = mapOf(
                "curve" to "M0 0C1 1 2 2 3 3Z", "relative" to "M0 0l1 1Z", "arc" to "M0 0A1 1 0 0 1 2 2Z", "cutShort" to "M0 0L1",
                "noCommand" to "0 0L1 1Z", "afterClose" to "M0 0L1 1Z2 2", "lineFirst" to "L1 1Z", "good" to "M1 2L3 4 5 6ZM7 8L9 10Z",
            ),
        )
        for (bad in listOf("curve", "relative", "arc", "cutShort", "noCommand", "afterClose", "lineFirst")) {
            assertFailsWith<RangeShapes.PathError>(bad) { shapes.paths(bad) }
        }
        val good = shapes.paths("good")
        assertEquals(2, good.size)
        assertEquals(listOf(1.0 to 2.0, 3.0 to 4.0, 5.0 to 6.0), good[0])
        assertEquals(listOf(7.0 to 8.0, 9.0 to 10.0), good[1])
    }
}
