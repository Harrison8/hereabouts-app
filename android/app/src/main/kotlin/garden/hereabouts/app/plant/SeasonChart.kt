// "Through the year": the brand kit's season chart, drawn on a Canvas from the shared seasonRows, as the website's
// renderSeason draws it. Twelve month letters across the top; one row per thing the plant does (in leaf, bloom, fruit,
// fall colour), each a dotted track with a coloured bar for the months it does it; this month ringed from the letters
// to the last row. Read aloud as one sentence, the website's own. The same as the iPhone app's chart.
package garden.hereabouts.app.plant

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.hereabouts.app.Brand
import garden.hereabouts.app.Figtree
import garden.hereabouts.core.Labels
import garden.hereabouts.core.Plant
import garden.hereabouts.core.SeasonRow
import java.time.LocalDate

private val letters = listOf("J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D")
private val monthNames = listOf(
    "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November",
    "December",
)

/**
 * The chart keeps twelve columns side by side, so past this text size the letters and labels stop growing; the spoken
 * sentence says the same months in words. The iPhone stops at its first accessibility size, about the same.
 */
private const val LARGEST_SCALE = 1.3f

/** The website's spoken label: "In leaf: March to October; Bloom: March to April". The heading above says "Through the year". */
internal fun spokenSeasons(rows: List<SeasonRow>): String {
    val said = rows.map { row ->
        val runs = row.runs.mapNotNull { run ->
            val a = run.firstOrNull() ?: return@mapNotNull null
            val b = run.lastOrNull() ?: return@mapNotNull null
            if (a !in monthNames.indices || b !in monthNames.indices) return@mapNotNull null
            if (a == b) monthNames[a] else "${monthNames[a]} to ${monthNames[b]}"
        }
        "${row.label}: ${runs.joinToString(" and ")}"
    }
    return said.joinToString("; ")
}

/** `now` is this month, 0 for January; the website rings the visitor's current month the same way. */
@Composable
fun SeasonChart(plant: Plant, modifier: Modifier = Modifier, now: Int = LocalDate.now().monthValue - 1) {
    val rows = remember(plant) { Labels.seasonRows(plant) }
    val outer = LocalDensity.current
    val capped = Density(outer.density, minOf(outer.fontScale, LARGEST_SCALE))
    CompositionLocalProvider(LocalDensity provides capped) {
        val rowHeight = with(capped) { 20.sp.toDp() }
        val labelWidth = with(capped) { 76.sp.toDp() }
        val labelStyle = TextStyle(fontFamily = Figtree, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Brand.inkSoft)
        val measurer = rememberTextMeasurer()
        Row(
            modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp)
                .testTag("seasonChart")
                .clearAndSetSemantics { contentDescription = spokenSeasons(rows) },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.width(labelWidth)) {
                Spacer(Modifier.height(rowHeight))
                for (row in rows) {
                    Text(
                        row.label,
                        style = labelStyle,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.height(rowHeight).wrapContentHeight(Alignment.CenterVertically),
                    )
                }
            }
            Canvas(Modifier.weight(1f).height(rowHeight * (rows.size + 1))) {
                val col = size.width / 12
                val rowPx = rowHeight.toPx()
                if (now in 0 until 12) {
                    // 2 dp corners, the website's small corner for the season bars and the month ring.
                    val line = 2.dp.toPx()
                    drawRoundRect(
                        color = Brand.ink,
                        topLeft = Offset(now * col + line / 2, -3.dp.toPx() + line / 2),
                        size = Size(col - line, rowPx * (rows.size + 1) + 6.dp.toPx() - line),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                        style = Stroke(width = line),
                    )
                }
                for (m in 0 until 12) {
                    val style = labelStyle.copy(
                        fontWeight = if (m == now) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (m == now) Brand.ink else Brand.inkSoft,
                    )
                    val text = measurer.measure(letters[m], style)
                    drawText(
                        text,
                        topLeft = Offset(m * col + (col - text.size.width) / 2, (rowPx - text.size.height) / 2),
                    )
                }
                val dots = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 3.dp.toPx()))
                rows.forEachIndexed { i, row ->
                    val mid = (i + 1) * rowPx + rowPx / 2
                    drawLine(
                        color = Brand.ruleStrong,
                        start = Offset(col / 2, mid),
                        end = Offset(size.width - col / 2, mid),
                        strokeWidth = 1.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = dots,
                    )
                    for (run in row.runs) {
                        val a = run.firstOrNull() ?: continue
                        val b = run.lastOrNull() ?: continue
                        if (b < a) continue
                        drawRoundRect(
                            color = Brand.season(row.state),
                            topLeft = Offset(a * col + 2.dp.toPx(), mid - 5.dp.toPx()),
                            size = Size((b - a + 1) * col - 4.dp.toPx(), 10.dp.toPx()),
                            cornerRadius = CornerRadius(2.dp.toPx()),
                        )
                    }
                }
            }
        }
    }
}
