// Photos for the plant screens. A photo the app ships with is read from the bundle. A photo of a plant added after the
// app was released (named by newer downloaded data) is downloaded from the website once, at the same path, and kept in
// Caches after that. Until it is on the phone, the plant screens show the "No photograph yet" plate.
import Foundation
import HereaboutsCore

actor PhotoCache {
    private let site: URL?
    private let bundle: URL
    private let folder: URL
    private let session: URLSession

    /// `bundle` is the shipped bundle/ folder, `folder` where downloaded photos are kept. `site` nil downloads nothing.
    init(site: URL?, bundle: URL, folder: URL, session: URLSession = .shared) {
        self.site = site
        self.bundle = bundle
        self.folder = folder
        self.session = session
    }

    /// The photo's file on the phone, shipped or downloaded earlier, or nil if it is not on the phone yet.
    nonisolated func file(for photo: Photo) -> URL? {
        guard Self.isPhotoPath(photo.url) else { return nil }
        for base in [bundle, folder] {
            let file = base.appendingPathComponent(photo.url)
            if FileManager.default.fileExists(atPath: file.path) { return file }
        }
        return nil
    }

    /// The photo's file on the phone, downloading it from the website first if needed. nil when it cannot be had
    /// now: no signal, the website does not have it, or its path is not a plain file name under images/.
    func load(_ photo: Photo) async -> URL? {
        if let file = file(for: photo) { return file }
        guard Self.isPhotoPath(photo.url), let site, let url = URL(string: photo.url, relativeTo: site)?.absoluteURL else {
            return nil
        }
        let request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 30)
        guard let answer = try? await session.data(for: request),
              (answer.1 as? HTTPURLResponse)?.statusCode == 200, !answer.0.isEmpty else { return nil }
        let file = folder.appendingPathComponent(photo.url)
        do {
            try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            try answer.0.write(to: file, options: .atomic)
        } catch {
            return nil
        }
        return file
    }

    /// True for "images/<name>" with a plain file name, so data from the network can never point outside the folders.
    nonisolated static func isPhotoPath(_ path: String) -> Bool {
        guard path.hasPrefix("images/") else { return false }
        let name = path.dropFirst("images/".count)
        return !name.isEmpty && !name.hasPrefix(".")
            && name.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "." || $0 == "-" || $0 == "_") }
    }
}
