// The Settings tab, as the approved picture draws it: the county with a Change button (which opens the first-open
// screen as a page titled "Change location"), the Saturday reminders switch, the privacy policy and "How affiliate
// links work" (both open in the phone's browser, never inside the app), the photo credits, and the app's version and
// the date of the plant and seller data it is using. Rows are Material's list items, in groups with a thin rule round
// them.
@file:OptIn(ExperimentalMaterial3Api::class)

package garden.hereabouts.app.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import garden.hereabouts.app.AppModel
import garden.hereabouts.app.Brand
import garden.hereabouts.app.Figtree
import garden.hereabouts.app.YoungSerif
import garden.hereabouts.app.firstopen.FirstOpenScreen
import garden.hereabouts.core.Labels
import garden.hereabouts.core.hereaboutsCompare

private const val PRIVACY_URL = "https://hereabouts.garden/privacy.html"
private const val AFFILIATE_URL = "https://hereabouts.garden/affiliate-disclosure.html"

private val RowText = TextStyle(fontFamily = Figtree, fontSize = 17.sp, lineHeight = 24.sp)
private val Corner = RoundedCornerShape(4.dp)

/**
 * The Settings tab's rows, under the tabs' own title bar. `onChangeLocation` opens the Change location page and
 * `onPhotoCredits` the photo credits.
 */
@Composable
fun SettingsScreen(model: AppModel, onChangeLocation: () -> Unit, onPhotoCredits: () -> Unit) {
    val context = LocalContext.current
    val location by model.location.collectAsStateWithLifecycle()
    val remindersOn by model.remindersOn.collectAsStateWithLifecycle()
    val data by model.data.collectAsStateWithLifecycle()
    val version = remember(context) { versionText(context) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        GroupLabel("Your location")
        Group {
            ListItem(
                headlineContent = { Text(location?.let { "${it.countyName}, ${it.stateName}" }.orEmpty(), style = RowText) },
                trailingContent = {
                    // The whole 48 dp area answers a tap, not only the word.
                    TextButton(
                        onClick = onChangeLocation,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .widthIn(min = 48.dp)
                            .semantics { contentDescription = "Change location" }
                            .testTag("changeLocation"),
                    ) {
                        Text("Change", style = RowText.copy(fontWeight = FontWeight.SemiBold), color = Brand.moss)
                    }
                },
                colors = rowColors(),
            )
        }

        GroupLabel("Reminders")
        Group {
            ListItem(
                headlineContent = { Text("Saturday reminders", style = RowText) },
                supportingContent = {
                    Text(
                        "Each Saturday at 8:00 you’ll get the week’s garden jobs for the plants you saved.",
                        style = RowText.copy(fontSize = 15.sp, lineHeight = 20.sp),
                        color = Brand.inkSoft,
                    )
                },
                trailingContent = { Switch(checked = remindersOn, onCheckedChange = null) },
                colors = rowColors(),
                modifier = Modifier.toggleable(
                    value = remindersOn,
                    role = Role.Switch,
                    onValueChange = { model.setRemindersOn(it) },
                ),
            )
        }

        GroupLabel("About Hereabouts")
        Group {
            OutsideLink("Privacy policy") { openInBrowser(context, PRIVACY_URL) }
            HorizontalDivider(color = Brand.rule)
            OutsideLink("How affiliate links work") { openInBrowser(context, AFFILIATE_URL) }
            HorizontalDivider(color = Brand.rule)
            ListItem(
                headlineContent = { Text("Photo credits and licenses", style = RowText) },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Brand.inkSoft)
                },
                colors = rowColors(),
                modifier = Modifier.clickable(onClick = onPhotoCredits),
            )
        }

        GroupLabel("This app")
        Group {
            ListItem(
                headlineContent = { Text("Version", style = RowText) },
                trailingContent = { Text(version, style = RowText, color = Brand.inkSoft) },
                colors = rowColors(),
            )
            HorizontalDivider(color = Brand.rule)
            ListItem(
                headlineContent = { Text("Plant and seller data", style = RowText) },
                trailingContent = { Text(dataDay(data.version.dataDate), style = RowText, color = Brand.inkSoft) },
                colors = rowColors(),
            )
        }
    }
}

@Composable
private fun rowColors() = ListItemDefaults.colors(
    containerColor = Brand.ground,
    headlineColor = Brand.ink,
    supportingColor = Brand.inkSoft,
    trailingIconColor = Brand.ink,
)

@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        style = RowText.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        color = Brand.inkSoft,
        modifier = Modifier
            .padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}

/** A group of rows with a thin rule round it and 4 dp corners. */
@Composable
private fun Group(content: @Composable () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .border(1.dp, Brand.rule, Corner)
            .padding(1.dp),
    ) {
        content()
    }
}

/** A row that opens a page of the website in the phone's browser. */
@Composable
private fun OutsideLink(title: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, style = RowText) },
        trailingContent = {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Opens outside the app", tint = Brand.ink)
        },
        colors = rowColors(),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Opens `url` in the phone's browser. A phone with no browser does nothing. */
private fun openInBrowser(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        // Nothing can open it.
    }
}

/** A full page over the tabs, with its own title bar and Back. */
@Composable
private fun Page(
    title: String,
    navigationIcon: @Composable () -> Unit,
    contentWindowInsets: WindowInsets = WindowInsets.safeDrawing,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = Brand.ground,
        contentWindowInsets = contentWindowInsets,
        topBar = {
            TopAppBar(
                title = { Text(title, modifier = Modifier.testTag("title")) },
                navigationIcon = navigationIcon,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Brand.ground,
                    scrolledContainerColor = Brand.ground,
                    titleContentColor = Brand.ink,
                    navigationIconContentColor = Brand.ink,
                ),
            )
        },
        content = content,
    )
}

/**
 * The first-open screen without its wordmark, title and lede, to choose another county. The close button, Back, or
 * leaving the page stops any lookup still waiting, so its answer sets nothing.
 */
@Composable
fun ChangeLocationPage(model: AppModel, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Page(
        title = "Change location",
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
        },
    ) { padding ->
        FirstOpenScreen(
            model,
            Modifier.padding(padding).consumeWindowInsets(padding),
            inSheet = true,
            onChosen = onClose,
        )
    }
}

/** Who took each plant photo and under which licence, plant by plant in name order. */
@Composable
fun PhotoCreditsPage(model: AppModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val data by model.data.collectAsStateWithLifecycle()
    val rows = remember(data) {
        data.plants
            .sortedWith { a, b -> hereaboutsCompare(a.common, b.common) }
            .mapNotNull { plant ->
                val credits = Labels.photos(plant).map { it.photo.credit }.distinct()
                if (credits.isEmpty()) null else Triple(plant.id, plant.common, credits)
            }
    }
    Page(
        title = "Photo credits and licenses",
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            items(rows, key = { it.first }) { (_, name, credits) ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .semantics(mergeDescendants = true) {},
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(name, style = TextStyle(fontFamily = YoungSerif, fontSize = 17.sp, lineHeight = 22.sp), color = Brand.ink)
                    for (credit in credits) {
                        Text(credit, style = RowText.copy(fontSize = 15.sp, lineHeight = 20.sp), color = Brand.inkSoft)
                    }
                }
                HorizontalDivider(color = Brand.rule)
            }
        }
    }
}

/** "1.0 (1)": the version people see, then the build number. */
fun versionText(version: String, build: String): String = if (build.isEmpty()) version else "$version ($build)"

/** This app's version and build number, as the phone has them. */
fun versionText(context: Context): String {
    val manager = context.packageManager
    val info = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(context.packageName, 0)
        }
    } catch (e: PackageManager.NameNotFoundException) {
        return ""
    }
    val build = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode.toString()
    } else {
        @Suppress("DEPRECATION")
        info.versionCode.toString()
    }
    return versionText(info.versionName.orEmpty(), build)
}

private val MONTHS = listOf(
    "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November",
    "December",
)

/**
 * The day the data was written, as "November 9, 2026", read from its first ten characters (yyyy-mm-dd) so it is the
 * same day in every time zone.
 */
fun dataDay(dataDate: String): String {
    val day = dataDate.take(10)
    val parts = day.split("-")
    val year = parts.getOrNull(0)?.toIntOrNull()
    val month = parts.getOrNull(1)?.toIntOrNull()
    val date = parts.getOrNull(2)?.toIntOrNull()
    if (parts.size != 3 || year == null || month == null || date == null || month !in 1..12) return day
    return "${MONTHS[month - 1]} $date, $year"
}
