// The brand's colour, fonts and corner, as the website's design rules set them: one moss green for buttons, the
// selected tab and links; Young Serif for plant names and titles; Figtree for everything else; 4-point corners on the
// brand's own parts (photo frames, the fact grid, chips, buttons). The phone's own parts (tab bar, top bars, menus)
// keep the phone's look, apart from the title font and the moss tint.
import SwiftUI
import UIKit

enum Theme {
    /// The one green, taken from the logo.
    static let moss = Color(hex: 0x3B4A2A)
    /// Text: the near-black ink, the softer ink for secondary lines, and the thin rule around groups.
    static let ink = Color(hex: 0x16211A)
    static let inkSoft = Color(hex: 0x52605A)
    static let rule = Color(hex: 0xE1E6DB)
    /// The darker rule round chips and photo frames, and the grey of photo credits.
    static let ruleStrong = Color(hex: 0xB4BEAD)
    static let creditGrey = Color(hex: 0x7C847D)
    /// The pale ground of the search field and the "No photograph yet" plate, and the kraft paper of the no-signal
    /// banner.
    static let surface = Color(hex: 0xF3F5EF)
    static let kraft = Color(hex: 0xECE5D3)
    /// Text on moss, and the paler green of a count on a chosen chip.
    static let onMoss = Color.white
    static let mossSoft = Color(hex: 0xC9D3BC)
    /// The design rules' error red, behind the bin of a saved plant's Remove button.
    static let error = Color(hex: 0xA8321F)

    /// The bloom colour swatches, as the brand kit draws them.
    static func swatch(_ color: String) -> Color {
        switch color {
        case "white": Color(hex: 0xF4F0E3)
        case "yellow": Color(hex: 0xE8C557)
        case "orange": Color(hex: 0xD68A3C)
        case "red": Color(hex: 0xB13C3C)
        case "pink": Color(hex: 0xD693A4)
        case "purple": Color(hex: 0x8E6BA8)
        case "blue": Color(hex: 0x6A8AA8)
        default: Color(hex: 0x7D8757)
        }
    }
    /// The seed-packet paper: the range map's region edges and the dot that marks the county.
    static let packet = Color(hex: 0xF7F2E6)

    /// The season chart's bar colours, by what the plant is doing, as the brand kit's chart draws them.
    static func season(_ state: String) -> Color {
        switch state {
        case "bloom": Color(hex: 0xB86B52)
        case "fruit": Color(hex: 0x8E3B3B)
        case "fall": Color(hex: 0xB5602E)
        default: Color(hex: 0x7D8757)
        }
    }

    /// Corner radius for the brand's own parts, in points.
    static let brandCorner: CGFloat = 4

    /// The PostScript names of the two fonts Info.plist registers. Figtree is one variable font; each weight is a
    /// named instance of it.
    enum FontName {
        static let display = "YoungSerif-Regular"
        static let body = "Figtree-Regular"
        static let bodyMedium = "Figtree-Medium"
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

    /// Figtree at a given size and weight (medium or semibold), for labels, links and buttons. Grows and shrinks
    /// with the phone's text size, from `style`.
    static func body(_ size: CGFloat, _ weight: Font.Weight, relativeTo style: Font.TextStyle) -> Font {
        let name = switch weight {
        case .medium: Theme.FontName.bodyMedium
        case .semibold, .bold, .heavy, .black: Theme.FontName.bodySemiBold
        default: Theme.FontName.body
        }
        return .custom(name, size: size, relativeTo: style)
    }

    /// Figtree leaning 12 degrees, for Latin names. The app carries only the upright Figtree, and SwiftUI's
    /// `.italic()` does nothing to a font with no italic face, so this slants the upright letters, as a browser does
    /// when it has no italic face. Sized for `typeSize`, so it follows the phone's text size like the others.
    static func bodySlanted(_ style: Font.TextStyle, typeSize: DynamicTypeSize) -> Font {
        let size = Theme.baseSize(style)
        guard let upright = UIFont(name: Theme.FontName.body, size: size) else { return .body(style).italic() }
        let lean = CGAffineTransform(a: 1, b: 0, c: tan(12 * .pi / 180), d: 1, tx: 0, ty: 0)
        let slanted = UIFont(descriptor: upright.fontDescriptor.withMatrix(lean), size: size)
        let traits = UITraitCollection(preferredContentSizeCategory: UIContentSizeCategory(typeSize))
        return Font(UIFontMetrics(forTextStyle: Theme.uiTextStyle(style)).scaledFont(for: slanted, compatibleWith: traits))
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
