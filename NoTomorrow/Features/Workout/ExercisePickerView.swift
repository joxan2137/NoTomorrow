import SwiftUI
import SwiftData
import MetalFxKit

/// Multi-select exercise picker sheet (design/Exercises.dc.html): search, muscle chips, result cards, create row, "Add n".
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
            results
        }
        .ntScreenBackground()
        .safeAreaInset(edge: .bottom) { addBar }
        .presentationBackground(NT.Colors.ground)
        .presentationDragIndicator(.visible)
        .onAppear { model.load(context: modelContext) }
        .sheet(item: $detail) { ExerciseDetailView(exercise: $0) }
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

    /// A 52 pt capsule in a glowing liquid-metal ring (1.5 pt, a little bolder than the library's 1 pt pill),
    /// brighter while it has focus. The metal circle at its end adds the picked exercises and shows how many there
    /// are — the libraries.dev composer pattern, with the send button as "add".
    private var searchField: some View {
        MetalFx(variant: .button, preset: .chromatic, theme: .dark,
                strength: searchFocused ? 1 : ExercisePickerView.idleMetalStrength, ringWidth: 1.5,
                tilt: false, fill: NT.Colors.surface) {
            HStack(spacing: 8) {
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
                            .frame(width: 36, height: ExercisePickerView.searchHeight)
                            .contentShape(Rectangle())
                    }
                }
                addCircle
            }
            .padding(.leading, 18)
            .padding(.trailing, 7)
            .frame(height: ExercisePickerView.searchHeight)
        }
    }

    private static let searchHeight: CGFloat = 52
    /// The ring stays visible but quieter until the field is tapped.
    private static let idleMetalStrength: Double = 0.7

    /// The metal "add" circle: the picked count once there is one (tap adds them), a faint plus before that.
    private var addCircle: some View {
        let count = model.selectedCount
        return Button(action: addSelected) {
            MetalFx(variant: .circle, preset: .chromatic, theme: .dark, strength: count > 0 ? 1 : 0.4,
                    innerShadow: true, glow: count > 0, tilt: false, fill: NT.Colors.surface2) {
                Group {
                    if count > 0 {
                        Text(verbatim: "\(count)")
                            .font(NT.Fonts.subheadlineBold)
                            .foregroundStyle(NT.Colors.ink)
                            .contentTransition(.numericText())
                    } else {
                        Image(systemName: "plus")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(NT.Colors.ink3)
                    }
                }
                .frame(width: 38, height: 38)
            }
        }
        .buttonStyle(PressScale())
        .disabled(count == 0)
        .animation(.snappy(duration: 0.25), value: count)
        .accessibilityLabel(Text(WorkoutStrings.add(max(count, 1))))
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

    /// "Add n exercises" as a liquid-metal pill (the libraries.dev "Upgrade to Pro" button), once something is picked.
    @ViewBuilder
    private var addBar: some View {
        if model.selectedCount > 0 {
            Button(action: addSelected) {
                MetalFx(variant: .button, preset: .chromatic, theme: .dark, tilt: false, fill: NT.Colors.surface2) {
                    Text(WorkoutStrings.add(model.selectedCount))
                        .font(NT.Fonts.headline)
                        .foregroundStyle(NT.Colors.ink)
                        .lineLimit(1)
                        .contentTransition(.numericText())
                        .frame(maxWidth: .infinity)
                        .frame(height: NT.Size.primaryButton)
                }
            }
            .buttonStyle(PressScale())
            .animation(.snappy(duration: 0.25), value: model.selectedCount)
            .padding(.horizontal, NT.Spacing.screenH)
            .padding(.top, 8)
            .padding(.bottom, 8)
            .background(NT.Colors.ground)
            .transition(.move(edge: .bottom).combined(with: .opacity))
        }
    }

    /// Appends the picked exercises (to the target workout, if any), reports them and closes the picker.
    private func addSelected() {
        guard model.selectedCount > 0 else { return }
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
}
