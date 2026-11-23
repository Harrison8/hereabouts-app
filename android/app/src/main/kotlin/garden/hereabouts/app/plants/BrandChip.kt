// The brand's chip, used for the plant types on the Plants tab and every choice in the filters panel: Material's
// filter chip with the brand's 4 dp corners, a hairline border, and the brand's moss fill with white words when chosen (DESIGN.md's active chip). An
// optional count follows the name in the softer colour. The chip is drawn 32 dp high but answers taps over 48 dp.
package garden.hereabouts.app.plants

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.hereabouts.app.Brand
import garden.hereabouts.app.Figtree

/**
 * `spokenLabel` replaces the label for TalkBack (the full month name for "Jan"); `spokenValue` is read after it (the
 * Filters chip's "2 chosen"). `icon` goes before the label.
 */
@Composable
fun BrandChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
    enabled: Boolean = true,
    spokenLabel: String? = null,
    spokenValue: String? = null,
    icon: (@Composable () -> Unit)? = null,
) {
    val style = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp)
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = style)
                if (count != null) {
                    Text("$count", style = style.copy(color = if (selected) Color.White else Brand.inkSoft, fontWeight = FontWeight.Normal))
                }
            }
        },
        leadingIcon = icon,
        shape = RoundedCornerShape(4.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Brand.ground,
            labelColor = Brand.ink,
            iconColor = Brand.ink,
            selectedContainerColor = Brand.moss,
            selectedLabelColor = Color.White,
            selectedLeadingIconColor = Color.White,
            disabledLabelColor = Brand.ruleStrong,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = Brand.ruleStrong,
            selectedBorderColor = Brand.moss,
            disabledBorderColor = Brand.rule,
            borderWidth = 1.dp,
            selectedBorderWidth = 1.dp,
        ),
        modifier = modifier.semantics {
            if (spokenLabel != null) contentDescription = spokenLabel
            if (spokenValue != null) stateDescription = spokenValue
        },
    )
}

/** True at the largest text sizes, where rows stack and chip rows wrap so nothing is cut off. */
@Composable
fun isLargeText(): Boolean = LocalDensity.current.fontScale >= 1.5f
