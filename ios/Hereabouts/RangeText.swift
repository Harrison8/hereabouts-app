// Ranges as the app shows them: "6 to 12 ft", "Mar to Apr", "$3.25 to $6.50". The website and the data write a range
// with an en dash or a hyphen ("6–12 ft", "1-2 ft", "Oct-Nov"); on the phone a range reads with "to", which is easier
// to read on a small screen. HereaboutsCore keeps the website's words unchanged. Every place the app shows a height, a
// bloom time, a growth line, a plant's note or growing note, a seller's size or price, or a garden job's fact passes
// the words through here just before they are shown.
//
// Only real ranges change:
// - A dash or a hyphen between two growth rates (slow, medium, moderate, fast, rapid): "Slow–moderate" and
//   "Slow-moderate" both read "Slow to moderate".
// - An en dash with no space on either side, between numbers, prices or month names: "6–12", "$16.95–$35.95",
//   "2nd–3rd", "Mar–Apr". An en dash between other words ("Jack–in–the–Pulpit", "frost-and-fog–covered") stays as the
//   data writes it. A dash with a space on each side is a sentence dash and stays.
// - A hyphen between two numbers, with or without a dollar sign: "1.5-2.5", "$3.25-$6.50". Not when the numbers are
//   part of a date or a longer code ("2026-11-20", "NA-FR-01-95"), and not when the hyphen joins a number to a word
//   ("3-gallon", "1-Pack").
// - A hyphen between two month names: "Oct-Nov", "April-May".
// Any other hyphenated word, such as "Slow-spreading" or "Moderate-to-fast", stays as the data writes it.
import Foundation

enum RangeText {
    /// `text` with each range written with "to".
    static func display(_ text: String) -> String {
        guard text.contains("\u{2013}") || text.contains("-") else { return text }
        var out = text
        for (rule, template) in rules {
            out = rule.stringByReplacingMatches(in: out, range: NSRange(out.startIndex..., in: out), withTemplate: template)
        }
        return out
    }

    private static let months = "January|February|March|April|May|June|July|August|September|October|November|December"
        + "|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sept|Sep|Oct|Nov|Dec"

    /// Each rule and what its match becomes. A regular expression is not changed after it is made, so sharing these
    /// between threads is safe.
    nonisolated(unsafe) private static let rules: [(NSRegularExpression, String)] = [
        // Two growth rates joined by a hyphen or an en dash, in any case.
        (#"(?i)\b(slow|medium|moderate|fast|rapid)[-–](slow|medium|moderate|fast|rapid)\b"#, "$1 to $2"),
        // The en dash: a number, price or month name before it, which starts the text or follows a space, "(" or "~";
        // a number, price, "~" or month name after it.
        (#"(?<![^\s(~])(\$?\d[A-Za-z0-9.]*|(?:\#(months)))–(?=\$?\d|~|(?:\#(months))\b)"#, "$1 to "),
        // The hyphen between numbers: no letter, digit, point, hyphen or slash just before the first number, and no
        // further "-digit" after the second.
        (#"(?<![A-Za-z0-9.\-/])(\$?\d+(?:\.\d+)?)-(\$?\d+(?:\.\d+)?)(?![\d.]*-\d)(?!\d)"#, "$1 to $2"),
        // The hyphen between month names.
        (#"\b(\#(months))-(\#(months))\b"#, "$1 to $2"),
    ].map { pattern, template in
        // The patterns are fixed text, checked by the unit tests, so making them cannot fail.
        (try! NSRegularExpression(pattern: pattern), template)
    }
}
