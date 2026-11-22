// The plant page's facts, worded by the shared growingFacts as the website words them. FactGrid: the four in the ruled
// box (height, width, sun, soil), two to a row as the approved picture draws them, one to a row at the largest text
// sizes. FactList: the rest (flowers, in fall, growth, feeds), a label at left and its value at right, the label above
// its value at the largest text sizes. A range in a value reads with "to" (RangeText): "6 to 12 ft", "Mar to Apr".
// Each fact is read aloud as one item, its label then its value.
package garden.hereabouts.app.plant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.hereabouts.app.Brand
import garden.hereabouts.app.Figtree
import garden.hereabouts.app.plants.isLargeText
import garden.hereabouts.core.RangeText

private val LabelStyle = TextStyle(fontFamily = Figtree, fontSize = 13.sp, lineHeight = 18.sp, color = Brand.inkSoft)
private val ValueStyle =
    TextStyle(fontFamily = Figtree, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, color = Brand.ink)

/** The four quick facts in the ruled box. Each fact is [label, value]. */
@Composable
fun FactGrid(facts: List<List<String>>, modifier: Modifier = Modifier) {
    val columns = if (isLargeText()) 1 else 2
    val shape = RoundedCornerShape(4.dp)
    Column(modifier.fillMaxWidth().clip(shape).border(1.dp, Brand.ruleStrong, shape)) {
        facts.chunked(columns).forEachIndexed { r, row ->
            if (r > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Brand.ruleStrong))
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                for (c in 0 until columns) {
                    if (c > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(Brand.ruleStrong))
                    val fact = row.getOrNull(c)
                    if (fact != null) {
                        Cell(fact, Modifier.weight(1f))
                    } else {
                        Box(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun Cell(fact: List<String>, modifier: Modifier) {
    val label = fact.firstOrNull().orEmpty()
    Column(
        modifier
            .fillMaxHeight()
            .padding(12.dp)
            .testTag("fact-$label")
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = LabelStyle)
        Text(RangeText.display(fact.getOrNull(1).orEmpty()), style = ValueStyle)
    }
}

/** The facts after the chart, one to a line with a rule between. Each fact is [label, value]. */
@Composable
fun FactList(facts: List<List<String>>, modifier: Modifier = Modifier) {
    val large = isLargeText()
    // The label column grows with the text size, as the iPhone's does.
    val labelWidth = with(LocalDensity.current) { 76.sp.toDp() }
    Column(modifier.fillMaxWidth()) {
        facts.forEachIndexed { i, fact ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Brand.rule))
            val label = fact.firstOrNull().orEmpty()
            val value = RangeText.display(fact.getOrNull(1).orEmpty())
            val item = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("fact-$label").semantics(mergeDescendants = true) {}
            if (large) {
                Column(item, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label, style = LabelStyle)
                    Text(value, style = ValueStyle)
                }
            } else {
                Row(item, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, style = LabelStyle, modifier = Modifier.width(labelWidth).alignByBaseline())
                    Text(value, style = ValueStyle, modifier = Modifier.weight(1f).alignByBaseline())
                }
            }
        }
    }
}
