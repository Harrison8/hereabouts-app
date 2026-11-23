// One way to type into a field for every screen test. Typing that starts before the field has the keyboard can lose
// its first characters or all of them (a ZIP field held "22" on one run, and typed nothing in 0.3 s on another), so
// the field is tapped, given time to take focus, typed into, and typed into once more from empty if it does not hold
// what was typed.
import XCTest

extension XCUIElement {
    @MainActor
    func typeReliably(_ text: String) {
        let focused = { (self.value(forKey: "hasKeyboardFocus") as? Bool) ?? false }
        tap()
        if !StubServer.wait(3, until: focused) { tap() }
        _ = StubServer.wait(3, until: focused)
        let before = value as? String ?? ""
        typeText(text)
        guard !text.hasSuffix("\n") else { return }
        // The field's value may be its placeholder when it is empty, so compare with the value from before typing.
        let matches = { (self.value as? String ?? "").hasSuffix(text) }
        if !StubServer.wait(2, until: matches) {
            let now = value as? String ?? ""
            print("NOTE: the field held \"\(now)\" after typing \"\(text)\"; typing it again")
            if now != before, !now.isEmpty {
                typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: now.count))
            }
            typeText(text)
        }
    }
}
