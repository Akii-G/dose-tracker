import Foundation

/// Share with Sottovoce on this iPhone, without a PC: Dose keeps a file up to date where the user
/// saved it once (On My iPhone, iCloud Drive…), and Sottovoce reads that same file. The file is
/// dose.json, the format Sottovoce already reads on the PC. Remembered as a security-scoped
/// bookmark. (A file, not a folder: picking a folder doesn't work with every iPhone and install
/// method; saving one file works everywhere.)
enum SottovoceShare {
    static let fileName = "dose-for-sottovoce.json"
    private static let key = "sottovoceFile"
    /// The file picked in this session: used directly, it doesn't depend on the bookmark.
    nonisolated(unsafe) private static var picked: URL?

    static var placeName: String? { UserDefaults.standard.string(forKey: "sottovoceFileName") }

    @MainActor
    static func content(_ store: AppStore) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(store.state())
    }

    /// A first copy in the app's temporary folder, for the Save picker to put where you choose.
    @MainActor
    static func draft(_ store: AppStore) throws -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("sottovoce-draft", isDirectory: true)
        try? FileManager.default.removeItem(at: dir)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent(fileName)
        try content(store).write(to: url, options: .atomic)
        return url
    }

    /// Remember the saved file, so Dose can keep writing to it after restarting.
    static func remember(file: URL) throws {
        let ok = file.startAccessingSecurityScopedResource()
        defer { if ok { file.stopAccessingSecurityScopedResource() } }
        // No .minimalBookmark: on iPhone that kind of bookmark loses the permission to write.
        let data = try file.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil)
        picked = file
        UserDefaults.standard.set(data, forKey: key)
        UserDefaults.standard.set(file.deletingLastPathComponent().lastPathComponent + " › " + file.lastPathComponent,
                                  forKey: "sottovoceFileName")
    }

    static func forget() {
        picked = nil
        UserDefaults.standard.removeObject(forKey: key)
        UserDefaults.standard.removeObject(forKey: "sottovoceFileName")
        UserDefaults.standard.removeObject(forKey: "sottovoceError")
    }

    static var lastError: String? {
        placeName == nil ? nil : UserDefaults.standard.string(forKey: "sottovoceError")
    }

    private static func file() -> URL? {
        if let picked { return picked }
        guard let data = UserDefaults.standard.data(forKey: key) else { return nil }
        var stale = false
        guard let url = try? URL(resolvingBookmarkData: data, options: [], relativeTo: nil, bookmarkDataIsStale: &stale)
        else { return nil }
        if stale { try? remember(file: url) }
        picked = url
        return url
    }

    /// Write the file again (after each change). Returns why it failed, or nil.
    @MainActor
    @discardableResult
    static func write(_ store: AppStore) -> String? {
        guard placeName != nil else { return nil }
        guard let url = file() else { return note(tr("Couldn't find the file. Save it again.")) }
        let ok = url.startAccessingSecurityScopedResource()
        defer { if ok { url.stopAccessingSecurityScopedResource() } }
        do {
            let data = try content(store)
            var failure: Error?
            var coordination: NSError?
            NSFileCoordinator().coordinate(writingItemAt: url, options: .forReplacing, error: &coordination) { target in
                do { try data.write(to: target, options: .atomic) } catch { failure = error }
            }
            if let error = failure ?? coordination.map({ $0 as Error }) { return note(error.localizedDescription) }
            return note(nil)
        } catch {
            return note(error.localizedDescription)
        }
    }

    private static func note(_ error: String?) -> String? {
        if let error { UserDefaults.standard.set(error, forKey: "sottovoceError") }
        else { UserDefaults.standard.removeObject(forKey: "sottovoceError") }
        return error
    }
}
