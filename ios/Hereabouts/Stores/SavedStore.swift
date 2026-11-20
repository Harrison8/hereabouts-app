// Keeps the saved plants on the phone, as plant ids in the order they were saved.
import Foundation

@MainActor
struct SavedStore {
    static let key = "hereabouts.saved"
    let defaults: UserDefaults

    func load() -> [String] {
        defaults.stringArray(forKey: Self.key) ?? []
    }

    func save(_ ids: [String]) {
        defaults.set(ids, forKey: Self.key)
    }
}
