import SwiftUI
import SwiftData

/// "Workout in progress": a Start tapped while another workout runs. Resume, Discard it and start new (only when
/// nothing was completed in it) and Cancel.
///
/// Drawn by the tab shell over everything (tabs, mini bar, tab bar), not a system alert: an alert runs a button's
/// action only after it has faded out, so "Discard it and start new" left the old mini bar up for most of a second
/// and the new workout came up about a second after the tap. Here the action runs at the tap, the old bar goes at
/// once and the new workout's cover comes straight up. The card follows the system alert's shape and behaviour:
/// modal for VoiceOver, the escape gesture cancels, a tap on the dimmed backdrop does nothing.
struct WorkoutStartConflictDialog: View {
    let conflict: WorkoutStarter.Conflict
    var onResume: () -> Void
    var onDiscardAndStart: () -> Void
    var onCancel: () -> Void

    @AccessibilityFocusState private var titleFocused: Bool

    var body: some View {
        ZStack {
            Color.black.opacity(0.55)
                .ignoresSafeArea()
                .contentShape(Rectangle())
                .onTapGesture {}
                .accessibilityHidden(true)

            VStack(spacing: 0) {
                VStack(spacing: 6) {
                    Text("workout.inProgress")
                        .font(NT.Fonts.headline)
                        .foregroundStyle(NT.Colors.ink)
                        .accessibilityAddTraits(.isHeader)
                        .accessibilityFocused($titleFocused)
                    Text("workout.alreadyActive.message \(conflict.activeName)")
                        .font(NT.Fonts.subheadline)
                        .foregroundStyle(NT.Colors.ink2)
                }
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 22)
                .padding(.top, 24)
                .padding(.bottom, 20)

                VStack(spacing: 8) {
                    PrimaryButton(title: "dashboard.resumeWorkout", height: 50, action: onResume)
                    if conflict.canDiscard {
                        SecondaryButton(title: "workout.alreadyActive.discardAndStart", height: 50, tint: NT.Colors.bad,
                                        action: onDiscardAndStart)
                    }
                    SecondaryButton(title: "common.cancel", height: 50, tint: NT.Colors.ink2, action: onCancel)
                }
                .padding(.horizontal, 14)
                .padding(.bottom, 14)
            }
            .frame(maxWidth: 320)
            .background(NT.Colors.surface, in: RoundedRectangle(cornerRadius: 30, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 30, style: .continuous).strokeBorder(NT.Colors.hairline, lineWidth: 1))
            .shadow(color: .black.opacity(0.45), radius: 24, y: 10)
            .padding(.horizontal, 36)
            .accessibilityElement(children: .contain)
            .accessibilityAddTraits(.isModal)
            .accessibilityAction(.escape, onCancel)
        }
        .onAppear { titleFocused = true }
    }
}

extension View {
    /// The start-conflict dialog of `session`, over this view (the tab shell). Fades in and out.
    func workoutStartConflictDialog(session: WorkoutSessionController, restTimer: RestTimerController,
                                    context: ModelContext) -> some View {
        overlay {
            ZStack {
                if let conflict = session.startConflict {
                    WorkoutStartConflictDialog(
                        conflict: conflict,
                        onResume: {
                            session.startConflict = nil
                            session.expand()
                        },
                        onDiscardAndStart: {
                            restTimer.skip()
                            WorkoutStarter.resolveByDiscarding(conflict, in: context, session: session)
                        },
                        onCancel: { session.startConflict = nil }
                    )
                    .transition(.opacity)
                }
            }
            .animation(.easeOut(duration: 0.2), value: session.startConflict?.id)
        }
    }
}
