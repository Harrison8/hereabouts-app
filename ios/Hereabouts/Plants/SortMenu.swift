// The sort menu beside the plant count: the chosen sort in moss with the phone's up-and-down arrows, opening the phone's
// own menu of the website's ten sorts, in the website's order and words.
import HereaboutsCore
import SwiftUI

struct SortMenu: View {
    @Binding var sort: SortKey

    var body: some View {
        Menu {
            Picker(selection: $sort) {
                ForEach(SortKey.allCases, id: \.self) { key in
                    Text(key.label).tag(key)
                }
            } label: {
                Text("Sort")
            }
            .pickerStyle(.inline)
        } label: {
            HStack(spacing: 4) {
                Text(sort.label)
                    .multilineTextAlignment(.leading)
                Image(systemName: "chevron.up.chevron.down")
                    .font(.body(13, .medium, relativeTo: .footnote))
                    .accessibilityHidden(true)
            }
            .font(.body(15, .medium, relativeTo: .subheadline))
            .foregroundStyle(Theme.moss)
            .frame(minWidth: 44, minHeight: 44)
            .contentShape(Rectangle())
        }
        .accessibilityLabel("Sort")
        .accessibilityValue(sort.label)
        .accessibilityIdentifier("sortMenu")
    }
}
