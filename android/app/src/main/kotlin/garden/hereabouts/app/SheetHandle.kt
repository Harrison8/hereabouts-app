// The bar at the top of a bottom sheet. Material's own handle shows a "Drag handle" tooltip on some phones and is
// read out by TalkBack; this one is the same size and colour, shows nothing when pressed and is skipped by TalkBack
// (a sheet is closed by swiping it down or with Back).
package garden.hereabouts.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

@Composable
fun PlainDragHandle() {
    Box(
        Modifier
            .padding(vertical = 22.dp)
            .size(width = 32.dp, height = 4.dp)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(50))
            .clearAndSetSemantics {},
    )
}
