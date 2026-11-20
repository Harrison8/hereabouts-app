// The banner the Plants tab shows when the phone has no signal, as the approved picture (plants-no-signal.png) draws
// it: a cloud with a line through it, then the words, on kraft paper in the ink colour with 4-point corners, between
// the search field and the chips. The cloud only decorates, so VoiceOver reads the words alone.
import SwiftUI

struct OfflineBanner: View {
    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Image(systemName: "icloud.slash")
                .font(.body(14, .regular, relativeTo: .footnote))
                .foregroundStyle(Theme.ink)
                .decorativeIcon("no-signal-icon")
            Text("No signal. Every plant and photo still works. Seller links open once you have signal again.")
                .font(.body(14, .regular, relativeTo: .footnote))
                .foregroundStyle(Theme.ink)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityIdentifier("offlineBanner")
        }
        .padding(.vertical, 8)
        .padding(.horizontal, 12)
        .background(Theme.kraft, in: RoundedRectangle(cornerRadius: Theme.brandCorner))
        .padding(.horizontal, 16)
        .padding(.top, 8)
    }
}
