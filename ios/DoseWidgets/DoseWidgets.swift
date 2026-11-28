import ActivityKit
import AppIntents
import SwiftUI
import WidgetKit

@main
struct DoseWidgetBundle: WidgetBundle {
    var body: some Widget {
        NextDoseLiveActivity()
    }
}

private extension Color {
    init(rgb: UInt32) {
        self.init(red: Double((rgb >> 16) & 0xff) / 255, green: Double((rgb >> 8) & 0xff) / 255,
                  blue: Double(rgb & 0xff) / 255)
    }
}

/// The next dose: lock screen (and the Watch's Smart Stack), Dynamic Island.
struct NextDoseLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: DoseActivityAttributes.self) { context in
            NextDoseView(state: context.state)
        } dynamicIsland: { context in
            let state = context.state
            let accent = Color(rgb: state.accent)
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Label(state.time, systemImage: "pills.fill")
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(accent)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    TakenButton(state: state)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    Names(state: state).frame(maxWidth: .infinity, alignment: .leading)
                }
            } compactLeading: {
                Image(systemName: "pills.fill").foregroundStyle(accent)
            } compactTrailing: {
                Text(state.time).foregroundStyle(accent)
            } minimal: {
                Image(systemName: "pills.fill").foregroundStyle(accent)
            }
        }
        .supplementalActivityFamilies([.small])   // the Apple Watch's Smart Stack
    }
}

private struct TakenButton: View {
    let state: DoseActivityAttributes.ContentState

    var body: some View {
        Button(intent: TakeDoseIntent(day: state.day, time: state.time)) {
            Label(state.takenText, systemImage: "checkmark")
                .font(.subheadline.weight(.semibold))
        }
        .buttonStyle(.borderedProminent)
        .tint(Color(rgb: state.accent))
    }
}

private struct Names: View {
    let state: DoseActivityAttributes.ContentState

    var body: some View {
        if state.items.isEmpty {
            Text(state.summary).font(.subheadline).foregroundStyle(.secondary)
        } else {
            VStack(alignment: .leading, spacing: 2) {
                ForEach(Array(state.items.prefix(3).enumerated()), id: \.offset) { _, item in
                    HStack(spacing: 6) {
                        Text(item.name).font(.subheadline.weight(.medium)).lineLimit(1)
                        Text(item.amount).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
                if state.items.count > 3 {
                    Text("+\(state.items.count - 3)").font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }
}

private struct NextDoseView: View {
    let state: DoseActivityAttributes.ContentState
    @Environment(\.activityFamily) private var family

    var body: some View {
        let accent = Color(rgb: state.accent)
        if family == .small {
            // Apple Watch: little room, so the time, how many, and the button
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Image(systemName: "pills.fill").foregroundStyle(accent)
                    Text(state.time).font(.headline)
                    Spacer(minLength: 0)
                }
                Text(state.items.count == 1 ? state.items[0].name : state.summary)
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                TakenButton(state: state)
            }
            .padding(8)
        } else {
            HStack(alignment: .center, spacing: 14) {
                ZStack {
                    Circle().fill(accent.opacity(0.18))
                    Image(systemName: "pills.fill").font(.title3).foregroundStyle(accent)
                }
                .frame(width: 46, height: 46)
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 6) {
                        Text(state.heading).font(.caption.weight(.semibold)).foregroundStyle(accent)
                        Text(state.time).font(.caption.weight(.semibold))
                    }
                    Names(state: state)
                }
                Spacer(minLength: 0)
                TakenButton(state: state)
            }
            .padding(16)
        }
    }
}
