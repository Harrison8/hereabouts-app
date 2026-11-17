// Checks the links to sellers against the website's: every case in apps/shared/bundle/expected/links.json is
// buyUrl(entry, vendors[vendor], ctx) from affiliates.js, for every seller entry of every plant and some crafted odd
// addresses. Also the button's safeHref step and the Google Maps directions link from index.html.
import Foundation
import XCTest
@testable import HereaboutsCore

final class LinksTests: XCTestCase {
    private func loadData() throws -> AppData {
        try AppData.load(from: Expected.bundleDir.appendingPathComponent("data"))
    }

    private static let caseFields: Set<String> = ["entry", "vendor", "ctx", "result"]
    private static let ctxFields: Set<String> = ["ecoregion", "plantId", "siteDomain", "channel"]

    func testEveryLinkCase() throws {
        let data = try loadData()
        let cases = Expected.cases("links.json")
        var bad: [String] = []
        var crafted = 0
        for (n, c) in cases.enumerated() {
            let ctx = c["ctx"] as? [String: Any] ?? [:]
            guard Set(c.keys) == Self.caseFields, Set(ctx.keys) == Self.ctxFields else {
                bad.append("case \(n): unknown fields \(c.keys.sorted()) / \(ctx.keys.sorted())")
                continue
            }
            let e = c["entry"] as? [String: Any] ?? [:]
            let name = c["vendor"] as? String ?? ""
            guard let plantId = ctx["plantId"] as? String, let siteDomain = ctx["siteDomain"] as? String,
                  let channel = Channel(rawValue: ctx["channel"] as? String ?? ""), e["name"] as? String == name else {
                bad.append("case \(n): ctx or entry not readable")
                continue
            }
            if e["url"] != nil, !(e["url"] is String) { bad.append("case \(n): entry url is not text") }
            let entry = VendorEntry(name: name, url: e["url"] as? String, price: e["price"] as? String, size: e["size"] as? String,
                                    shipsTo: (e["shipsTo"] as? [Any])?.compactMap { $0 as? String })
            let vendor = data.vendors[name] ?? Vendor(name: name)
            if data.vendors[name] == nil { crafted += 1 }
            let ecoregion = ctx["ecoregion"] as? String
            let want = c["result"] as? String
            let got = Links.buyUrl(entry: entry, vendor: vendor, ecoregion: ecoregion, plantId: plantId, channel: channel,
                                   siteDomain: siteDomain)
            if got != want {
                bad.append("case \(n) (\(name), \(entry.url ?? "no url"), \(ecoregion ?? "null"), \(channel)): got \(got ?? "nil"), expected \(want ?? "nil")")
            }
        }
        print("links.json: \(cases.count) link cases")
        XCTAssertGreaterThan(cases.count, 1000)
        XCTAssertEqual(crafted, 0, "every seller in links.json is in vendors.json")
        if !bad.isEmpty {
            XCTFail("\(bad.count) of \(cases.count) link cases differ; first:\n" + bad.prefix(5).joined(separator: "\n"))
        }
    }

    func testAppLinkEndsInApp() throws {
        let data = try loadData()
        let gfw = try XCTUnwrap(data.vendors["Garden for Wildlife"])
        XCTAssertEqual(gfw.affiliate, true)
        var found: (Plant, VendorEntry)? = nil
        for p in data.plants {
            if let e = p.vendors.first(where: { $0.name == gfw.name }), e.url != nil {
                found = (p, e)
                break
            }
        }
        let (plant, entry) = try XCTUnwrap(found, "no plant is sold by Garden for Wildlife")
        let app = try XCTUnwrap(Links.buyUrl(entry: entry, vendor: gfw, ecoregion: "71", plantId: plant.id, channel: .app,
                                             siteDomain: "hereabouts.garden"))
        let web = try XCTUnwrap(Links.buyUrl(entry: entry, vendor: gfw, ecoregion: "71", plantId: plant.id, channel: .web,
                                             siteDomain: "hereabouts.garden"))
        XCTAssertTrue(app.contains("sca_source=71__\(plant.id)__app"), app)
        XCTAssertTrue(web.contains("sca_source=71__\(plant.id)"), web)
        XCTAssertFalse(web.contains("__app"), web)
        XCTAssertEqual(Links.subId(ecoregion: "71", plantId: "asclepias-tuberosa", channel: .app), "71__asclepias-tuberosa__app")
        XCTAssertEqual(Links.subId(ecoregion: nil, plantId: "asclepias-tuberosa", channel: .web), "none__asclepias-tuberosa")
        XCTAssertEqual(Links.subId(ecoregion: "", plantId: "x", channel: .web), "none__x")

        // The buy button in the app carries the app tag too.
        let view = Sellers.buyView(plant, nil, channel: .app, data: data)
        let row = try XCTUnwrap(view.rows.first { $0.name == gfw.name })
        XCTAssertTrue(row.href.contains("__app"), row.href)
        XCTAssertTrue(view.hasAffiliate)
    }

    func testSafeHrefKeepsOnlyWebLinks() {
        // index.html's safeHref: the button gets the link itself when it is http or https, and "#" for anything else.
        XCTAssertEqual(Links.safeHref("mailto:orders@example.com?subject=Aster"), "#")
        XCTAssertEqual(Links.safeHref("javascript:alert(1)"), "#")
        XCTAssertEqual(Links.safeHref("not a web address"), "#")
        XCTAssertEqual(Links.safeHref(""), "#")
        XCTAssertEqual(Links.safeHref(nil), "#")
        XCTAssertEqual(Links.safeHref("https://example.com/plants/aster?size=quart"), "https://example.com/plants/aster?size=quart")
        XCTAssertEqual(Links.safeHref("http://example.com/plants/aster"), "http://example.com/plants/aster")
        // The link is kept exactly as written, not tidied: the website passes the text through.
        XCTAssertEqual(Links.safeHref("HTTPS://Example.com/wild aster"), "HTTPS://Example.com/wild aster")
    }

    func testUnsupportedWebAddressKeepsAWorkingButton() {
        // Valid http or https addresses that WebURL does not rewrite. The website tags them; the app sends them out
        // as written, and the button must still work. Seller data reaches phones without an app update, so a seller
        // could add one of these after the app is built.
        let vendor = Vendor(name: "Some Nursery")
        for url in ["https://user@shop.example.com/p", "https://192.168.1.10/p", "https://caf\u{E9}.example/p",
                    "https://[2001:db8::1]/p"] {
            let entry = VendorEntry(name: vendor.name, url: url, price: nil, size: nil, shipsTo: nil)
            let link = Links.buyUrl(entry: entry, vendor: vendor, ecoregion: "71", plantId: "asclepias-tuberosa",
                                    channel: .app, siteDomain: Links.siteDomain)
            XCTAssertEqual(link, url)
            XCTAssertNotEqual(Links.safeHref(link), "#", url)
        }
        // The scheme check still comes first, and an address the browser rejects is still not a web address.
        XCTAssertEqual(Links.safeHref("mailto:user@example.com"), "#")
        XCTAssertEqual(Links.safeHref("ftp://user@example.com/p"), "#")
        XCTAssertEqual(Links.safeHref("https://user@/p"), "#")
        XCTAssertEqual(Links.safeHref("https://user@shop.example.com:99999/p"), "#")
        XCTAssertEqual(Links.safeHref("https://user@shop example.com/p"), "#")
    }

    func testGardenForWildlifeLinkFindsMarksBeforeAccents() {
        // affiliates.js uses indexOf('#') and includes('?'), which look at single code units. A "#" or "?" followed
        // by an accent mark is one Character in Swift, so the search must look at code points.
        let sub = "71__asclepias-tuberosa"
        XCTAssertEqual(Links.gardenForWildlifeLink("https://gardenforwildlife.com/products/aster#\u{301}d", sub),
                       "https://gardenforwildlife.com/products/aster?sca_ref=\(Links.gardenForWildlifeRef)&sca_source=\(sub)#\u{301}d")
        XCTAssertEqual(Links.gardenForWildlifeLink("https://gardenforwildlife.com/products/aster?\u{301}v=1", sub),
                       "https://gardenforwildlife.com/products/aster?\u{301}v=1&sca_ref=\(Links.gardenForWildlifeRef)&sca_source=\(sub)")
    }

    func testDirectionsURL() {
        // directionsHref: the shop's point, written the way JavaScript writes numbers (38, not 38.0).
        let shop = Vendor(name: "Shop", coords: [34.518252, -89.030319])
        XCTAssertEqual(Links.directionsURL(shop), "https://www.google.com/maps/dir/?api=1&destination=34.518252%2C-89.030319")
        XCTAssertEqual(Links.directionsURL(Vendor(name: "Shop", coords: [38, -85.5])),
                       "https://www.google.com/maps/dir/?api=1&destination=38%2C-85.5")
        XCTAssertNil(Links.directionsURL(Vendor(name: "Shop")))
        XCTAssertNil(Links.directionsURL(Vendor(name: "Shop", coords: [38])))
    }

    func testJavaScriptNumberText() {
        XCTAssertEqual(JSText.numberString(38), "38")
        XCTAssertEqual(JSText.numberString(-0.0), "0")
        XCTAssertEqual(JSText.numberString(38.1), "38.1")
        XCTAssertEqual(JSText.numberString(-85.58), "-85.58")
        XCTAssertEqual(JSText.numberString(0.000001), "0.000001")
        XCTAssertEqual(JSText.numberString(0.0000001), "1e-7")
        XCTAssertEqual(JSText.numberString(1.5e-7), "1.5e-7")
        XCTAssertEqual(JSText.numberString(1e16), "10000000000000000")
        XCTAssertEqual(JSText.numberString(1e21), "1e+21")
        XCTAssertEqual(JSText.numberString(1.25e22), "1.25e+22")
        XCTAssertEqual(JSText.numberString(123456.789), "123456.789")
        XCTAssertEqual(JSText.encodeURIComponent("71__a-b.c_d!~*'()\u{E9} ,"), "71__a-b.c_d!~*'()%C3%A9%20%2C")
    }
}
