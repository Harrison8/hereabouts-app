// The plant page's photos, as the approved picture draws them: the full width of the screen, one photo per season the
// website has (bloom, fruit, green, fall, winter, dormant, in that order), swiped sideways. Under the photo: its credit,
// the dots, and the season with "1 of 3". A plant with no photo shows the brand kit's "No photograph yet" plate in the
// same 4:3 frame, with no credit and no counter. The same as the iPhone app's pager.
package garden.hereabouts.app.plant

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.hereabouts.app.Brand
import garden.hereabouts.app.Figtree
import garden.hereabouts.app.R
import garden.hereabouts.app.plants.isLargeText
import garden.hereabouts.app.plants.readPhoto
import garden.hereabouts.app.services.PhotoCache
import garden.hereabouts.core.Labels
import garden.hereabouts.core.PhasePhoto
import garden.hereabouts.core.Plant
import kotlin.math.max

/** The word for each season under the photo, as the approved picture writes them. */
private val seasonWords = mapOf(
    "bloom" to "In bloom",
    "fruit" to "In fruit",
    "green" to "In leaf",
    "fall" to "In fall",
    "winter" to "In winter",
    "dormant" to "Dormant",
)

@Composable
fun PhotoPager(plant: Plant, photos: PhotoCache?) {
    val shots = remember(plant) { Labels.photos(plant) }
    val noPhotoSpoken = stringResource(R.string.no_photo_spoken, plant.common)
    if (shots.isEmpty()) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .background(Brand.surface)
                .testTag("noPhoto")
                .clearAndSetSemantics { contentDescription = noPhotoSpoken },
            contentAlignment = Alignment.Center,
        ) {
            NoPhotoPlate(15.sp)
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp).background(Brand.ruleStrong))
        }
        return
    }
    val pager = rememberPagerState { shots.size }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalPager(
            state = pager,
            // By place, not by photo: two seasons of a downloaded plant may share one photo.
            key = { it },
            modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f).testTag("photoPager"),
        ) { i ->
            PagerPhoto(plant, shots[i], photos)
        }
        Caption(shots, pager.currentPage.coerceIn(0, shots.size - 1), Modifier.padding(horizontal = 16.dp))
    }
}

/**
 * The credit at left, the dots in the middle, the season and count at right. At the largest text sizes the count sits
 * above the credit, so neither is cut.
 */
@Composable
private fun Caption(shots: List<PhasePhoto>, shown: Int, modifier: Modifier) {
    val counter = @Composable { m: Modifier ->
        Text(
            "${seasonWords[shots[shown].season].orEmpty()} · ${shown + 1} of ${shots.size}",
            style = TextStyle(fontFamily = Figtree, fontSize = 15.sp, lineHeight = 20.sp, color = Brand.inkSoft),
            maxLines = 1,
            softWrap = false,
            modifier = m.testTag("photoCounter"),
        )
    }
    val credit = @Composable { m: Modifier ->
        Text(
            shots[shown].photo.credit,
            style = TextStyle(fontFamily = Figtree, fontSize = 12.sp, lineHeight = 16.sp, color = Brand.creditGrey),
            modifier = m,
        )
    }
    if (isLargeText()) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            counter(Modifier)
            credit(Modifier)
        }
    } else {
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            credit(Modifier.weight(1f))
            Dots(shots.size, shown)
            // The count stays on one line; the credit takes what room is left and wraps.
            counter(Modifier)
        }
    }
}

/** One dot per photo, the shown one in ink. The counter already says the same, so the dots are not read aloud. */
@Composable
private fun Dots(count: Int, current: Int) {
    Row(
        Modifier.alpha(if (count > 1) 1f else 0f).clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(count) { i ->
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (i == current) Brand.ink else Brand.ruleStrong))
        }
    }
}

/**
 * One photo of the pager, read at the size it is shown. It comes from the photo store like the list's photos; until it
 * arrives, or if it cannot be had, the frame holds the "No photograph yet" plate.
 */
@Composable
private fun PagerPhoto(plant: Plant, shot: PhasePhoto, photos: PhotoCache?) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Brand.surface)
            .clearAndSetSemantics {
                contentDescription = shot.photo.alt.ifEmpty { "${plant.common}, ${shot.season}" }
                role = Role.Image
            },
        contentAlignment = Alignment.Center,
    ) {
        val pixels = with(LocalDensity.current) { max(maxWidth.roundToPx(), maxHeight.roundToPx()) }
        val shown by produceState<Shown>(Shown.Waiting, shot.photo.url, pixels, photos) {
            value = Shown.Waiting
            val bitmap = photos?.let { readPhoto(it, shot.photo, pixels) }
            value = if (bitmap != null) Shown.Photo(bitmap.asImageBitmap()) else Shown.Missing
        }
        when (val s = shown) {
            is Shown.Photo -> Image(
                bitmap = s.bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Shown.Missing -> NoPhotoPlate(15.sp)
            Shown.Waiting -> {}
        }
    }
}

private sealed interface Shown {
    data object Waiting : Shown
    data object Missing : Shown
    class Photo(val bitmap: ImageBitmap) : Shown
}

/** The brand kit's "No photograph yet" plate. */
@Composable
internal fun NoPhotoPlate(size: TextUnit) {
    Text(
        stringResource(R.string.no_photo),
        style = TextStyle(fontFamily = Figtree, fontSize = size, color = Brand.inkSoft, textAlign = TextAlign.Center),
        modifier = Modifier.padding(4.dp),
    )
}
