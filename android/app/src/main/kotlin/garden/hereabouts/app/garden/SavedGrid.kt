// The saved plants on the Garden tab: one row per plant, in the order they were saved, with its photo, name, Latin
// name, how many of this week's jobs name it, and the photo credit. Tapping a row opens the plant's page. Swiping a
// row to the left shows a red button with a bin behind it, as the iPhone app does; tapping the button, or swiping on
// past half the row, unsaves the plant. TalkBack offers the same as the action "Remove". A plant that is not native to
// the county chosen now stays here until it is removed.
//
// The swipe is drawn here rather than with Material's SwipeToDismissBox: that one either removes or does nothing, so it
// cannot rest open with the button showing, and it moves its row for a swipe the phone calls off.
package garden.hereabouts.app.garden

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import garden.hereabouts.app.Brand
import garden.hereabouts.app.plants.PlantThumbnail
import garden.hereabouts.app.plants.isLargeText
import garden.hereabouts.app.services.PhotoCache
import garden.hereabouts.core.Labels
import garden.hereabouts.core.Plant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The saved plants. `jobCount` says how many of this week's jobs name a plant. `swipeable` is false while a plant page
 * is open over the list.
 */
@Composable
fun SavedGrid(
    plants: List<Plant>,
    photos: PhotoCache?,
    jobCount: (String) -> Int,
    open: (String) -> Unit,
    remove: (String) -> Unit,
    swipeable: Boolean,
    modifier: Modifier = Modifier,
) {
    // The plant whose row is swiped open, if any. Opening one row closes the others.
    var openId by remember { mutableStateOf<String?>(null) }
    val large = isLargeText()
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
            Text("Saved plants", style = TitleStyle, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.weight(1f).widthIn(min = 12.dp))
            Text(
                "${plants.size}",
                style = BodyStyle.copy(color = Brand.inkSoft),
                modifier = Modifier
                    .testTag("savedCount")
                    .clearAndSetSemantics { contentDescription = if (plants.size == 1) "1 plant" else "${plants.size} plants" },
            )
        }
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .clip(Corner)
                .background(Color.White)
                .border(1.dp, Brand.rule, Corner),
        ) {
            plants.forEachIndexed { i, plant ->
                key(plant.id) {
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Brand.rule))
                    SwipeToRemove(
                        id = plant.id,
                        openId = openId,
                        setOpen = { openId = it },
                        remove = { remove(plant.id) },
                        swipeable = swipeable,
                    ) {
                        PlantRow(
                            plant,
                            photos,
                            jobCount(plant.id),
                            large,
                            // With a row swiped open, a tap only closes it.
                            onTap = { if (openId != null) openId = null else open(plant.id) },
                            onRemove = { remove(plant.id) },
                        )
                    }
                }
            }
        }
    }
}

private fun jobsLine(count: Int): String = when (count) {
    0 -> "Nothing to do this week"
    1 -> "1 job this week"
    else -> "$count jobs this week"
}

@Composable
private fun PlantRow(plant: Plant, photos: PhotoCache?, jobs: Int, large: Boolean, onTap: () -> Unit, onRemove: () -> Unit) {
    val line = jobsLine(jobs)
    val words = @Composable { m: Modifier ->
        Column(m, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(plant.common, style = TitleStyle.copy(fontSize = 20.sp, lineHeight = 26.sp))
            Text(plant.sci, style = SmallStyle.copy(fontStyle = FontStyle.Italic))
            Text(
                line,
                style = SmallStyle.copy(color = if (jobs == 0) Brand.inkSoft else Brand.ink, fontWeight = FontWeight.Medium),
                modifier = Modifier.padding(top = 2.dp),
            )
            val credit = Labels.mainPhoto(plant)?.photo?.credit
            if (!credit.isNullOrEmpty()) Text(credit, style = CreditStyle, modifier = Modifier.padding(top = 2.dp))
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .testTag("saved-${plant.id}")
            .clickable(role = Role.Button, onClick = onTap)
            .clearAndSetSemantics {
                contentDescription = "${plant.common}, ${plant.sci}, $line"
                role = Role.Button
                customActions = listOf(CustomAccessibilityAction("Remove") { onRemove(); true })
            }
            .padding(16.dp, 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (large) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PlantThumbnail(plant, photos, 120.dp)
                words(Modifier)
            }
        } else {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PlantThumbnail(plant, photos, 64.dp)
                words(Modifier.weight(1f))
            }
        }
        Chevron()
    }
}

/** Where a saved plant's row comes to rest when a sideways swipe stops. */
internal enum class SwipeEnd {
    /** The plant is unsaved. */
    Remove,

    /** The row rests slid open, with the Remove button showing. */
    Open,

    /** The row slides shut. */
    Shut;

    companion object {
        /**
         * `from` is where the row sat when the swipe began and `dx` how far the finger moved since, in pixels, negative
         * to the left; `width` is the row's width and `openPast` the distance past which the row rests open. A swipe
         * past 55% of the row removes the plant. When the phone calls the swipe off (`lifted` is false), the row goes
         * back to where it was and the plant is never removed.
         */
        fun settle(from: Float, dx: Float, width: Float, openPast: Float, lifted: Boolean): SwipeEnd {
            if (!lifted) return if (from < 0) Open else Shut
            val end = min(0f, from + dx)
            if (width > 0 && -end > width * 0.55f) return Remove
            return if (-end > openPast) Open else Shut
        }
    }
}

/** What a row's swipe keeps between touches, none of which needs the row drawn again. */
private class SwipeMemory {
    /** Where the row sat when the current sideways swipe began; null when no sideways swipe is under way. */
    var start: Float? = null
    var slide: Job? = null
}

/**
 * One saved plant's row that slides to the left over the red Remove button. A short swipe leaves the button showing,
 * 80 dp wide; a swipe past half the row removes the plant at once; a swipe that goes mostly up or down is left to the
 * page's scrolling. The button exists only while the row is slid open, so TalkBack and a closed row never meet it.
 */
@Composable
private fun SwipeToRemove(
    id: String,
    openId: String?,
    setOpen: (String?) -> Unit,
    remove: () -> Unit,
    swipeable: Boolean,
    row: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val buttonWidth = with(density) { 80.dp.toPx() }
    val openPast = with(density) { 40.dp.toPx() }
    // How far the row has slid to the left, as a negative number of pixels.
    var offset by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(0) }
    val memory = remember { SwipeMemory() }
    val scope = rememberCoroutineScope()
    val currentOpenId by rememberUpdatedState(openId)
    val currentSetOpen by rememberUpdatedState(setOpen)
    val currentRemove by rememberUpdatedState(remove)

    fun slide(to: Float) {
        memory.slide?.cancel()
        memory.slide = scope.launch {
            animate(offset, to, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { value, _ -> offset = value }
        }
    }

    fun removeNow() {
        if (currentOpenId == id) currentSetOpen(null)
        currentRemove()
    }

    fun changed(dx: Float) {
        val start = memory.start ?: offset.also {
            memory.start = it
            memory.slide?.cancel()
            if (currentOpenId != id) currentSetOpen(id)
        }
        offset = min(0f, start + dx)
    }

    fun ended(dx: Float, lifted: Boolean) {
        val from = memory.start ?: return
        memory.start = null
        when (SwipeEnd.settle(from, dx, width.toFloat(), openPast, lifted)) {
            SwipeEnd.Remove -> removeNow()
            SwipeEnd.Open -> {
                slide(-buttonWidth)
                currentSetOpen(id)
            }
            SwipeEnd.Shut -> {
                slide(0f)
                if (currentOpenId == id) currentSetOpen(null)
            }
        }
    }

    // Another row opened, or a tap closed this one.
    LaunchedEffect(openId) {
        if (openId != id && memory.start == null && offset != 0f) slide(0f)
    }

    val swipe = if (!swipeable) Modifier else Modifier.pointerInput(Unit) {
        awaitEachGesture {
            // The row's own tap handling sees the finger first, so a finger it has already noted still counts here.
            val down = awaitFirstDown(requireUnconsumed = false)
            // Taken only once the finger has moved more sideways than up or down; until then the page may scroll.
            val crossed = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                val moved = change.position - down.position
                if (abs(moved.x) > abs(moved.y)) change.consume()
            } ?: return@awaitEachGesture
            var dx = crossed.position.x - down.position.x
            changed(dx)
            // Taking every move from here on also stops the row's tap and the page's scrolling.
            val lifted = try {
                horizontalDrag(crossed.id) { change ->
                    dx += change.positionChange().x
                    change.consume()
                    changed(dx)
                }
            } catch (e: CancellationException) {
                ended(dx, lifted = false)
                throw e
            }
            // False when the phone called the swipe off.
            ended(dx, lifted)
        }
    }

    Box(Modifier.fillMaxWidth().onSizeChanged { width = it.width }.then(swipe)) {
        if (offset < 0f) {
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                val shown = with(density) { (-offset).toDp() }
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(max(80.dp, shown))
                        .background(Brand.error)
                        .testTag("remove-$id")
                        .clickable(role = Role.Button) { removeNow() }
                        .clearAndSetSemantics {
                            contentDescription = "Remove"
                            role = Role.Button
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = null, tint = Color.White)
                }
            }
        }
        Box(Modifier.offset { IntOffset(offset.roundToInt(), 0) }.background(Color.White)) {
            row()
        }
    }
}
