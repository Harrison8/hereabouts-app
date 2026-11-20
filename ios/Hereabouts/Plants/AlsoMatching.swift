// The "Also matching" lines under the results, word for word as the website's alsoMatchingHtml writes them: plants
// whose name matches the search but which are not native to the county, then those hidden by the filters. Each line
// names up to twelve plants and says how many more there are.
import HereaboutsCore
import SwiftUI

struct AlsoMatching: View {
    let hidden: (county: [Plant], filters: [Plant])
    let location: Location?

    var body: some View {
        if let location, !hidden.county.isEmpty {
            line(Self.text("Also matching, but not native to \(location.countyName), \(location.stateName):", hidden.county))
                .accessibilityIdentifier("alsoCounty")
        }
        if !hidden.filters.isEmpty {
            line(Self.text("Also matching, but hidden by your filters:", hidden.filters))
                .accessibilityIdentifier("alsoFilters")
        }
    }

    /// The label, then the first twelve names joined with commas, then " and N more" past twelve.
    static func text(_ label: String, _ plants: [Plant]) -> String {
        let names = plants.prefix(12).map(\.common).joined(separator: ", ")
        let more = plants.count > 12 ? " and \(plants.count - 12) more" : ""
        return "\(label) \(names)\(more)"
    }

    private func line(_ text: String) -> some View {
        Text(text)
            .font(.body(.subheadline))
            .foregroundStyle(Theme.inkSoft)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 16)
    }
}
