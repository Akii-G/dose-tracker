import ActivityKit
import AppIntents
import Foundation

/// The next dose as a Live Activity: on the lock screen, in the Dynamic Island and, from iOS 18
/// and watchOS 11, in the Apple Watch's Smart Stack. Compiled into Dose and into the widget
/// extension. Dose sends everything to show inside the state (texts already translated), so the
/// extension needs no shared storage: that keeps it working however the app is signed.
struct DoseActivityAttributes: ActivityAttributes {
    struct Item: Codable, Hashable {
        var name: String
        var amount: String
    }

    struct ContentState: Codable, Hashable {
        var day: String          // 2026-11-28
        var time: String         // 20:00
        var due: Date
        var items: [Item]        // empty when names are hidden (discreet reminders)
        var count: Int           // doses at that time
        var heading: String      // "Next dose" / "Not taken yet"
        var summary: String      // "2 doses" (shown when names are hidden, and on the Watch)
        var takenText: String    // "Taken"
        var accent: UInt32
    }
}

/// The Taken button. A LiveActivityIntent runs in Dose's own process, not in the extension.
struct TakeDoseIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "Taken"
    static var description = IntentDescription("Marks the doses of this time as taken.")
    static var isDiscoverable = false

    @Parameter(title: "Day") var day: String
    @Parameter(title: "Time") var time: String

    init() {}

    init(day: String, time: String) {
        self.day = day
        self.time = time
    }

    func perform() async throws -> some IntentResult {
        await DoseActivityActions.take(day: day, time: time)
        return .result()
    }
}

/// Dose sets the handler when it starts; in the extension it's never called.
enum DoseActivityActions {
    @MainActor static var takeHandler: ((String, String) -> Void)?

    @MainActor static func take(day: String, time: String) {
        takeHandler?(day, time)
    }
}
