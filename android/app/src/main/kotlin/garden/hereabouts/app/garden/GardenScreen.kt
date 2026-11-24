// The Garden tab, as the approved pictures draw it: the county under the title, after a map pin; This week, the jobs
// due for the saved plants in the week that runs from Saturday to Friday, each with a tick circle, and how many are
// ticked off; a line saying where the dates come from; then the saved plants. Tapping a job opens its sheet
// (JobSheet); tapping a plant opens its page, and swiping it to the left shows Remove (SavedGrid). With nothing saved,
// a short note and a button to the Plants tab. The same as the iPhone app's Garden tab.
package garden.hereabouts.app.garden

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import garden.hereabouts.app.AppModel
import garden.hereabouts.app.Brand
import garden.hereabouts.app.CountyLine
import garden.hereabouts.app.Figtree
import garden.hereabouts.app.R
import garden.hereabouts.app.TicksStore
import garden.hereabouts.app.YoungSerif
import garden.hereabouts.app.plants.isLargeText
import garden.hereabouts.core.Location

internal val TitleStyle = TextStyle(fontFamily = YoungSerif, fontSize = 22.sp, lineHeight = 28.sp, color = Brand.ink)
internal val BodyStyle = TextStyle(fontFamily = Figtree, fontSize = 17.sp, lineHeight = 22.sp, color = Brand.ink)
internal val SmallStyle = TextStyle(fontFamily = Figtree, fontSize = 15.sp, lineHeight = 20.sp, color = Brand.inkSoft)
internal val CreditStyle = TextStyle(fontFamily = Figtree, fontSize = 10.sp, lineHeight = 13.sp, color = Brand.creditGrey)
internal val Corner = RoundedCornerShape(4.dp)

/**
 * The Garden tab. `openJob` is the job whose sheet is open, by rule id, kept by the tabs so a tapped reminder can close
 * it. `swipeable` is false while a plant page is open over the list.
 */
@Composable
fun GardenScreen(
    model: AppModel,
    scroll: ScrollState,
    swipeable: Boolean,
    openJob: String?,
    onOpenJob: (String?) -> Unit,
    onOpenPlant: (String) -> Unit,
    onBrowsePlants: () -> Unit,
) {
    val data by model.data.collectAsStateWithLifecycle()
    val location by model.location.collectAsStateWithLifecycle()
    val saved by model.saved.collectAsStateWithLifecycle()
    val ticks by model.ticks.collectAsStateWithLifecycle()
    val remindersOn by model.remindersOn.collectAsStateWithLifecycle()
    val permission by model.permission.collectAsStateWithLifecycle()
    val today by model.today.collectAsStateWithLifecycle()
    val week = remember(data, location, saved, today) {
        GardenWeek(model.care, data.plants, location?.countyFips, location?.countyName.orEmpty(), saved, today)
    }
    val ticked = ticks[TicksStore.weekId(today)].orEmpty()
    val willCome = AppModel.willCome(remindersOn, permission)

    Column(Modifier.fillMaxSize().background(Brand.surface).verticalScroll(scroll).padding(bottom = 24.dp)) {
        location?.let { CountyRow(it) } ?: Spacer(Modifier.height(4.dp))
        if (week.savedPlants.isEmpty()) {
            EmptyState(willCome, onBrowsePlants)
        } else {
            ThisWeek(week, ticked, willCome, toggle = { model.toggleTick(it, today) }, open = { onOpenJob(it) })
            SavedGrid(
                plants = week.savedPlants,
                photos = model.photos,
                jobCount = week::jobCount,
                open = onOpenPlant,
                remove = model::removeSaved,
                swipeable = swipeable,
                modifier = Modifier.padding(top = 32.dp),
            )
        }
    }

    val row = openJob?.let { id -> week.rows.firstOrNull { it.id == id } }
    if (row != null) {
        JobSheet(
            model = model,
            row = row,
            week = week,
            done = row.id in ticked,
            toggle = { model.toggleTick(row.id, today) },
            onClose = { onOpenJob(null) },
        )
    }
}

@Composable
private fun CountyRow(location: Location) {
    CountyLine(Brand.ink, Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp), size = 17.sp) {
        Text("${location.countyName}, ${location.stateName}", style = BodyStyle, modifier = Modifier.testTag("gardenCounty"))
    }
}

@Composable
private fun EmptyState(willCome: Boolean, onBrowsePlants: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.garden_empty_title),
            style = TitleStyle,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("gardenEmpty").semantics { heading() },
        )
        // The Saturday sentence only while a reminder will come: not with the switch off in Settings, and not when
        // the phone has refused the app's notifications.
        val saturday = if (willCome) " " + stringResource(R.string.reminder_sentence) else ""
        val tapSpoken = stringResource(R.string.garden_empty_tap_spoken, saturday)
        val tapParts = stringResource(R.string.garden_empty_tap, "@@", saturday).split("@@")
        val note = buildAnnotatedString {
            append(tapParts[0])
            appendInlineContent("bookmark", "save")
            append(tapParts[1])
        }
        val bookmark = mapOf(
            "bookmark" to InlineTextContent(Placeholder(1.em, 1.em, PlaceholderVerticalAlign.TextCenter)) {
                Icon(Icons.Outlined.BookmarkBorder, contentDescription = null, tint = Brand.inkSoft)
            },
        )
        Text(
            note,
            inlineContent = bookmark,
            style = BodyStyle.copy(color = Brand.inkSoft),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .testTag("gardenEmptyNote")
                .clearAndSetSemantics { contentDescription = tapSpoken },
        )
        Box(
            Modifier
                .padding(top = 8.dp)
                .widthIn(min = 200.dp)
                .heightIn(min = 50.dp)
                .background(Brand.moss, Corner)
                .testTag("browsePlants")
                .clickable(role = Role.Button, onClick = onBrowsePlants)
                .padding(horizontal = 32.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.browse_plants), style = BodyStyle.copy(color = Color.White, fontWeight = FontWeight.SemiBold))
        }
    }
}

@Composable
private fun ThisWeek(
    week: GardenWeek,
    ticked: Set<String>,
    willCome: Boolean,
    toggle: (String) -> Unit,
    open: (String) -> Unit,
) {
    val large = isLargeText()
    val title = @Composable { Text(stringResource(R.string.this_week), style = TitleStyle, modifier = Modifier.semantics { heading() }) }
    val dates = @Composable { Text(week.datesLabel, style = BodyStyle.copy(color = Brand.inkSoft), modifier = Modifier.testTag("thisWeekDates")) }
    val headerPadding = Modifier.padding(start = 16.dp, end = 16.dp, top = 32.dp, bottom = 4.dp)
    if (large) {
        Column(headerPadding, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            title()
            dates()
        }
    } else {
        Row(headerPadding.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            title()
            Spacer(Modifier.weight(1f).widthIn(min = 12.dp))
            dates()
        }
    }

    if (week.rows.isNotEmpty()) {
        Text(
            stringResource(R.string.done_count, week.rows.count { it.id in ticked }, week.rows.size),
            style = SmallStyle.copy(fontWeight = FontWeight.Medium),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp).testTag("doneCount"),
        )
    }

    Column(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = if (week.rows.isEmpty()) 8.dp else 0.dp)
            .fillMaxWidth()
            .background(Color.White, Corner)
            .border(1.dp, Brand.rule, Corner),
    ) {
        if (week.rows.isEmpty()) {
            Text(
                stringResource(R.string.nothing_this_week_full),
                style = BodyStyle.copy(color = Brand.inkSoft),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(16.dp),
            )
        }
        week.rows.forEachIndexed { i, row ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Brand.rule))
            JobRow(row, week.subline(row), done = row.id in ticked, large, toggle = { toggle(row.id) }, open = { open(row.id) })
        }
    }

    Text(
        week.footer(willCome),
        style = SmallStyle,
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp).testTag("thisWeekFooter"),
    )
}

@Composable
private fun JobRow(row: WeekRow, subline: String, done: Boolean, large: Boolean, toggle: () -> Unit, open: () -> Unit) {
    // At the largest text sizes a row is many lines tall, so the tick circle sits beside the job's first line instead
    // of halfway down the row.
    Row(verticalAlignment = if (large) Alignment.Top else Alignment.CenterVertically) {
        TickCircle(
            done = done,
            label = stringResource(R.string.tick_off, row.heading),
            toggle = toggle,
            modifier = Modifier.padding(start = 4.dp, top = if (large) 8.dp else 0.dp).testTag("tick-${row.id}"),
        )
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 64.dp)
                .testTag("job-${row.id}")
                .clickable(role = Role.Button, onClick = open)
                .clearAndSetSemantics {
                    contentDescription = if (subline.isEmpty()) row.heading else "${row.heading}, $subline"
                    role = Role.Button
                }
                .padding(top = 12.dp, bottom = 12.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.heading, style = BodyStyle.copy(color = if (done) Brand.inkSoft else Brand.ink))
                if (subline.isNotEmpty()) Text(subline, style = SmallStyle)
            }
            Chevron()
        }
    }
}

/**
 * A round tick: an empty ring until the job is done, then a filled moss circle with a white tick. The whole 48 dp
 * square answers a tap. The tag goes on `modifier`, ahead of the spoken label.
 */
@Composable
internal fun TickCircle(done: Boolean, label: String, toggle: () -> Unit, modifier: Modifier = Modifier) {
    val state = stringResource(if (done) R.string.done else R.string.not_done)
    Box(
        modifier
            .size(48.dp)
            .toggleable(value = done, role = Role.Checkbox, onValueChange = { toggle() })
            .clearAndSetSemantics {
                contentDescription = label
                stateDescription = state
                role = Role.Checkbox
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(26.dp)) {
            if (done) {
                drawCircle(Brand.moss)
                val inset = 7.dp.toPx()
                drawTick(Offset(inset, inset), size.width - 2 * inset, size.height - 2 * inset, Color.White, 2.2.dp.toPx())
            } else {
                val line = 1.5.dp.toPx()
                drawCircle(Brand.ruleStrong, radius = (size.minDimension - line) / 2, style = Stroke(line))
            }
        }
    }
}

/** A tick, drawn in the box at `origin` that is `w` by `h`. */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTick(origin: Offset, w: Float, h: Float, color: Color, line: Float) {
    val path = Path().apply {
        moveTo(origin.x, origin.y + h / 2 + h * 0.05f)
        lineTo(origin.x + w * 0.36f, origin.y + h - h * 0.12f)
        lineTo(origin.x + w, origin.y + h * 0.12f)
    }
    drawPath(path, color, style = Stroke(line, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** The arrow at the end of a row that opens something. It only decorates. */
@Composable
internal fun Chevron() {
    Canvas(Modifier.size(width = 8.dp, height = 14.dp)) {
        val line = 2.dp.toPx()
        val path = Path().apply {
            moveTo(line / 2, line / 2)
            lineTo(size.width - line / 2, size.height / 2)
            lineTo(line / 2, size.height - line / 2)
        }
        drawPath(path, Brand.ruleStrong, style = Stroke(line, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
