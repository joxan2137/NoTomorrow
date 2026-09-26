import SwiftUI
import SwiftData

/// Edit a custom exercise (long press in the exercise picker): its name, main muscle and equipment. The equipment
/// matters beyond the label: a barbell exercise's warm-up ramp starts with the empty bar.
struct CustomExerciseEditor: View {
    let exercise: Exercise

    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var context
    @State private var name = ""
    @State private var muscle: String?
    @State private var equipment: String?

    static let muscles = ["chest", "lats", "middle back", "lower back", "traps", "shoulders", "biceps", "triceps",
                          "forearms", "abdominals", "quadriceps", "hamstrings", "glutes", "calves", "adductors",
                          "abductors", "neck"]
    static let equipmentOptions = ["barbell", "dumbbell", "cable", "machine", "body only", "kettlebells", "bands",
                                   "e-z curl bar", "other"]

    private var trimmed: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button { dismiss() } label: {
                    Text("common.cancel").font(NT.Fonts.body).foregroundStyle(NT.Colors.ink2).frame(minHeight: NT.Size.control)
                }
                Spacer()
                Text("customExercise.edit").font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink)
                Spacer()
                Button(action: save) {
                    Text("common.save").font(NT.Fonts.headline)
                        .foregroundStyle(trimmed.isEmpty ? NT.Colors.ink3 : NT.Colors.ember)
                        .frame(minHeight: NT.Size.control)
                }
                .disabled(trimmed.isEmpty)
            }
            .padding(.horizontal, NT.Spacing.screenH)
            .padding(.top, 16)
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    STGroup {
                        HStack(spacing: 12) {
                            Text("workout.edit.name").font(NT.Fonts.body).foregroundStyle(NT.Colors.ink)
                                .accessibilityHidden(true)
                            TextField("", text: $name)
                                .font(NT.Fonts.body).foregroundStyle(NT.Colors.ink)
                                .accessibilityLabel(Text("workout.edit.name"))
                                .multilineTextAlignment(.trailing)
                                .submitLabel(.done)
                        }
                        .frame(minHeight: NT.Size.control)
                    }
                    choices(title: "customExercise.muscle", options: Self.muscles, selection: $muscle,
                            label: WorkoutStrings.muscle)
                    choices(title: "customExercise.equipment", options: Self.equipmentOptions, selection: $equipment,
                            label: WorkoutStrings.equipment)
                }
                .padding(.horizontal, NT.Spacing.screenH)
                .padding(.top, 12)
                .padding(.bottom, NT.Spacing.section)
            }
        }
        .ntScreenBackground()
        .presentationBackground(NT.Colors.ground)
        .presentationDragIndicator(.visible)
        .onAppear {
            name = exercise.name
            muscle = exercise.primaryMuscles.first
            equipment = exercise.equipment
        }
    }

    private func choices(title: LocalizedStringKey, options: [String], selection: Binding<String?>,
                         label: @escaping (String) -> String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).eyebrow().padding(.leading, 16)
            BroFlowLayout(spacing: 8) {
                ForEach(options, id: \.self) { option in
                    Chip(title: label(option), isSelected: selection.wrappedValue == option) {
                        selection.wrappedValue = selection.wrappedValue == option ? nil : option
                    }
                }
            }
        }
    }

    /// The picker's Delete exercise (offered only while no workout uses it). Routine lines have no inverse
    /// relationship to their exercise, so they are let go of first: a line left pointing at a deleted exercise
    /// would fault on its next read (Train list, Today card). The routine keeps the line without an exercise, as
    /// after an Android delete (`SET NULL`); the editor and Start already skip such lines.
    @MainActor
    static func delete(_ exercise: Exercise, in context: ModelContext) {
        let id = exercise.persistentModelID
        let items = (try? context.fetch(FetchDescriptor<RoutineItem>())) ?? []
        for item in items where item.exercise?.persistentModelID == id { item.exercise = nil }
        context.delete(exercise)
        try? context.save()
    }

    private func save() {
        exercise.name = trimmed
        exercise.primaryMuscles = muscle.map { [$0] } ?? []
        exercise.equipment = equipment
        try? context.save()
        NotificationCenter.default.post(name: .workoutHistoryDidChange, object: nil)
        dismiss()
    }
}
