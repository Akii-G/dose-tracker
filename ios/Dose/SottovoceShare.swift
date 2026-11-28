import Foundation
import UIKit

/// Sottovoce on this iPhone, without a PC. iPhone apps can't read each other's files, and on
/// some phones and install methods the Files picker can't open a file at all; app links work
/// everywhere. So Dose sends its medicines and recent doses inside a link to Sottovoce:
///   sottovoce://dose#<base64url of the zlib-compressed JSON, dose.json's format>
/// either when you tap Send to Sottovoce, or when Sottovoce asks with dose://share.
enum SottovoceShare {
    static let days = 400   // doses older than this aren't needed by Sottovoce

    static var sottovoceInstalled: Bool {
        URL(string: "sottovoce://").map { UIApplication.shared.canOpenURL($0) } ?? false
    }

    /// Medicines and the doses of the last `days` days, compressed for a link.
    @MainActor
    static func payload(_ store: AppStore) -> String? {
        let state = store.state()
        let since = Day.adding(-days, to: Day.today)
        let recent = DoseState(medicines: state.medicines, doses: state.doses.filter { $0.day >= since }, deleted: [])
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        guard let json = try? encoder.encode(recent),
              let packed = try? (json as NSData).compressed(using: .zlib) as Data else { return nil }
        return packed.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// Open Sottovoce with Dose's data. False when Sottovoce isn't there to take it.
    @MainActor
    @discardableResult
    static func send(_ store: AppStore) -> Bool {
        guard let data = payload(store), let url = URL(string: "sottovoce://dose#" + data) else { return false }
        UIApplication.shared.open(url) { opened in
            if opened { UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: "sentToSottovoce") }
        }
        return true
    }

    static var lastSent: Date? {
        let t = UserDefaults.standard.double(forKey: "sentToSottovoce")
        return t > 0 ? Date(timeIntervalSince1970: t) : nil
    }
}
