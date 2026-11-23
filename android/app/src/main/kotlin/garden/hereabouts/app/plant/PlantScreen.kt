// One plant, opened from a row on the Plants tab, as the approved picture draws it: the photos; the name, the Latin name
// and "Shrub · Native to Fairfax County, Virginia"; Save and Where to buy; the four facts in the ruled box and the
// plant's note; "Through the year"; flowers, fall colour, growth and what it feeds; the Where to buy row; "How to grow
// it"; "Where it grows wild" with the USDA link. Every word is the website's, from the shared code. Where to buy opens
// as a sheet over the page. Back, in the top bar or the phone's own, returns to the list. The same as the iPhone app's
// plant page.
@file:OptIn(ExperimentalMaterial3Api::class)

package garden.hereabouts.app.plant

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import garden.hereabouts.app.AppModel
import garden.hereabouts.app.Brand
import garden.hereabouts.app.Figtree
import garden.hereabouts.app.R
import garden.hereabouts.app.YoungSerif
import garden.hereabouts.app.openInBrowser
import garden.hereabouts.app.plants.isLargeText
import garden.hereabouts.core.BuyView
import garden.hereabouts.core.Channel
import garden.hereabouts.core.EventValue
import garden.hereabouts.core.Labels
import garden.hereabouts.core.Location
import garden.hereabouts.core.LocationLookup
import garden.hereabouts.core.Plant
import garden.hereabouts.core.PlantGeo
import garden.hereabouts.core.RangeText
import garden.hereabouts.core.Sellers

private val Body = TextStyle(fontFamily = Figtree, fontSize = 17.sp, lineHeight = 24.sp, color = Brand.ink)
private val ButtonText = TextStyle(fontFamily = Figtree, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)

// The default 24 dp each side leaves "Where to buy" too little room on a 320 dp wide phone, so it broke onto two lines.
private val ButtonPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

/**
 * `announced` says whether this opening of the page has already sent its two events; `onAnnounced` is called when it
 * sends them. The flag lives with the tabs, so coming back from another tab does not send them again.
 */
@Composable
fun PlantScreen(
    model: AppModel,
    plant: Plant,
    isOnline: Boolean,
    announced: Boolean,
    onAnnounced: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val data by model.data.collectAsStateWithLifecycle()
    val location by model.location.collectAsStateWithLifecycle()
    val saved by model.saved.collectAsStateWithLifecycle()
    val isSaved = plant.id in saved
    val savedState = stringResource(if (isSaved) R.string.saved else R.string.not_saved)
    val savePlantSpoken = stringResource(R.string.save_plant, plant.common)
    val buy = remember(plant, location, data) { Sellers.buyView(plant, location, Channel.App, data) }
    val facts = remember(plant) { Labels.growingFacts(plant) }
    var buying by rememberSaveable(plant.id) { mutableStateOf(false) }

    // The website sends these two when a plant page opens, and the founder's popular-plants count reads
    // buy_panel_open as "someone looked at this plant". So the app sends them when the page opens, not when the sheet
    // does, and once per opening.
    LaunchedEffect(plant.id) {
        if (announced) return@LaunchedEffect
        onAnnounced()
        model.track("buy_panel_open", mapOf("plant" to EventValue.Text(plant.id)))
        val here = location
        if (buy.noSellerText != null && here != null) {
            model.track(
                "no_seller_shown",
                mapOf("plant" to EventValue.Text(plant.id), "state" to EventValue.Text(here.stateUsps)),
            )
        }
    }

    Scaffold(
        modifier = Modifier.testTag("plantPage"),
        containerColor = Brand.packet,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconToggleButton(
                        checked = isSaved,
                        onCheckedChange = { model.pressSave(plant.id) },
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { stateDescription = savedState }
                            .testTag("toolbarSave"),
                    ) {
                        Icon(
                            if (isSaved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                            contentDescription = savePlantSpoken,
                            tint = if (isSaved) Brand.moss else Brand.ink,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Brand.packet,
                    scrolledContainerColor = Brand.packet,
                    navigationIconContentColor = Brand.ink,
                    actionIconContentColor = Brand.ink,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            PhotoPager(plant, model.photos)
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp)) {
                Title(plant, location, data.geo)
                Buttons(plant, isSaved, onSave = { model.pressSave(plant.id) }, onBuy = { buying = true })
                if (facts.quick.isNotEmpty()) FactGrid(facts.quick, Modifier.padding(top = 16.dp))
                plant.notes?.takeIf { it.isNotEmpty() }?.let { Paragraph(it, Modifier.padding(top = 24.dp)) }
                Heading(stringResource(R.string.through_the_year))
                SeasonChart(plant)
                val more = facts.flowers + facts.more
                if (more.isNotEmpty()) FactList(more, Modifier.padding(top = 24.dp))
                BuyRow(buySubtitle(buy, location), onClick = { buying = true }, modifier = Modifier.padding(top = 16.dp))
                plant.maturity?.notes?.takeIf { it.isNotEmpty() }?.let { care ->
                    Heading(stringResource(R.string.how_to_grow))
                    Paragraph(care)
                }
                data.geo[plant.id]?.let { geo ->
                    Heading(stringResource(R.string.where_grows_wild))
                    RangeMap(plant, geo.eco, location, Modifier.padding(top = 4.dp))
                }
                plant.usda?.takeIf { it.isNotEmpty() }?.let { UsdaLine(it, Modifier.padding(top = 8.dp)) }
            }
        }
    }

    if (buying) {
        WhereToBuySheet(model, plant, buy, location, isOnline, onClose = { buying = false })
    }
}

@Composable
private fun Title(plant: Plant, location: Location?, geo: Map<String, PlantGeo>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            plant.common,
            style = TextStyle(fontFamily = YoungSerif, fontSize = 34.sp, lineHeight = 41.sp, color = Brand.ink),
            modifier = Modifier.semantics { heading() }.testTag("plantName"),
        )
        Text(
            plant.sci,
            style = TextStyle(
                fontFamily = Figtree,
                fontStyle = FontStyle.Italic,
                fontSize = 20.sp,
                lineHeight = 25.sp,
                color = Brand.inkSoft,
            ),
            modifier = Modifier.testTag("plantSci"),
        )
        Text(
            plantMeta(plant, location, geo),
            style = TextStyle(
                fontFamily = Figtree,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                color = Brand.ink,
            ),
            modifier = Modifier.testTag("plantMeta"),
        )
    }
}

/**
 * The website's line under the Latin name: "Shrub · Native to Fairfax County, Virginia", "Not native to …", or only
 * the type when no county is set.
 */
internal fun plantMeta(plant: Plant, location: Location?, geo: Map<String, PlantGeo>): String {
    val type = Labels.category(plant.category)
    if (location == null) return type
    val native = LocationLookup.nativeHere(plant.id, location, geo)
    return "$type · ${if (native) "Native" else "Not native"} to ${location.countyName}, ${location.stateName}"
}

/** Save and Where to buy, side by side; one above the other at the largest text sizes. */
@Composable
private fun Buttons(plant: Plant, isSaved: Boolean, onSave: () -> Unit, onBuy: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    val icon = with(LocalDensity.current) { 20.sp.toDp() }
    val savePlantSpoken = stringResource(R.string.save_plant, plant.common)
    val savedState = stringResource(if (isSaved) R.string.saved else R.string.not_saved)
    val save = @Composable { m: Modifier ->
        OutlinedButton(
            onClick = onSave,
            contentPadding = ButtonPadding,
            shape = shape,
            border = BorderStroke(1.dp, Brand.moss),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Brand.moss),
            modifier = m
                .heightIn(min = 50.dp)
                .semantics {
                    contentDescription = savePlantSpoken
                    stateDescription = savedState
                }
                .testTag("pageSave"),
        ) {
            Icon(
                if (isSaved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                contentDescription = null,
                modifier = Modifier.size(icon),
            )
            Text(stringResource(if (isSaved) R.string.saved else R.string.save), style = ButtonText, modifier = Modifier.padding(start = 8.dp))
        }
    }
    val where = @Composable { m: Modifier ->
        Button(
            onClick = onBuy,
            contentPadding = ButtonPadding,
            shape = shape,
            colors = ButtonDefaults.buttonColors(containerColor = Brand.moss, contentColor = Brand.ground),
            modifier = m.heightIn(min = 50.dp).testTag("whereToBuy"),
        ) {
            Text(stringResource(R.string.where_to_buy), style = ButtonText)
        }
    }
    if (isLargeText()) {
        Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            save(Modifier.fillMaxWidth())
            where(Modifier.fillMaxWidth())
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            save(Modifier.weight(1f))
            where(Modifier.weight(1f))
        }
    }
}

/**
 * The kraft row that also opens Where to buy, with how many sellers there are and the grey arrow at its right. With no
 * seller it says the website card's "No seller listed yet".
 */
@Composable
private fun BuyRow(subtitle: String, onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(4.dp)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(Brand.kraft, shape)
            .border(1.dp, Brand.ruleStrong, shape)
            .testTag("whereToBuyCard")
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.where_to_buy), style = ButtonText.copy(color = Brand.ink))
            Text(
                subtitle,
                style = TextStyle(fontFamily = Figtree, fontSize = 14.sp, lineHeight = 19.sp, color = Brand.inkSoft),
                modifier = Modifier.testTag("whereToBuySubtitle"),
            )
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = Brand.ruleStrong)
    }
}

/** "7 sellers ship to Virginia", "1 seller near you · 6 sellers ship to Kentucky", or "No seller listed yet". */
internal fun buySubtitle(buy: BuyView, location: Location?): String {
    if (buy.rows.isEmpty()) return "No seller listed yet"
    val near = buy.rows.count { it.local }
    val ships = buy.rows.size - near
    if (location == null) return if (buy.rows.size == 1) "1 seller" else "${buy.rows.size} sellers"
    val parts = mutableListOf<String>()
    if (near > 0) parts += if (near == 1) "1 seller near you" else "$near sellers near you"
    if (ships > 0) {
        parts += if (ships == 1) "1 seller ships to ${location.stateName}" else "$ships sellers ship to ${location.stateName}"
    }
    return parts.joinToString(" · ")
}

/** The website's USDA line: the link, then the plant's USDA number and where the map's regions come from. */
@Composable
private fun UsdaLine(usda: String, modifier: Modifier) {
    val context = LocalContext.current
    val icon = with(LocalDensity.current) { 18.sp.toDp() }
    Column(modifier) {
        Row(
            Modifier
                .heightIn(min = 48.dp)
                .testTag("usdaLink")
                .clickable { openInBrowser(context, usdaUrl(usda)) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(R.string.usda_profile),
                style = TextStyle(
                    fontFamily = Figtree,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Brand.moss,
                    textDecoration = TextDecoration.Underline,
                ),
            )
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = stringResource(R.string.opens_outside),
                tint = Brand.moss,
                modifier = Modifier.size(icon),
            )
        }
        Text(
            stringResource(R.string.usda_note, usda),
            style = TextStyle(fontFamily = Figtree, fontSize = 13.sp, lineHeight = 18.sp, color = Brand.inkSoft),
        )
    }
}

/** The website's usdaUrl: the USDA number escaped as JavaScript's encodeURIComponent escapes it. */
internal fun usdaUrl(usda: String): String {
    val keep = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"
    val id = buildString {
        for (b in usda.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xFF).toChar()
            if (b >= 0 && c in keep) append(c) else append("%%%02X".format(b.toInt() and 0xFF))
        }
    }
    return "https://plants.sc.egov.usda.gov/plant-profile/$id"
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = TextStyle(fontFamily = YoungSerif, fontSize = 22.sp, lineHeight = 28.sp, color = Brand.ink),
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp).semantics { heading() },
    )
}

/** The plant's note or its growing note, its ranges read with "to" ("3 to 5 ft stalks"). */
@Composable
private fun Paragraph(text: String, modifier: Modifier = Modifier) {
    Text(RangeText.display(text), style = Body, modifier = modifier)
}
