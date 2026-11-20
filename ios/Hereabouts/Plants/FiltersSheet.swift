// The filters panel, as the approved picture (filters-sheet.png) draws it: "Filters" in Young Serif with "Clear all"
// on the right, then the website's filter groups in its words (sun, soil moisture, mature height, bloom colour, bloom
// month, yard zone, wildlife, pickup or shipping), each a row of chips, the height a slider, the bloom colours
// swatches. The moss button at the bottom counts the plants the choices leave, as they change, and closes the panel.
// Plant type is not here: the chips on the Plants tab choose it.
//
// The choices change the list behind the panel at once, as the website's filters do; the button only closes it.
// At the largest text sizes the chip rows wrap instead of scrolling sideways, so every choice can be seen.
import HereaboutsCore
import SwiftUI

struct FiltersSheet: View {
    @Binding var filter: FilterState
    let location: Location?
    /// How many plants the list would show with these choices.
    let count: (FilterState) -> Int

    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var typeSize

    static let sun = [("full", "Full sun (6+ hrs)"), ("part", "Part sun / shade"), ("shade", "Full shade")]
    static let moisture = [("dry", "Dry"), ("medium", "Medium"), ("wet", "Wet")]
    static let colors = ["white", "yellow", "orange", "red", "pink", "purple", "blue", "green"]
    static let months = ["January", "February", "March", "April", "May", "June", "July", "August", "September",
                         "October", "November", "December"]
    static let zones = [("front-yard", "Front yard"), ("back-yard", "Back yard"), ("foundation", "Foundation planting"),
                        ("screening", "Screening / hedge"), ("sunny-bed", "Sunny bed"), ("shade-border", "Shade border"),
                        ("woodland", "Woodland"), ("rain-garden", "Rain garden / wet"), ("meadow", "Meadow / prairie"),
                        ("lawn-replace", "Lawn replacement"), ("slope", "Slope / erosion"), ("trellis", "Trellis / arbor"),
                        ("specimen", "Specimen / focal")]
    static let wildlife = [("bees", "Bees"), ("butterflies", "Butterflies"), ("hummingbirds", "Hummingbirds"),
                           ("birds", "Birds"), ("host", "Host plant (caterpillars)")]
    static let buy: [(BuyMode, String)] = [(.pickup, "Pick up within 75 miles"), (.ship, "Ships to you")]

    var body: some View {
        let n = count(filter)
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .center) {
                    Text("Filters")
                        .font(.display(.title2))
                        .foregroundStyle(Theme.ink)
                        .accessibilityAddTraits(.isHeader)
                    Spacer(minLength: 8)
                    Button {
                        clearAll()
                    } label: {
                        Text("Clear all")
                            .font(.body(17, .regular, relativeTo: .body))
                            .foregroundStyle(Theme.moss)
                            .padding(.horizontal, 8)
                            .frame(minWidth: 44, minHeight: 44)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("clearFilters")
                }
                .padding(.leading, 16)
                .padding(.trailing, 8)
                .padding(.top, 16)

                group("Sun") { chips(Self.sun, \.sun) }
                group("Soil moisture") { chips(Self.moisture, \.moisture) }
                group("Mature height", value: heightText) { heightSlider }
                group("Bloom color") { swatches }
                group("Bloom month") {
                    chipRow {
                        ForEach(1...12, id: \.self) { m in
                            Chip(label: String(Self.months[m - 1].prefix(3)), isOn: filter.bloomMonth.contains(m),
                                 spokenLabel: Self.months[m - 1]) {
                                filter.bloomMonth.formSymmetricDifference([m])
                            }
                        }
                    }
                }
                group("Yard zone / use") { chips(Self.zones, \.zones) }
                group("Wildlife value") { chips(Self.wildlife, \.wildlife) }
                group("Pickup / ship") {
                    chipRow {
                        ForEach(Self.buy, id: \.0) { option in
                            Chip(label: option.1, isOn: filter.buy.contains(option.0)) {
                                filter.buy.formSymmetricDifference([option.0])
                            }
                            .disabled(location == nil)
                        }
                    }
                    if location == nil {
                        Text("Set your location first")
                            .font(.body(.footnote))
                            .foregroundStyle(Theme.inkSoft)
                            .padding(.horizontal, 16)
                    }
                }
            }
            .padding(.bottom, 16)
        }
        .background(Color.white)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: 0) {
                Divider().overlay(Theme.rule)
                Button {
                    dismiss()
                } label: {
                    Text("Show \(n) \(n == 1 ? "plant" : "plants")")
                        .font(.body(17, .semibold, relativeTo: .body))
                        .foregroundStyle(Theme.onMoss)
                        .multilineTextAlignment(.center)
                        .padding(.vertical, 8)
                        .frame(maxWidth: .infinity, minHeight: 50)
                        .background(Theme.moss, in: RoundedRectangle(cornerRadius: Theme.brandCorner))
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("showPlants")
                .padding(.horizontal, 16)
                .padding(.top, 12)
                .padding(.bottom, 8)
            }
            .background(Color.white)
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }

    // MARK: - the parts

    /// A group: its name in semibold (and, for the height, the chosen value on the right), then its choices.
    private func group<Content: View>(_ title: String, value: String? = nil,
                                      @ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                Text(title)
                    .font(.body(15, .semibold, relativeTo: .subheadline))
                    .foregroundStyle(Theme.ink)
                    .accessibilityAddTraits(.isHeader)
                Spacer(minLength: 8)
                if let value {
                    Text(value)
                        .font(.body(.subheadline))
                        .foregroundStyle(Theme.inkSoft)
                        .accessibilityHidden(true)
                }
            }
            .padding(.horizontal, 16)
            content()
        }
        .padding(.top, 12)
    }

    /// One chip per choice, each turning its value on or off in the given set.
    private func chips(_ options: [(String, String)], _ field: WritableKeyPath<FilterState, Set<String>>) -> some View {
        chipRow {
            ForEach(options, id: \.0) { option in
                Chip(label: option.1, isOn: filter[keyPath: field].contains(option.0)) {
                    filter[keyPath: field].formSymmetricDifference([option.0])
                }
            }
        }
    }

    /// A row of chips that scrolls sideways, as the picture draws it, or wraps at the largest text sizes.
    @ViewBuilder
    private func chipRow<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        if typeSize.isAccessibilitySize {
            FlowLayout(spacing: 8) { content() }
                .padding(.horizontal, 16)
        } else {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) { content() }
                    .padding(.horizontal, 16)
            }
        }
    }

    /// "any" at 100 feet or more, as the website's height readout says, else "12 ft".
    private var heightText: String {
        filter.maxHeight >= 100 ? "any" : "\(Int(filter.maxHeight)) ft"
    }

    private var heightSlider: some View {
        Slider(value: $filter.maxHeight, in: 1...100, step: 1)
            .tint(Theme.moss)
            .frame(minHeight: 44)
            .padding(.horizontal, 16)
            .accessibilityLabel("Maximum mature height in feet")
            .accessibilityValue(heightText)
    }

    /// The bloom colours as swatches with their names under them. The chosen ones get a moss ring.
    private var swatches: some View {
        let items = ForEach(Self.colors, id: \.self) { color in
            let isOn = filter.bloomColor.contains(color)
            Button {
                filter.bloomColor.formSymmetricDifference([color])
            } label: {
                VStack(spacing: 4) {
                    RoundedRectangle(cornerRadius: 2)
                        .fill(Theme.swatch(color))
                        .overlay(RoundedRectangle(cornerRadius: 2).strokeBorder(Theme.ruleStrong, lineWidth: 1))
                        .frame(width: 36, height: 24)
                        .padding(3)
                        .overlay(RoundedRectangle(cornerRadius: 4).strokeBorder(isOn ? Theme.moss : .clear, lineWidth: 2))
                    Text(color.prefix(1).uppercased() + color.dropFirst())
                        .font(.body(12, isOn ? .semibold : .regular, relativeTo: .caption))
                        .foregroundStyle(isOn ? Theme.moss : Theme.inkSoft)
                }
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(color.prefix(1).uppercased() + color.dropFirst())
            .accessibilityAddTraits(isOn ? .isSelected : [])
        }
        return Group {
            if typeSize.isAccessibilitySize {
                FlowLayout(spacing: 8) { items }
            } else {
                HStack(spacing: 0) {
                    items.frame(maxWidth: .infinity)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 4)
    }

    /// Clear all: every choice in this panel back to none, and the height back to any. The type chips, the sort and
    /// the search stay as they are, because they are not in this panel.
    private func clearAll() {
        filter.sun = []
        filter.moisture = []
        filter.wildlife = []
        filter.zones = []
        filter.bloomMonth = []
        filter.bloomColor = []
        filter.buy = []
        filter.maxHeight = 100
    }
}

/// Lays its parts out left to right, starting a new line when the next one does not fit.
struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = arrange(width: proposal.width ?? .infinity, subviews: subviews)
        let width = rows.map { $0.width }.max() ?? 0
        let height = rows.reduce(0) { $0 + $1.height } + spacing * CGFloat(max(0, rows.count - 1))
        return CGSize(width: proposal.width ?? width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for row in arrange(width: bounds.width, subviews: subviews) {
            var x = bounds.minX
            for (index, size) in row.items {
                subviews[index].place(at: CGPoint(x: x, y: y), anchor: .topLeading,
                                      proposal: ProposedViewSize(width: size.width, height: size.height))
                x += size.width + spacing
            }
            y += row.height + spacing
        }
    }

    private struct Row {
        var items: [(Int, CGSize)] = []
        var width: CGFloat = 0
        var height: CGFloat = 0
    }

    private func arrange(width: CGFloat, subviews: Subviews) -> [Row] {
        var rows: [Row] = []
        var row = Row()
        for index in subviews.indices {
            let size = subviews[index].sizeThatFits(ProposedViewSize(width: width, height: nil))
            if !row.items.isEmpty && row.width + spacing + size.width > width {
                rows.append(row)
                row = Row()
            }
            row.width += (row.items.isEmpty ? 0 : spacing) + size.width
            row.height = max(row.height, size.height)
            row.items.append((index, size))
        }
        if !row.items.isEmpty { rows.append(row) }
        return rows
    }
}
