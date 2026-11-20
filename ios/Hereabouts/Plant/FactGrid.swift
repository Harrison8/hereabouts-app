// The plant page's facts, worded by HereaboutsCore's growingFacts as the website words them. FactGrid: the four in the
// ruled box (height, width, sun, soil), two to a row as plant-page-top.png draws them, one to a row at the largest
// text sizes. FactList: the rest (flowers, in fall, growth, feeds), a label at left and its value at right, as
// plant-page-scrolled.png draws them, the label above its value at the largest text sizes.
import SwiftUI

struct FactGrid: View {
    /// Each fact as [label, value].
    let facts: [[String]]

    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        let columns = typeSize.isAccessibilitySize ? 1 : 2
        let rows = stride(from: 0, to: facts.count, by: columns).map { Array(facts[$0..<min($0 + columns, facts.count)]) }
        VStack(spacing: 0) {
            ForEach(rows.indices, id: \.self) { r in
                if r > 0 { Theme.ruleStrong.frame(height: 1) }
                HStack(spacing: 0) {
                    ForEach(0..<columns, id: \.self) { c in
                        if c > 0 { Theme.ruleStrong.frame(width: 1) }
                        if c < rows[r].count {
                            cell(rows[r][c])
                        } else {
                            Color.clear.frame(maxWidth: .infinity)
                        }
                    }
                }
                .fixedSize(horizontal: false, vertical: true)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: Theme.brandCorner))
        .overlay(RoundedRectangle(cornerRadius: Theme.brandCorner).strokeBorder(Theme.ruleStrong, lineWidth: 1))
    }

    private func cell(_ fact: [String]) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(fact.first ?? "")
                .font(.body(13, .regular, relativeTo: .footnote))
                .foregroundStyle(Theme.inkSoft)
            Text(fact.count > 1 ? fact[1] : "")
                .font(.body(16, .semibold, relativeTo: .callout))
                .foregroundStyle(Theme.ink)
        }
        .fixedSize(horizontal: false, vertical: true)
        .padding(.horizontal, 12)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .accessibilityElement(children: .combine)
    }
}

struct FactList: View {
    /// Each fact as [label, value].
    let facts: [[String]]

    @Environment(\.dynamicTypeSize) private var typeSize
    @ScaledMetric(relativeTo: .footnote) private var labelWidth: CGFloat = 76

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(facts.indices, id: \.self) { i in
                if i > 0 { Theme.rule.frame(height: 1) }
                row(facts[i])
                    .padding(.vertical, 8)
            }
        }
    }

    @ViewBuilder
    private func row(_ fact: [String]) -> some View {
        let label = Text(fact.first ?? "")
            .font(.body(13, .regular, relativeTo: .footnote))
            .foregroundStyle(Theme.inkSoft)
        let value = Text(fact.count > 1 ? fact[1] : "")
            .font(.body(16, .semibold, relativeTo: .callout))
            .foregroundStyle(Theme.ink)
            .fixedSize(horizontal: false, vertical: true)
        Group {
            if typeSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 4) {
                    label
                    value
                }
            } else {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    label.frame(width: labelWidth, alignment: .leading)
                    value.frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
        .accessibilityElement(children: .combine)
    }
}
