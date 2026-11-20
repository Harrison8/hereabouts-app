// The banner the Plants tab shows when the phone has no signal, as the approved picture (plants-no-signal.png) draws
// it: kraft paper, the ink colour, 4-point corners, between the search field and the chips. The picture's cloud icon is
// left out: the app's icons are only Apple's own, and only for the tab bar, save, search, back, filters, sort and
// "opens outside the app".
import SwiftUI

struct OfflineBanner: View {
    var body: some View {
        Text("No signal. Every plant and photo still works. Seller links open once you have signal again.")
            .font(.body(14, .regular, relativeTo: .footnote))
            .foregroundStyle(Theme.ink)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, 8)
            .padding(.horizontal, 12)
            .background(Theme.kraft, in: RoundedRectangle(cornerRadius: Theme.brandCorner))
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .accessibilityIdentifier("offlineBanner")
    }
}
