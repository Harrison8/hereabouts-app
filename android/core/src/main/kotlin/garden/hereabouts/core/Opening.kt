// The opening's sums, with no drawing: where the tag, its point and the map sit on a given screen, how far each
// area's moss has filled in at a given moment, how far the first screen has opened out of the tag's point, and
// whether the opening plays at all. Also the home map's shades, worked out as the website's home map works them out.
//
// Every number is the approved preview's. The opening lasts 500 ms: each area fills on a spring that settles in
// 200 ms, starting up to 360 ms late the farther it is from the visitor's county, and from 250 ms the first screen
// opens as a growing circle centred on the tag's point. Sizes are in density-independent pixels (dp) on Android,
// which stand where the preview's points stand.
package garden.hereabouts.core

import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** A point in dp or in map units. */
data class OpeningPoint(val x: Double, val y: Double)

/** A rectangle in dp. */
data class OpeningRect(val x: Double, val y: Double, val width: Double, val height: Double)

/** Where the opening's parts sit on a screen of the given size, in dp. */
data class OpeningLayout(val screenWidth: Double, val screenHeight: Double) {
    /** The tag, centred on the whole screen (the loading screen puts it in the same place). */
    val tag: OpeningRect = OpeningRect((screenWidth - TAG_SIZE) / 2, (screenHeight - TAG_SIZE) / 2, TAG_SIZE, TAG_SIZE)

    /**
     * The tag's point: units (128, 232) of the tag's drawing. The county sits under it, and the first screen opens
     * out of it.
     */
    val point: OpeningPoint = OpeningPoint(tag.x + 128 * UNIT, tag.y + 232 * UNIT)

    /** The kraft patch behind the tag's label, so the cut-out H stays kraft while the map fills in around it. */
    val backing: OpeningRect = OpeningRect(tag.x + 46 * UNIT, tag.y + 26 * UNIT, 164 * UNIT, 116 * UNIT)

    /** The circle's last radius: from the tag's point to the farthest corner of the screen, plus 2 dp. */
    val maxRadius: Double = hypot(max(point.x, screenWidth - point.x), max(point.y, screenHeight - point.y)) + 2

    /** Where the map's top-left corner goes, in dp, so that `county` (in map units) lands on the tag's point. */
    fun mapOrigin(county: OpeningPoint, scale: Double): OpeningPoint =
        OpeningPoint(point.x - county.x * scale, point.y - county.y * scale)

    companion object {
        /** The tag is 160 dp square; its drawing is 256 units square, so one unit is 0.625 dp. */
        const val TAG_SIZE = 160.0
        const val UNIT = TAG_SIZE / 256
    }
}

/** The opening's timing. */
object OpeningMotion {
    /** The whole opening, and the moment the first screen starts to open, in milliseconds. */
    const val LENGTH_MS = 500.0
    const val OPEN_AT_MS = 250.0

    /** The map is drawn 1000 dp wide. */
    const val MAP_WIDTH = 1000.0

    /** Each area's fill settles in 200 ms. */
    const val SETTLE_MS = 200.0

    /** No area starts later than 360 ms, which it does when it is 620 dp or more from the county. */
    const val LONGEST_DELAY_MS = 360.0
    const val FARTHEST = 620.0

    /** The circle grows as (R / 0.6)(1 - e^(-kt)), so it reaches its last radius R exactly 250 ms after it starts. */
    val circleK: Double = -ln(0.4) / 0.25

    /** dp per map unit. */
    fun scale(mapWidth: Double): Double = MAP_WIDTH / mapWidth

    /** The county's point in map units, or, with no county (or one off the map), the map's middle. */
    fun county(point: OpeningPoint?, mapWidth: Double, mapHeight: Double): OpeningPoint =
        point ?: OpeningPoint(mapWidth / 2, mapHeight / 2)

    /**
     * How far an area's moss has filled in, from 0 to nearly 1, `ms` after that area started: a critically damped
     * spring that settles in 200 ms.
     */
    fun fill(ms: Double): Double {
        if (ms <= 0) return 0.0
        val omega = 7.43 / (SETTLE_MS / 1000)
        val t = ms / 1000
        return 1 - (1 + omega * t) * exp(-omega * t)
    }

    /** How long an area waits before it starts to fill, from its distance to the county in dp. */
    fun delay(distance: Double): Double = min(distance / FARTHEST, 1.0) * LONGEST_DELAY_MS

    /** The same, from the county and the middle of the area's bounding box, both in map units. */
    fun delay(county: OpeningPoint, centre: OpeningPoint, scale: Double): Double =
        delay(hypot(centre.x - county.x, centre.y - county.y) * scale)

    /** The radius of the circle the first screen shows through, in dp, `ms` after the opening started. */
    fun radius(ms: Double, maxRadius: Double): Double {
        if (ms <= OPEN_AT_MS) return 0.0
        if (ms >= LENGTH_MS) return maxRadius
        val t = (ms - OPEN_AT_MS) / 1000
        return min(maxRadius, (maxRadius / 0.6) * (1 - exp(-circleK * t)))
    }
}

/** How the opening starts on this launch. */
sealed interface OpeningStart {
    /** It plays once and goes. */
    data object Play : OpeningStart

    /** It is not shown: the first screen shows straight away. */
    data object Off : OpeningStart

    /** A screen test holds it still at this moment, in milliseconds, so the frame can be photographed. */
    data class Hold(val ms: Double) : OpeningStart
}

/** When the opening plays. */
object OpeningRule {
    /**
     * It plays on a fresh launch, and not with animations turned off on the phone, not when a tapped reminder opened
     * the app, not when it has already been shown in this launch (coming back from the background), and not when a
     * screen test turns it off. A screen test may hold it at one moment instead, which never overrides a reason not
     * to play.
     */
    fun start(reduceMotion: Boolean, fromReminder: Boolean, shownBefore: Boolean, skipForTests: Boolean, holdAtMs: Double?): OpeningStart {
        if (reduceMotion || fromReminder || shownBefore || skipForTests) return OpeningStart.Off
        if (holdAtMs != null) return OpeningStart.Hold(min(max(holdAtMs, 0.0), OpeningMotion.LENGTH_MS))
        return OpeningStart.Play
    }
}

/** The website's home map: each area shaded by how many of our plants are native there. */
object HomeMap {
    /** The most plants an area can have and still take shades 1, 2 and 3; any more takes shade 4. None is shade 0. */
    val STEP_MAXIMA = listOf(20, 75, 125)

    /** How strong the moss is for shades 0 to 4. */
    val STRENGTHS = listOf(0.07, 0.2, 0.4, 0.66, 0.92)

    fun step(plantCount: Int): Int {
        if (plantCount <= 0) return 0
        val i = STEP_MAXIMA.indexOfFirst { plantCount <= it }
        return 1 + if (i < 0) STEP_MAXIMA.size else i
    }

    /** How many plants are native to each area: a plant counts once for every area in its native list. */
    fun counts(geo: Map<String, PlantGeo>): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (g in geo.values) for (e in g.eco) out[e] = (out[e] ?: 0) + 1
        return out
    }

    /** Each of `regions`' shade, 0 to 4. */
    fun steps(geo: Map<String, PlantGeo>, regions: List<String>): Map<String, Int> {
        val n = counts(geo)
        return regions.associateWith { step(n[it] ?: 0) }
    }
}
