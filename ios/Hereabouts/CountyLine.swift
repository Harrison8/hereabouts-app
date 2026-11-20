// The county under the Plants and My garden titles, with the map pin the approved pictures (d1-plants.png,
// d1-my-garden.png) draw before it. Each tab keeps its own look for the words: on Plants the county is a moss button
// that changes the location, on My garden plain ink text. The pin takes the words' colour, size and weight.
import SwiftUI

struct CountyLine<Words: View>: View {
    let color: Color
    let weight: Font.Weight
    @ViewBuilder let words: () -> Words

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Image(systemName: "mappin.and.ellipse")
                .font(.body(17, weight, relativeTo: .body))
                .foregroundStyle(color)
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
