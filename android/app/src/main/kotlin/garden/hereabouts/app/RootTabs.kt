// The three tabs at the bottom: Plants, Garden and Settings, with Material's navigation bar and icons. The top bar
// shows the open tab's name in Young Serif. The app opens on Plants, and the open tab survives a screen rotation.
// Until the Plants screen is built, the Plants tab lists the plant names from the data in use, one per line.
@file:OptIn(ExperimentalMaterial3Api::class)

package garden.hereabouts.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

enum class Tab(val title: String, val icon: ImageVector) {
    Plants("Plants", Icons.Outlined.Eco),
    Garden("Garden", Icons.Outlined.BookmarkBorder),
    Settings("Settings", Icons.Outlined.Settings),
}

@Composable
fun RootTabs(model: AppModel) {
    var open by rememberSaveable { mutableStateOf(Tab.Plants) }

    Scaffold(
        containerColor = Brand.ground,
        topBar = {
            TopAppBar(
                title = { Text(open.title, modifier = Modifier.testTag("title")) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Brand.ground,
                    scrolledContainerColor = Brand.ground,
                    titleContentColor = Brand.ink,
                ),
            )
        },
        bottomBar = {
            NavigationBar(modifier = Modifier.testTag("tabs")) {
                for (tab in Tab.entries) {
                    NavigationBarItem(
                        selected = tab == open,
                        onClick = { open = tab },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.title) },
                        modifier = Modifier.testTag("tab-${tab.title}"),
                    )
                }
            }
        },
    ) { padding ->
        // Each tab's screen: for now the Plants tab lists plant names, and the others show only their title.
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (open == Tab.Plants) PlantNames(model)
        }
    }
}

/** Stands in for the Plants screen: every plant's common name, in the order of the data in use. */
@Composable
private fun PlantNames(model: AppModel) {
    val data by model.data.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        for (plant in data.plants) {
            Text(plant.common, color = Brand.ink, modifier = Modifier.testTag("plant-${plant.id}"))
        }
    }
}
