import Foundation
import Observation
import UIKit

enum SyncError: LocalizedError {
    case notConfigured, badAddress, unauthorized, off
    case unreachable(String)
    case server(String)

    var errorDescription: String? {
        switch self {
        case .notConfigured: return tr("Add your PC's address and token first.")
        case .badAddress: return tr("The address doesn't look right. It should be like pop-os.local:8766")
        case .unauthorized: return tr("Wrong token. Copy it again from Dose on your PC (☰ → Phone sync).")
        case .off: return tr("Phone sync is switched off in Dose on your PC (☰ → Phone sync).")
        case .unreachable(let why):
            return tr("Can't reach your PC. Is it on, with Phone sync switched on, and on the same Wi-Fi? (%@)", why)
        case .server(let message): return message
        }
    }
}

enum SyncClient {
    /// "pop-os.local:8766", "192.168.1.5:8766" or a full URL.
    static func baseURL(_ pc: PCLink) throws -> URL {
        var raw = pc.cleanServer
        if raw.isEmpty || pc.cleanToken.isEmpty { throw SyncError.notConfigured }
        if !raw.lowercased().hasPrefix("http://") && !raw.lowercased().hasPrefix("https://") { raw = "http://" + raw }
        while raw.hasSuffix("/") { raw.removeLast() }
        guard let url = URL(string: raw), url.host != nil else { throw SyncError.badAddress }
        if url.port == nil, let withPort = URL(string: raw + ":8766") { return withPort }
        return url
    }

    /// Sends `payload` encrypted to `path` and returns the decrypted reply. The token is only the key.
    static func request(_ path: String, payload: Data, pc: PCLink) async throws -> Data {
        let token = pc.cleanToken
        var req = URLRequest(url: try baseURL(pc).appendingPathComponent(String(path.dropFirst())))
        req.httpMethod = "POST"
        req.timeoutInterval = 20
        req.httpBody = try SyncCrypto.seal(payload, token: token, path: path)
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let result: (Data, URLResponse)
        do {
            result = try await URLSession.shared.data(for: req)
        } catch let e as URLError where e.code == .cancelled {
            throw CancellationError()
        } catch {
            throw SyncError.unreachable(error.localizedDescription)
        }
        let data = result.0
        guard let http = result.1 as? HTTPURLResponse else { throw SyncError.server(tr("No answer from your PC.")) }
        if http.statusCode == 401 { throw SyncError.unauthorized }
        if http.statusCode == 403 { throw SyncError.off }
        if !(200..<300).contains(http.statusCode) {
            if let doc = try? JSONSerialization.jsonObject(with: data) as? [String: Any], let message = doc["error"] as? String {
                throw SyncError.server(message)
            }
            throw SyncError.server(tr("Your PC answered with error %@.", String(http.statusCode)))
        }
        do {
            return try SyncCrypto.open(data, token: token, path: path)
        } catch {
            throw SyncError.unauthorized
        }
    }

    static func ping(_ pc: PCLink) async throws -> String {
        let data = try await request("/api/e/ping", payload: Data("{}".utf8), pc: pc)
        let doc = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        return tr("Connected to Dose %@ on your PC (encrypted)", (doc?["version"] as? String) ?? "?")
    }

    private struct Outgoing: Encodable {
        var medicines: [Medicine]
        var doses: [DoseRecord]
        var deleted: [Tombstone]
        var device: String
    }

    @MainActor
    static func sync(_ store: AppStore, pc: PCLink) async throws -> Int {
        let s = store.state()
        let body = try JSONEncoder().encode(Outgoing(medicines: s.medicines, doses: s.doses, deleted: s.deleted,
                                                     device: UIDevice.current.name))
        let data = try await request("/api/e/sync", payload: body, pc: pc)
        guard let remote = try? JSONDecoder().decode(DoseState.self, from: data) else {
            throw SyncError.server(tr("Your PC sent something unexpected. Update Dose on your PC."))
        }
        return store.absorb(remote)
    }
}

/// Runs syncs: when the app opens, a couple of seconds after a change, and on request.
@MainActor
@Observable
final class SyncCoordinator {
    var status = tr("Not synced yet")
    var busy = false
    @ObservationIgnored private var pending: Task<Void, Never>?
    @ObservationIgnored private var again = false

    func syncSoon(_ store: AppStore, delay: Double) {
        guard UserDefaults.standard.bool(forKey: "autoSync"), !PCList.shared.configured.isEmpty else { return }
        pending?.cancel()
        pending = Task { [weak self] in
            if delay > 0 { try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000)) }
            if Task.isCancelled { return }
            Task { await self?.run(store) }
        }
    }

    /// Syncs with every PC in turn. When a later PC brings something new, the earlier
    /// ones get a second round, so every PC ends up with everything.
    func run(_ store: AppStore) async {
        if busy { again = true; return }
        let list = PCList.shared
        let pcs = list.configured
        guard !pcs.isEmpty else {
            status = SyncError.notConfigured.localizedDescription
            return
        }
        busy = true
        var changes = 0
        var failed: [String: String] = [:]
        var stopped = false
        rounds: for round in 0..<2 {
            var newFromLater = false
            for (i, pc) in pcs.enumerated() {
                if round == 1 && failed[pc.id] != nil { continue }
                do {
                    let n = try await SyncClient.sync(store, pc: pc)
                    changes += n
                    if n > 0 && i > 0 { newFromLater = true }
                    failed[pc.id] = nil
                    list.status[pc.id] = tr("Synced at %@", Day.nowTime)
                } catch is CancellationError {
                    stopped = true
                    break rounds
                } catch {
                    failed[pc.id] = error.localizedDescription
                    list.status[pc.id] = error.localizedDescription
                }
            }
            if !newFromLater || pcs.count < 2 { break }
        }
        if !stopped {
            if failed.count == pcs.count {
                status = pcs.count == 1 ? failed[pcs[0].id]! : tr("Couldn't sync with any PC. Tap one in Settings to see why.")
            } else {
                var text = changes > 0 ? trn("Synced at %2$@ · %1$@ change", "Synced at %2$@ · %1$@ changes", changes, Day.nowTime)
                    : tr("Synced at %@", Day.nowTime)
                for pc in pcs where failed[pc.id] != nil {
                    text += "\n" + tr("%@: not reached", pc.title)
                }
                status = text
            }
            if changes > 0 { Reminders.plan(store) }
        }
        busy = false
        if again {
            again = false
            await run(store)
        }
    }
}
