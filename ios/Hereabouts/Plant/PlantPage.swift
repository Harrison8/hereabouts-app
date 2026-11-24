// One plant, as plant-page-top.png and plant-page-scrolled.png draw it: the photos; the name, the Latin name and
// "Shrub · Native to Fairfax County, Virginia"; Save and Where to buy; the four facts in the ruled box and the plant's
// note; "Through the year"; flowers, fall colour, growth and what it feeds; the Where to buy row; "How to grow it";
// "Where it grows wild" with the USDA link. Every word is the website's, from HereaboutsCore. Where to buy opens as a
// sheet over the page.
import HereaboutsCore
import SwiftUI

struct PlantPage: View {
    let plant: Plant

    @Environment(AppModel.self) private var model
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.openURL) private var openURL
    @State private var buying = false
    @State private var sentOpenEvents = false

    var body: some View {
        let location = model.location
        let buy = Sellers.buyView(plant, location, channel: .app, data: model.data)
        let facts = Labels.growingFacts(plant)
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                PhotoPager(plant: plant, photos: model.photos)
                VStack(alignment: .leading, spacing: 0) {
                    title(location: location)
                    buttons
                        .padding(.top, 16)
                    if !facts.quick.isEmpty {
                        FactGrid(facts: facts.quick)
                            .padding(.top, 16)
                    }
                    if let notes = plant.notes, !notes.isEmpty {
                        paragraph(notes)
                            .padding(.top, 24)
                    }
                    heading("Through the year")
                    SeasonChart(plant: plant, now: Calendar.current.component(.month, from: model.now) - 1)
                    if !(facts.flowers.isEmpty && facts.more.isEmpty) {
                        FactList(facts: facts.flowers + facts.more)
                            .padding(.top, 24)
                    }
                    buyRow(buy, location: location)
                        .padding(.top, 16)
                    if let care = plant.maturity?.notes, !care.isEmpty {
                        heading("How to grow it")
                        paragraph(care)
                    }
                    if let geo = model.data.geo[plant.id] {
                        heading("Where it grows wild")
                        RangeMapView(plant: plant, native: geo.eco, location: location)
                            .padding(.top, 4)
                    }
                    if let usda = plant.usda, !usda.isEmpty {
                        usdaLine(usda)
                            .padding(.top, 8)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 12)
                .padding(.bottom, 32)
            }
        }
        .background(Theme.packet)
        .navigationTitle(plant.common)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { model.pressSave(plant.id) } label: {
                    Image(systemName: model.isSaved(plant.id) ? "bookmark.fill" : "bookmark")
                }
                .accessibilityLabel("Save \(plant.common)")
                .accessibilityValue(model.isSaved(plant.id) ? "Saved" : "Not saved")
                .accessibilityIdentifier("toolbarSave")
            }
        }
        .sheet(isPresented: $buying) {
            WhereToBuySheet(plant: plant, view: buy, location: location)
                .capturingLinksForTests()
        }
        // A tapped reminder closes every open sheet (AppModel.openedFromNotification).
        .onChange(of: model.closeSheetsCount) { _, _ in
            buying = false
        }
        // The website sends these two when a plant page opens (index.html, after renderPlantPage), and the founder's
        // popular-plants count reads buy_panel_open as "someone looked at this plant". So the app sends them when the
        // page opens, not when the sheet does, and once per opening: coming back from another tab does not resend.
        .onAppear {
            guard !sentOpenEvents else { return }
            sentOpenEvents = true
            model.track("buy_panel_open", ["plant": .string(plant.id)])
            if buy.noSellerText != nil, let location {
                model.track("no_seller_shown", ["plant": .string(plant.id), "state": .string(location.stateUsps)])
            }
        }
    }

    // MARK: Parts

    private func title(location: Location?) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(plant.common)
                .font(.display(.largeTitle))
                .foregroundStyle(Theme.ink)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            Text(plant.sci)
                .font(.bodySlanted(.title3, typeSize: typeSize))
                .foregroundStyle(Theme.inkSoft)
                .fixedSize(horizontal: false, vertical: true)
            Text(Self.meta(plant, location: location, geo: model.data.geo))
                .font(.body(15, .medium, relativeTo: .subheadline))
                .foregroundStyle(Theme.ink)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    /// The website's line under the Latin name: "Shrub · Native to Fairfax County, Virginia", "Not native to …", or
    /// only the type when no county is set.
    static func meta(_ plant: Plant, location: Location?, geo: [String: PlantGeo]) -> String {
        let type = Labels.category(plant.category)
        guard let location else { return type }
        let native = LocationLookup.nativeHere(plant.id, location, geo: geo)
        return "\(type) \u{00B7} \(native ? "Native" : "Not native") to \(location.countyName), \(location.stateName)"
    }

    /// Save and Where to buy, side by side; one above the other at the largest text sizes.
    private var buttons: some View {
        let saved = model.isSaved(plant.id)
        let layout = typeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(spacing: 12))
            : AnyLayout(HStackLayout(spacing: 12))
        return layout {
            Button { model.pressSave(plant.id) } label: {
                Label(saved ? "Saved" : "Save", systemImage: saved ? "bookmark.fill" : "bookmark")
                    .font(.body(17, .semibold, relativeTo: .body))
                    .foregroundStyle(Theme.moss)
                    .frame(maxWidth: .infinity, minHeight: 50)
                    .overlay(RoundedRectangle(cornerRadius: Theme.brandCorner).strokeBorder(Theme.moss, lineWidth: 1))
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Save \(plant.common)")
            .accessibilityValue(saved ? "Saved" : "Not saved")
            .accessibilityIdentifier("pageSave")
            Button { buying = true } label: {
                Text("Where to buy")
                    .font(.body(17, .semibold, relativeTo: .body))
                    .foregroundStyle(Theme.onMoss)
                    .frame(maxWidth: .infinity, minHeight: 50)
                    .background(Theme.moss, in: RoundedRectangle(cornerRadius: Theme.brandCorner))
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("whereToBuy")
        }
    }

    /// The kraft row that also opens Where to buy, with how many sellers there are and the grey arrow at its right, as
    /// plant-page-scrolled.png draws it. With no seller it says the website card's "No seller listed yet".
    private func buyRow(_ buy: BuyView, location: Location?) -> some View {
        Button { buying = true } label: {
            HStack(spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Where to buy")
                        .font(.body(17, .semibold, relativeTo: .body))
                        .foregroundStyle(Theme.ink)
                    Text(Self.buySubtitle(buy, location: location))
                        .font(.body(14, .regular, relativeTo: .footnote))
                        .foregroundStyle(Theme.inkSoft)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "chevron.right")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Theme.ruleStrong)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
            .background(Theme.kraft, in: RoundedRectangle(cornerRadius: Theme.brandCorner))
            .overlay(RoundedRectangle(cornerRadius: Theme.brandCorner).strokeBorder(Theme.ruleStrong, lineWidth: 1))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("whereToBuyCard")
    }

    /// "7 sellers ship to Virginia", "1 seller near you · 6 sellers ship to Kentucky", or "No seller listed yet".
    static func buySubtitle(_ buy: BuyView, location: Location?) -> String {
        guard !buy.rows.isEmpty else { return "No seller listed yet" }
        let near = buy.rows.filter(\.local).count
        let ships = buy.rows.count - near
        guard let location else { return buy.rows.count == 1 ? "1 seller" : "\(buy.rows.count) sellers" }
        var parts: [String] = []
        if near > 0 { parts.append(near == 1 ? "1 seller near you" : "\(near) sellers near you") }
        if ships > 0 {
            parts.append(ships == 1 ? "1 seller ships to \(location.stateName)" : "\(ships) sellers ship to \(location.stateName)")
        }
        return parts.joined(separator: " \u{00B7} ")
    }

    /// The website's USDA line: the link, then the plant's USDA number and where the map's regions come from.
    private func usdaLine(_ usda: String) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Button {
                if let url = Self.usdaURL(usda) { openURL(url) }
            } label: {
                HStack(spacing: 4) {
                    Text("USDA plant profile").underline()
                    Image(systemName: "arrow.up.forward.square")
                }
                .font(.body(15, .medium, relativeTo: .subheadline))
                .foregroundStyle(Theme.moss)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("USDA plant profile")
            .accessibilityHint("Opens outside the app")
            .accessibilityIdentifier("usdaLink")
            Text("No. \(usda) \u{00B7} Regions from the US EPA")
                .font(.body(13, .regular, relativeTo: .footnote))
                .foregroundStyle(Theme.inkSoft)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    /// The website's usdaUrl: the USDA number escaped as JavaScript's encodeURIComponent escapes it.
    static func usdaURL(_ usda: String) -> URL? {
        let keep = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()")
        guard let id = usda.addingPercentEncoding(withAllowedCharacters: keep) else { return nil }
        return URL(string: "https://plants.sc.egov.usda.gov/plant-profile/\(id)")
    }

    private func heading(_ text: String) -> some View {
        Text(text)
            .font(.display(.title2))
            .foregroundStyle(Theme.ink)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityAddTraits(.isHeader)
            .padding(.top, 24)
            .padding(.bottom, 8)
    }

    /// The plant's note or its growing note, its ranges read with "to" ("3 to 5 ft stalks").
    private func paragraph(_ text: String) -> some View {
        Text(RangeText.display(text))
            .font(.body(17, .regular, relativeTo: .body))
            .foregroundStyle(Theme.ink)
            .lineSpacing(3)
            .fixedSize(horizontal: false, vertical: true)
    }
}
