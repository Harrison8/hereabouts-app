// The Plants tab, as the approved picture (d1-plants.png) draws it: the title "Plants" with the county under it (a tap
// opens "Change location"), the phone's own search field, the no-signal banner when there is no signal, the type chips
// with their counts, the number of plants and the sort menu, then one row per plant, the "Also matching" lines and the
// website's empty-results text. The list is the website's own: the same filters, sorts and search, from HereaboutsCore.
//
// Events, as the website sends them: results_shown once each time the county changes (with empty_results when no plant
// is native there), and plant_search one second after typing stops, or on Search, with the two counts only, never the
// typed text.
import HereaboutsCore
import SwiftUI

struct PlantsView: View {
    @Environment(AppModel.self) private var model
    @Environment(Connectivity.self) private var connectivity
    @Environment(\.dynamicTypeSize) private var typeSize

    /// The person's choices: filters, sort and search. The county comes from the model.
    @State private var filter = FilterState()
    @State private var path: [String] = []
    @State private var showingFilters = false
    @State private var changingLocation = false
    @State private var searchTimer: Task<Void, Never>?
    /// The last search sent as plant_search, normalised, so the same search is not sent twice (lastSentSearch).
    @State private var lastSentSearch = ""

    /// The choices with the chosen county, as the website's `state` holds them.
    private var state: FilterState {
        var st = filter
        st.location = model.location
        if model.location == nil { st.buy = [] }
        return st
    }

    var body: some View {
        let st = state
        let data = model.data
        let shown = PlantList.sorted(data.plants.filter { PlantList.passes($0, st, data: data) }, st, data: data)
        // The website asks for the "Also matching" plants only when the search box is not empty.
        let hidden = st.search.isEmpty ? (county: [Plant](), filters: [Plant]()) : PlantList.hidden(shown, st, data: data)
        let nativeCount = countNative(data)

        NavigationStack(path: $path) {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    header(nativeCount: nativeCount, shownCount: shown.count, st: st, data: data)
                    ForEach(shown) { plant in
                        PlantRow(plant: plant, isSaved: model.isSaved(plant.id), photos: model.photos,
                                 open: { path.append(plant.id) }, toggleSave: { model.pressSave(plant.id) })
                        Divider().overlay(Theme.rule).padding(.leading, 16)
                    }
                    AlsoMatching(hidden: hidden, location: st.location)
                    if shown.isEmpty {
                        emptyText(st: st, hiddenCount: hidden.county.count + hidden.filters.count)
                    }
                }
                .padding(.bottom, 24)
            }
            .scrollDismissesKeyboard(.immediately)
            .background(Color.white)
            .navigationTitle("Plants")
            .searchable(text: $filter.search, placement: .navigationBarDrawer(displayMode: .always),
                        prompt: Text("Search \(nativeCount) plants"))
            .onSubmit(of: .search) { sendSearchEvent() }
            .navigationDestination(for: String.self) { id in
                if let plant = data.plants.first(where: { $0.id == id }) {
                    PlantPage(plant: plant)
                }
            }
        }
        .onChange(of: filter.search) { _, _ in
            // Sent one second after typing stops, as the website sends it after its pause.
            searchTimer?.cancel()
            searchTimer = Task { @MainActor in
                try? await Task.sleep(for: .seconds(1))
                if !Task.isCancelled { sendSearchEvent() }
            }
        }
        .onChange(of: model.location?.countyFips, initial: true) { _, _ in
            resultsShown()
        }
        .sheet(isPresented: $showingFilters) {
            FiltersSheet(filter: $filter, location: model.location, count: { draft in
                var st = draft
                st.location = model.location
                return data.plants.filter { PlantList.passes($0, st, data: data) }.count
            })
        }
        .sheet(isPresented: $changingLocation) {
            NavigationStack {
                FirstOpenView(onChosen: { changingLocation = false }, onCancel: { changingLocation = false })
                    .navigationTitle("Change location")
                    .navigationBarTitleDisplayMode(.inline)
            }
        }
        // A tapped reminder closes every open sheet (AppModel.openedFromNotification).
        .onChange(of: model.closeSheetsCount) { _, _ in
            showingFilters = false
            changingLocation = false
        }
    }

    // MARK: - the parts above the rows

    @ViewBuilder
    private func header(nativeCount: Int, shownCount: Int, st: FilterState, data: AppData) -> some View {
        if let loc = model.location {
            CountyLine(color: Theme.moss, weight: .medium) {
                Button {
                    changingLocation = true
                } label: {
                    Text("\(loc.countyName), \(loc.stateName)")
                        .font(.body(17, .medium, relativeTo: .body))
                        .foregroundStyle(Theme.moss)
                        .multilineTextAlignment(.leading)
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("plantsCounty")
                .accessibilityHint("Change location")
            }
            .padding(.horizontal, 16)

            let coverage = LocationLookup.coverageLine(loc, nativeCount: nativeCount, served: data.served,
                                                       ecoregionNames: model.tables.ecoregionNames)
            if !coverage.isEmpty {
                Text(coverage)
                    .font(.body(.subheadline))
                    .foregroundStyle(Theme.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 4)
                    .accessibilityIdentifier("coverageLine")
            }
        }
        if connectivity.isOffline {
            OfflineBanner()
        }
        typeChips(st: st, data: data)
        listHead(shownCount: shownCount)
    }

    /// Filters, then All and one chip per plant type, each with how many of the plants that pass the other choices are
    /// of that type. The chips choose types as the website's plant type boxes do: any number at once, All for none.
    private func typeChips(st: FilterState, data: AppData) -> some View {
        var others = st
        others.category = []
        let passing = data.plants.filter { PlantList.passes($0, others, data: data) }
        // A chosen type stays as a chip even when none of its plants pass, counting 0, so it can still be turned off.
        var countOf: [String: Int] = [:]
        for row in PlantList.typeCounts(passing) { countOf[row.category] = row.count }
        let counts = PlantList.categoryOrder.compactMap { c -> (category: String, count: Int)? in
            if let n = countOf[c] { return (category: c, count: n) }
            return filter.category.contains(c) ? (category: c, count: 0) : nil
        }
        let panelChoices = filterCount
        return ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                Chip(label: "Filters", count: panelChoices == 0 ? nil : panelChoices, symbol: "line.3.horizontal.decrease",
                     isOn: panelChoices > 0, spokenValue: panelChoices == 0 ? "" : "\(panelChoices) chosen") {
                    showingFilters = true
                }
                .accessibilityIdentifier("filtersChip")
                Chip(label: "All", count: passing.count, isOn: filter.category.isEmpty) {
                    filter.category = []
                }
                .accessibilityIdentifier("chip-all")
                ForEach(counts, id: \.category) { row in
                    Chip(label: Self.plural(row.category), count: row.count, isOn: filter.category.contains(row.category)) {
                        if filter.category.contains(row.category) {
                            filter.category.remove(row.category)
                        } else {
                            filter.category.insert(row.category)
                        }
                    }
                    .accessibilityIdentifier("chip-\(row.category)")
                }
            }
            .padding(.horizontal, 16)
        }
        .padding(.top, 4)
    }

    /// How many choices are made in the filters panel, for the Filters chip's spoken value.
    private var filterCount: Int {
        filter.sun.count + filter.moisture.count + filter.wildlife.count + filter.zones.count + filter.bloomMonth.count
            + filter.bloomColor.count + filter.buy.count + (filter.maxHeight < 100 ? 1 : 0)
    }

    /// "122 native plants" and the sort menu, side by side, or one above the other at the largest text sizes.
    private func listHead(shownCount: Int) -> some View {
        let count = Text("\(shownCount) native \(shownCount == 1 ? "plant" : "plants")")
            .font(.body(.subheadline))
            .foregroundStyle(Theme.inkSoft)
        let layout = typeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 0))
            : AnyLayout(HStackLayout(alignment: .center, spacing: 8))
        return layout {
            count
            if !typeSize.isAccessibilitySize { Spacer(minLength: 8) }
            SortMenu(sort: $filter.sort)
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
    }

    /// The website's empty-results text, word for word. It is not shown when the search matches plants that are
    /// hidden, because the "Also matching" lines say where they are.
    @ViewBuilder
    private func emptyText(st: FilterState, hiddenCount: Int) -> some View {
        let query = st.search.trimmingCharacters(in: .whitespacesAndNewlines)
        if query.isEmpty || hiddenCount == 0 {
            Text(!query.isEmpty && hiddenCount == 0
                 ? "No plants match \u{201C}\(query)\u{201D}."
                 : "No plants match these filters. Try turning one off.")
                .font(.body(.body))
                .foregroundStyle(Theme.inkSoft)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 32)
                .padding(.top, 32)
                .accessibilityIdentifier("emptyResults")
        }
    }

    // MARK: - what the screen does

    /// The plants native to the county, with no filter: the number in the search prompt and the coverage line.
    private func countNative(_ data: AppData) -> Int {
        guard let loc = model.location else { return data.plants.count }
        return data.plants.filter { LocationLookup.nativeHere($0.id, loc, geo: data.geo) }.count
    }

    /// results_shown with the number of plants the list shows, as showLocation sends it, and empty_results when no
    /// plant is native to the county.
    private func resultsShown() {
        guard let loc = model.location else { return }
        let data = model.data
        let st = state
        let count = data.plants.filter { PlantList.passes($0, st, data: data) }.count
        model.track("results_shown", ["count": .number(Double(count)), "ecoregion": .string(loc.ecoregion)])
        if countNative(data) == 0 {
            model.track("empty_results", ["ecoregion": .string(loc.ecoregion), "state": .string(loc.stateUsps)])
        }
    }

    /// sendSearchEvent: the number of plants shown and the number hidden, never the typed text. Nothing is sent for
    /// an empty search or for the same search sent last.
    private func sendSearchEvent() {
        searchTimer?.cancel()
        let q = PlantList.normalizeName(filter.search)
        guard !q.isEmpty, q != lastSentSearch else { return }
        lastSentSearch = q
        let st = state
        let data = model.data
        let shown = PlantList.sorted(data.plants.filter { PlantList.passes($0, st, data: data) }, st, data: data)
        let hidden = PlantList.hidden(shown, st, data: data)
        model.track("plant_search", ["count": .number(Double(shown.count)),
                                     "hidden_count": .number(Double(hidden.county.count + hidden.filters.count))])
    }

    /// The chip names: "Trees", "Shrubs", "Grasses" and so on, one for each type in PlantList.categoryOrder.
    static func plural(_ category: String) -> String {
        switch category {
        case "tree": "Trees"
        case "shrub": "Shrubs"
        case "perennial": "Perennials"
        case "grass": "Grasses"
        case "fern": "Ferns"
        case "vine": "Vines"
        case "groundcover": "Groundcovers"
        default: Labels.category(category)
        }
    }
}

/// A chip: the brand's bordered label, moss when chosen, with an optional count in the softer colour. The whole cell,
/// at least 44 points high, answers a tap, though the drawn chip is 36 points.
struct Chip: View {
    let label: String
    var count: Int? = nil
    var symbol: String? = nil
    let isOn: Bool
    var spokenLabel: String? = nil
    /// What a screen reader says after the label; the count when not given.
    var spokenValue: String? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                if let symbol {
                    Image(systemName: symbol)
                        .font(.body(15, .medium, relativeTo: .subheadline))
                        .accessibilityHidden(true)
                }
                Text(label)
                if let count {
                    Text("\(count)")
                        .foregroundStyle(isOn ? Theme.mossSoft : Theme.inkSoft)
                }
            }
            .font(.body(15, .medium, relativeTo: .subheadline))
            .foregroundStyle(isOn ? Theme.onMoss : Theme.ink)
            .padding(.horizontal, 12)
            .frame(minHeight: 36)
            .background(isOn ? Theme.moss : Color.white, in: RoundedRectangle(cornerRadius: Theme.brandCorner))
            .overlay(RoundedRectangle(cornerRadius: Theme.brandCorner)
                .strokeBorder(isOn ? Theme.moss : Theme.ruleStrong, lineWidth: 1))
            .padding(.vertical, 4)
            .frame(minWidth: 44, minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(spokenLabel ?? label)
        .accessibilityValue(spokenValue ?? count.map { "\($0)" } ?? "")
        .accessibilityAddTraits(isOn ? .isSelected : [])
    }
}
