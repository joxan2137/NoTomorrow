import SwiftUI
import SwiftData

/// What the mini bar says at one moment. Pure, so the phrasing rules are testable.
struct WorkoutMiniBarState: Equatable {
    /// "42:10" / "1:02:03" since the workout started.
    var elapsed: String
    /// Seconds of rest left, nil when not resting.
    var restRemaining: TimeInterval?
    /// Share of the rest still to go (1 → 0), for the ring.
    var restFraction: Double
    /// Resting: the exercise up next. Otherwise: the exercise the user is on. Empty when there is none.
    var exerciseName: String

    var isResting: Bool { restRemaining != nil }

    init(startedAt: Date, now: Date, restEnd: Date?, restTotal: Int, upNextName: String, currentName: String?) {
        elapsed = Fmt.elapsed(now.timeIntervalSince(startedAt))
        if let restEnd, restEnd > now {
            let remaining = restEnd.timeIntervalSince(now)
            restRemaining = remaining
            restFraction = restTotal > 0 ? min(1, remaining / Double(restTotal)) : 0
            exerciseName = upNextName.isEmpty ? (currentName ?? "") : upNextName
        } else {
            restRemaining = nil
            restFraction = 0
            exerciseName = currentName ?? ""
        }
    }
}

/// The workout in progress, pinned above the tab bar on every tab: name, elapsed time and current exercise;
/// while resting, the countdown, what is up next and a Skip button. Tapping anywhere else expands the workout.
/// Stateless: everything comes from the session and the rest timer, so collapsing and tab switches lose nothing.
struct WorkoutMiniBar: View {
    @Environment(WorkoutSessionController.self) private var session
    @Environment(RestTimerController.self) private var restTimer
    @Environment(\.modelContext) private var context

    var body: some View {
        if let workout = session.workout(in: context), workout.isActive {
            // Not `restTimer.isRunning`: that only flips when something calls `finishIfElapsed()`.
            TimelineView(.periodic(from: .now, by: 1)) { ctx in
                bar(workout, state: state(for: workout, now: ctx.date), now: ctx.date)
            }
        }
    }

    private func state(for workout: Workout, now: Date) -> WorkoutMiniBarState {
        let current = session.model?.currentExercise
            ?? ActiveWorkoutModel.currentExercise(in: workout.sortedExercises, expandedID: nil)
        return WorkoutMiniBarState(startedAt: workout.startedAt, now: now,
                                   restEnd: restTimer.endDate, restTotal: restTimer.totalSeconds,
                                   upNextName: restTimer.exerciseName,
                                   currentName: current?.exercise?.localizedName)
    }

    private func bar(_ workout: Workout, state: WorkoutMiniBarState, now: Date) -> some View {
        HStack(spacing: 8) {
            Button { session.expand() } label: {
                HStack(spacing: 12) {
                    leading(state)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(WorkoutStrings.displayName(workout.name))
                            .font(NT.Fonts.subheadlineBold).foregroundStyle(NT.Colors.ink).lineLimit(1)
                        detail(state)
                    }
                    Spacer(minLength: 0)
                    if !state.isResting {
                        Image(systemName: "chevron.up")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundStyle(NT.Colors.ink2)
                            .frame(width: 24, height: 24)
                    }
                }
                .frame(maxHeight: .infinity)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("workout.miniBar.label \(WorkoutStrings.displayName(workout.name)) \(Fmt.duration(now.timeIntervalSince(workout.startedAt)))"))
            .accessibilityValue(accessibilityValue(state))
            .accessibilityHint(Text("workout.miniBar.hint"))

            if state.isResting {
                Button { restTimer.skip() } label: {
                    Text("common.skip")
                        .font(NT.Fonts.footnoteBold).foregroundStyle(NT.Colors.onPrimary)
                        .padding(.horizontal, 14)
                        .frame(height: 32)
                        .background(NT.Colors.ink, in: Capsule())
                        .frame(minHeight: NT.Size.control)
                        .contentShape(Rectangle())
                }
                .buttonStyle(PressScale())
            }
        }
        .padding(.leading, 12)
        .padding(.trailing, state.isResting ? 8 : 12)
        .frame(height: WorkoutMiniBar.height)
        .miniBarChrome()
    }

    @ViewBuilder
    private func leading(_ state: WorkoutMiniBarState) -> some View {
        if state.isResting {
            ProgressRing(progress: state.restFraction, lineWidth: 3)
                .frame(width: 24, height: 24)
        } else {
            Circle().fill(NT.Colors.ember)
                .frame(width: 8, height: 8)
                .frame(width: 24, height: 24)
        }
    }

    /// "42:10 · Bench press", or while resting "Rest 1:12 · Squat" with the countdown in ember.
    private func detail(_ state: WorkoutMiniBarState) -> some View {
        HStack(spacing: 0) {
            Group {
                if let remaining = state.restRemaining {
                    Text("timer.rest")
                    Text(verbatim: " ")
                    Text(Fmt.clock(remaining)).foregroundStyle(NT.Colors.ember)
                } else {
                    Text(state.elapsed)
                }
            }
            .layoutPriority(1)
            if !state.exerciseName.isEmpty {
                Text(verbatim: " · \(state.exerciseName)")
            }
        }
        .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2).tabular().lineLimit(1)
    }

    private func accessibilityValue(_ state: WorkoutMiniBarState) -> Text {
        guard let remaining = state.restRemaining else { return Text(verbatim: state.exerciseName) }
        let rest = "\(Fmt.localized("timer.rest")) \(Fmt.clock(remaining))"
        return Text(verbatim: state.exerciseName.isEmpty ? rest : "\(rest), \(state.exerciseName)")
    }

    static let height: CGFloat = NT.Size.cardButton
}

/// Pins the bar above the tab bar via a bottom safe-area inset, so the tab's own bottom insets (Fuel's add bar)
/// stack above it. Stays out of the way while the keyboard is up, like a bar attached to the tab bar would.
private struct WorkoutMiniBarInset: ViewModifier {
    var isEnabled: Bool
    @State private var isKeyboardUp = false

    func body(content: Content) -> some View {
        content
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if isEnabled && !isKeyboardUp {
                    WorkoutMiniBar()
                        .padding(.horizontal, NT.Spacing.screenH)
                        .padding(.bottom, 8)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            .animation(.spring(response: 0.35, dampingFraction: 0.85), value: isEnabled)
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { _ in
                isKeyboardUp = true
            }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in
                isKeyboardUp = false
            }
    }
}

extension View {
    /// The workout mini bar on a tab. Apply to each tab's root content, unconditionally (`isEnabled` shows and hides it).
    ///
    /// iOS 26's `tabViewBottomAccessory(isEnabled:)` would be the native container, but the iOS 26.1 SDK in
    /// Xcode 26.1.1 does not declare that overload, and the 26.0 one leaves an empty glass capsule on 26.1 when
    /// hidden, so every iOS version uses this inset (glass on iOS 26, a surface capsule before).
    func workoutMiniBar(isEnabled: Bool) -> some View {
        modifier(WorkoutMiniBarInset(isEnabled: isEnabled))
    }

    @ViewBuilder
    fileprivate func miniBarChrome() -> some View {
        if #available(iOS 26.0, *) {
            glassEffect(.regular.interactive(), in: Capsule())
        } else {
            background(NT.Colors.surface, in: Capsule())
                .overlay(Capsule().strokeBorder(NT.Colors.hairline, lineWidth: 1))
                .shadow(color: .black.opacity(0.5), radius: 12, y: 8)
        }
    }
}
