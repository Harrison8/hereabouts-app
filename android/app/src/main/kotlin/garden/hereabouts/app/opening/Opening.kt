// The opening, "Your map fills in". For half a second after a fresh launch, the website's home map fills in around
// the tag the loading screen showed, nearest areas first, and then the first screen opens out of the tag's point as
// a growing circle. Its first frame is the loading screen exactly: kraft, with the moss tag 160 dp square in the
// middle of the whole screen. Every frame is worked out from the time since its first frame was drawn, with the core's
// OpeningMotion, so the timing is the approved preview's and the same as the iPhone's.
//
// Whether it plays is decided once, when the screen is made, before anything is drawn (see MainActivity). It is not
// shown with the phone's animations off, when a tapped reminder opened the app, when the screen is made again (as
// when the phone is turned) or in a later screen of the same launch. Touches reach the screen underneath from 250 ms,
// when the circle starts. TalkBack never sees it.
//
// In a debug build under test, it can be turned off or held still at one moment by an extra on the starting intent,
// it is found under the test tag "opening", and once it has been on screen it leaves a mark, "openingShown", which
// stays after it goes. A release build has none of this.
package garden.hereabouts.app.opening

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import garden.hereabouts.app.AppModel
import garden.hereabouts.app.Brand
import garden.hereabouts.app.R
import garden.hereabouts.app.plant.MapShapes
import garden.hereabouts.app.plant.countyPoint
import garden.hereabouts.app.services.openingMarkForTests
import garden.hereabouts.core.Location
import garden.hereabouts.core.OpeningLayout
import garden.hereabouts.core.OpeningMotion
import garden.hereabouts.core.OpeningPoint
import garden.hereabouts.core.OpeningStart
import garden.hereabouts.core.PlantGeo
import garden.hereabouts.core.HomeMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One screen's opening: how it starts, the map it draws, and whether it has been on screen and has gone. */
class Opening(val start: OpeningStart) {
    /** The map, once built. Until then, and if the shipped map file is broken, a frame shows the tag on kraft. */
    var map by mutableStateOf<OpeningMap?>(null)
        private set

    /** Whether the map has been built, or found broken. The screen draws its first frame only after this. */
    @Volatile
    var mapSettled = false
        private set

    /** True once the opening has gone, for good. */
    var ended by mutableStateOf(start == OpeningStart.Off)
        private set

    /** How the opening was on screen, once it has been. */
    var shown by mutableStateOf<OpeningStart?>(null)
        private set

    /** Completed when the opening's first frame is drawn: its clock starts then. */
    internal val firstDrawn = CompletableDeferred<Unit>()

    /**
     * Builds the map away from the main thread from the map's outlines and the model's plant regions and county.
     * Called once the model has loaded. The screen starts reading `shapes` when it is made, alongside the model, so
     * they are usually read by then and only this short build is left. If anything here fails, the map stays empty
     * and a frame shows the tag on kraft.
     */
    internal suspend fun buildMap(shapes: Deferred<MapShapes?>, model: AppModel) {
        if (start == OpeningStart.Off) return
        try {
            val read = shapes.await()
            map = read?.let {
                withContext(Dispatchers.Default) { OpeningMap.build(it, model.data.value.geo, model.location.value) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            map = null
        } finally {
            mapSettled = true
        }
    }

    /** A reminder was tapped while the opening was up: it goes at once. */
    fun stop() {
        ended = true
    }

    internal fun appeared() {
        if (shown == null) shown = start
    }

    internal fun finish() {
        ended = true
    }
}

/**
 * The home map as the opening draws it: each area's outline, how strong its moss ends up (the website's home-map
 * shade) and how long it waits before it starts to fill.
 */
class OpeningMap(val areas: List<Area>, val county: OpeningPoint, val scale: Double) {
    class Area(val path: Path, val strength: Double, val delayMs: Double)

    companion object {
        internal fun build(map: MapShapes, geo: Map<String, PlantGeo>, location: Location?): OpeningMap {
            val shapes = map.shapes
            val scale = OpeningMotion.scale(shapes.width)
            val dot = countyPoint(location, shapes)?.let { OpeningPoint(it.x.toDouble(), it.y.toDouble()) }
            val county = OpeningMotion.county(dot, shapes.width, shapes.height)
            val steps = HomeMap.steps(geo, map.paths.map { it.first })
            val areas = map.paths.map { (code, path) ->
                val box = path.getBounds()
                val centre = OpeningPoint(box.center.x.toDouble(), box.center.y.toDouble())
                Area(path, HomeMap.STRENGTHS[steps[code] ?: 0], OpeningMotion.delay(county, centre, scale))
            }
            return OpeningMap(areas, county, scale)
        }
    }
}

/** The opening above the first screen while it shows, and the tests' mark once it has shown. */
@Composable
fun OpeningLayer(opening: Opening) {
    if (opening.start == OpeningStart.Off) return
    val tag = painterResource(R.drawable.opening_tag)
    Box(Modifier.fillMaxSize()) {
        if (!opening.ended) {
            val held = (opening.start as? OpeningStart.Hold)?.ms
            var ms by remember { mutableDoubleStateOf(held ?: 0.0) }
            LaunchedEffect(opening) {
                opening.firstDrawn.await()
                opening.appeared()
                if (held != null) return@LaunchedEffect
                val startedAt = withFrameNanos { it }
                while (ms < OpeningMotion.LENGTH_MS) {
                    ms = withFrameNanos { (it - startedAt) / 1_000_000.0 }
                }
                opening.finish()
            }
            val blocking = ms < OpeningMotion.OPEN_AT_MS
            Canvas(
                Modifier
                    .fillMaxSize()
                    .testTag("opening")
                    .clearAndSetSemantics {}
                    .then(if (blocking) Modifier.pointerInput(Unit) { holdBackTouches() } else Modifier),
            ) {
                drawOpening(opening.map, ms, tag)
                if (!opening.firstDrawn.isCompleted) opening.firstDrawn.complete(Unit)
            }
        }
        val shown = opening.shown
        if (shown != null && openingMarkForTests()) {
            val words = if (shown == OpeningStart.Play) "Opening played" else "Opening held"
            Box(Modifier.size(2.dp).testTag("openingShown").semantics { contentDescription = words })
        }
    }
}

/** Takes every touch, so none reaches the screen underneath. */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.holdBackTouches() {
    awaitEachGesture {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
            if (event.changes.none { it.pressed }) break
        }
    }
}

/** One frame of the opening, `ms` after its first frame, over the whole screen. */
private fun DrawScope.drawOpening(map: OpeningMap?, ms: Double, tag: Painter) {
    val d = density
    val layout = OpeningLayout(size.width / d.toDouble(), size.height / d.toDouble())
    // The first screen shows through a circle centred on the tag's point.
    val radius = OpeningMotion.radius(ms, layout.maxRadius)
    if (radius > 0) {
        val p = layout.point
        val circle = Path().apply {
            addOval(Rect(Offset((p.x * d).toFloat(), (p.y * d).toFloat()), (radius * d).toFloat()))
        }
        clipPath(circle, ClipOp.Difference) { drawFrame(layout, map, ms, tag) }
    } else {
        drawFrame(layout, map, ms, tag)
    }
}

private fun DrawScope.drawFrame(layout: OpeningLayout, map: OpeningMap?, ms: Double, tag: Painter) {
    val d = density
    drawRect(Brand.kraft)
    if (map != null) {
        // The map as the brand kit draws it: moss areas edged with kraft lines 0.75 dp wide at any scale.
        val origin = layout.mapOrigin(map.county, map.scale)
        val k = (map.scale * d).toFloat()
        translate((origin.x * d).toFloat(), (origin.y * d).toFloat()) {
            scale(k, k, pivot = Offset.Zero) {
                val edge = Stroke(width = (0.75 / map.scale).toFloat(), join = StrokeJoin.Round)
                for (area in map.areas) {
                    val strength = area.strength * OpeningMotion.fill(ms - area.delayMs)
                    if (strength > 0) drawPath(area.path, Brand.moss, alpha = strength.toFloat().coerceAtMost(1f))
                    drawPath(area.path, Brand.kraft, style = edge)
                }
            }
        }
    }
    val b = layout.backing
    drawRect(
        Brand.kraft,
        topLeft = Offset((b.x * d).toFloat(), (b.y * d).toFloat()),
        size = Size((b.width * d).toFloat(), (b.height * d).toFloat()),
    )
    val t = layout.tag
    translate((t.x * d).toFloat(), (t.y * d).toFloat()) {
        with(tag) { draw(Size((t.width * d).toFloat(), (t.height * d).toFloat())) }
    }
}
