// The daily check for new plant and seller data. The app ships with the data in its bundle; once a day it may
// download a newer set (version.json, plants.json, vendors.json and plant-regions.json) and uses it only when every
// check below passes. Otherwise it keeps what it has, so a broken or cut-off download never reaches the screen.
import Foundation

public enum DataUpdate {
    /// How long to wait between checks: 24 hours.
    public static let interval: TimeInterval = 24 * 60 * 60

    /// Whether to check for new data now: when it has never been checked, when the last check was 24 hours ago or
    /// more, or when the last check is in the future (the phone's clock was moved back).
    public static func shouldCheck(last: Date?, now: Date) -> Bool {
        guard let last else { return true }
        // A last check in the future means the clock was moved back; check anyway, or updates would stop until then.
        let gap = now.timeIntervalSince(last)
        return gap >= interval || gap < 0
    }

    /// The downloaded data, ready to use, or nil to keep the current data. Nil unless all of these hold:
    /// - version.json reads, with `schema` 1 (a later schema is for a later app);
    /// - its `dataHash` differs from the current one (the same hash is the data the app already has);
    /// - its `dataDate` is a date and time with an offset that is not earlier than the current one (an older set is
    ///   never swapped in; the same date with a new hash is a second publish that day, and is used);
    /// - the SHA-256 of the plants, vendors and regions files, joined in that order, equals `dataHash`, so a file
    ///   cut short or changed on the way does not pass;
    /// - all three files read, the sellers file is an object keyed by seller name, and every plant has an id, a
    ///   common name, a scientific name and a type;
    /// - there is at least one plant, one served region and one plant range, and no two plants share an id.
    /// If the current `dataDate` cannot be read, the date check is skipped.
    public static func accept(version: Data, plants: Data, vendors: Data, regions: Data, current: DataVersion) -> AppData? {
        guard let next = try? JSONDecoder().decode(DataVersion.self, from: version) else { return nil }
        guard next.schema == 1 else { return nil }
        guard next.dataHash != current.dataHash else { return nil }
        guard let nextDate = instant(next.dataDate) else { return nil }
        if let currentDate = instant(current.dataDate), nextDate < currentDate { return nil }
        var joined = Data()
        joined.append(plants)
        joined.append(vendors)
        joined.append(regions)
        guard SHA256.hex(joined) == next.dataHash.lowercased() else { return nil }
        guard let data = try? AppData.decode(plants: plants, vendors: vendors, regions: regions, version: next) else {
            return nil
        }
        for p in data.plants where p.id.isEmpty || p.common.isEmpty || p.sci.isEmpty || p.category.isEmpty {
            return nil
        }
        // The publisher has no check of its own, so a publish with no plants, no served regions or no plant ranges
        // would empty every installed app's list. Keep the current data instead.
        if data.plants.isEmpty || data.served.isEmpty || data.geo.isEmpty { return nil }
        // Two plants with the same id: the list, saved plants and reminders all look plants up by id.
        if Set(data.plants.map(\.id)).count != data.plants.count { return nil }
        return data
    }

    /// An ISO 8601 date and time with an offset, like "2026-11-09T21:26:41-05:00" or "2026-11-10T02:26:41Z", as
    /// seconds since 1970 UTC. Seconds and a fraction of a second are optional. nil for any other text, or for a
    /// month, day, hour, minute or offset out of range.
    static func instant(_ text: String) -> Double? {
        let s = Array(text.utf8)
        var i = 0
        func digits(_ n: Int) -> Int? {
            guard i + n <= s.count else { return nil }
            var v = 0
            for k in i..<(i + n) {
                guard s[k] >= 0x30 && s[k] <= 0x39 else { return nil }
                v = v * 10 + Int(s[k] - 0x30)
            }
            i += n
            return v
        }
        func expect(_ c: Character) -> Bool {
            guard i < s.count, let a = c.asciiValue, s[i] == a else { return false }
            i += 1
            return true
        }
        guard let year = digits(4), expect("-"), let month = digits(2), expect("-"), let day = digits(2),
              expect("T"), let hour = digits(2), expect(":"), let minute = digits(2) else { return nil }
        var second = 0.0
        if expect(":") {
            guard let whole = digits(2) else { return nil }
            second = Double(whole)
            if expect(".") {
                var scale = 0.1
                let start = i
                while i < s.count && s[i] >= 0x30 && s[i] <= 0x39 {
                    second += Double(s[i] - 0x30) * scale
                    scale /= 10
                    i += 1
                }
                if i == start { return nil }
            }
        }
        var offset = 0
        if expect("Z") {
            offset = 0
        } else if i < s.count && (s[i] == UInt8(ascii: "+") || s[i] == UInt8(ascii: "-")) {
            let sign = s[i] == UInt8(ascii: "-") ? -1 : 1
            i += 1
            guard let oh = digits(2), expect(":"), let om = digits(2), oh <= 23, om <= 59 else { return nil }
            offset = sign * (oh * 60 + om) * 60
        } else {
            return nil
        }
        guard i == s.count else { return nil }
        guard (1...12).contains(month), day >= 1, day <= daysIn(year: year, month: month), hour <= 23, minute <= 59,
              second < 60 else { return nil }
        let days = daysFromCivil(year: year, month: month, day: day)
        return Double(days * 86400 + hour * 3600 + minute * 60 - offset) + second
    }

    private static func daysIn(year: Int, month: Int) -> Int {
        let leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
        return [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1]
    }

    /// Days from 1970-01-01 to the given date (Howard Hinnant's days_from_civil).
    private static func daysFromCivil(year: Int, month: Int, day: Int) -> Int {
        let y = month <= 2 ? year - 1 : year
        let era = (y >= 0 ? y : y - 399) / 400
        let yoe = y - era * 400
        let mp = (month + 9) % 12
        let doy = (153 * mp + 2) / 5 + day - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }
}
