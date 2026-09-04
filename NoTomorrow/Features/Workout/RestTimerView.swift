import SwiftUI
import SwiftData

/// Full rest-timer sheet: 280 pt draining ring, display time, −15 / Skip rest / +15, "Up next" card.
/// Reads everything from `RestTimerController`; closes itself when the rest elapses.
struct RestTimerView: View {
    var workout: Workout? = nil
    var upNext: UpNextTarget? = nil

    @Environment(RestTimerController.self) private var restTimer
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 0) {
            header
                .padding(.horizontal, NT.Spacing.screenH)
                .padding(.top, 20)
            ring
                .padding(.top, 48)
            controls
                .padding(.horizontal, NT.Spacing.screenH)
                .padding(.top, 40)
            upNextCard
                .padding(.horizontal, NT.Spacing.screenH)
                .padding(.top, 40)
            Spacer(minLength: 16)
            HStack(spacing: 6) {
                Image(systemName: "lock").font(.system(size: 12, weight: .semibold))
                Text("timer.lockScreen").font(NT.Fonts.footnote)
            }
            .foregroundStyle(NT.Colors.ink2)
            .padding(.bottom, 16)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .ntScreenBackground()
        .presentationDetents([.large])
        .presentationDragIndicator(.hidden)
        .onAppear { if !restTimer.isRunning { dismiss() } }
    }

    // MARK: Header

    private var header: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                Text("timer.rest").eyebrow(NT.Colors.ember)
                HStack(spacing: 0) {
                    Text(workout?.name ?? restTimer.workoutName)
                    if let workout {
                        Text(verbatim: " · ")
                        TimelineView(.periodic(from: .now, by: 1)) { ctx in
                            Text(Fmt.clock((workout.endedAt ?? ctx.date).timeIntervalSince(workout.startedAt))).tabular()
                        }
                    }
                }
                .font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink).lineLimit(1)
            }
            Spacer(minLength: 12)
            Button { dismiss() } label: {
                Image(systemName: "chevron.down")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(NT.Colors.ink)
                    .frame(width: 36, height: 36)
                    .background(NT.Colors.surface2, in: Circle())
                    .frame(width: NT.Size.control, height: NT.Size.control)
                    .contentShape(Rectangle())
            }
            .buttonStyle(PressScale())
        }
        .frame(height: NT.Size.control)
    }

    // MARK: Ring

    private var ring: some View {
        TimelineView(.periodic(from: .now, by: 1)) { ctx in
            let remaining = max(0, restTimer.endDate?.timeIntervalSince(ctx.date) ?? 0)
            let total = Double(restTimer.totalSeconds)
            ZStack {
                ProgressRing(progress: total > 0 ? remaining / total : 0, lineWidth: 12, track: NT.Colors.surface)
                    .frame(width: 280, height: 280)
                VStack(spacing: 8) {
                    Text(Fmt.clock(remaining))
                        .font(NT.Fonts.display(104)).foregroundStyle(NT.Colors.ink).tabular()
                    Text("timer.of \(Fmt.clock(total))")
                        .font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2).tabular()
                }
            }
            .onChange(of: ctx.date) { _, now in
                if let end = restTimer.endDate, end <= now {
                    restTimer.finishIfElapsed()
                    dismiss()
                }
            }
        }
    }

    // MARK: Controls

    private var controls: some View {
        HStack(spacing: 16) {
            adjustButton(delta: -15, label: "−15")
            Button {
                restTimer.skip()
                dismiss()
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "forward.end.fill").font(.system(size: 15, weight: .bold))
                    Text("timer.skip").font(NT.Fonts.headline)
                }
                .foregroundStyle(NT.Colors.onPrimary)
                .frame(maxWidth: .infinity)
                .frame(height: NT.Size.primaryButton)
                .background(NT.Colors.ink, in: Capsule())
            }
            .buttonStyle(PressScale())
            adjustButton(delta: 15, label: "+15")
        }
    }

    private func adjustButton(delta: Int, label: String) -> some View {
        Button { restTimer.adjust(by: delta) } label: {
            Text(verbatim: label)
                .font(NT.Fonts.subheadlineBold).foregroundStyle(NT.Colors.ink).tabular()
                .frame(width: 64, height: NT.Size.primaryButton)
                .background(NT.Colors.surface2, in: Capsule())
        }
        .buttonStyle(PressScale())
    }

    // MARK: Up next

    private var upNextCard: some View {
        NTCard(padding: 16) {
            VStack(alignment: .leading, spacing: 12) {
                Text("timer.upNext").eyebrow()
                HStack(alignment: .center) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(upNext?.exerciseName ?? restTimer.exerciseName)
                            .font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink).lineLimit(1)
                        Text(upNextLine)
                            .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2).tabular().lineLimit(1)
                    }
                    Spacer(minLength: 12)
                    if let upNext, upNext.weightKg > 0 || upNext.reps > 0 {
                        HStack(alignment: .firstTextBaseline, spacing: 4) {
                            Text(Fmt.weight(upNext.weightKg, withUnit: false))
                                .font(NT.Fonts.display(32)).foregroundStyle(NT.Colors.ink).tabular()
                            Text(verbatim: "kg ×").font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2)
                            Text("\(upNext.reps)")
                                .font(NT.Fonts.display(32)).foregroundStyle(NT.Colors.ink).tabular()
                        }
                    }
                }
            }
            .padding(.horizontal, 2)
        }
    }

    private var upNextLine: String {
        guard let upNext else { return restTimer.nextSetLabel }
        if let kg = upNext.bestKg, let reps = upNext.bestReps {
            return "\(upNext.setLabel) · \(String(localized: "workout.best")): \(Fmt.set(kg, reps))"
        }
        return upNext.setLabel
    }
}
