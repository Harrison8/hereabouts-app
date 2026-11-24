// "Through the year": the brand kit's season chart, drawn with SwiftUI shapes from HereaboutsCore's seasonRows, as
// the website's renderSeason draws it. Twelve month letters across the top; one row per thing the plant does (in leaf,
// bloom, fruit, fall colour), each a dotted track with a coloured bar for the months it does it; this month ringed
// from the letters to the last row. Read aloud as one sentence, the website's own.
import HereaboutsCore
import SwiftUI

struct SeasonChart: View {
    let plant: Plant
    /// This month, 0 for January. The website rings the visitor's current month the same way.
    var now: Int = Calendar.current.component(.month, from: .now) - 1

    @ScaledMetric(relativeTo: .subheadline) private var labelWidth: CGFloat = 76
    @ScaledMetric(relativeTo: .subheadline) private var rowHeight: CGFloat = 20

    static let letters = ["J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D"]
    static let monthNames = ["January", "February", "March", "April", "May", "June", "July", "August", "September",
                             "October", "November", "December"]

    /// The website's spoken label: "In leaf: March to October; Bloom: March to April". The heading above says "Through the year".
    static func spoken(_ rows: [SeasonRow]) -> String {
        let said = rows.map { row in
            let runs = row.runs.compactMap { run -> String? in
                guard let a = run.first, let b = run.last, monthNames.indices.contains(a), monthNames.indices.contains(b)
                else { return nil }
                return a == b ? monthNames[a] : "\(monthNames[a]) to \(monthNames[b])"
            }
            return "\(row.label): \(runs.joined(separator: " and "))"
        }
        return said.joined(separator: "; ")
    }

    var body: some View {
        let rows = Labels.seasonRows(plant)
        HStack(alignment: .top, spacing: 8) {
            VStack(alignment: .leading, spacing: 0) {
                Color.clear.frame(height: rowHeight)
                ForEach(rows.indices, id: \.self) { i in
                    Text(rows[i].label)
                        .font(.body(13, .medium, relativeTo: .footnote))
                        .foregroundStyle(Theme.inkSoft)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                        .frame(height: rowHeight, alignment: .leading)
                }
            }
            .frame(width: labelWidth, alignment: .leading)

            GeometryReader { geo in
                let col = geo.size.width / 12
                ZStack(alignment: .topLeading) {
                    if (0..<12).contains(now) {
                        // 2 points, the website's small corner (--r-sm) for the season bars and the month ring.
                        RoundedRectangle(cornerRadius: 2)
                            .strokeBorder(Theme.ink, lineWidth: 2)
                            .frame(width: col, height: CGFloat(rows.count + 1) * rowHeight + 6)
                            .offset(x: CGFloat(now) * col, y: -3)
                    }
                    ForEach(0..<12, id: \.self) { m in
                        Text(Self.letters[m])
                            .font(.body(13, m == now ? .semibold : .medium, relativeTo: .footnote))
                            .foregroundStyle(m == now ? Theme.ink : Theme.inkSoft)
                            .frame(width: col, height: rowHeight)
                            .offset(x: CGFloat(m) * col)
                    }
                    ForEach(rows.indices, id: \.self) { i in
                        let mid = CGFloat(i + 1) * rowHeight + rowHeight / 2
                        Path { path in
                            path.move(to: CGPoint(x: col / 2, y: mid))
                            path.addLine(to: CGPoint(x: geo.size.width - col / 2, y: mid))
                        }
                        .stroke(Theme.ruleStrong, style: StrokeStyle(lineWidth: 1, lineCap: .round, dash: [1, 3]))
                        ForEach(rows[i].runs.indices, id: \.self) { r in
                            let run = rows[i].runs[r]
                            if let a = run.first, let b = run.last, b >= a {
                                RoundedRectangle(cornerRadius: 2)
                                    .fill(Theme.season(rows[i].state))
                                    .frame(width: CGFloat(b - a + 1) * col - 4, height: 10)
                                    .offset(x: CGFloat(a) * col + 2, y: mid - 5)
                            }
                        }
                    }
                }
            }
            .frame(height: CGFloat(rows.count + 1) * rowHeight)
        }
        // The chart keeps twelve columns side by side, so past the first accessibility size the letters and labels
        // stop growing; the spoken sentence says the same months in words.
        .dynamicTypeSize(...DynamicTypeSize.accessibility1)
        .padding(.vertical, 3)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Self.spoken(rows))
        .accessibilityIdentifier("seasonChart")
    }
}
