// Checks the hand-written SHA-256 against the standard published values, and against the bundle's own data hash.
import Foundation
import XCTest
@testable import HereaboutsCore

final class SHA256Tests: XCTestCase {
    func testStandardValues() {
        XCTAssertEqual(SHA256.hex(Data()), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        XCTAssertEqual(SHA256.hex(Data("abc".utf8)), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        XCTAssertEqual(SHA256.hex(Data("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".utf8)),
                       "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1")
        XCTAssertEqual(SHA256.hex(Data(repeating: UInt8(ascii: "a"), count: 1_000_000)),
                       "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0")
    }

    func testBundleFilesGiveTheirDataHash() throws {
        let dir = Expected.bundleDir.appendingPathComponent("data")
        var all = Data()
        for name in ["plants.json", "vendors.json", "plant-regions.json"] {
            all.append(try Data(contentsOf: dir.appendingPathComponent(name)))
        }
        let version = try JSONDecoder().decode(DataVersion.self, from: Data(contentsOf: dir.appendingPathComponent("version.json")))
        XCTAssertEqual(SHA256.hex(all), version.dataHash)
    }
}
