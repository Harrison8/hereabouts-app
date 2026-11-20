// Keeps the chosen county on the phone, the way the website does: only the county code and the position, rounded to
// two decimal places (about 1 km) so it cannot point at a house. The county's name, state and ecoregion are looked
// up again from the shipped tables on every load, so a data update reaches a county chosen earlier. Nothing here is
// ever sent anywhere.
import Foundation
import HereaboutsCore

@MainActor
struct LocationStore {
    static let key = "hereabouts.location"
    let defaults: UserDefaults

    /// What is written to the phone.
    struct Kept: Codable, Equatable {
        let countyFips: String
        let lat: Double?
        let lon: Double?
    }

    /// The kept location, looked up again in `tables`. Nil when none is set, when what is kept no longer reads, or
    /// when the county is no longer one the tables serve.
    func load(tables: Tables) -> Location? {
        guard let data = defaults.data(forKey: Self.key),
              let kept = try? JSONDecoder().decode(Kept.self, from: data),
              kept.countyFips.count == 5, kept.countyFips.allSatisfy({ $0.isASCII && $0.isNumber }) else { return nil }
        guard case .found(let location) = LocationLookup.resolve(countyFips: kept.countyFips, lat: kept.lat,
                                                                 lon: kept.lon, tables: tables) else { return nil }
        return location
    }

    /// Keeps the county code and the rounded position of `location`, or forgets it when nil.
    func save(_ location: Location?) {
        guard let location else {
            defaults.removeObject(forKey: Self.key)
            return
        }
        let kept = Kept(countyFips: location.countyFips,
                        lat: location.lat.map(LocationLookup.roundPosition),
                        lon: location.lon.map(LocationLookup.roundPosition))
        guard let data = try? JSONEncoder().encode(kept) else { return }
        defaults.set(data, forKey: Self.key)
    }

    /// `location` with its position rounded the same way it is kept.
    static func rounded(_ location: Location) -> Location {
        Location(countyFips: location.countyFips, countyName: location.countyName, stateFips: location.stateFips,
                 stateName: location.stateName, stateUsps: location.stateUsps, ecoregion: location.ecoregion,
                 lat: location.lat.map(LocationLookup.roundPosition), lon: location.lon.map(LocationLookup.roundPosition))
    }
}
