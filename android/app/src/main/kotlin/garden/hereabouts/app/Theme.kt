// The brand's colours and fonts, as the website's design rules set them: one moss green for buttons, the selected tab
// and links, on a white ground; Young Serif for plant names and titles; Figtree for everything else. The phone's own
// parts (tab bar, top bar) keep Material's shapes, with the brand's colours in place of Material's purple defaults.
@file:OptIn(ExperimentalTextApi::class)

package garden.hereabouts.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

object Brand {
    /** The one green, taken from the logo. */
    val moss = Color(0xFF3B4A2A)
    /** The page ground. */
    val ground = Color(0xFFFFFFFF)
    /** A pale panel on the ground, such as the tab bar. */
    val surface = Color(0xFFF3F5EF)
    /** Body text. */
    val ink = Color(0xFF16211A)
    /** Secondary text, such as an unselected tab. */
    val inkSoft = Color(0xFF52605A)
    /** The words of a control that is waiting, such as the first-open button before a county is found. */
    val inkFaint = Color(0xFF67726B)
    /** Hairlines and the selected tab's pill. */
    val rule = Color(0xFFE1E6DB)
    /** Stronger lines, such as a field's outline. */
    val ruleStrong = Color(0xFFB4BEAD)
    /** Error text. */
    val error = Color(0xFFA8321F)
    /** Photo credits under a plant's name. */
    val creditGrey = Color(0xFF7C847D)
    /** The kraft paper of the no-signal line. */
    val kraft = Color(0xFFECE5D3)
    /** A chosen chip's fill. */
    val mossSoft = Color(0xFFC9D3BC)
    /** The seed-packet paper: the range map's region edges and the dot that marks the county. */
    val packet = Color(0xFFF7F2E6)

    /** The season chart's bar colours, by what the plant is doing, as the brand kit's chart draws them. */
    fun season(state: String): Color = when (state) {
        "bloom" -> Color(0xFFB86B52)
        "fruit" -> Color(0xFF8E3B3B)
        "fall" -> Color(0xFFB5602E)
        else -> Color(0xFF7D8757)
    }
}

/**
 * Young Serif, for plant names and titles. One weight only.
 */
val YoungSerif = FontFamily(Font(R.font.young_serif, FontWeight.Normal))

/**
 * Figtree, for everything else. The file is one variable font whose default weight is Light (300), so each weight
 * names its own point on the weight axis; without that, every weight would draw at Light.
 */
val Figtree = FontFamily(
    Font(
        R.font.figtree,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        R.font.figtree,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    Font(
        R.font.figtree,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600)),
    ),
)

private val colors = lightColorScheme(
    primary = Brand.moss,
    onPrimary = Color.White,
    primaryContainer = Brand.rule,
    onPrimaryContainer = Brand.moss,
    secondary = Brand.moss,
    onSecondary = Color.White,
    // The pill behind the selected tab's icon.
    secondaryContainer = Brand.rule,
    onSecondaryContainer = Brand.moss,
    tertiary = Brand.moss,
    onTertiary = Color.White,
    background = Brand.ground,
    onBackground = Brand.ink,
    surface = Brand.ground,
    onSurface = Brand.ink,
    surfaceVariant = Brand.surface,
    onSurfaceVariant = Brand.inkSoft,
    surfaceContainerLowest = Brand.ground,
    surfaceContainerLow = Brand.surface,
    // The tab bar.
    surfaceContainer = Brand.surface,
    surfaceContainerHigh = Brand.surface,
    surfaceContainerHighest = Brand.rule,
    // Material tints raised surfaces with this; white keeps them white.
    surfaceTint = Brand.ground,
    outline = Brand.ruleStrong,
    outlineVariant = Brand.rule,
    error = Brand.error,
    onError = Color.White,
)

/** Material's type scale with the brand's fonts: Young Serif for display, headline and title sizes, Figtree elsewhere. */
private val typography: Typography = Typography().run {
    fun TextStyle.display() = copy(fontFamily = YoungSerif, fontWeight = FontWeight.Normal)
    fun TextStyle.body(weight: FontWeight = FontWeight.Normal) = copy(fontFamily = Figtree, fontWeight = weight)
    Typography(
        displayLarge = displayLarge.display(),
        displayMedium = displayMedium.display(),
        displaySmall = displaySmall.display(),
        headlineLarge = headlineLarge.display(),
        headlineMedium = headlineMedium.display(),
        headlineSmall = headlineSmall.display(),
        titleLarge = titleLarge.display(),
        titleMedium = titleMedium.body(FontWeight.SemiBold),
        titleSmall = titleSmall.body(FontWeight.SemiBold),
        bodyLarge = bodyLarge.body(),
        bodyMedium = bodyMedium.body(),
        bodySmall = bodySmall.body(),
        labelLarge = labelLarge.body(FontWeight.SemiBold),
        labelMedium = labelMedium.body(FontWeight.SemiBold),
        labelSmall = labelSmall.body(FontWeight.SemiBold),
    )
}

@Composable
fun HereaboutsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
