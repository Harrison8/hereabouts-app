// "Where it grows wild": the brand kit's range map, drawn on a Canvas from the shared RangeShapes, as the website's
// renderRangeMap draws it. Every EPA natural region of the lower 48 states in kraft, the ones where the plant grows
// wild in moss, edged in packet paper; the visitor's county marked with a dot when it falls on the map. The dot is
// placed from the position the app already has. Nothing is sent anywhere. The same as the iPhone app's map.
package garden.hereabouts.app.plant

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import garden.hereabouts.app.Brand
import garden.hereabouts.core.Location
import garden.hereabouts.core.Plant
import garden.hereabouts.core.RangeShapes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The map's outlines, read once from the app's files and turned into paths, so scrolling never reads them again.
 * Null only if the shipped map file is missing or broken; the page then leaves the map out.
 */
internal class MapShapes(val shapes: RangeShapes, val paths: List<Pair<String, Path>>)

internal object MapShapesStore {
    @Volatile private var loaded: MapShapes? = null
    @Volatile private var failed = false

    suspend fun get(context: Context): MapShapes? {
        loaded?.let { return it }
        if (failed) return null
        return withContext(Dispatchers.Default) {
            loaded ?: try {
                val text = context.assets.open("tables/range-shapes.json").use { it.readBytes().decodeToString() }
                val shapes = RangeShapes.decode(text)
                val paths = shapes.regions.keys.sorted().map { code ->
                    val path = Path()
                    for (shape in shapes.paths(code)) {
                        val first = shape.firstOrNull() ?: continue
                        path.moveTo(first.first.toFloat(), first.second.toFloat())
                        for (p in shape.drop(1)) path.lineTo(p.first.toFloat(), p.second.toFloat())
                        path.close()
                    }
                    code to path
                }
                MapShapes(shapes, paths).also { loaded = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                null
            }
        }
    }

    /** Forgets the outlines read, as a new process would, so a test can time a launch that reads them again. */
    fun forget() {
        loaded = null
        failed = false
    }
}

/** `native` is the ecoregion codes where the plant grows wild. */
@Composable
fun RangeMap(plant: Plant, native: List<String>, location: Location?, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val map by produceState<MapShapes?>(null) { value = MapShapesStore.get(context) }
    val m = map ?: return
    val nativeSet = remember(native) { native.toSet() }
    val point = remember(m, location) { countyPoint(location, m.shapes) }
    val label = rangeMapLabel(plant, location, marked = point != null)
    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio((m.shapes.width / m.shapes.height).toFloat())
            .testTag("rangeMap")
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Image
            },
    ) {
        val k = (size.width / m.shapes.width).toFloat()
        // The website's lines keep their width at any map size, so the widths here are in dp, divided by the scale.
        scale(k, k, pivot = Offset.Zero) {
            for ((code, path) in m.paths) drawPath(path, if (code in nativeSet) Brand.moss else Brand.kraft)
            val edge = Stroke(width = 0.75.dp.toPx() / k, join = StrokeJoin.Round)
            for ((_, path) in m.paths) drawPath(path, Brand.packet, style = edge)
            if (point != null) {
                drawCircle(Brand.packet, radius = 15f, center = point)
                drawCircle(Brand.ink, radius = 15f, center = point, style = Stroke(width = 2.dp.toPx() / k))
            }
        }
    }
}

/** The visitor's county on the map, or null when there is no position or it falls off the lower 48 states. */
internal fun countyPoint(location: Location?, map: RangeShapes): Offset? {
    val lat = location?.lat ?: return null
    val lon = location.lon ?: return null
    if (!lat.isFinite() || !lon.isFinite()) return null
    val (x, y) = map.point(lon, lat)
    if (x < 0 || x > map.width || y < 0 || y > map.height) return null
    return Offset(x.toFloat(), y.toFloat())
}

/** The website's words for the map. */
internal fun rangeMapLabel(plant: Plant, location: Location?, marked: Boolean): String {
    var text = "The shaded parts of this map of the lower 48 states are where ${plant.common} grows wild."
    if (marked && location != null) text += " ${location.countyName}, ${location.stateName} is marked with a dot."
    return text
}
