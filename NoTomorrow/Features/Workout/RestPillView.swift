import SwiftUI

/// Floating rest pill above the bottom edge of the active workout: draining ring, mm:ss, +15, Skip.
/// Time is derived from `RestTimerController.endDate` inside a `TimelineView` — no timer of its own.
struct RestPillView: View {
    @Environment(RestTimerController.self) private var restTimer
    var onTap: () -> Void

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { ctx in
            let remaining = max(0, restTimer.endDate?.timeIntervalSince(ctx.date) ?? 0)
            let fraction = restTimer.totalSeconds > 0 ? remaining / Double(restTimer.totalSeconds) : 0
            HStack(spacing: 12) {
                Button(action: onTap) {
                    HStack(spacing: 12) {
                        ProgressRing(progress: fraction, lineWidth: 3)
                            .frame(width: 36, height: 36)
                        VStack(alignment: .leading, spacing: 0) {
                            Text("timer.rest").eyebrow()
                            Text(Fmt.clock(remaining))
                                .font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink).tabular()
                        }
                        Spacer(minLength: 0)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)

                Button { restTimer.adjust(by: 15) } label: {
                    Text(verbatim: "+15")
                        .font(NT.Fonts.subheadlineBold).foregroundStyle(NT.Colors.ink).tabular()
                        .padding(.horizontal, 14)
                        .frame(height: 36)
                        .background(NT.Colors.surface2, in: Capsule())
                }
                .buttonStyle(PressScale())

                Button { restTimer.skip() } label: {
                    Text("common.skip")
                        .font(NT.Fonts.subheadlineBold).foregroundStyle(NT.Colors.onPrimary)
                        .padding(.horizontal, 14)
                        .frame(height: 36)
                        .background(NT.Colors.ink, in: Capsule())
                }
                .buttonStyle(PressScale())
            }
            .padding(.leading, 10)
            .padding(.trailing, 8)
            .frame(height: 56)
            .background(NT.Colors.surface, in: Capsule())
            .overlay(Capsule().strokeBorder(NT.Colors.hairline, lineWidth: 1))
            .shadow(color: .black.opacity(0.5), radius: 12, y: 8)
        }
    }
}

/// Invisible observer that closes out the rest timer the second it elapses (ends the Live Activity, haptic),
/// so `restTimer.isRunning` flips through observation and the pill disappears.
struct RestTimerExpiryWatcher: View {
    @Environment(RestTimerController.self) private var restTimer

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { ctx in
            Color.clear
                .onChange(of: ctx.date) { _, now in
                    if let end = restTimer.endDate, end <= now { restTimer.finishIfElapsed() }
                }
        }
        .frame(width: 0, height: 0)
        .allowsHitTesting(false)
    }
}
