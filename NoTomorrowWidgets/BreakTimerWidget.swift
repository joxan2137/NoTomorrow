import SwiftUI
import WidgetKit

/// Break timer: start a rest in one tap, watch it count down, +15 / Skip without opening the app. The buttons are
/// `LiveActivityIntent`s run by the app's `RestTimerController`, so this is the same rest as the workout's (Live
/// Activity, notification, chime). `docs/widgets.md`, "Break timer".
struct BreakTimerWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: WidgetKind.rest, provider: BreakProvider()) { entry in
            BreakTimerView(entry: entry)
                .widgetGround()
                .widgetURL(RestTimerAttributes.deepLink)
        }
        .configurationDisplayName("widget.rest.name")
        .description("widget.rest.description")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

struct BreakEntry: TimelineEntry {
    enum Phase: Equatable { case idle, running, justEnded }

    let date: Date
    let phase: Phase
    let rest: SharedRestState
    let snapshot: WidgetSnapshot?

    var defaultSeconds: Int { snapshot?.defaultRestSeconds ?? 90 }
}

struct BreakProvider: TimelineProvider {
    /// How long "Rest is over. Go." stays after the end.
    static let endedFor: TimeInterval = 120

    func placeholder(in context: Context) -> BreakEntry {
        BreakEntry(date: .now, phase: .idle, rest: sample, snapshot: nil)
    }

    func getSnapshot(in context: Context, completion: @escaping (BreakEntry) -> Void) {
        completion(entries(now: .now).first ?? placeholder(in: context))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<BreakEntry>) -> Void) {
        // The app reloads this timeline on every start, ±15 and skip; nothing else changes it.
        completion(Timeline(entries: entries(now: .now), policy: .never))
    }

    private func entries(now: Date) -> [BreakEntry] {
        let snapshot = WidgetStore.readSnapshot()
        WidgetText.languageOverride = snapshot?.languageOverride
        let rest = SharedRestState.load()
        guard let end = rest.endDate else { return [BreakEntry(date: now, phase: .idle, rest: rest, snapshot: snapshot)] }
        if end > now {
            return [BreakEntry(date: now, phase: .running, rest: rest, snapshot: snapshot),
                    BreakEntry(date: end, phase: .justEnded, rest: rest, snapshot: snapshot),
                    BreakEntry(date: end.addingTimeInterval(Self.endedFor), phase: .idle, rest: rest, snapshot: snapshot)]
        }
        if now.timeIntervalSince(end) < Self.endedFor {
            return [BreakEntry(date: now, phase: .justEnded, rest: rest, snapshot: snapshot),
                    BreakEntry(date: end.addingTimeInterval(Self.endedFor), phase: .idle, rest: rest, snapshot: snapshot)]
        }
        return [BreakEntry(date: now, phase: .idle, rest: rest, snapshot: snapshot)]
    }

    private var sample: SharedRestState {
        SharedRestState(endDate: nil, totalSeconds: 90, exerciseName: "", nextSetLabel: "", workoutName: "")
    }
}

struct BreakTimerView: View {
    let entry: BreakEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        switch (entry.phase, family) {
        case (.running, .systemMedium):
            HStack(spacing: 16) {
                ring(size: 110, font: 34)
                VStack(alignment: .leading, spacing: 4) {
                    Text(verbatim: WidgetText.string("timer.rest")).eyebrowStyle(W.ember)
                    detailLines
                    Spacer(minLength: 6)
                    HStack(spacing: 8) {
                        Button(intent: AdjustBreakIntent(seconds: -15)) { CapsuleFace(title: "−15") }.buttonStyle(.plain)
                        Button(intent: AdjustBreakIntent(seconds: 15)) { CapsuleFace(title: "+15") }.buttonStyle(.plain)
                        Button(intent: SkipBreakIntent()) { CapsuleFace(title: WidgetText.string("common.skip")) }.buttonStyle(.plain)
                    }
                }
            }
        case (.running, _):
            VStack(spacing: 8) {
                ring(size: 84, font: 26)
                HStack(spacing: 6) {
                    Button(intent: AdjustBreakIntent(seconds: 15)) { CapsuleFace(title: "+15", height: 32) }.buttonStyle(.plain)
                    Button(intent: SkipBreakIntent()) { CapsuleFace(title: WidgetText.string("common.skip"), height: 32) }.buttonStyle(.plain)
                }
            }
        case (_, .systemMedium):
            HStack(alignment: .center, spacing: 16) {
                idleRing(size: 110, font: 34)
                VStack(alignment: .leading, spacing: 4) {
                    idleHeader
                    caption
                    Spacer(minLength: 6)
                    HStack(spacing: 6) { presetButtons(height: 36) }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            }
        default:
            VStack(alignment: .leading, spacing: 0) {
                idleHeader
                Text(verbatim: WidgetText.duration(entry.defaultSeconds))
                    .font(W.display(44)).monospacedDigit().foregroundStyle(W.ink)
                caption
                Spacer(minLength: 6)
                HStack(spacing: 5) { presetButtons(height: 32) }
            }
        }
    }

    // MARK: Parts

    private var idleHeader: some View {
        Text(verbatim: WidgetText.string("timer.rest")).eyebrowStyle()
    }

    private var caption: some View {
        let ended = entry.phase == .justEnded
        return Text(verbatim: WidgetText.string(ended ? "timer.notification.title" : "widget.rest.start"))
            .font(W.caption).foregroundStyle(ended ? W.ember : W.ink2)
            .lineLimit(2).fixedSize(horizontal: false, vertical: true)
    }

    @ViewBuilder
    private func presetButtons(height: CGFloat) -> some View {
        let lengths = BreakPresets.lengths(default: entry.defaultSeconds)
        ForEach(lengths, id: \.self) { seconds in
            Button(intent: StartBreakIntent(seconds: seconds)) {
                CapsuleFace(title: WidgetText.duration(seconds), primary: seconds == entry.defaultSeconds, height: height)
            }
            .buttonStyle(.plain)
        }
    }

    @ViewBuilder
    private var detailLines: some View {
        if !entry.rest.exerciseName.isEmpty {
            Text(verbatim: entry.rest.exerciseName).font(W.subheadlineBold).foregroundStyle(W.ink).lineLimit(1)
        }
        if !entry.rest.nextSetLabel.isEmpty {
            Text(verbatim: entry.rest.nextSetLabel).font(W.footnote).foregroundStyle(W.ink2).lineLimit(1)
        }
    }

    /// The idle ring: the empty track with the default rest inside, where the running ring will count down.
    private func idleRing(size: CGFloat, font: CGFloat) -> some View {
        ZStack {
            Circle().stroke(W.track, lineWidth: 6)
            Text(verbatim: WidgetText.duration(entry.defaultSeconds))
                .font(W.display(font)).monospacedDigit().foregroundStyle(W.ink)
                .lineLimit(1).minimumScaleFactor(0.6)
                .frame(width: size - 20)
        }
        .frame(width: size, height: size)
    }

    /// The system draws the countdown and the ring from the rest's own start…end, so both tick without timeline
    /// entries (the fixed range also keeps them from trapping once the end has passed, as in the Live Activity).
    private func ring(size: CGFloat, font: CGFloat) -> some View {
        let end = entry.rest.endDate ?? entry.date
        let start = end.addingTimeInterval(-Double(max(1, entry.rest.totalSeconds)))
        return ZStack {
            Circle().stroke(W.track, lineWidth: 6)
            ProgressView(timerInterval: start...end, countsDown: true, label: { EmptyView() }, currentValueLabel: { EmptyView() })
                .progressViewStyle(.circular)
                .tint(W.ember)
            Text(timerInterval: start...end, countsDown: true)
                .font(W.display(font)).monospacedDigit().foregroundStyle(W.ink)
                .multilineTextAlignment(.center)
                .frame(width: size - 20)
        }
        .frame(width: size, height: size)
    }
}
