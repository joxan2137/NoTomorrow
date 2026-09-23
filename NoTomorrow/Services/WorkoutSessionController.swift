import Foundation
import Observation
import SwiftData

/// Single source of truth for the workout in progress, shared by the full-screen workout, the mini bar on every tab
/// and the start guards. The workout itself lives in SwiftData; this holds which one it is (persisted, by `Workout.id`),
/// whether the full screen is up or collapsed into the mini bar, and the per-workout UI model, so collapsing and
/// expanding keep the open exercise, the PR hint, "up next" and the summary.
@Observable
@MainActor
final class WorkoutSessionController {
    /// `Workout.id` of the workout in progress, or of the one whose summary is on screen (its `endedAt` is stamped).
    private(set) var activeWorkoutID: UUID? {
        didSet {
            persist()
            if activeWorkoutID != model?.workout.id { model = nil }
        }
    }
    /// Full screen up (true) or collapsed into the mini bar (false). This is the request; SwiftUI cannot always honour
    /// it (a Fuel or Settings sheet is up), so `isCoverOnScreen` says what is really presented.
    var showsActiveWorkout = false
    /// The full-screen cover is really on screen (its content is in a window).
    private(set) var isCoverOnScreen = false
    /// Identity of the cover's presenter (`MainTabView`). Bumped to replace a presenter SwiftUI has wedged, see `present`.
    private(set) var coverEpoch = 0
    /// Set by a rest notification / Live Activity tap; the full screen opens the rest sheet and clears it.
    var wantsRestSheet = false
    /// Per-workout UI state shared by the full screen and the mini bar. Survives a collapse, dropped on `end()`.
    private(set) var model: ActiveWorkoutModel?
    /// A Start tapped while another workout is in progress: the tab shell asks what to do
    /// (`WorkoutStartConflictDialog`), above every tab, the mini bar and the tab bar.
    var startConflict: WorkoutStarter.Conflict?
    /// The mini bar stays away although a workout is in progress: a discard-and-start swapped workouts, and the new
    /// one's cover is sliding up over the spot (`hideMiniBarWhileSwapping`).
    private(set) var hidesMiniBar = false
    /// The mini bar is shown: a workout in progress, not being swapped out.
    var showsMiniBar: Bool { isWorkoutInProgress && !hidesMiniBar }

    @ObservationIgnored private let defaults: UserDefaults
    /// Workouts on their way out (deleted a moment after `discard`): never adopted again meanwhile.
    @ObservationIgnored private var discarding: Set<UUID> = []
    @ObservationIgnored private var swapBarTask: Task<Void, Never>?

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if let raw = defaults.string(forKey: Keys.active), let id = UUID(uuidString: raw) {
            activeWorkoutID = id
        }
        // Pre-mini-bar builds stored a PersistentIdentifier; `restore(in:)` adopts that workout again.
        defaults.removeObject(forKey: Keys.legacy)
    }

    // MARK: State

    var hasActiveWorkout: Bool { activeWorkoutID != nil }
    /// On the summary the workout is already finished: no mini bar, nothing to resume.
    var isShowingSummary: Bool { model?.showsSummary == true }
    /// A workout is running: drives the mini bar on every tab (covered while the full screen is up) and "Resume".
    var isWorkoutInProgress: Bool { hasActiveWorkout && !isShowingSummary }

    // MARK: Lookups

    /// The session's workout whatever its state (in progress or on its summary). Never adopts and never mutates,
    /// so it is safe in a view body.
    func workout(in context: ModelContext) -> Workout? {
        guard let id = activeWorkoutID else { return nil }
        // The cached model is trusted only while its row is alive: a row deleted under it (account deletion) is
        // detached from its context, and reading it would crash. The fetch then finds nothing.
        if let model, !model.workout.isDeleted, model.workout.modelContext != nil, model.workout.id == id {
            return model.workout
        }
        return Self.fetchWorkout(id, in: context)
    }

    /// The workout in progress. Adopts the newest unfinished workout when the session lost track of it
    /// (cleared defaults, an older build) and lets go of one that has finished. Not for view bodies.
    func activeWorkout(in context: ModelContext) -> Workout? {
        if let workout = workout(in: context), workout.isActive { return workout }
        if isShowingSummary { return nil }
        let descriptor = FetchDescriptor<Workout>(predicate: #Predicate { $0.endedAt == nil },
                                                  sortBy: [SortDescriptor(\.startedAt, order: .reverse)])
        let found = ((try? context.fetch(descriptor)) ?? []).first { !discarding.contains($0.id) }
        if activeWorkoutID != found?.id { activeWorkoutID = found?.id }
        if found == nil {
            showsActiveWorkout = false
            wantsRestSheet = false
        }
        return found
    }

    /// The model behind the full screen and the mini bar for `workout`, created once per workout.
    /// Call from `onAppear` / actions, never from a view body (it mutates observed state).
    func model(for workout: Workout, context: ModelContext) -> ActiveWorkoutModel {
        if let model, model.workout.id == workout.id { return model }
        let created = ActiveWorkoutModel(workout: workout, context: context)
        model = created
        return created
    }

    // MARK: Actions

    /// A new workout was started: it becomes the session's and opens full screen.
    func begin(_ workout: Workout) {
        activeWorkoutID = workout.id
        wantsRestSheet = false
        present()
    }

    /// Opens the workout in progress full screen (mini bar tap, "Resume", notification). `restSheet` also opens the rest sheet.
    func expand(restSheet: Bool = false) {
        guard hasActiveWorkout else { return }
        wantsRestSheet = restSheet
        present()
    }

    /// Asks for the cover. A request UIKit refused (a route that arrived while a tab's sheet was still animating in)
    /// leaves `showsActiveWorkout` true with nothing on screen, and SwiftUI's presenter stays wedged: setting the flag
    /// again, or off and on, presents nothing. Then the presenter is replaced (`coverEpoch`), and the new one presents
    /// the flag it finds set.
    private func present() {
        if showsActiveWorkout && !isCoverOnScreen { coverEpoch += 1 }
        startConflict = nil   // a notification opening the workout answers the start dialog too
        showsActiveWorkout = true
    }

    /// The cover's content appeared / went away (`ActiveWorkoutCover`).
    func coverDidAppear() {
        isCoverOnScreen = true
    }

    func coverDidDisappear() {
        isCoverOnScreen = false
    }

    /// Collapses the full screen into the mini bar. Set edits are written through already; the save makes them durable.
    func collapse(context: ModelContext) {
        try? context.save()
        hidesMiniBar = false
        showsActiveWorkout = false
    }

    /// Discard-and-start: the old workout's mini bar leaves at once instead of turning into the new workout's bar
    /// for the length of the cover's slide. Back after `swapBarDelay` (under the cover by then), or on a collapse.
    func hideMiniBarWhileSwapping() {
        hidesMiniBar = true
        swapBarTask?.cancel()
        swapBarTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: Self.swapBarDelay)
            guard !Task.isCancelled else { return }
            self?.hidesMiniBar = false
        }
    }

    static let swapBarDelay: Duration = .milliseconds(900)

    /// Done on the summary, or the workout was discarded: the session lets go and the full screen closes.
    func end() {
        activeWorkoutID = nil
        showsActiveWorkout = false
        wantsRestSheet = false
        model = nil
        hidesMiniBar = false
    }

    /// Account deletion: lets go of the workout in progress and of any pending discard, persisted ones included,
    /// since every workout row is about to be deleted. With the rows gone, a relaunch has nothing to adopt.
    func forgetAll() {
        end()
        startConflict = nil
        discarding.removeAll()
        persistDiscarding()
    }

    /// Drops a workout nobody wants (no completed sets). The session lets go at once; the row is deleted a moment
    /// later, after the full screen and the mini bar have animated out, so nothing renders a deleted model.
    /// The pending delete is persisted, so a kill inside that window is finished by `restore(in:)`.
    /// A workout reopened from its summary ("Edit sets") and emptied had its start day counted by that Finish; the
    /// day is reverted now, locally and on the backend (`WorkoutEditor.releaseAttendance`).
    @discardableResult
    func discard(_ workout: Workout, context: ModelContext, today: Date = .now) -> Task<Void, Never> {
        let id = workout.id
        WorkoutEditor.releaseAttendance(of: workout, in: context, today: today)
        discarding.insert(id)
        persistDiscarding()
        if activeWorkoutID == id { end() }
        return Task { @MainActor [weak self] in
            try? await Task.sleep(for: .milliseconds(700))
            guard !Task.isCancelled else { return }
            // Gone already (account deletion in the meantime): a deleted row must not be touched again.
            if workout.modelContext != nil, !workout.isDeleted {
                context.delete(workout)
                try? context.save()
            }
            self?.discarding.remove(id)
            self?.persistDiscarding()
        }
    }

    // MARK: Launch

    /// Launch pass: finishes an interrupted discard, closes orphaned workouts, then settles on the workout in
    /// progress, if any (a workout killed on its summary is finished already and is let go).
    /// Never opens the full screen: a cold start with a workout running shows the mini bar only.
    func restore(in context: ModelContext) {
        deleteInterruptedDiscards(in: context)
        repairOrphans(in: context)
        _ = activeWorkout(in: context)
    }

    /// Keeps one unfinished workout (the session's own, else the newest). The others, left by builds without the
    /// start guard, are closed: with completed sets they end at their last completed set, without any they are deleted.
    func repairOrphans(in context: ModelContext) {
        let descriptor = FetchDescriptor<Workout>(predicate: #Predicate { $0.endedAt == nil },
                                                  sortBy: [SortDescriptor(\.startedAt, order: .reverse)])
        let open = ((try? context.fetch(descriptor)) ?? []).filter { !discarding.contains($0.id) }
        guard open.count > 1 else { return }
        let keep = open.first { $0.id == activeWorkoutID }?.id ?? open.first?.id
        for workout in open where workout.id != keep {
            if let last = workout.exercises.flatMap(\.sets).compactMap(\.completedAt).max() {
                workout.endedAt = max(last, workout.startedAt)
            } else {
                context.delete(workout)
            }
        }
        try? context.save()
    }

    private func deleteInterruptedDiscards(in context: ModelContext) {
        let pending = (defaults.stringArray(forKey: Keys.discarding) ?? []).compactMap(UUID.init(uuidString:))
        guard !pending.isEmpty, discarding.isEmpty else { return }
        for id in pending {
            if let workout = Self.fetchWorkout(id, in: context), workout.completedSetCount == 0 {
                if activeWorkoutID == id { end() }
                context.delete(workout)
            }
        }
        try? context.save()
        defaults.removeObject(forKey: Keys.discarding)
    }

    // MARK: Persistence

    private enum Keys {
        static let active = "nt.workout.active"
        static let discarding = "nt.workout.discarding"
        static let legacy = "nt.activeWorkoutID"
    }

    private func persist() {
        if let id = activeWorkoutID {
            defaults.set(id.uuidString, forKey: Keys.active)
        } else {
            defaults.removeObject(forKey: Keys.active)
        }
    }

    private func persistDiscarding() {
        if discarding.isEmpty {
            defaults.removeObject(forKey: Keys.discarding)
        } else {
            defaults.set(discarding.map(\.uuidString), forKey: Keys.discarding)
        }
    }

    private static func fetchWorkout(_ id: UUID, in context: ModelContext) -> Workout? {
        var descriptor = FetchDescriptor<Workout>(predicate: #Predicate { $0.id == id })
        descriptor.fetchLimit = 1
        return try? context.fetch(descriptor).first
    }
}
