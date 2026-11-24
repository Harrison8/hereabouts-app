// The first-open lookups inside the app: what a typed ZIP code, town, state or address finds, and the website's message
// for each failure; the street-address lookup's question and how it reads the Census reply, against saved replies and
// a stand-in server on 127.0.0.1 (never the Census itself); and the Settings lines for the version and the data date.
import HereaboutsCore
import XCTest
@testable import Hereabouts

final class FirstOpenUnitTests: XCTestCase {
    @MainActor
    private func tables() -> Tables { AppModel.forLaunch(arguments: []).tables }

    private func county(_ outcome: FirstOpenLookup.Outcome?) -> String? {
        guard case .found(let location, _) = outcome else { return nil }
        return "\(location.countyName), \(location.stateName)"
    }

    private func method(_ outcome: FirstOpenLookup.Outcome?) -> String? {
        guard case .found(_, let method) = outcome else { return nil }
        return method
    }

    private func message(_ outcome: FirstOpenLookup.Outcome?) -> String? {
        guard case .message(let text) = outcome else { return nil }
        return text
    }

    // MARK: ZIP codes, as they are typed

    @MainActor
    func testAZipFindsItsCountyOnlyAtFiveDigits() {
        let t = tables()
        XCTAssertNil(FirstOpenLookup.zip("", tables: t))
        XCTAssertNil(FirstOpenLookup.zip("2203", tables: t))
        XCTAssertEqual(county(FirstOpenLookup.zip("22030", tables: t)), "Fairfax County, Virginia")
        XCTAssertEqual(method(FirstOpenLookup.zip("22030", tables: t)), "zip")
        XCTAssertEqual(message(FirstOpenLookup.zip("99501", tables: t)), "We only cover the lower 48 states for now.")
        XCTAssertEqual(message(FirstOpenLookup.zip("00000", tables: t)),
                       "We couldn't find ZIP 00000. Try a nearby ZIP or your street address.")
    }

    // MARK: Towns, counties, states and addresses, as the website's lookup takes them

    @MainActor
    func testTypedTextGetsTheWebsitesAnswers() async {
        let t = tables()
        let offline = AddressLookup(tables: t, endpoint: URL(string: "http://127.0.0.1:9/none")!)
        func run(_ text: String) async -> FirstOpenLookup.Outcome {
            await FirstOpenLookup.text(text, tables: t, address: offline)
        }
        var outcome = await run("   ")
        XCTAssertEqual(message(outcome), "Type your city, county, or ZIP code first.")
        outcome = await run("Dayton, OH")
        XCTAssertEqual(county(outcome), "Montgomery County, Ohio")
        // The website's method values: the kind of place found, "city" or "county".
        XCTAssertEqual(method(outcome), "city")
        outcome = await run("Montgomery County, OH")
        XCTAssertEqual(county(outcome), "Montgomery County, Ohio")
        XCTAssertEqual(method(outcome), "county")
        outcome = await run("22030")
        XCTAssertEqual(county(outcome), "Fairfax County, Virginia")
        XCTAssertEqual(method(outcome), "zip")
        outcome = await run("99501")
        XCTAssertEqual(message(outcome), "We only cover the lower 48 states for now.")
        outcome = await run("Ohio")
        XCTAssertEqual(message(outcome), "Ohio contains several different ecoregions. Type a city, county, or ZIP code.")
        outcome = await run("Alaska")
        XCTAssertEqual(message(outcome), "We only cover the lower 48 states for now.")
        outcome = await run("Springfield")
        XCTAssertEqual(message(outcome), "There's a Springfield in more than one state. Add the state, like Springfield, MO.")
        outcome = await run("Nowhereville")
        XCTAssertEqual(message(outcome), "We couldn't find that place. Try your ZIP code, or a city and state like Dayton, OH.")
        // A street address, or an unknown name with a digit in it, goes to the address lookup, which has no answer here.
        outcome = await run("123 Main St, Springfield, IL")
        XCTAssertEqual(message(outcome), "The address lookup isn't responding. Try your 5-digit ZIP code instead.")
        outcome = await run("Route 9 Nowhereville")
        XCTAssertEqual(message(outcome), "The address lookup isn't responding. Try your 5-digit ZIP code instead.")
    }

    @MainActor
    func testThePhonesPositionFindsTheNearestZipsCounty() {
        let t = tables()
        let found = FirstOpenLookup.device(lat: 38.94, lon: -77.16, tables: t)
        XCTAssertEqual(county(found), "Fairfax County, Virginia")
        XCTAssertEqual(method(found), "device")
        // Kept and shown as the ZIP code's own point, never the phone's.
        if case .found(let location, _) = found {
            XCTAssertEqual(location.lat, 38.94)
            XCTAssertEqual(location.lon, -77.16)
        }
        XCTAssertEqual(message(FirstOpenLookup.device(lat: 61.2181, lon: -149.9003, tables: t)),
                       "We only cover the lower 48 states for now.")
    }

    // MARK: The street-address lookup

    func testTheAddressQuestionIsTheWebsitesAsJson() throws {
        let url = AddressLookup.url(for: "4600 Silver Hill Rd, Washington, DC 20233 & co+1")
        XCTAssertEqual(url.scheme, "https")
        XCTAssertEqual(url.host, "geocoding.geo.census.gov")
        XCTAssertEqual(url.path, "/geocoder/geographies/onelineaddress")
        let query = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false)?.percentEncodedQuery)
        XCTAssertEqual(query, "address=4600%20Silver%20Hill%20Rd%2C%20Washington%2C%20DC%2020233%20%26%20co%2B1"
                       + "&benchmark=Public_AR_Current&vintage=Current_Current&layers=Counties&format=json")
    }

    func testRepliesAreReadAsTheWebsiteReadsThem() throws {
        func read(_ json: String) -> GeocoderPlace? {
            AddressLookup.countyFromGeocoderReply(try? JSONSerialization.jsonObject(with: Data(json.utf8)))
        }
        let fairfax = try XCTUnwrap(read(Self.fairfaxReply))
        XCTAssertEqual(fairfax.countyFips, "51059")
        XCTAssertEqual(fairfax.countyName, "Fairfax County")
        XCTAssertEqual(fairfax.lat, 38.846016, accuracy: 0.000001)
        XCTAssertEqual(fairfax.lon, -77.307487, accuracy: 0.000001)
        // The website's own cases.
        XCTAssertEqual(read(#"{"result":{"addressMatches":[{"coordinates":{"x":-77.1,"y":38.9},"geographies":{"Counties":[{"GEOID":"51059","NAME":"Fairfax County"}]}}]}}"#),
                       GeocoderPlace(countyFips: "51059", countyName: "Fairfax County", lat: 38.9, lon: -77.1))
        XCTAssertNil(read(Self.noMatchReply))
        XCTAssertNil(read("null"))
        XCTAssertNil(read(#"{"result":{"addressMatches":[{"geographies":{"Counties":[{"GEOID":"1","NAME":"x"}]}}]}}"#))
        XCTAssertNil(read(#"{"result":{"addressMatches":[{"coordinates":{"x":1,"y":2},"geographies":{"Counties":[]}}]}}"#))
    }

    @MainActor
    func testTheAddressLookupAgainstAStandIn() async throws {
        let server = try StubServer()
        defer { server.stop() }
        let t = tables()
        let lookup = AddressLookup(tables: t, endpoint: URL(string: server.baseURL + "/geocoder")!)
        func path(_ text: String) -> String {
            let parts = URLComponents(url: lookup.requestURL(for: text), resolvingAgainstBaseURL: false)!
            return parts.percentEncodedPath + "?" + (parts.percentEncodedQuery ?? "")
        }

        server.serve(path("1 Found St"), Data(Self.fairfaxReply.utf8))
        let found = try await lookup.county(for: "1 Found St")
        guard case .found(let location) = found else { return XCTFail("No county: \(found)") }
        XCTAssertEqual(location.countyName, "Fairfax County")
        XCTAssertEqual(location.stateName, "Virginia")

        server.serve(path("2 Missing St"), Data(Self.noMatchReply.utf8))
        let missing = try await lookup.county(for: "2 Missing St")
        XCTAssertEqual(missing, .notFound)

        server.serve(path("3 Alaska St"), Data(Self.anchorageReply.utf8))
        let alaska = try await lookup.county(for: "3 Alaska St")
        XCTAssertEqual(alaska, .outsideUS48)

        server.serve(path("4 Broken St"), Data("<html>".utf8))
        await assertFails(lookup, "4 Broken St", .network)
        server.serve(path("5 Error St"), Data(Self.fairfaxReply.utf8), status: 500)
        await assertFails(lookup, "5 Error St", .network)

        // The website's messages for each answer.
        server.serve(path("2 Missing St"), Data(Self.noMatchReply.utf8))
        let outcome = await FirstOpenLookup.text("2 Missing St", tables: t, address: lookup)
        XCTAssertEqual(message(outcome), "We couldn't match that address. Try your 5-digit ZIP code instead.")
        let foundOutcome = await FirstOpenLookup.text("1 Found St", tables: t, address: lookup)
        XCTAssertEqual(method(foundOutcome), "address")
    }

    @MainActor
    private func assertFails(_ lookup: AddressLookup, _ text: String, _ expected: AddressLookup.Failure,
                             line: UInt = #line) async {
        do {
            let result = try await lookup.county(for: text)
            XCTFail("\(text) gave \(result) instead of failing", line: line)
        } catch {
            XCTAssertEqual(error as? AddressLookup.Failure, expected, line: line)
        }
    }

    // MARK: A lookup that was overtaken

    /// Set from inside a lookup, so the test knows the lookup has started waiting.
    @MainActor
    private final class Flag {
        var on = false
    }

    /// Starts a lookup that waits until `release` is set, and returns once it is waiting.
    @MainActor
    private func startWaitingLookup(_ latest: LatestLookup, answer: Int, release: Flag) async -> Task<Int?, Never> {
        let started = Flag()
        let task = Task { @MainActor in
            await latest.run {
                started.on = true
                while !release.on { try? await Task.sleep(for: .milliseconds(10)) }
                return answer
            }
        }
        while !started.on { await Task.yield() }
        return task
    }

    /// The website's superseded(): Cancel, or a change to what was typed, while the Census is asked means the old
    /// answer sets nothing; and when a second lookup starts, only its answer is used.
    @MainActor
    func testALookupThatWasOvertakenSetsNothing() async {
        let latest = LatestLookup()
        let finished = await latest.run { 1 }
        XCTAssertEqual(finished, 1, "A lookup nothing overtook lost its answer")

        // Cancel, or the text changed, while the lookup waited.
        let release = Flag()
        let cancelled = await startWaitingLookup(latest, answer: 2, release: release)
        latest.supersede()
        release.on = true
        let cancelledAnswer = await cancelled.value
        XCTAssertNil(cancelledAnswer, "A lookup the screen gave up on still gave its answer")

        // A second lookup started while the first waited.
        let releaseFirst = Flag()
        let first = await startWaitingLookup(latest, answer: 3, release: releaseFirst)
        let second = await latest.run { 4 }
        releaseFirst.on = true
        let firstAnswer = await first.value
        XCTAssertEqual(second, 4, "The newer lookup lost its answer")
        XCTAssertNil(firstAnswer, "The older lookup's answer was used after a newer one started")
    }

    // MARK: Settings

    func testTheDataDateIsTheDayItWasWritten() {
        XCTAssertEqual(SettingsView.dataDay("2026-11-09T21:26:41-05:00"), "November 9, 2026")
        XCTAssertEqual(SettingsView.dataDay("2099-01-01T00:00:00Z"), "January 1, 2099")
        XCTAssertEqual(SettingsView.dataDay("2026-02-28"), "February 28, 2026")
    }

    @MainActor
    func testTheVersionAndTheShippedDataDate() throws {
        XCTAssertEqual(SettingsView.versionText(info: ["CFBundleShortVersionString": "1.0", "CFBundleVersion": "1"]), "1.0 (1)")
        XCTAssertEqual(SettingsView.versionText(info: Bundle.main.infoDictionary), "1.0 (1)")
        let shipped = AppModel.forLaunch(arguments: []).data.version.dataDate
        XCTAssertTrue(SettingsView.dataDay(shipped).range(of: #"^[A-Z][a-z]+ \d{1,2}, \d{4}$"#, options: .regularExpression) != nil,
                      "The shipped date \(shipped) reads as \(SettingsView.dataDay(shipped))")
    }

    // MARK: Saved Census replies

    /// A full reply for an address in Fairfax County, as the Census sends it with layers=Counties and format=json.
    static let fairfaxReply = """
        {"result":{"input":{"address":{"address":"1 Found St"},"vintage":{"isDefault":true,"id":"4","vintageName":"Current_Current",
        "vintageDescription":"Current Vintage - Current Benchmark"},"benchmark":{"isDefault":false,"benchmarkDescription":
        "Public Address Ranges - Current Benchmark","id":"4","benchmarkName":"Public_AR_Current"}},"addressMatches":[{
        "tigerLine":{"side":"L","tigerLineId":"76355984"},"geographies":{"Counties":[{"GEOID":"51059","CENTLAT":"+38.8336500",
        "AREAWATER":24726305,"BASENAME":"Fairfax","OID":27590405339546,"LSADC":"06","FUNCSTAT":"A","INTPTLAT":"+38.8344764",
        "NAME":"Fairfax County","OBJECTID":2318,"CENTLON":"-077.2765473","COUNTYCC":"H1","COUNTYNS":"01480119",
        "AREALAND":1012435806,"INTPTLON":"-077.2765196","MTFCC":"G4020","STATE":"51","COUNTY":"059"}]},
        "coordinates":{"x":-77.30748724230096,"y":38.84601622386617},"addressComponents":{"zip":"22030","streetName":"FOUND",
        "preType":"","city":"FAIRFAX","preDirection":"","suffixDirection":"","fromAddress":"1","state":"VA","suffixType":"ST",
        "toAddress":"99","suffixQualifier":"","preQualifier":""},"matchedAddress":"1 FOUND ST, FAIRFAX, VA, 22030"}]}}
        """

    /// The reply for an address the Census cannot match.
    static let noMatchReply = """
        {"result":{"input":{"address":{"address":"2 Missing St"},"benchmark":{"id":"4","benchmarkName":"Public_AR_Current"},
        "vintage":{"id":"4","vintageName":"Current_Current"}},"addressMatches":[]}}
        """

    /// An address in Anchorage, Alaska, outside the lower 48 states.
    static let anchorageReply = """
        {"result":{"addressMatches":[{"coordinates":{"x":-149.9003,"y":61.2181},"geographies":{"Counties":[
        {"GEOID":"02020","NAME":"Anchorage Municipality","STATE":"02","COUNTY":"020"}]},"matchedAddress":"3 ALASKA ST"}]}}
        """
}
