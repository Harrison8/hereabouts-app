// The plant page's addresses and words, checked without the screen: every seller link the website makes reaches the
// phone's browser character for character, odd ones as a browser would send them; Directions goes to the same place
// in Apple Maps; the USDA link, the line under the Latin name and the season chart's spoken sentence are the website's.
import HereaboutsCore
import XCTest
@testable import Hereabouts

final class PlantPageUnitTests: XCTestCase {
    /// ZIP codes spread across the country, so the rows include nearby shops, shipping-only sellers and no seller.
    private let zips = ["22030", "40202", "97201", "10001", "60601", "78701", "80202", "33101", "55401", "98101"]

    @MainActor
    private func everyView(_ body: (Plant, Location?, BuyView) throws -> Void) rethrows {
        let model = AppModel.forLaunch(arguments: [])
        var locations: [Location?] = [nil]
        for zip in zips {
            if case .found(let location) = LocationLookup.zip(zip, tables: model.tables) { locations.append(location) }
        }
        XCTAssertGreaterThan(locations.count, 5, "Too few of the test ZIP codes were found")
        for plant in model.data.plants {
            for location in locations {
                try body(plant, location, Sellers.buyView(plant, location, channel: .app, data: model.data))
            }
        }
    }

    @MainActor
    func testEverySellerLinkReachesTheBrowserUnchanged() {
        var checked = 0
        everyView { plant, location, view in
            for row in view.rows {
                guard row.href != "#" else { continue }
                XCTAssertEqual(OutsideLink.url(row.href)?.absoluteString, row.href,
                               "\(plant.id), \(row.name), \(location?.countyFips ?? "no county")")
                checked += 1
            }
        }
        XCTAssertGreaterThan(checked, 100)
    }

    @MainActor
    func testDirectionsGoToTheSamePlaceInAppleMaps() throws {
        var checked = 0
        try everyView { plant, _, view in
            for row in view.rows where row.kind == "directions" {
                guard let directions = row.directions,
                      let destination = URLComponents(string: directions)?.queryItems?
                        .first(where: { $0.name == "destination" })?.value
                else { XCTFail("\(plant.id), \(row.name): a nearby shop with no destination"); continue }
                let maps = try XCTUnwrap(OutsideLink.appleMaps(directions), "\(plant.id), \(row.name): no Apple Maps link")
                XCTAssertEqual(maps.host, "maps.apple.com")
                XCTAssertEqual(URLComponents(url: maps, resolvingAgainstBaseURL: false)?.queryItems?
                    .first(where: { $0.name == "daddr" })?.value, destination)
                checked += 1
            }
        }
        XCTAssertGreaterThan(checked, 0, "No nearby shop was found for any test ZIP code")
    }

    func testOddAddressesComeOutAsABrowserSendsThem() {
        XCTAssertEqual(OutsideLink.url("https://example.com/a b?c=d e")?.absoluteString,
                       "https://example.com/a%20b?c=d%20e")
        XCTAssertEqual(OutsideLink.url("https://example.com/cafe\u{0301}")?.absoluteString,
                       "https://example.com/cafe%CC%81")
        XCTAssertEqual(OutsideLink.url("https://example.com/p?q=1#a#b")?.absoluteString,
                       "https://example.com/p?q=1#a%23b")
        XCTAssertEqual(OutsideLink.url("https://example.com/already%20escaped")?.absoluteString,
                       "https://example.com/already%20escaped")
        XCTAssertNil(OutsideLink.url("#"))
        XCTAssertNil(OutsideLink.url("mailto:shop@example.com"))
        XCTAssertNil(OutsideLink.url("javascript:alert(1)"))
        XCTAssertNil(OutsideLink.url("not a web address"))
    }

    @MainActor
    func testTheUsdaLinkIsTheWebsites() {
        XCTAssertEqual(PlantPage.usdaURL("LIBE3")?.absoluteString, "https://plants.sc.egov.usda.gov/plant-profile/LIBE3")
        XCTAssertEqual(PlantPage.usdaURL("A B/C")?.absoluteString,
                       "https://plants.sc.egov.usda.gov/plant-profile/A%20B%2FC")
    }

    @MainActor
    func testTheLineUnderTheLatinNameAndTheSeasonSentence() throws {
        let model = AppModel.forLaunch(arguments: [])
        let spicebush = try XCTUnwrap(model.data.plants.first { $0.id == "lindera-benzoin" })
        guard case .found(let fairfax) = LocationLookup.zip("22030", tables: model.tables) else {
            return XCTFail("22030 was not found")
        }
        XCTAssertEqual(PlantPage.meta(spicebush, location: fairfax, geo: model.data.geo),
                       "Shrub \u{00B7} Native to Fairfax County, Virginia")
        XCTAssertEqual(PlantPage.meta(spicebush, location: nil, geo: model.data.geo), "Shrub")
        XCTAssertTrue(SeasonChart.spoken(Labels.seasonRows(spicebush))
            .hasPrefix("In leaf: March to October; Bloom: March to April"))

        let view = Sellers.buyView(spicebush, fairfax, channel: .app, data: model.data)
        XCTAssertEqual(WhereToBuySheet.summary(plant: spicebush, view: view, location: fairfax),
                       "7 sellers ship Spicebush to Virginia. None has a shop near you.")
        XCTAssertEqual(PlantPage.buySubtitle(view, location: fairfax), "7 sellers ship to Virginia")
    }
}
