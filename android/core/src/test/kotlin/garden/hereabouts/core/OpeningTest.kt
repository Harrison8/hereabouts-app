// Checks the opening's sums against the approved preview, the rules for when it plays, and the home map's shades
// against the website's own home map (expected/home-map.json).
//
// The preview's numbers below were worked out with the preview's own formulas, for its 393 x 852 point phone with
// Fairfax County's point under the tag. They are the same numbers the iPhone's checks use.
package garden.hereabouts.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpeningTest {
    private val fairfax = OpeningPoint(546.2722381929555, 176.57759987397765)

    // Where things sit

    @Test
    fun thePreviewPhoneLayout() {
        val layout = OpeningLayout(393.0, 852.0)
        assertEquals(OpeningRect(116.5, 346.0, 160.0, 160.0), layout.tag)
        assertEquals(OpeningPoint(196.5, 491.0), layout.point)
        assertEquals(OpeningRect(145.25, 362.25, 102.5, 72.5), layout.backing)
        assertEquals(530.8603312784956, layout.maxRadius, 1e-9)
        val scale = OpeningMotion.scale(640.0)
        assertEquals(1.5625, scale)
        val origin = layout.mapOrigin(fairfax, scale)
        assertEquals(-657.0503721764929, origin.x, 1e-9)
        assertEquals(215.09750019690995, origin.y, 1e-9)
    }

    @Test
    fun anotherPhoneSizeCentresTheTagOnTheWholeScreen() {
        val layout = OpeningLayout(402.0, 874.0)
        assertEquals(OpeningRect(121.0, 357.0, 160.0, 160.0), layout.tag)
        assertEquals(OpeningPoint(201.0, 502.0), layout.point)
        assertEquals(542.7448566560759, layout.maxRadius, 1e-9)
    }

    @Test
    fun withNoCountyTheMapsMiddleSitsUnderTheTagsPoint() {
        val middle = OpeningMotion.county(null, 640.0, 407.0)
        assertEquals(OpeningPoint(320.0, 203.5), middle)
        assertEquals(fairfax, OpeningMotion.county(fairfax, 640.0, 407.0))
        val layout = OpeningLayout(402.0, 874.0)
        assertEquals(OpeningPoint(-299.0, 184.03125), layout.mapOrigin(middle, OpeningMotion.scale(640.0)))
    }

    // Timing

    @Test
    fun eachAreaFillsOnTheSpringThatSettlesIn200ms() {
        val want = listOf(
            -10.0 to 0.0, 0.0 to 0.0, 20.0 to 0.17088153540753814, 50.0 to 0.5540519811031672,
            100.0 to 0.8851640951426633, 150.0 to 0.9750181501148801, 200.0 to 0.9949994292746207,
            300.0 to 0.999824537012993,
        )
        for ((ms, value) in want) assertEquals(value, OpeningMotion.fill(ms), 1e-12, "at $ms ms")
    }

    @Test
    fun nearerAreasStartSoonerAndNoneLaterThan360ms() {
        val want = listOf(0.0 to 0.0, 100.0 to 58.064516129032256, 310.0 to 180.0, 620.0 to 360.0, 1000.0 to 360.0)
        for ((distance, delay) in want) assertEquals(delay, OpeningMotion.delay(distance), 1e-12, "at $distance points")
        // 64 map units east of the county is 100 points on screen.
        val east = OpeningPoint(fairfax.x + 64, fairfax.y)
        assertEquals(58.064516129032256, OpeningMotion.delay(fairfax, east, 1.5625), 1e-9)
    }

    @Test
    fun theFirstScreenOpensOutOfTheTagsPointFrom250msAndIsWholeAt500ms() {
        val maxRadius = 530.8603312784956
        val want = listOf(
            0.0 to 0.0, 250.0 to 0.0, 260.0 to 31.841081956973472, 300.0 to 148.1514329836053,
            350.0 to 271.49538369528955, 400.0 to 374.1857854739207, 450.0 to 459.68100884410717,
            499.0 to 529.5608248779678, 500.0 to maxRadius, 700.0 to maxRadius,
        )
        for ((ms, radius) in want) assertEquals(radius, OpeningMotion.radius(ms, maxRadius), 1e-9, "at $ms ms")
        assertEquals(500.0, OpeningMotion.LENGTH_MS)
        assertEquals(250.0, OpeningMotion.OPEN_AT_MS)
    }

    // When it plays

    @Test
    fun itPlaysOnAPlainLaunch() {
        assertEquals(
            OpeningStart.Play,
            OpeningRule.start(reduceMotion = false, fromReminder = false, shownBefore = false, skipForTests = false, holdAtMs = null),
        )
    }

    @Test
    fun itDoesNotPlayWithReduceMotionAReminderTapAReturnFromTheBackgroundOrTheTestSwitch() {
        val cases = listOf(
            "Remove animations" to listOf(true, false, false, false),
            "opened from a reminder" to listOf(false, true, false, false),
            "already shown this launch" to listOf(false, false, true, false),
            "the tests' switch" to listOf(false, false, false, true),
        )
        for ((name, c) in cases) {
            assertEquals(OpeningStart.Off, OpeningRule.start(c[0], c[1], c[2], c[3], null), name)
            // Holding a frame for a screenshot never overrides a reason not to play.
            assertEquals(OpeningStart.Off, OpeningRule.start(c[0], c[1], c[2], c[3], 150.0), name)
        }
    }

    @Test
    fun aScreenTestCanHoldOneFrame() {
        fun hold(ms: Double) = OpeningRule.start(false, false, false, false, ms)
        assertEquals(OpeningStart.Hold(150.0), hold(150.0))
        assertEquals(OpeningStart.Hold(0.0), hold(0.0))
        assertEquals(OpeningStart.Hold(0.0), hold(-5.0))
        assertEquals(OpeningStart.Hold(500.0), hold(900.0))
    }

    // The home map's shades

    @Test
    fun theShadeStepsAreTheWebsites() {
        val want = listOf(0 to 0, 1 to 1, 20 to 1, 21 to 2, 75 to 2, 76 to 3, 125 to 3, 126 to 4, 900 to 4)
        for ((count, step) in want) assertEquals(step, HomeMap.step(count), "$count plants")
        assertEquals(listOf(0.07, 0.2, 0.4, 0.66, 0.92), HomeMap.STRENGTHS)
    }

    @Test
    fun eachPlantCountsOnceForEveryRegionItIsNativeTo() {
        val geo = mapOf(
            "a" to PlantGeo(eco = listOf("1", "2"), intro = emptyList()),
            "b" to PlantGeo(eco = listOf("2"), intro = listOf("51")),
        )
        assertEquals(mapOf("1" to 1, "2" to 1, "3" to 0), HomeMap.steps(geo, listOf("1", "2", "3")))
        assertEquals(mapOf("1" to 1, "2" to 2), HomeMap.counts(geo))
    }

    @Test
    fun theShadesMatchTheWebsitesHomeMap() {
        val shapes = RangeShapes.load(Expected.bundleDir.resolve("tables").resolve("range-shapes.json"))
        val steps = HomeMap.steps(Expected.data.geo, shapes.regions.keys.toList())
        val cases = Expected.cases("home-map.json")
        assertEquals(shapes.regions.size, cases.size, "home-map.json should have one case per region")
        val bad = ArrayList<String>()
        for (c in cases) {
            val region = c.string("region")
            val want = c.double("step").toInt()
            if (steps[region] != want) bad.add("region $region: ${steps[region]}, want $want")
        }
        assertTrue(bad.isEmpty(), "${bad.size} mismatches in home-map.json; first: ${bad.take(5).joinToString(" | ")}")
        println("home-map.json: ${cases.size} regions checked")
    }
}
