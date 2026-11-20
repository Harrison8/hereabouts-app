// The three tabs at the bottom: Plants, Garden and Settings, with the phone's own tab bar and Apple's icons. Each tab
// is its own navigation stack, titled with the tab's name. The app opens on Plants.
import SwiftUI

struct RootTabs: View {
    enum TabID: Hashable {
        case plants, garden, settings
    }

    @State private var selection: TabID = .plants

    var body: some View {
        TabView(selection: $selection) {
            PlantNames()
                .tabItem { Label("Plants", systemImage: "leaf") }
                .tag(TabID.plants)
            TabScreen(title: "Garden")
                .tabItem { Label("Garden", systemImage: "bookmark") }
                .tag(TabID.garden)
            TabScreen(title: "Settings")
                .tabItem { Label("Settings", systemImage: "gearshape") }
                .tag(TabID.settings)
        }
    }
}

/// The Plants tab until its real screen is built: the name of every plant in the data the app is using, so a change
/// of data shows at once.
private struct PlantNames: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        NavigationStack {
            List(model.data.plants) { plant in
                Text(plant.common)
            }
            .navigationTitle("Plants")
        }
    }
}

/// One tab's screen: for now only its title, on the white ground.
private struct TabScreen: View {
    let title: String

    var body: some View {
        NavigationStack {
            Color.white
                .ignoresSafeArea(edges: .bottom)
                .navigationTitle(title)
        }
    }
}
