// The county under the Plants and My garden titles, with the map pin the approved pictures (d1-plants.png,
// d1-my-garden.png) draw before it. Each tab keeps its own look for the words: on Plants the county is a moss button
// that changes the location, on My garden plain ink text. The pin takes the words' colour and scales with the text size.
import SwiftUI

struct CountyLine<Words: View>: View {
    let color: Color
    let weight: Font.Weight
    @ViewBuilder let words: () -> Words

    /// The pin grows with the phone's text size, like the words beside it.
    @ScaledMetric(relativeTo: .body) private var pinSize: CGFloat = 20

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Image("CountyPin")
                .renderingMode(.template)
                .resizable()
                .scaledToFit()
                .frame(width: pinSize, height: pinSize)
                .foregroundStyle(color)
                // Sits on the first line's baseline with its point just below it, as a letter's descender would.
                .alignmentGuide(.firstTextBaseline) { $0[VerticalAlignment.bottom] - $0.height * 0.16 }
                .decorativeIcon("county-pin")
            words()
        }
    }
}

extension View {
    /// An icon that only decorates: VoiceOver skips it, since the words beside it already say everything. The tests
    /// find it by `identifier`, which they can only do while VoiceOver is off, because a view hidden from VoiceOver
    /// is hidden from the tests too.
    func decorativeIcon(_ identifier: String) -> some View {
        modifier(DecorativeIcon(identifier: identifier))
    }
}

private struct DecorativeIcon: ViewModifier {
    let identifier: String
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver

    func body(content: Content) -> some View {
        content
            .accessibilityHidden(voiceOver)
            .accessibilityIdentifier(identifier)
    }
}
