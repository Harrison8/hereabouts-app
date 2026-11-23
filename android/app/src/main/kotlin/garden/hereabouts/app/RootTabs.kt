// The three tabs at the bottom: Plants, Garden and Settings, with Material's navigation bar and icons. The top bar
// shows the open tab's title in Young Serif ("My garden" on the Garden tab); the Plants tab draws its own large title,
// which shrinks as its list scrolls. The app opens on Plants, and the open tab survives a screen rotation. A plant
// opens over the list of the tab it was tapped on, with the tab bar still under it; Back returns to the list where it
// was. Change location (from Settings or from the county on Plants) and the photo credits open as full pages over the
// tabs, and Back returns to the tab. A tapped reminder opens the Garden tab at the top, with nothing open over it.
@file:OptIn(ExperimentalMaterial3Api::class)

package garden.hereabouts.app

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import garden.hereabouts.app.garden.GardenScreen
import garden.hereabouts.app.plant.PlantScreen
import garden.hereabouts.app.plants.PlantsScreen
import garden.hereabouts.app.settings.ChangeLocationPage
import garden.hereabouts.app.settings.PhotoCreditsPage
import garden.hereabouts.app.settings.SettingsScreen

/** Each tab: its name in the tab bar, the title over its screen, and its icon. */
enum class Tab(@StringRes val title: Int, @StringRes val heading: Int, val icon: ImageVector) {
    Plants(R.string.tab_plants, R.string.tab_plants, Icons.Outlined.Eco),
    Garden(R.string.tab_garden, R.string.title_my_garden, Icons.Outlined.BookmarkBorder),
    Settings(R.string.tab_settings, R.string.tab_settings, Icons.Outlined.Settings),
}

/** The pages that open over the tabs. */
private enum class Page { ChangeLocation, PhotoCredits }

@Composable
fun RootTabs(model: AppModel, isOnline: Boolean) {
    var open by rememberSaveable { mutableStateOf(Tab.Plants) }
    var page by rememberSaveable { mutableStateOf<Page?>(null) }
    // The plant open on the Plants tab, by id, or null for the list.
    var openPlant by rememberSaveable { mutableStateOf<String?>(null) }
    // Whether the open plant page has sent its two events, kept here so a trip to another tab does not send them again.
    var announced by rememberSaveable { mutableStateOf(false) }
    // The list's place and its title's size, kept here so they last while a plant page or another tab is open.
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val barState = rememberSaveable(saver = TopAppBarState.Saver) { TopAppBarState(-Float.MAX_VALUE, 0f, 0f) }
    val data by model.data.collectAsStateWithLifecycle()
    val plant = remember(openPlant, data) { openPlant?.let { id -> data.plants.firstOrNull { it.id == id } } }
    // The same for the Garden tab: the plant open over its list, whether that page has sent its events, the job whose
    // sheet is open, by rule id, and the list's place.
    var gardenPlantId by rememberSaveable { mutableStateOf<String?>(null) }
    var gardenAnnounced by rememberSaveable { mutableStateOf(false) }
    var openJob by rememberSaveable { mutableStateOf<String?>(null) }
    val gardenScroll = rememberScrollState()
    val gardenPlant = remember(gardenPlantId, data) { gardenPlantId?.let { id -> data.plants.firstOrNull { it.id == id } } }

    // A tapped reminder: the Garden tab at the top, with no page, plant or job sheet over it.
    val gardenRequest by model.gardenRequest.collectAsStateWithLifecycle()
    LaunchedEffect(gardenRequest) {
        if (!gardenRequest) return@LaunchedEffect
        page = null
        open = Tab.Garden
        gardenPlantId = null
        openJob = null
        gardenScroll.scrollTo(0)
        model.gardenShown()
    }

    when (page) {
        Page.ChangeLocation -> {
            ChangeLocationPage(model, onClose = { page = null })
            return
        }
        Page.PhotoCredits -> {
            PhotoCreditsPage(model, onBack = { page = null })
            return
        }
        null -> {}
    }

    // Garden sits on the pale surface (its cards stay white); the other tabs sit on white.
    val ground = if (open == Tab.Garden && gardenPlant == null) Brand.surface else Brand.ground
    Scaffold(
        containerColor = ground,
        topBar = {
            if (open == Tab.Settings || (open == Tab.Garden && gardenPlant == null)) TopAppBar(
                title = { Text(stringResource(open.heading), modifier = Modifier.testTag("title")) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ground,
                    scrolledContainerColor = ground,
                    titleContentColor = Brand.ink,
                ),
            )
        },
        bottomBar = {
            NavigationBar(modifier = Modifier.testTag("tabs")) {
                for (tab in Tab.entries) {
                    NavigationBarItem(
                        selected = tab == open,
                        onClick = {
                            // The Plants tab, tapped again on a plant page, goes back to the list.
                            if (tab == Tab.Plants && open == Tab.Plants) openPlant = null
                            open = tab
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.title)) },
                        modifier = Modifier.testTag("tab-${tab.name}"),
                    )
                }
            }
        },
    ) { padding ->
        // Each tab's screen. The Plants tab, and a plant page on the Garden tab, keep their own top bar, so they take
        // only the room above the tab bar, and draw under the status bar themselves.
        val ownTop = open == Tab.Plants || (open == Tab.Garden && gardenPlant != null)
        val room = if (ownTop) PaddingValues(bottom = padding.calculateBottomPadding()) else padding
        Box(Modifier.fillMaxSize().padding(room).consumeWindowInsets(room)) {
            when (open) {
                Tab.Plants -> if (plant != null) {
                    PlantScreen(
                        model,
                        plant,
                        isOnline,
                        announced = announced,
                        onAnnounced = { announced = true },
                        onBack = { openPlant = null },
                    )
                } else {
                    PlantsScreen(
                        model,
                        isOnline,
                        onChangeLocation = { page = Page.ChangeLocation },
                        onOpenPlant = {
                            announced = false
                            openPlant = it
                        },
                        listState = listState,
                        barState = barState,
                    )
                }
                Tab.Garden -> if (gardenPlant != null) {
                    PlantScreen(
                        model,
                        gardenPlant,
                        isOnline,
                        announced = gardenAnnounced,
                        onAnnounced = { gardenAnnounced = true },
                        onBack = { gardenPlantId = null },
                    )
                } else {
                    GardenScreen(
                        model,
                        scroll = gardenScroll,
                        swipeable = true,
                        openJob = openJob,
                        onOpenJob = { openJob = it },
                        onOpenPlant = {
                            gardenAnnounced = false
                            gardenPlantId = it
                        },
                        onBrowsePlants = { open = Tab.Plants },
                    )
                }
                Tab.Settings -> SettingsScreen(
                    model,
                    onChangeLocation = { page = Page.ChangeLocation },
                    onPhotoCredits = { page = Page.PhotoCredits },
                )
            }
        }
    }
}
