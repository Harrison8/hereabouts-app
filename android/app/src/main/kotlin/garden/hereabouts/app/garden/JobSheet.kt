// One job of This week, in a sheet that opens at full height: the job's heading, the saved plants it names, when to
// do it (with the county's timing dates it comes from), what to do (the rule's facts), and the sources the facts come
// from, each opening in the phone's browser. A job that shares a pair with another shows the other below it, since
// either way will do. Mark as done ticks the job off for the week, as its tick circle does. The same as the iPhone
// app's job sheet.
@file:OptIn(ExperimentalMaterial3Api::class)

package garden.hereabouts.app.garden

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.hereabouts.app.AppModel
import garden.hereabouts.app.Brand
import garden.hereabouts.app.CareTables
import garden.hereabouts.app.openInBrowser
import garden.hereabouts.app.plants.PlantThumbnail
import garden.hereabouts.core.Job
import garden.hereabouts.core.Labels
import garden.hereabouts.core.Plant
import garden.hereabouts.core.RangeText
import kotlinx.coroutines.launch

@Composable
fun JobSheet(
    model: AppModel,
    row: WeekRow,
    week: GardenWeek,
    done: Boolean,
    toggle: () -> Unit,
    onClose: () -> Unit,
) {
    // Full height, so the job, the plants, when and what to do show without pulling the sheet up.
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // The sources of every job in the row, each once, in order.
    val sources = row.jobs.flatMap { model.care.sources[it.ruleId].orEmpty() }.distinctBy { it.url }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet, containerColor = Brand.ground) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Header(row.heading, onDone = { scope.launch { sheet.hide() }.invokeOnCompletion { onClose() } })
            for (id in row.plantIds) week.plant(id)?.let { PlantLine(model, it) }
            row.jobs.forEachIndexed { i, job ->
                if (i > 0) {
                    Text(
                        "Or: ${job.heading}",
                        style = TitleStyle.copy(fontSize = 20.sp, lineHeight = 26.sp),
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 32.dp).semantics { heading() },
                    )
                }
                JobDetail(job, week)
            }
            if (sources.isNotEmpty()) SourceList(sources)
        }
        MarkDoneBar(done, toggle)
    }
}

@Composable
private fun Header(title: String, onDone: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = TitleStyle, modifier = Modifier.weight(1f).padding(top = 6.dp).semantics { heading() })
        TextButton(
            onClick = onDone,
            modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).testTag("jobSheetDone"),
        ) {
            Text("Done", style = BodyStyle.copy(color = Brand.moss, fontWeight = FontWeight.SemiBold))
        }
    }
}

/** "Your Spicebush", with its photo and the photo's credit. */
@Composable
private fun PlantLine(model: AppModel, plant: Plant) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PlantThumbnail(plant, model.photos, 64.dp)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Your ${plant.common}", style = BodyStyle)
            val credit = Labels.mainPhoto(plant)?.photo?.credit
            if (!credit.isNullOrEmpty()) Text(credit, style = CreditStyle)
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = BodyStyle.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 6.dp).semantics { heading() },
    )
}

@Composable
private fun JobDetail(job: Job, week: GardenWeek) {
    val whenText = week.whenText(job)
    val explained = week.whenExplained(job)
    if (whenText.isNotEmpty()) {
        Label("When")
        Text(whenText, style = BodyStyle, modifier = Modifier.padding(horizontal = 16.dp).testTag("jobWhen-${job.ruleId}"))
        if (explained.isNotEmpty()) {
            Text(explained, style = SmallStyle, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
        }
    }
    Label("What to do")
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // The square sits about halfway up the first line's small letters.
        val squareTop = with(LocalDensity.current) { (BodyStyle.lineHeight.toDp() - 6.dp) / 2 + 1.dp }
        for (fact in job.facts) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.padding(top = squareTop).size(6.dp).background(Brand.ink))
                Text(RangeText.display(fact), style = BodyStyle)
            }
        }
    }
}

@Composable
private fun SourceList(sources: List<CareTables.Source>) {
    val context = LocalContext.current
    Label("Sources")
    sources.forEachIndexed { i, source ->
        if (i > 0) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(1.dp).background(Brand.rule))
        val host = Uri.parse(source.url).host?.removePrefix("www.")
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .testTag("source-$i")
                .clickable(role = Role.Button) { openInBrowser(context, source.url) }
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(source.title, style = BodyStyle)
                if (!host.isNullOrEmpty()) Text(host, style = SmallStyle)
            }
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Opens outside the app", tint = Brand.moss)
            }
        }
    }
}

/** Mark as done, kept above the bottom edge. */
@Composable
private fun MarkDoneBar(done: Boolean, toggle: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color.White)
            .drawBehind { drawLine(Brand.rule, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .background(if (done) Color.White else Brand.moss, Corner)
                .then(if (done) Modifier.border(1.5.dp, Brand.moss, Corner) else Modifier)
                .testTag("markDone")
                .clickable(role = Role.Button, onClick = toggle)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        ) {
            if (!done) {
                Canvas(Modifier.size(width = 16.dp, height = 13.dp)) {
                    drawTick(Offset.Zero, size.width, size.height, Color.White, 2.2.dp.toPx())
                }
            }
            Text(
                if (done) "Mark as not done" else "Mark as done",
                style = BodyStyle.copy(color = if (done) Brand.moss else Color.White, fontWeight = FontWeight.SemiBold),
            )
        }
    }
}
