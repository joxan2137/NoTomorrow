import SwiftUI
import SwiftData
import MetalFxKit

/// Multi-select exercise picker sheet (design/Exercises.dc.html): search, muscle and equipment chips, result cards, create row, "Add n".
/// The search field has a liquid-metal edge that brightens while it has focus.
struct ExercisePickerView: View {
    /// Ids of exercises already in the workout; shown as "In" and not selectable.
    var alreadyIn: Set<String>
    var onAdd: ([Exercise]) -> Void
    /// When set, chosen exercises are appended to this workout (prefilled rows) before `onAdd` runs.
    private var targetWorkout: Workout?

    init(alreadyIn: Set<String> = [], onAdd: @escaping ([Exercise]) -> Void) {
        self.alreadyIn = alreadyIn
        self.onAdd = onAdd
    }

    /// Convenience for the active workout: hides what is already in it and appends the selection itself.
    init(workout: Workout, onAdd: @escaping ([Exercise]) -> Void = { _ in }) {
        self.alreadyIn = Set(workout.exercises.compactMap { $0.exercise?.id })
        self.onAdd = onAdd
        self.targetWorkout = workout
    }

    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var modelContext
    @Query private var profiles: [UserProfile]
    @State private var detail: Exercise?
    @State private var editing: Exercise?
    @State private var model = ExercisePickerViewModel()
    @FocusState private var searchFocused: Bool

    private var unit: WeightUnit { profiles.first?.units ?? .kg }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            searchField
                .padding(.horizontal, NT.Spacing.screenH)
                .padding(.top, 12)
            chips
                .padding(.top, 12)
            equipmentChips
                .padding(.top, 8)
            results
        }
        .ntScreenBackground()
        .safeAreaInset(edge: .bottom) { addBar }
        .presentationBackground(NT.Colors.ground)
        .presentationDragIndicator(.visible)
        .onAppear { model.load(context: modelContext) }
        .sheet(item: $detail) { ExerciseDetailView(exercise: $0) }
        .sheet(item: $editing, onDismiss: { model.load(context: modelContext) }) { CustomExerciseEditor(exercise: $0) }
    }

    // MARK: Header

    private var header: some View {
        HStack {
            Text("exercises.add")
                .font(NT.Fonts.title2)
                .foregroundStyle(NT.Colors.ink)
            Spacer()
            Button { dismiss() } label: {
                Text("common.cancel")
                    .font(NT.Fonts.body)
                    .foregroundStyle(NT.Colors.ink2)
                    .frame(minHeight: NT.Size.control)
            }
        }
        .frame(height: NT.Size.control)
        .padding(.horizontal, NT.Spacing.screenH)
        .padding(.top, 16)
    }

    // MARK: Search

    private var searchField: some View {
        MetalFx(variant: .button, preset: .chromatic, theme: .dark,
                strength: searchFocused ? 1 : ExercisePickerView.idleMetalStrength, ringWidth: 1,
                cornerRadius: Double(NT.Radius.field), glow: false, tilt: false, fill: NT.Colors.surface) {
            searchFieldContent
        }
    }

    /// The edge stays visible but quiet until the field is tapped.
    private static let idleMetalStrength: Double = 0.45

    private var searchFieldContent: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(NT.Colors.ink2)
            TextField("exercises.search", text: $model.query)
                .font(NT.Fonts.body)
                .foregroundStyle(NT.Colors.ink)
                .autocorrectionDisabled()
                .submitLabel(.search)
                .focused($searchFocused)
            if !model.query.isEmpty {
                Button {
                    model.query = ""
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 9, weight: .heavy))
                        .foregroundStyle(NT.Colors.ground)
                        .frame(width: 20, height: 20)
                        .background(NT.Colors.ink3, in: Circle())
                        .frame(width: NT.Size.control, height: NT.Size.control)
                        .contentShape(Rectangle())
                }
            }
        }
        .padding(.leading, 14)
        .padding(.trailing, model.query.isEmpty ? 14 : 2)
        .frame(height: NT.Size.control)
    }

    // MARK: Chips (bleed to the screen edge)

    private var chips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(ExerciseLibrary.MuscleGroup.allCases) { group in
                    Chip(title: String(localized: String.LocalizationValue(group.titleKey)),
                         isSelected: model.group == group) {
                        model.group = group
                    }
                }
            }
            .padding(.horizontal, NT.Spacing.screenH)
        }
    }

    /// Any equipment, Barbell, Dumbbell…: a second row under the muscles, combined with them and the search.
    private var equipmentChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(ExerciseLibrary.Equipment.allCases) { equipment in
                    Chip(title: String(localized: String.LocalizationValue(equipment.titleKey)),
                         isSelected: model.equipment == equipment) {
                        model.equipment = equipment
                    }
                }
            }
            .padding(.horizontal, NT.Spacing.screenH)
        }
    }

    // MARK: Results

    private var results: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text(WorkoutStrings.results(model.results.count)).eyebrow()
                    Spacer()
                    Text("workout.last").eyebrow().padding(.trailing, ExerciseResultCard.lastColumnTrailing)
                }
                .padding(.top, 18)
                .padding(.bottom, 8)

                ForEach(model.results) { entry in
                    ExerciseResultCard(exercise: entry.exercise, unit: unit, state: state(for: entry.exercise)) {
                        model.toggle(entry.exercise.id)
                    } onDetails: {
                        detail = entry.exercise
                    }
                    .padding(.bottom, 8)
                    .contextMenu {
                        if entry.exercise.isCustom {
                            Button("customExercise.edit", systemImage: "pencil") { editing = entry.exercise }
                            if entry.exercise.usages.isEmpty {
                                Button("customExercise.delete", systemImage: "trash", role: .destructive) {
                                    model.deselect(entry.exercise.id)
                                    modelContext.delete(entry.exercise)
                                    try? modelContext.save()
                                    model.load(context: modelContext)
                                }
                            }
                        }
                    }
                }

                if model.showsCreateRow {
                    CreateExerciseRow(query: model.trimmedQuery) {
                        model.createExercise(context: modelContext)
                        searchFocused = false
                    }
                } else if model.results.isEmpty {
                    Text("exercises.noResults")
                        .font(NT.Fonts.subheadline)
                        .foregroundStyle(NT.Colors.ink2)
                        .padding(.vertical, 12)
                }
            }
            .padding(.horizontal, NT.Spacing.screenH)
            .padding(.bottom, 12)
        }
        .scrollDismissesKeyboard(.immediately)
    }

    private func state(for exercise: Exercise) -> ExercisePickerRow.State {
        if alreadyIn.contains(exercise.id) { return .alreadyIn }
        return model.isSelected(exercise.id) ? .selected : .available
    }

    // MARK: Add bar

    @ViewBuilder
    private var addBar: some View {
        if model.selectedCount > 0 {
            PrimaryButton(title: LocalizedStringKey(WorkoutStrings.add(model.selectedCount))) {
                let exercises = model.selectedExercises()
                let now = Date.now
                exercises.forEach { $0.lastUsedAt = now }
                if let targetWorkout {
                    var order = (targetWorkout.exercises.map(\.order).max() ?? -1) + 1
                    for exercise in exercises {
                        WorkoutStarter.append(exercise, to: targetWorkout, order: order, in: modelContext)
                        order += 1
                    }
                }
                try? modelContext.save()
                onAdd(exercises)
                dismiss()
            }
            .padding(.horizontal, NT.Spacing.screenH)
            .padding(.top, 8)
            .padding(.bottom, 8)
            .background(NT.Colors.ground)
            .transition(.move(edge: .bottom).combined(with: .opacity))
        }
    }
}
