// The website's "Also matching" lines under the results, shown only while something is typed in the search field:
// plants the search matches that are not native to the county, and plants it matches that the filters hide. Each
// names the first 12 plants and says how many more there are.
package garden.hereabouts.app.plants

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.hereabouts.app.Brand
import garden.hereabouts.app.Figtree
import garden.hereabouts.core.HiddenMatches
import garden.hereabouts.core.Location
import garden.hereabouts.core.Plant

@Composable
fun AlsoMatching(hidden: HiddenMatches, location: Location?, modifier: Modifier = Modifier) {
    if (hidden.county.isEmpty() && hidden.filters.isEmpty()) return
    val style = TextStyle(fontFamily = Figtree, fontSize = 15.sp, lineHeight = 21.sp, color = Brand.inkSoft)
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (location != null && hidden.county.isNotEmpty()) {
            Text(
                alsoText("Also matching, but not native to ${location.countyName}, ${location.stateName}:", hidden.county),
                style = style,
                modifier = Modifier.padding(vertical = 4.dp).testTag("alsoCounty"),
            )
        }
        if (hidden.filters.isNotEmpty()) {
            Text(
                alsoText("Also matching, but hidden by your filters:", hidden.filters),
                style = style,
                modifier = Modifier.padding(vertical = 4.dp).testTag("alsoFilters"),
            )
        }
    }
}

/** "label name, name and 3 more": the first 12 names, as the website lists them. */
internal fun alsoText(label: String, plants: List<Plant>): String {
    val names = plants.take(12).joinToString(", ") { it.common }
    val more = if (plants.size > 12) " and ${plants.size - 12} more" else ""
    return "$label $names$more"
}
