// Colour pairs that must stay readable (WCAG contrast ratio, 4.5 to 1 for ordinary text).
package garden.hereabouts.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class ContrastTest {
    private fun ratio(a: Color, b: Color): Double {
        val x = a.luminance().toDouble()
        val y = b.luminance().toDouble()
        return (max(x, y) + 0.05) / (min(x, y) + 0.05)
    }

    @Test
    fun theWaitingButtonWordsReadOnWhite() {
        val r = ratio(Brand.inkFaint, Brand.ground)
        assertTrue("waiting button text is $r to 1 on white, needs 4.5", r >= 4.5)
    }

    @Test
    fun theChosenChipWordsReadOnMoss() {
        val r = ratio(Color.White, Brand.moss)
        assertTrue("chosen chip text is $r to 1 on moss, needs 4.5", r >= 4.5)
    }
}
