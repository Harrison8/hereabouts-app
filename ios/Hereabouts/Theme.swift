// The brand's colour, fonts and corner, as the website's design rules set them: one moss green for buttons, the
// selected tab and links; Young Serif for plant names and titles; Figtree for everything else; 4-point corners on the
// brand's own parts (photo frames, the fact grid, chips, buttons). The phone's own parts (tab bar, top bars, menus)
// keep the phone's look, apart from the title font and the moss tint.
import SwiftUI
import UIKit

enum Theme {
    /// The one green, taken from the logo.
    static let moss = Color(hex: 0x3B4A2A)
    /// Corner radius for the brand's own parts, in points.
    static let brandCorner: CGFloat = 4

    /// The PostScript names of the two fonts Info.plist registers. Figtree is one variable font; each weight is a
    /// named instance of it.
    enum FontName {
        static let display = "YoungSerif-Regular"
        static let body = "Figtree-Regular"
        static let bodySemiBold = "Figtree-SemiBold"
    }

    /// The size iOS gives each text style at the default text size. `Font.custom(_:size:relativeTo:)` scales from
    /// here as the person changes the phone's text size.
    static func baseSize(_ style: Font.TextStyle) -> CGFloat {
        switch style {
        case .largeTitle: 34
        case .title: 28
        case .title2: 22
        case .title3: 20
        case .headline: 17
        case .subheadline: 15
        case .body: 17
        case .callout: 16
        case .footnote: 13
        case .caption: 12
        case .caption2: 11
        default: 17
        }
    }

    /// The UIKit text style matching a SwiftUI one, for scaling fonts given to UIKit's own bars.
    static func uiTextStyle(_ style: Font.TextStyle) -> UIFont.TextStyle {
        switch style {
        case .largeTitle: .largeTitle
        case .title: .title1
        case .title2: .title2
        case .title3: .title3
        case .headline: .headline
        case .subheadline: .subheadline
        case .callout: .callout
        case .footnote: .footnote
        case .caption: .caption1
        case .caption2: .caption2
        default: .body
        }
    }

    /// Young Serif for the titles in the top bars, at the phone's text size. The bars themselves keep the phone's look.
    @MainActor
    static func styleNavigationTitles() {
        let bar = UINavigationBar.appearance()
        if let large = scaledUIFont(Theme.FontName.display, .largeTitle) {
            bar.largeTitleTextAttributes = [.font: large]
        }
        if let inline = scaledUIFont(Theme.FontName.display, .headline) {
            bar.titleTextAttributes = [.font: inline]
        }
    }

    @MainActor
    private static func scaledUIFont(_ name: String, _ style: Font.TextStyle) -> UIFont? {
        guard let font = UIFont(name: name, size: baseSize(style)) else { return nil }
        return UIFontMetrics(forTextStyle: uiTextStyle(style)).scaledFont(for: font)
    }
}

extension Font {
    /// Young Serif, for plant names and titles. Grows and shrinks with the phone's text size.
    static func display(_ style: Font.TextStyle) -> Font {
        .custom(Theme.FontName.display, size: Theme.baseSize(style), relativeTo: style)
    }

    /// Figtree, for everything else. Headlines take its semibold weight, as the system font's headline does.
    static func body(_ style: Font.TextStyle) -> Font {
        let name = style == .headline ? Theme.FontName.bodySemiBold : Theme.FontName.body
        return .custom(name, size: Theme.baseSize(style), relativeTo: style)
    }
}

extension Color {
    /// A colour from a 0xRRGGBB number, in sRGB.
    init(hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: 1
        )
    }
}
