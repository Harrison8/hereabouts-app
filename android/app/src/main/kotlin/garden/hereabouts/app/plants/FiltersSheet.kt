// The filters panel, a sheet that slides up over the Plants tab: "Filters" in Young Serif with "Clear all" on the
// right, then the website's filter groups in its words (sun, soil moisture, mature height, bloom colour, bloom month,
// yard zone, wildlife, pickup or shipping), each a row of chips, the height a slider, the bloom colours swatches. The
// moss button at the bottom counts the plants the choices leave, as they change, and closes the panel. Plant type is
// not here: the chips on the Plants tab choose it. The same groups and words as the iPhone app's panel.
//
// The choices change the list behind the panel at once, as the website's filters do; the button only closes it. At
// the largest text sizes the chip rows wrap instead of scrolling sideways, so every choice can be seen.
@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package garden.hereabouts.app.plants

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.hereabouts.app.Brand
import garden.hereabouts.app.PlainDragHandle
import garden.hereabouts.app.Figtree
import garden.hereabouts.app.R
import garden.hereabouts.app.YoungSerif
import garden.hereabouts.core.BuyMode
import garden.hereabouts.core.FilterState
import garden.hereabouts.core.Location
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private val sunChoices = listOf("full" to "Full sun (6+ hrs)", "part" to "Part sun / shade", "shade" to "Full shade")
private val moistureChoices = listOf("dry" to "Dry", "medium" to "Medium", "wet" to "Wet")
private val colorChoices = listOf("white", "yellow", "orange", "red", "pink", "purple", "blue", "green")
private val monthNames = listOf(
    "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November",
    "December",
)
private val zoneChoices = listOf(
    "front-yard" to "Front yard", "back-yard" to "Back yard", "foundation" to "Foundation planting",
    "screening" to "Screening / hedge", "sunny-bed" to "Sunny bed", "shade-border" to "Shade border",
    "woodland" to "Woodland", "rain-garden" to "Rain garden / wet", "meadow" to "Meadow / prairie",
    "lawn-replace" to "Lawn replacement", "slope" to "Slope / erosion", "trellis" to "Trellis / arbor",
    "specimen" to "Specimen / focal",
)
private val wildlifeChoices = listOf(
    "bees" to "Bees", "butterflies" to "Butterflies", "hummingbirds" to "Hummingbirds", "birds" to "Birds",
    "host" to "Host plant (caterpillars)",
)
private val buyChoices = listOf(BuyMode.Pickup to "Pick up within 75 miles", BuyMode.Ship to "Ships to you")

/** The bloom colour swatches, as the brand kit draws them. */
private fun swatch(color: String): Color = when (color) {
    "white" -> Color(0xFFF4F0E3)
    "yellow" -> Color(0xFFE8C557)
    "orange" -> Color(0xFFD68A3C)
    "red" -> Color(0xFFB13C3C)
    "pink" -> Color(0xFFD693A4)
    "purple" -> Color(0xFF8E6BA8)
    "blue" -> Color(0xFF6A8AA8)
    else -> Color(0xFF7D8757)
}

/** How many choices are made in the panel: the Filters chip's count. */
internal fun panelChoices(f: FilterState): Int =
    f.sun.size + f.moisture.size + f.wildlife.size + f.zones.size + f.bloomMonth.size + f.bloomColor.size +
        f.buy.size + (if (f.maxHeight < 100) 1 else 0)

/**
 * Clear all: every choice in this panel back to none, and the height back to any. The type chips, the sort and the
 * search stay as they are, because they are not in this panel.
 */
internal fun clearPanel(f: FilterState): FilterState = f.copy(
    sun = emptySet(), moisture = emptySet(), wildlife = emptySet(), zones = emptySet(), bloomMonth = emptySet(),
    bloomColor = emptySet(), buy = emptySet(), maxHeight = 100.0,
)

private fun <T> Set<T>.flip(value: T): Set<T> = if (value in this) this - value else this + value

/** `count` says how many plants the list would show with the given choices. */
@Composable
fun FiltersSheet(
    filter: FilterState,
    onChange: (FilterState) -> Unit,
    location: Location?,
    count: (FilterState) -> Int,
    onClose: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val large = isLargeText()
    val n = count(filter)
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheet,
        containerColor = Brand.ground,
        dragHandle = { PlainDragHandle() },
    ) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.filters),
                    style = TextStyle(fontFamily = YoungSerif, fontSize = 22.sp, lineHeight = 28.sp, color = Brand.ink),
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                TextButton(
                    onClick = { onChange(clearPanel(filter)) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("clearFilters"),
                ) {
                    Text(stringResource(R.string.clear_all), style = TextStyle(fontFamily = Figtree, fontSize = 17.sp, color = Brand.moss))
                }
            }

            Group("Sun") { Chips(sunChoices, filter.sun, large) { onChange(filter.copy(sun = filter.sun.flip(it))) } }
            Group("Soil moisture") {
                Chips(moistureChoices, filter.moisture, large) { onChange(filter.copy(moisture = filter.moisture.flip(it))) }
            }
            val height = if (filter.maxHeight >= 100) "any" else "${filter.maxHeight.toInt()} ft"
            val heightSpoken = stringResource(R.string.max_height)
            Group("Mature height", value = height) {
                Slider(
                    value = filter.maxHeight.toFloat(),
                    onValueChange = { onChange(filter.copy(maxHeight = it.roundToInt().toDouble())) },
                    valueRange = 1f..100f,
                    steps = 98,
                    colors = SliderDefaults.colors(
                        thumbColor = Brand.moss,
                        activeTrackColor = Brand.moss,
                        inactiveTrackColor = Brand.rule,
                        activeTickColor = Color.Transparent,
                        inactiveTickColor = Color.Transparent,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 16.dp)
                        .semantics {
                            contentDescription = heightSpoken
                            stateDescription = height
                        },
                )
            }
            Group("Bloom color") { Swatches(filter.bloomColor, large) { onChange(filter.copy(bloomColor = filter.bloomColor.flip(it))) } }
            Group("Bloom month") {
                ChipRow(large) {
                    for (m in 1..12) {
                        BrandChip(
                            label = monthNames[m - 1].take(3),
                            spokenLabel = monthNames[m - 1],
                            selected = m in filter.bloomMonth,
                            onClick = { onChange(filter.copy(bloomMonth = filter.bloomMonth.flip(m))) },
                        )
                    }
                }
            }
            Group("Yard zone / use") { Chips(zoneChoices, filter.zones, large) { onChange(filter.copy(zones = filter.zones.flip(it))) } }
            Group("Wildlife value") {
                Chips(wildlifeChoices, filter.wildlife, large) { onChange(filter.copy(wildlife = filter.wildlife.flip(it))) }
            }
            Group("Pickup / ship") {
                ChipRow(large) {
                    for ((mode, label) in buyChoices) {
                        BrandChip(
                            label = label,
                            selected = mode in filter.buy,
                            enabled = location != null,
                            onClick = { onChange(filter.copy(buy = filter.buy.flip(mode))) },
                        )
                    }
                }
                if (location == null) {
                    Text(
                        stringResource(R.string.set_location_first),
                        style = TextStyle(fontFamily = Figtree, fontSize = 13.sp, color = Brand.inkSoft),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
        HorizontalDivider(color = Brand.rule)
        Button(
            onClick = { scope.launch { sheet.hide() }.invokeOnCompletion { onClose() } },
            shape = RoundedCornerShape(4.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Brand.moss, contentColor = Color.White),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)
                .heightIn(min = 50.dp)
                .testTag("showPlants"),
        ) {
            Text(
                "Show $n ${if (n == 1) "plant" else "plants"}",
                style = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A group: its name in semibold (and, for the height, the chosen value on the right), then its choices. */
@Composable
private fun Group(title: String, value: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Brand.ink),
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (value != null) {
                Text(
                    value,
                    style = TextStyle(fontFamily = Figtree, fontSize = 15.sp, color = Brand.inkSoft),
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
        content()
    }
}

/** One chip per choice, each turning its value on or off. */
@Composable
private fun Chips(options: List<Pair<String, String>>, chosen: Set<String>, large: Boolean, onFlip: (String) -> Unit) {
    ChipRow(large) {
        for ((value, label) in options) {
            BrandChip(label = label, selected = value in chosen, onClick = { onFlip(value) })
        }
    }
}

/** A row of chips that scrolls sideways, or wraps at the largest text sizes. */
@Composable
fun ChipRow(large: Boolean, content: @Composable RowScope.() -> Unit) {
    if (large) {
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    } else {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}

/**
 * The bloom colours as swatches with their names under them. The chosen ones get a moss ring. Eight 48 dp swatches do
 * not fit across most phones, so they scroll sideways like the other rows here, and wrap at the largest text sizes.
 */
@Composable
private fun Swatches(chosen: Set<String>, large: Boolean, onFlip: (String) -> Unit) {
    ChipRow(large) {
        for (color in colorChoices) {
            val isOn = color in chosen
            val name = color.replaceFirstChar { it.uppercase() }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .widthIn(min = 48.dp)
                    .heightIn(min = 48.dp)
                    .selectable(selected = isOn, role = Role.Checkbox, onClick = { onFlip(color) })
                    .clearAndSetSemantics {
                        contentDescription = name
                    },
            ) {
                Box(
                    Modifier
                        .border(2.dp, if (isOn) Brand.moss else Color.Transparent, RoundedCornerShape(4.dp))
                        .padding(3.dp)
                        .size(width = 36.dp, height = 24.dp)
                        .background(swatch(color), RoundedCornerShape(2.dp))
                        .border(1.dp, Brand.ruleStrong, RoundedCornerShape(2.dp)),
                )
                Text(
                    name,
                    style = TextStyle(
                        fontFamily = Figtree,
                        fontSize = 12.sp,
                        fontWeight = if (isOn) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isOn) Brand.moss else Brand.inkSoft,
                    ),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
