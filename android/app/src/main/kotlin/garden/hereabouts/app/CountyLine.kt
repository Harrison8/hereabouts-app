// The county under the Plants and Garden titles, with the map pin the approved pictures draw before it. Each tab keeps
// its own look for the words: on Plants the county is a moss link that changes the location, on Garden plain ink text.
// The pin takes the words' colour and size. It only decorates, so TalkBack reads the words alone.
package garden.hereabouts.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The map pin, then `words`. `size` is the words' text size, so the pin grows with the phone's text size. */
@Composable
fun CountyLine(
    color: Color,
    modifier: Modifier = Modifier,
    size: TextUnit = 15.sp,
    words: @Composable () -> Unit,
) {
    val pin = with(LocalDensity.current) { size.toDp() }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Place,
            contentDescription = null,
            tint = color,
            modifier = Modifier
                .size(pin)
                .testTag("county-pin")
                .clearAndSetSemantics {},
        )
        words()
    }
}
