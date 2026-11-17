// Reads the bundle's data/ folder: plants.json, vendors.json, plant-regions.json and version.json.
import Foundation

/// Everything the app knows about plants and sellers, read once from the bundle.
public struct AppData: Sendable {
    public let plants: [Plant]
    /// Sellers by name. vendors.json is an object keyed by seller name, not a list.
    public let vendors: [String: Vendor]
    /// Where each plant is native, by plant id.
    public let geo: [String: PlantGeo]
    /// The ecoregion codes the site serves fully (SERVED_ECOREGIONS on the website).
    public let served: [String]
    public let version: DataVersion

    public init(plants: [Plant], vendors: [String: Vendor], geo: [String: PlantGeo], served: [String], version: DataVersion) {
        self.plants = plants
        self.vendors = vendors
        self.geo = geo
        self.served = served
        self.version = version
    }

    /// plant-regions.json: { schema, plants: { id: { eco, intro } }, served: [code] }.
    private struct Regions: Decodable {
        let plants: [String: PlantGeo]
        let served: [String]
    }

    /// Reads the four files in `dataDir` (the bundle's data/ folder). Throws if a file is missing or does not decode.
    public static func load(from dataDir: URL) throws -> AppData {
        func read(_ name: String) throws -> Data { try Data(contentsOf: dataDir.appendingPathComponent(name)) }
        let version = try JSONDecoder().decode(DataVersion.self, from: read("version.json"))
        return try decode(plants: read("plants.json"), vendors: read("vendors.json"), regions: read("plant-regions.json"),
                          version: version)
    }

    /// Decodes plants.json, vendors.json and plant-regions.json, the same way for the bundle and for a downloaded
    /// update (DataUpdate.accept). Throws if a file does not decode.
    static func decode(plants: Data, vendors: Data, regions: Data, version: DataVersion) throws -> AppData {
        let decoder = JSONDecoder()
        let r = try decoder.decode(Regions.self, from: regions)
        return AppData(
            plants: try decoder.decode([Plant].self, from: plants),
            vendors: try decoder.decode([String: Vendor].self, from: vendors),
            geo: r.plants,
            served: r.served,
            version: version
        )
    }
}
