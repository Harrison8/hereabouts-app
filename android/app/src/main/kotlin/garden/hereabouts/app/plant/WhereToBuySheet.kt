// "Where to buy", as the approved picture draws it: a sheet over the plant page, half the screen high and pulled up to
// full height. A line saying how many sellers ship the plant to the person's state and whether any has a shop nearby;
// one row per seller in the website's order, with the seller, size and price. A nearby shop's row has a Directions
// button that opens the phone's maps app at the same place as the website's Google Maps link (or that link in the
// browser when no maps app takes it); every other row opens the seller's page, the address made exactly as the website
// makes it (the shared Sellers.buyView with the app's channel). Every link opens in the phone's own browser. Below the
// rows: the website's line when no seller sells the plant to the state, and the commission note when any link earns
// one. The same as the iPhone app's sheet.
@file:OptIn(ExperimentalMaterial3Api::class)

package garden.hereabouts.app.plant

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.hereabouts.app.AppModel
import garden.hereabouts.app.Brand
import garden.hereabouts.app.Figtree
import garden.hereabouts.app.YoungSerif
import garden.hereabouts.app.openDirections
import garden.hereabouts.app.openInBrowser
import garden.hereabouts.app.plants.OfflineBanner
import garden.hereabouts.app.plants.isLargeText
import garden.hereabouts.app.settings.AFFILIATE_URL
import garden.hereabouts.core.BuyRow
import garden.hereabouts.core.BuyView
import garden.hereabouts.core.EventValue
import garden.hereabouts.core.Location
import garden.hereabouts.core.Plant
import garden.hereabouts.core.RangeText
import kotlinx.coroutines.launch

private val NameStyle =
    TextStyle(fontFamily = Figtree, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = Brand.ink)
private val LineStyle = TextStyle(fontFamily = Figtree, fontSize = 14.sp, lineHeight = 19.sp, color = Brand.inkSoft)

@Composable
fun WhereToBuySheet(
    model: AppModel,
    plant: Plant,
    view: BuyView,
    location: Location?,
    isOnline: Boolean,
    onClose: () -> Unit,
) {
    val large = isLargeText()
    // At the largest text sizes the half-height sheet holds only the title and part of the summary, with no seller in
    // view, so there it opens at full height.
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = large)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet, containerColor = Brand.ground) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Header(
                summary = buySummary(plant, view, location),
                onDone = { scope.launch { sheet.hide() }.invokeOnCompletion { onClose() } },
            )
            if (!isOnline) OfflineBanner(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            for (row in view.rows) {
                SellerRow(model, plant, row, location, large)
                Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(1.dp).background(Brand.rule))
            }
            view.noSellerText?.let { none ->
                Text(
                    none,
                    style = TextStyle(fontFamily = Figtree, fontSize = 16.sp, lineHeight = 22.sp, color = Brand.ink),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).testTag("noSeller"),
                )
            }
            // At the largest text sizes the note scrolls with the rows, so it never covers half the sheet.
            if (view.hasAffiliate && large) CommissionNote(Modifier.padding(top = 16.dp))
        }
        if (view.hasAffiliate && !large) CommissionNote(Modifier.padding(bottom = 8.dp))
    }
}

@Composable
private fun Header(summary: String?, onDone: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Where to buy",
                style = TextStyle(fontFamily = YoungSerif, fontSize = 22.sp, lineHeight = 28.sp, color = Brand.ink),
                modifier = Modifier.semantics { heading() },
            )
            if (summary != null) {
                Text(
                    summary,
                    style = TextStyle(fontFamily = Figtree, fontSize = 15.sp, lineHeight = 20.sp, color = Brand.inkSoft),
                    modifier = Modifier.testTag("whereToBuySummary"),
                )
            }
        }
        TextButton(
            onClick = onDone,
            modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).testTag("whereToBuyDone"),
        ) {
            Text("Done", style = NameStyle.copy(color = Brand.moss))
        }
    }
}

@Composable
private fun SellerRow(model: AppModel, plant: Plant, row: BuyRow, location: Location?, large: Boolean) {
    val context = LocalContext.current
    val directions = row.directions
    if (row.kind == "directions" && directions != null && hasPlace(directions)) {
        val info = @Composable { m: Modifier -> Info(row, location, m.testTag("seller").semantics(mergeDescendants = true) {}) }
        val button = @Composable {
            OutlinedButton(
                onClick = {
                    model.track(
                        "local_vendor_click",
                        mapOf("plant" to EventValue.Text(plant.id), "vendor" to EventValue.Text(row.name)),
                    )
                    openDirections(context, directions)
                },
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, Brand.moss),
                contentPadding = PaddingValues(horizontal = 14.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("directions")
                    .semantics { contentDescription = "Directions to ${row.name}" },
            ) {
                Text("Directions", style = NameStyle.copy(fontSize = 16.sp, color = Brand.moss))
            }
        }
        val frame = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp)
        if (large) {
            Column(frame, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                info(Modifier.fillMaxWidth())
                button()
            }
        } else {
            Row(frame, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                info(Modifier.weight(1f))
                button()
            }
        }
    } else {
        val url = sellerLink(row.href)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .testTag("seller")
                .clickable(enabled = url != null, role = Role.Button) {
                    if (url == null) return@clickable
                    model.track(
                        "affiliate_click",
                        mapOf(
                            "plant" to EventValue.Text(plant.id),
                            "vendor" to EventValue.Text(row.name),
                            "local" to EventValue.Bool(row.local),
                            "affiliate" to EventValue.Bool(row.affiliate),
                        ),
                    )
                    if (row.local) {
                        model.track(
                            "local_vendor_click",
                            mapOf("plant" to EventValue.Text(plant.id), "vendor" to EventValue.Text(row.name)),
                        )
                    }
                    openInBrowser(context, url)
                }
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Info(row, location, Modifier.weight(1f))
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = "Opens outside the app",
                    tint = if (url == null) Brand.ruleStrong else Brand.moss,
                )
            }
        }
    }
}

/** The seller's name, then how the person gets the plant and the size and price. */
@Composable
private fun Info(row: BuyRow, location: Location?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(row.name, style = NameStyle)
        for (line in sellerLines(row, location)) Text(line, style = LineStyle)
    }
}

/** The website's commission note, with its "How this works" link made a full-size button. */
@Composable
private fun CommissionNote(modifier: Modifier) {
    val context = LocalContext.current
    val icon = with(LocalDensity.current) { 18.sp.toDp() }
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Brand.rule))
        Text(
            "Each link opens in your browser. Some of these links earn us a commission.",
            style = TextStyle(fontFamily = Figtree, fontSize = 13.sp, lineHeight = 18.sp, color = Brand.inkSoft),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp).testTag("commissionNote"),
        )
        Row(
            Modifier
                .padding(horizontal = 16.dp)
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button) { openInBrowser(context, AFFILIATE_URL) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "How this works",
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
                contentDescription = "Opens outside the app",
                tint = Brand.moss,
                modifier = Modifier.size(icon),
            )
        }
    }
}

/** A seller's address from the shared code, or null when the website would show no link ("#", or not a web page). */
internal fun sellerLink(href: String): String? {
    if (href == "#") return null
    val lower = href.lowercase()
    return href.takeIf { lower.startsWith("https://") || lower.startsWith("http://") }
}

/** True when the website's Google Maps link names a place, the only kind of Directions the row offers. */
private fun hasPlace(directions: String): Boolean =
    Uri.parse(directions).getQueryParameter("destination").orEmpty().isNotEmpty()

/**
 * The lines under a seller's name. A shop nearby: "Pick up · 15 mi · Goshen, KY", the website's words. With no county
 * set, where the seller ships, as the website says it. Then the size and price ("1-Pack · $69.00"), a range read with
 * "to" ("1 to 2 ft").
 */
internal fun sellerLines(row: BuyRow, location: Location?): List<String> {
    val lines = mutableListOf<String>()
    if (row.local && row.miles != null) {
        lines += "Pick up · ${row.miles} mi · ${row.place ?: "Storefront"}"
    } else if (location == null && !row.ships.isNullOrEmpty()) {
        lines += row.ships!!
    }
    val detail = RangeText.display(listOfNotNull(row.size, row.price).filter { it.isNotEmpty() }.joinToString(" · "))
    if (detail.isNotEmpty()) lines += detail
    return lines
}

/**
 * The line under the title: how many sellers ship the plant to the person's state, and whether any has a shop near
 * them. Null when no seller sells it; the website's no-seller line says so instead.
 */
internal fun buySummary(plant: Plant, view: BuyView, location: Location?): String? {
    val rows = view.rows
    if (rows.isEmpty()) return null
    if (location == null) {
        return if (rows.size == 1) "1 seller sells ${plant.common}." else "${rows.size} sellers sell ${plant.common}."
    }
    val near = rows.count { it.local }
    val ships = rows.size - near
    val parts = mutableListOf<String>()
    if (ships == 1) parts += "1 seller ships ${plant.common} to ${location.stateName}."
    else if (ships > 1) parts += "$ships sellers ship ${plant.common} to ${location.stateName}."
    parts += when {
        near == 1 -> "1 seller has a shop near you."
        near > 1 -> "$near sellers have a shop near you."
        else -> "None has a shop near you."
    }
    return parts.joinToString(" ")
}
