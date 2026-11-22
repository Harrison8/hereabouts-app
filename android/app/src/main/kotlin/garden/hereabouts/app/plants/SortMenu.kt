// The sort menu beside the plant count: the chosen sort in moss with a small arrow, opening the phone's own menu of
// the website's ten sorts, in the website's order and words.
package garden.hereabouts.app.plants

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.hereabouts.app.Brand
import garden.hereabouts.app.Figtree
import garden.hereabouts.app.R
import garden.hereabouts.core.SortKey

@Composable
fun SortMenu(sort: SortKey, onSort: (SortKey) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val style = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = Brand.moss)
    val arrow = with(LocalDensity.current) { 20.sp.toDp() }
    val sortWord = stringResource(R.string.sort)
    Box(modifier) {
        Row(
            // The tag goes before clearAndSetSemantics, which would otherwise wipe it.
            modifier = Modifier
                .testTag("sortMenu")
                .heightIn(min = 48.dp)
                .clickable(role = Role.DropdownList, onClickLabel = sortWord) { open = true }
                .clearAndSetSemantics {
                    contentDescription = sortWord
                    stateDescription = sort.label
                }
                .padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(sort.label, style = style)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = Brand.moss, modifier = Modifier.size(arrow))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (key in SortKey.entries) {
                val chosen = key == sort
                DropdownMenuItem(
                    text = {
                        Text(
                            key.label,
                            style = TextStyle(
                                fontFamily = Figtree,
                                fontSize = 16.sp,
                                fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (chosen) Brand.moss else Brand.ink,
                            ),
                        )
                    },
                    onClick = {
                        open = false
                        onSort(key)
                    },
                    modifier = Modifier.semantics { selected = chosen },
                )
            }
        }
    }
}
