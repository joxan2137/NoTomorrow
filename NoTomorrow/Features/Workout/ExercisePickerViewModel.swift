import Foundation
import Observation
import SwiftData

/// Search + muscle filter over the exercise library. Debounces typing (200 ms) and matches every
/// query token case- and diacritic-insensitively against the English and Polish names.
@MainActor
@Observable
final class ExercisePickerViewModel {

    struct Entry: Identifiable {
        let exercise: Exercise
        let folded: String
        var id: String { exercise.id }
    }

    var query = "" {
        didSet {
            // Starting a search drops the body-map muscle, so a name typed in full isn't hidden by it.
            if oldValue.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !trimmedQuery.isEmpty && muscle != nil {
                muscle = nil
            }
            scheduleFilter()
        }
    }
    var group: ExerciseLibrary.MuscleGroup = .all {
        didSet {
            muscle = nil
            applyFilter()
        }
    }
    /// One muscle picked on the body map; replaces the group chip until cleared.
    var muscle: String? {
        didSet { applyFilter() }
    }
    private(set) var results: [Entry] = []

    /// Selected exercise ids in tap order.
    private(set) var selectedIDs: [String] = []
    var selectedCount: Int { selectedIDs.count }

    private var all: [Entry] = []
    private var filterTask: Task<Void, Never>?

    var trimmedQuery: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }
    /// Offers "Create «…»" unless the library already has an exercise by that name, filtered out or not.
    var showsCreateRow: Bool {
        let name = WorkoutStrings.fold(trimmedQuery)
        guard !name.isEmpty else { return false }
        return !all.contains { WorkoutStrings.fold($0.exercise.name) == name || WorkoutStrings.fold($0.exercise.namePL ?? "") == name }
    }

    // MARK: Loading

    func load(context: ModelContext) {
        let descriptor = FetchDescriptor<Exercise>(sortBy: [SortDescriptor(\.name)])
        let exercises = (try? context.fetch(descriptor)) ?? []
        all = exercises
            .sorted { lhs, rhs in
                // Recently used first, then alphabetical.
                let l = lhs.lastUsedAt ?? .distantPast, r = rhs.lastUsedAt ?? .distantPast
                if l != r { return l > r }
                return lhs.name.localizedStandardCompare(rhs.name) == .orderedAscending
            }
            .map { Entry(exercise: $0, folded: WorkoutStrings.fold([$0.name, $0.namePL ?? ""].joined(separator: " "))) }
        applyFilter()
    }

    // MARK: Filtering

    private func scheduleFilter() {
        filterTask?.cancel()
        filterTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(200))
            guard !Task.isCancelled else { return }
            self?.applyFilter()
        }
    }

    private func applyFilter() {
        let tokens = WorkoutStrings.fold(trimmedQuery).split(separator: " ").map(String.init)
        let muscles = muscle.map { Set([$0]) } ?? group.muscles
        results = all.filter { entry in
            if !muscles.isEmpty && !entry.exercise.primaryMuscles.contains(where: muscles.contains) { return false }
            return tokens.allSatisfy { entry.folded.contains($0) }
        }
    }

    /// Library exercises with `muscle` among their primary muscles, for the body-map filter.
    func count(for muscle: String) -> Int {
        all.reduce(0) { $0 + ($1.exercise.primaryMuscles.contains(muscle) ? 1 : 0) }
    }

    // MARK: Selection

    func isSelected(_ id: String) -> Bool { selectedIDs.contains(id) }

    func toggle(_ id: String) {
        if let index = selectedIDs.firstIndex(of: id) {
            selectedIDs.remove(at: index)
        } else {
            selectedIDs.append(id)
        }
    }

    /// Selected exercises in tap order.
    func selectedExercises() -> [Exercise] {
        let byID = Dictionary(all.map { ($0.exercise.id, $0.exercise) }, uniquingKeysWith: { first, _ in first })
        return selectedIDs.compactMap { byID[$0] }
    }

    // MARK: Custom exercises

    /// One representative free-exercise-db muscle per chip, for custom exercises.
    private static let representativeMuscle: [ExerciseLibrary.MuscleGroup: String] = [
        .chest: "chest", .back: "lats", .legs: "quadriceps", .shoulders: "shoulders", .arms: "biceps", .core: "abdominals",
    ]

    /// Inserts a custom exercise named after the query, selects it and clears the search so it shows at the top.
    @discardableResult
    func createExercise(context: ModelContext) -> Exercise? {
        let name = trimmedQuery
        guard !name.isEmpty else { return nil }
        let muscles = Self.representativeMuscle[group].map { [$0] } ?? []
        let exercise = Exercise(id: "custom-\(UUID().uuidString.lowercased())", name: name, primaryMuscles: muscles, isCustom: true)
        exercise.lastUsedAt = .now
        context.insert(exercise)
        try? context.save()
        selectedIDs.append(exercise.id)
        query = ""
        load(context: context)
        return exercise
    }
}
