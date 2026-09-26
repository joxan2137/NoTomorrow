import Foundation
import Observation
import SwiftData

/// Search + muscle and equipment filters over the exercise library. Debounces typing (200 ms) and matches every
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
        didSet { scheduleFilter() }
    }
    var group: ExerciseLibrary.MuscleGroup = .all {
        didSet { applyFilter() }
    }
    private(set) var results: [Entry] = []

    /// Selected exercise ids in tap order.
    private(set) var selectedIDs: [String] = []
    var selectedCount: Int { selectedIDs.count }

    private var all: [Entry] = []
    private var filterTask: Task<Void, Never>?

    var trimmedQuery: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }
    var showsCreateRow: Bool { !trimmedQuery.isEmpty }

    /// The equipment chip row; combines with the muscle chips and the search.
    var equipment: ExerciseLibrary.Equipment = .all {
        didSet { applyFilter() }
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
        let muscles = group.muscles
        results = all.filter { entry in
            if !muscles.isEmpty && !entry.exercise.primaryMuscles.contains(where: muscles.contains) { return false }
            if !equipment.matches(entry.exercise.equipment) { return false }
            return tokens.allSatisfy { entry.folded.contains($0) }
        }
    }

    // MARK: Favorites

    /// Starred exercise ids (`FavoriteExercises`), read once per picker and kept in step by `toggleFavorite`.
    private(set) var favoriteIDs: Set<String> = FavoriteExercises.ids()

    func isFavorite(_ id: String) -> Bool { favoriteIDs.contains(id) }

    func toggleFavorite(_ id: String) {
        favoriteIDs = FavoriteExercises.toggle(id)
    }

    /// With no search text the favorites among the results sit in their own section on top.
    var sections: FavoriteExercises.Sections<Entry> {
        FavoriteExercises.sections(results, favorites: favoriteIDs, isSearching: !trimmedQuery.isEmpty, id: { $0.id })
    }

    // MARK: Selection

    func isSelected(_ id: String) -> Bool { selectedIDs.contains(id) }

    func deselect(_ id: String) {
        selectedIDs.removeAll { $0 == id }
    }

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
        let exercise = Exercise(id: "custom-\(UUID().uuidString.lowercased())", name: name, primaryMuscles: muscles,
                                equipment: equipment.representative, isCustom: true)
        exercise.lastUsedAt = .now
        context.insert(exercise)
        try? context.save()
        selectedIDs.append(exercise.id)
        query = ""
        load(context: context)
        return exercise
    }
}
