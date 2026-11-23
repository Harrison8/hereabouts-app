// Colour pairs that must stay readable: the WCAG contrast ratio, 4.5 to 1 for ordinary text.
import XCTest
@testable import Hereabouts

final class ContrastUnitTests: XCTestCase {
    private func luminance(_ hex: UInt32) -> Double {
        func channel(_ value: UInt32) -> Double {
            let c = Double(value) / 255
            return c <= 0.03928 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel((hex >> 16) & 0xFF) + 0.7152 * channel((hex >> 8) & 0xFF) + 0.0722 * channel(hex & 0xFF)
    }

    private func ratio(_ a: UInt32, _ b: UInt32) -> Double {
        let x = luminance(a), y = luminance(b)
        return (max(x, y) + 0.05) / (min(x, y) + 0.05)
    }

    func testTheWaitingButtonWordsReadOnWhite() {
        let r = ratio(ContinueButtonStyle.waitingTextHex, ContinueButtonStyle.waitingBackgroundHex)
        XCTAssertGreaterThanOrEqual(r, 4.5, "waiting button text is \(r) to 1 on white")
    }

    func testTheChosenChipWordsReadOnMoss() {
        let r = ratio(0xFFFFFF, 0x3B4A2A)
        XCTAssertGreaterThanOrEqual(r, 4.5, "chosen chip text is \(r) to 1 on moss")
    }
}
