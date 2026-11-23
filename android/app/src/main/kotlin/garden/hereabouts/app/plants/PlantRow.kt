// One plant in the Plants list, as the approved picture draws it: the photo in its frame, the name in Young Serif, the
// Latin name, "Type · height", the photo credit, and the bookmark on the right. Tapping the row opens the plant; the
// bookmark only saves or unsaves it. At the largest text sizes the photo sits above the words, so nothing is cut.
package garden.hereabouts.app.plants

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.hereabouts.app.Brand
import garden.hereabouts.app.Figtree
import garden.hereabouts.app.R
import garden.hereabouts.app.YoungSerif
import garden.hereabouts.app.services.PhotoCache
import garden.hereabouts.core.Labels
import garden.hereabouts.core.Photo
import garden.hereabouts.core.Plant
import garden.hereabouts.core.RangeText
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PlantRow(
    plant: Plant,
    isSaved: Boolean,
    photos: PhotoCache?,
    largeText: Boolean,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val meta = rowMeta(plant)
    // The part of the row that opens the plant: everything but the bookmark. TalkBack reads it as one item.
    val opens = Modifier
        .testTag("plant-${plant.id}")
        .clickable(role = Role.Button, onClick = onOpen)
        .clearAndSetSemantics { contentDescription = "${plant.common}, ${plant.sci}, $meta" }
    if (largeText) {
        Box(modifier.fillMaxWidth()) {
            Column(
                opens.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlantThumbnail(plant, photos, side = 120.dp)
                Words(plant, meta)
            }
            SaveButton(plant, isSaved, onSave, Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 4.dp))
        }
    } else {
        Row(modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.Top) {
            Row(
                opens.weight(1f).padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PlantThumbnail(plant, photos, side = 84.dp)
                Words(plant, meta)
            }
            SaveButton(plant, isSaved, onSave, Modifier.padding(top = 4.dp))
        }
    }
}

/** "Perennial · 2 to 3 ft", or the type alone when the plant has no heights. */
internal fun rowMeta(plant: Plant): String =
    listOf(Labels.category(plant.category), RangeText.display(Labels.ftRange(plant.height)))
        .filter { it.isNotEmpty() }
        .joinToString(" · ")

@Composable
private fun Words(plant: Plant, meta: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(plant.common, style = TextStyle(fontFamily = YoungSerif, fontSize = 20.sp, lineHeight = 25.sp, color = Brand.ink))
        Text(
            plant.sci,
            style = TextStyle(fontFamily = Figtree, fontStyle = FontStyle.Italic, fontSize = 15.sp, lineHeight = 20.sp, color = Brand.inkSoft),
        )
        Text(meta, style = TextStyle(fontFamily = Figtree, fontSize = 14.sp, lineHeight = 19.sp, color = Brand.inkSoft))
        val credit = Labels.mainPhoto(plant)?.photo?.credit.orEmpty()
        if (credit.isNotEmpty()) {
            Text(
                Labels.creditText(credit),
                style = TextStyle(fontFamily = Figtree, fontSize = 10.sp, lineHeight = 14.sp, color = Brand.creditGrey),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** The bookmark: filled moss when saved, an outline when not. 48 dp square, so it is easy to hit. */
@Composable
private fun SaveButton(plant: Plant, isSaved: Boolean, onSave: () -> Unit, modifier: Modifier = Modifier) {
    val savedState = stringResource(if (isSaved) R.string.saved else R.string.not_saved)
    IconToggleButton(
        checked = isSaved,
        onCheckedChange = { onSave() },
        modifier = modifier
            .size(48.dp)
            .semantics { stateDescription = savedState }
            .testTag("save-${plant.id}"),
    ) {
        Icon(
            imageVector = if (isSaved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
            contentDescription = stringResource(R.string.save_plant, plant.common),
            tint = if (isSaved) Brand.moss else Brand.inkSoft,
        )
    }
}

/**
 * The plant's main photo in the brand's frame: a thin darker rule and 4 dp corners on the pale ground. A photo not on
 * the phone yet is fetched through the photo store; until it arrives, or if it cannot be had, the frame holds the
 * website's "No photograph yet" plate. The photo is read off the main thread, at the size it is shown.
 */
@Composable
fun PlantThumbnail(plant: Plant, photos: PhotoCache?, side: Dp) {
    val photo = Labels.mainPhoto(plant)?.photo
    val pixels = with(LocalDensity.current) { side.roundToPx() }
    val image by produceState<Loaded>(Loaded.Waiting, photo?.url, pixels, photos) {
        value = Loaded.Waiting
        val bitmap = if (photo == null || photos == null) null else readPhoto(photos, photo, pixels)
        value = if (bitmap != null) Loaded.Photo(bitmap.asImageBitmap()) else Loaded.Missing
    }
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .size(side)
            .clip(shape)
            .background(Brand.surface)
            .border(1.dp, Brand.ruleStrong, shape),
        contentAlignment = Alignment.Center,
    ) {
        when (val loaded = image) {
            is Loaded.Photo -> Image(
                bitmap = loaded.bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(side),
            )
            Loaded.Missing -> Text(
                stringResource(R.string.no_photo),
                style = TextStyle(fontFamily = Figtree, fontSize = 12.sp, color = Brand.inkSoft, textAlign = TextAlign.Center),
                modifier = Modifier.padding(4.dp),
            )
            Loaded.Waiting -> {}
        }
    }
}

private sealed interface Loaded {
    data object Waiting : Loaded
    data object Missing : Loaded
    class Photo(val bitmap: ImageBitmap) : Loaded
}

/**
 * A photo read from the phone, off the main thread, and decoded at about `pixels` across; null when it cannot be had.
 * That covers a photo not on the phone and not downloadable now, and also a file that cannot be read or decoded, or
 * is too big to decode: the frame then shows "No photograph yet" instead of the app closing. Leaving the screen still
 * stops the work.
 */
internal suspend fun readPhoto(photos: PhotoCache, photo: Photo, pixels: Int): Bitmap? = withContext(Dispatchers.IO) {
    if (!photos.load(photo)) return@withContext null
    try {
        photos.open(photo)?.use { it.readBytes() }?.let { decode(it, pixels) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        null
    } catch (e: RuntimeException) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}

/** The photo decoded at about the size it is shown, not at full size, so a long list stays light on memory. */
private fun decode(bytes: ByteArray, pixels: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= pixels && bounds.outHeight / (sample * 2) >= pixels) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}
