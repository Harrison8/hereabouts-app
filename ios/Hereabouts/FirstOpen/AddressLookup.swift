// The street-address lookup: asks the US Census Bureau's address finder which county a typed address is in. It sends
// the same question the website sends (onelineaddress, current address ranges, the Counties layer), asking for JSON,
// and reads the reply with the website's rules (countyFromGeocoderReply). Only the typed text goes to the Census; the
// answer is turned into a county on the phone, and the position it returns is rounded before anything keeps it.
import Foundation
import HereaboutsCore

/// What countyFromGeocoderReply takes from a Census reply: the county code and name, and the matched point.
struct GeocoderPlace: Equatable, Sendable {
    let countyFips: String
    let countyName: String
    let lat: Double
    let lon: Double
}

struct AddressLookup: Sendable {
    /// The Census Bureau's address finder, the only outside address the app asks besides the website.
    static let geocoder = URL(string: "https://geocoding.geo.census.gov/geocoder/geographies/onelineaddress")!

    /// Why no answer came back. The website gives each its own message.
    enum Failure: Error, Equatable {
        /// No answer, an error answer, or an answer that is not the Census's JSON.
        case network
        /// No answer within 8 seconds, as on the website.
        case timeout
    }

    let tables: Tables
    let endpoint: URL

    init(tables: Tables, endpoint: URL = AddressLookup.geocoder) {
        self.tables = tables
        self.endpoint = endpoint
    }

    /// The characters JavaScript's encodeURIComponent leaves as they are, so the question matches the website's.
    private static let uriComponentAllowed = CharacterSet(
        charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()")

    /// The website's question for the typed text, asking for JSON instead of a script.
    static func url(for text: String, endpoint: URL = geocoder) -> URL {
        let address = text.addingPercentEncoding(withAllowedCharacters: uriComponentAllowed) ?? ""
        var parts = URLComponents(url: endpoint, resolvingAgainstBaseURL: false)!
        parts.percentEncodedQuery = "address=\(address)&benchmark=Public_AR_Current&vintage=Current_Current"
            + "&layers=Counties&format=json"
        return parts.url!
    }

    func requestURL(for text: String) -> URL { Self.url(for: text, endpoint: endpoint) }

    /// countyFromGeocoderReply: the first match's first county and its point, or nil when the reply has no county or
    /// no point.
    static func countyFromGeocoderReply(_ reply: Any?) -> GeocoderPlace? {
        guard let reply = reply as? [String: Any],
              let result = reply["result"] as? [String: Any],
              let matches = result["addressMatches"] as? [[String: Any]],
              let match = matches.first,
              let geographies = match["geographies"] as? [String: Any],
              let counties = geographies["Counties"] as? [[String: Any]],
              let county = counties.first,
              let fips = county["GEOID"] as? String,
              let coordinates = match["coordinates"] as? [String: Any],
              let lat = (coordinates["y"] as? NSNumber)?.doubleValue,
              let lon = (coordinates["x"] as? NSNumber)?.doubleValue
        else { return nil }
        return GeocoderPlace(countyFips: fips, countyName: county["NAME"] as? String ?? "", lat: lat, lon: lon)
    }

    /// The county of a typed address: `.found` with the matched point rounded to two decimal places, `.notFound`
    /// when the Census has no match, `.outsideUS48` when the county is outside the lower 48 states. Throws
    /// `Failure` when no usable answer comes back.
    func county(for text: String) async throws -> LocationResult {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 8
        configuration.timeoutIntervalForResource = 8
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        let session = URLSession(configuration: configuration)
        defer { session.finishTasksAndInvalidate() }

        let body: Data
        do {
            let (data, response) = try await session.data(from: requestURL(for: text))
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                throw Failure.network
            }
            body = data
        } catch let error as URLError where error.code == .timedOut {
            throw Failure.timeout
        } catch let error as Failure {
            throw error
        } catch {
            throw Failure.network
        }

        guard let reply = try? JSONSerialization.jsonObject(with: body) as? [String: Any] else { throw Failure.network }
        guard let place = Self.countyFromGeocoderReply(reply) else { return .notFound }
        return LocationLookup.resolve(countyFips: place.countyFips,
                                      lat: LocationLookup.roundPosition(place.lat),
                                      lon: LocationLookup.roundPosition(place.lon), tables: tables)
    }
}
