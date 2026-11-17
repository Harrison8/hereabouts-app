// Which events, and which of their properties, the app may send to analytics. A copy of EVENT_PROPS and
// ONCE_PER_SESSION in the website's analytics.js, with its cleanEvent. What a visitor types (an address, ZIP, city or
// county name) is never on the list, so it cannot reach analytics. The tests check both lists and every case against
// expected/analytics.json, which records the website's own lists, so a change on the website fails the tests here.
import Foundation

/// One property value of an event: text, a number or true/false.
public enum EventValue: Codable, Equatable, Hashable, Sendable {
    case string(String)
    case number(Double)
    case bool(Bool)

    public init(from decoder: any Decoder) throws {
        let c = try decoder.singleValueContainer()
        // true/false first: some decoders read a JSON true as the number 1.
        if let b = try? c.decode(Bool.self) {
            self = .bool(b)
        } else if let n = try? c.decode(Double.self) {
            self = .number(n)
        } else {
            self = .string(try c.decode(String.self))
        }
    }

    public func encode(to encoder: any Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .string(let s): try c.encode(s)
        case .number(let n): try c.encode(n)
        case .bool(let b): try c.encode(b)
        }
    }
}

public enum Analytics {
    /// EVENT_PROPS: each event the app may send, with the properties it may carry.
    public static let allowed: [String: [String]] = [
        "location_set": ["method", "county", "ecoregion", "state"],
        "results_shown": ["count", "ecoregion"],
        "buy_panel_open": ["plant"],
        "affiliate_click": ["plant", "vendor", "local", "affiliate"],
        "local_vendor_click": ["plant", "vendor"],
        "no_seller_shown": ["plant", "state"],
        "newsletter_signup": ["ecoregion"],
        "newsletter_waitlist": ["state"],
        "geocode_failed": ["method", "reason"],
        "zip_fallback_used": [],
        "empty_results": ["ecoregion", "state"],
        "browse_all_opened": [],
        "plant_search": ["count", "hidden_count"],
        "home_pick_opened": ["plant"],
        "plant_saved": [],
        "plant_unsaved": [],
        "notifications_answer": ["granted"],
        "this_week_opened": ["from"],
    ]

    /// ONCE_PER_SESSION: events sent at most once per session for each plant and seller.
    public static let onceKeys = ["buy_panel_open", "affiliate_click", "local_vendor_click", "no_seller_shown"]

    /// cleanEvent: the event's allowed properties only, or nil for an event not on the list. The website looks the
    /// event up as the list's own key (hasOwnProperty), so names like "toString" are unknown events there too.
    public static func clean(event: String, props: [String: EventValue]) -> [String: EventValue]? {
        guard let keys = allowed[event] else { return nil }
        var out: [String: EventValue] = [:]
        for key in keys {
            if let v = props[key] { out[key] = v }
        }
        return out
    }
}
