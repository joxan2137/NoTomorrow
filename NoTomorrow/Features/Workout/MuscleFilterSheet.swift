import SwiftUI

/// The exercise picker's body-map filter: tap a muscle on the figure (or its chip), see how many exercises train it,
/// then show them. "Clear" goes back to the group chips.
struct MuscleFilterSheet: View {
    var initial: String?
    /// Library exercises whose primary muscles include the muscle.
    var count: (String) -> Int
    var onPick: (String?) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var current: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    BodyMapView(fill: { $0 == current ? NT.Colors.ember : NT.Colors.surface3 },
                                bodyColor: NT.Colors.surface2,
                                gap: NT.Colors.ground,
                                onTap: { muscle in
                                    guard let muscle else { return }
                                    withAnimation(.easeOut(duration: 0.15)) { current = muscle }
                                })
                        .frame(maxHeight: 360)
                    Group {
                        if let current {
                            Text(verbatim: WorkoutStrings.muscle(current) + " · " + WorkoutStrings.exercises(count(current)))
                                .foregroundStyle(NT.Colors.ink)
                        } else {
                            Text("exercises.tapMuscle").foregroundStyle(NT.Colors.ink3)
                        }
                    }
                    .font(NT.Fonts.subheadline)
                    BroFlowLayout(spacing: 6) {
                        ForEach(BodyMap.muscles, id: \.self) { muscle in
                            Button {
                                withAnimation(.easeOut(duration: 0.15)) { current = muscle }
                            } label: {
                                Text(WorkoutStrings.muscle(muscle))
                                    .font(current == muscle ? NT.Fonts.subheadlineBold : NT.Fonts.subheadline)
                                    .foregroundStyle(current == muscle ? NT.Colors.onPrimary : NT.Colors.ink)
                                    .padding(.horizontal, 12)
                                    .frame(height: 32)
                                    .background(current == muscle ? NT.Colors.ink : NT.Colors.surface, in: Capsule())
                            }
                            .buttonStyle(PressScale())
                        }
                    }
                }
                .padding(.horizontal, NT.Spacing.screenH)
                .padding(.bottom, 16)
            }
            .safeAreaInset(edge: .bottom) {
                PrimaryButton(title: LocalizedStringKey(current.map { String(format: String(localized: "exercises.showMatching"), locale: .current, WorkoutStrings.exercises(count($0))) } ?? String(localized: "exercises.pickMuscle"))) {
                    onPick(current)
                    dismiss()
                }
                .disabled(current == nil)
                .opacity(current == nil ? 0.5 : 1)
                .padding(.horizontal, NT.Spacing.screenH)
                .padding(.vertical, 8)
                .background(NT.Colors.ground)
            }
            .navigationTitle(Text("exercises.bodyMap"))
            .navigationBarTitleDisplayMode(.inline)
            .ntScreenBackground()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("common.cancel") { dismiss() } }
                if initial != nil {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("exercises.clearFilter") {
                            onPick(nil)
                            dismiss()
                        }
                    }
                }
            }
        }
        .onAppear { current = initial }
        .presentationDragIndicator(.visible)
    }
}
