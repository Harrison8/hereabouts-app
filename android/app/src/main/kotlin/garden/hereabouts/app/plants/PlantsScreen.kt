// The Plants tab, as the approved picture draws it: the large title "Plants" that shrinks as the list scrolls, the
// county under it (a tap opens Change location), the phone's own search field, the no-signal line when there is no
// signal, the type chips with their counts, the number of plants and the sort menu, then one row per plant, the "Also
// matching" lines and the website's empty-results text. The list is the website's own: the same filters, sorts and
// search, from the shared plant logic. The iPhone app's Plants tab works the same way.
//
// Events, as the website sends them: results_shown once each time the county changes (with empty_results when no plant
// is native there), and plant_search one second after typing stops, or on Search, with the two counts only, never the
// typed text.
@file:OptIn(ExperimentalMaterial3Api::class)

package garden.hereabouts.app.plants

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import garden.hereabouts.app.AppModel
import garden.hereabouts.app.Brand
import garden.hereabouts.app.CountyLine
import garden.hereabouts.app.Figtree
import garden.hereabouts.app.R
import garden.hereabouts.core.AppData
import garden.hereabouts.core.EventValue
import garden.hereabouts.core.FilterState
import garden.hereabouts.core.HiddenMatches
import garden.hereabouts.core.Labels
import garden.hereabouts.core.Location
import garden.hereabouts.core.LocationLookup
import garden.hereabouts.core.PlantList
import garden.hereabouts.core.SortKey
import garden.hereabouts.core.Tables
import kotlinx.coroutines.delay

/**
 * The person's choices on the Plants tab: filters, sort and search. The model keeps them, so they last while another
 * tab is open, as the iPhone's do. The county comes from the model.
 */
class PlantsChoices {
    var filter by mutableStateOf(FilterState())
    /** The last search sent as plant_search, normalised, so the same search is not sent twice (lastSentSearch). */
    var lastSentSearch = ""
    /** The county results_shown was last sent for, so coming back to the tab does not send it again. */
    var resultsShownFor: String? = null
}

@Composable
fun PlantsScreen(
    model: AppModel,
    isOnline: Boolean,
    onChangeLocation: () -> Unit,
    onOpenPlant: (String) -> Unit,
    modifier: Modifier = Modifier,
    // Held by the caller, so the list and its title keep their place while a plant page is open over them.
    listState: LazyListState = rememberLazyListState(),
    barState: TopAppBarState = rememberTopAppBarState(),
) {
    val data by model.data.collectAsStateWithLifecycle()
    val location by model.location.collectAsStateWithLifecycle()
    val saved by model.saved.collectAsStateWithLifecycle()
    val choices = model.plantsChoices
    val filter = choices.filter
    var showingFilters by rememberSaveable { mutableStateOf(false) }
    val large = isLargeText()
    val focus = LocalFocusManager.current

    val st = remember(filter, location) { state(filter, location) }
    val shown = remember(st, data) { PlantList.sorted(data.plants.filter { PlantList.passes(it, st, data) }, st, data) }
    // The website asks for the "Also matching" plants only when the search box is not empty.
    val hidden = remember(st, data, shown) {
        if (st.search.isEmpty()) HiddenMatches(emptyList(), emptyList()) else PlantList.hidden(shown, st, data)
    }
    val nativeCount = remember(data, location) { countNative(data, location) }

    // results_shown with the number of plants the list shows, as showLocation sends it, and empty_results when no
    // plant is native to the county. Once for each county.
    LaunchedEffect(location?.countyFips) {
        val loc = location ?: return@LaunchedEffect
        if (choices.resultsShownFor == loc.countyFips) return@LaunchedEffect
        choices.resultsShownFor = loc.countyFips
        val now = state(choices.filter, loc)
        val count = data.plants.count { PlantList.passes(it, now, data) }
        model.track(
            "results_shown",
            mapOf("count" to EventValue.Number(count.toDouble()), "ecoregion" to EventValue.Text(loc.ecoregion)),
        )
        if (countNative(data, loc) == 0) {
            model.track(
                "empty_results",
                mapOf("ecoregion" to EventValue.Text(loc.ecoregion), "state" to EventValue.Text(loc.stateUsps)),
            )
        }
    }

    // plant_search, one second after typing stops, as the website sends it after its pause.
    LaunchedEffect(filter.search) {
        delay(1000)
        sendSearchEvent(model, choices, location)
    }

    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior(barState)
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = Brand.ground,
        topBar = {
            // A small bar with the title, the way Garden has it. It slides off the top as the list scrolls and comes back
            // when the list scrolls down, so on a small phone it does not hold the room of a plant row.
            TopAppBar(
                title = { Text(stringResource(R.string.tab_plants), modifier = Modifier.testTag("title")) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Brand.ground,
                    scrolledContainerColor = Brand.ground,
                    titleContentColor = Brand.ink,
                ),
                scrollBehavior = scroll,
            )
        },
    ) { inner ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag("plantList"),
            contentPadding = PaddingValues(top = inner.calculateTopPadding(), bottom = inner.calculateBottomPadding() + 24.dp),
        ) {
            location?.let { loc ->
                item(key = "head-county") { County(loc, onChangeLocation) }
                val coverage = coverageLine(loc, nativeCount, data, model.tables)
                if (coverage.isNotEmpty()) {
                    item(key = "head-coverage") {
                        Text(
                            coverage,
                            style = TextStyle(fontFamily = Figtree, fontSize = 15.sp, lineHeight = 21.sp, color = Brand.inkSoft),
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp).testTag("coverageLine"),
                        )
                    }
                }
            }
            item(key = "head-search") {
                SearchField(
                    query = filter.search,
                    placeholder = stringResource(R.string.search_plants, nativeCount),
                    onQuery = { choices.filter = choices.filter.copy(search = it) },
                    onSearch = {
                        focus.clearFocus()
                        sendSearchEvent(model, choices, location)
                    },
                )
            }
            if (!isOnline) item(key = "head-offline") { OfflineBanner() }
            item(key = "head-chips") {
                TypeChips(
                    st = st,
                    data = data,
                    panel = panelChoices(filter),
                    large = large,
                    onFilters = { showingFilters = true },
                    onCategory = { choices.filter = choices.filter.copy(category = it) },
                )
            }
            item(key = "head-count") {
                ListHead(shown.size, large, filter.sort) { choices.filter = choices.filter.copy(sort = it) }
            }
            items(shown, key = { it.id }) { plant ->
                PlantRow(
                    plant = plant,
                    isSaved = plant.id in saved,
                    photos = model.photos,
                    largeText = large,
                    onOpen = {
                        // The keyboard closes, so it does not cover the plant page.
                        focus.clearFocus()
                        onOpenPlant(plant.id)
                    },
                    onSave = { model.pressSave(plant.id) },
                )
            }
            item(key = "also") { AlsoMatching(hidden, st.location) }
            if (shown.isEmpty()) {
                item(key = "empty") { EmptyText(st, hidden.county.size + hidden.filters.size) }
            }
        }
    }

    if (showingFilters) {
        FiltersSheet(
            filter = filter,
            onChange = { choices.filter = it },
            location = location,
            count = { draft -> data.plants.count { PlantList.passes(it, draft.copy(location = location), data) } },
            onClose = { showingFilters = false },
        )
    }
}

/** The choices with the chosen county, as the website's `state` holds them. Without a county, buy choices are off. */
private fun state(filter: FilterState, location: Location?): FilterState =
    filter.copy(location = location, buy = if (location == null) emptySet() else filter.buy)

/** The plants native to the county, with no filter: the number in the search prompt and the coverage line. */
private fun countNative(data: AppData, location: Location?): Int {
    val loc = location ?: return data.plants.size
    return data.plants.count { LocationLookup.nativeHere(it.id, loc, data.geo) }
}

/**
 * The line under the county, as showLocation in the website picks it: the "None of our plants" line when no plant is
 * native there, else the coverage text, which is empty for a region the website serves.
 */
private fun coverageLine(loc: Location, nativeCount: Int, data: AppData, tables: Tables): String {
    if (nativeCount == 0) {
        // A missing name reads "undefined" on the website; no ecoregion the tables hold lacks one.
        return "None of our plants are recorded as native to ${tables.ecoregionNames[loc.ecoregion] ?: "undefined"} yet."
    }
    return LocationLookup.coverageText(nativeCount, data.served.contains(loc.ecoregion))
}

/**
 * sendSearchEvent: the number of plants shown and the number hidden, never the typed text. Nothing is sent for an
 * empty search or for the same search sent last.
 */
private fun sendSearchEvent(model: AppModel, choices: PlantsChoices, location: Location?) {
    val q = PlantList.normalizeName(choices.filter.search)
    if (q.isEmpty() || q == choices.lastSentSearch) return
    choices.lastSentSearch = q
    val data = model.data.value
    val st = state(choices.filter, location)
    val shown = PlantList.sorted(data.plants.filter { PlantList.passes(it, st, data) }, st, data)
    val hidden = PlantList.hidden(shown, st, data)
    model.track(
        "plant_search",
        mapOf(
            "count" to EventValue.Number(shown.size.toDouble()),
            "hidden_count" to EventValue.Number((hidden.county.size + hidden.filters.size).toDouble()),
        ),
    )
}

/** The county in moss, after the map pin. A tap opens Change location. */
@Composable
private fun County(loc: Location, onChangeLocation: () -> Unit) {
    CountyLine(color = Brand.moss, size = 17.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.change_location), onClick = onChangeLocation)
                .testTag("plantsCounty"),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                "${loc.countyName}, ${loc.stateName}",
                style = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = 17.sp, color = Brand.moss),
            )
        }
    }
}

/** The phone's own search field, with the magnifying glass, as Material draws it. */
@Composable
private fun SearchField(query: String, placeholder: String, onQuery: (String) -> Unit, onSearch: () -> Unit) {
    Surface(
        shape = SearchBarDefaults.inputFieldShape,
        color = Brand.surface,
        // 4 dp above and below (DESIGN.md xs): the field is 56 dp tall, so it keeps its tap area.
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        SearchBarDefaults.InputField(
            query = query,
            onQueryChange = onQuery,
            onSearch = { onSearch() },
            expanded = false,
            onExpandedChange = {},
            placeholder = { Text(placeholder, style = TextStyle(fontFamily = Figtree, fontSize = 16.sp, color = Brand.inkSoft)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Brand.inkSoft) },
            modifier = Modifier.fillMaxWidth().testTag("search"),
        )
    }
}

/**
 * Filters, then All and one chip per plant type, each with how many of the plants that pass the other choices are of
 * that type. The chips choose types as the website's plant type boxes do: any number at once, All for none.
 */
@Composable
private fun TypeChips(
    st: FilterState,
    data: AppData,
    panel: Int,
    large: Boolean,
    onFilters: () -> Unit,
    onCategory: (Set<String>) -> Unit,
) {
    val others = st.copy(category = emptySet())
    val passing = remember(others, data) { data.plants.filter { PlantList.passes(it, others, data) } }
    val countOf = remember(passing) { PlantList.typeCounts(passing).associate { it.category to it.count } }
    // A chosen type stays as a chip even when none of its plants pass, counting 0, so it can still be turned off.
    val counts = PlantList.categoryOrder.mapNotNull { c ->
        countOf[c]?.let { c to it } ?: if (c in st.category) c to 0 else null
    }
    val iconSize = with(LocalDensity.current) { 18.sp.toDp() }
    Box {
        // One row that scrolls sideways at every text size, as on the iPhone; it never wraps into a column.
        ChipRow(false) {
            BrandChip(
                label = stringResource(R.string.filters),
                count = if (panel == 0) null else panel,
                selected = panel > 0,
                spokenValue = if (panel == 0) null else stringResource(R.string.n_chosen, panel),
                onClick = onFilters,
                icon = { Icon(Icons.Filled.FilterList, contentDescription = null, modifier = Modifier.size(iconSize)) },
                modifier = Modifier.testTag("filtersChip"),
            )
            BrandChip(
                label = stringResource(R.string.all),
                count = passing.size,
                selected = st.category.isEmpty(),
                onClick = { onCategory(emptySet()) },
                modifier = Modifier.testTag("chip-all"),
            )
            for ((category, n) in counts) {
                val on = category in st.category
                BrandChip(
                    label = plural(category),
                    count = n,
                    selected = on,
                    onClick = { onCategory(if (on) st.category - category else st.category + category) },
                    modifier = Modifier.testTag("chip-$category"),
                )
            }
        }
    }
}

/** "122 native plants" and the sort menu, side by side, or one above the other at the largest text sizes. */
@Composable
private fun ListHead(count: Int, large: Boolean, sort: SortKey, onSort: (SortKey) -> Unit) {
    val words: @Composable () -> Unit = {
        Text(
            "$count native ${if (count == 1) "plant" else "plants"}",
            style = TextStyle(fontFamily = Figtree, fontSize = 15.sp, color = Brand.inkSoft),
            modifier = Modifier.testTag("plantCount"),
        )
    }
    if (large) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp)) {
            words()
            SortMenu(sort, onSort)
        }
    } else {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            words()
            Spacer(Modifier.weight(1f))
            SortMenu(sort, onSort)
        }
    }
}

/**
 * The website's empty-results text, word for word. It is not shown when the search matches plants that are hidden,
 * because the "Also matching" lines say where they are.
 */
@Composable
private fun EmptyText(st: FilterState, hiddenCount: Int) {
    val query = st.search.trim()
    if (query.isNotEmpty() && hiddenCount > 0) return
    Text(
        if (query.isNotEmpty()) "No plants match “$query”." else "No plants match these filters. Try turning one off.",
        style = TextStyle(fontFamily = Figtree, fontSize = 17.sp, lineHeight = 24.sp, color = Brand.inkSoft, textAlign = TextAlign.Center),
        modifier = Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 32.dp).testTag("emptyResults"),
    )
}

/** The chip names: "Trees", "Shrubs", "Grasses" and so on, one for each type in the website's type order. */
internal fun plural(category: String): String = when (category) {
    "tree" -> "Trees"
    "shrub" -> "Shrubs"
    "perennial" -> "Perennials"
    "grass" -> "Grasses"
    "fern" -> "Ferns"
    "vine" -> "Vines"
    "groundcover" -> "Groundcovers"
    else -> Labels.category(category)
}
