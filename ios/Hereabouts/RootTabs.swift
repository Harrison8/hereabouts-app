// The three tabs at the bottom: Plants, Garden and Settings, with the phone's own tab bar and Apple's icons. Each tab
// is its own navigation stack. The app opens on Plants; a tapped reminder, or the Garden tab's Browse plants button,
// changes the tab through the model.
import SwiftUI

struct RootTabs: View {
    enum TabID: Hashable {
        case plants, garden, settings
    }

    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        TabView(selection: $model.selectedTab) {
            PlantsView()
                .tabItem { Label("Plants", systemImage: "leaf") }
                .tag(TabID.plants)
            GardenView()
                .tabItem { Label("Garden", systemImage: "bookmark") }
                .tag(TabID.garden)
            SettingsView()
                .tabItem { Label("Settings", systemImage: "gearshape") }
                .tag(TabID.settings)
        }
    }
}
