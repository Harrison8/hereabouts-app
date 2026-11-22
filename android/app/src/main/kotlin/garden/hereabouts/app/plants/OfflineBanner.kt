// The line the Plants tab shows when the phone has no signal: a cloud with a line through it, then the words, on kraft
// paper in the ink colour with 4 dp corners, between the search field and the chips. The cloud only decorates, so
// TalkBack reads the words alone. The same words as the iPhone app's.
package garden.hereabouts.app.plants

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.hereabouts.app.Brand
import garden.hereabouts.app.Figtree

@Composable
fun OfflineBanner(modifier: Modifier = Modifier) {
    val text = TextStyle(fontFamily = Figtree, fontSize = 14.sp, lineHeight = 20.sp, color = Brand.ink)
    val icon = with(LocalDensity.current) { 18.sp.toDp() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(Brand.kraft, RoundedCornerShape(4.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.CloudOff,
            contentDescription = null,
            tint = Brand.ink,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(icon)
                .testTag("no-signal-icon")
                .clearAndSetSemantics {},
        )
        Text(
            "No signal. Every plant and photo still works. Seller links open once you have signal again.",
            style = text,
            modifier = Modifier.testTag("offlineBanner"),
        )
    }
}
