import XCTest
import SwiftUI
import SwiftData
import UIKit
@testable import NoTomorrow

/// Renders the gym screens (the real views over an in-memory store seeded by `GymSnapshotSeed`) to images and prints
/// them into the test log as base64 blocks, for checking layouts without Xcode:
///
///     NTSHOT-BEGIN train.png
///     NTSHOT:iVBORw0KGgoAAAANSUhEUgAA…   (76 base64 characters per line)
///     NTSHOT-END train.png
///
/// `scripts/extract_shots.py` turns a job log back into the files. Skipped unless the test process has
/// `NT_SNAPSHOTS` set: `1` (or `en`) for every screen in the device language, `pl` for the Polish subset (run that
/// one with `-testLanguage pl -testRegion PL` so `String(localized:)` and exercise names follow too). xcodebuild
/// hands `TEST_RUNNER_NT_SNAPSHOTS=…` to the test process as `NT_SNAPSHOTS`. With `NT_SNAPSHOTS_DIR` (a host path)
/// the files are also written there. Mirrors Android's `GymScreenshots`.
@MainActor
final class GymSnapshotTests: XCTestCase {

    private static var language: GymShooter.Language? {
        switch ProcessInfo.processInfo.environment["NT_SNAPSHOTS"]?.lowercased() {
        case "1", "true", "yes", "en": return .en
        case "pl": return .pl
        default: return nil
        }
    }

    func testRenderGymScreens() throws {
        guard let language = Self.language else {
            throw XCTSkip("Renders the gym screens only with NT_SNAPSHOTS=1 (or pl) in the test environment.")
        }

        let defaults = UserDefaults.standard
        let savedLanguage = defaults.string(forKey: "nt.language")
        let savedFavorites = defaults.stringArray(forKey: FavoriteExercises.key)
        let sessionSuite = "GymSnapshotTests-\(UUID().uuidString)"
        let sessionDefaults = UserDefaults(suiteName: sessionSuite) ?? .standard
        let seed = try GymSnapshotSeed()
        defer {
            seed.removePhotoFiles()
            if let savedLanguage { defaults.set(savedLanguage, forKey: "nt.language") } else { defaults.removeObject(forKey: "nt.language") }
            if let savedFavorites { defaults.set(savedFavorites, forKey: FavoriteExercises.key) } else { defaults.removeObject(forKey: FavoriteExercises.key) }
            sessionDefaults.removePersistentDomain(forName: sessionSuite)
        }
        // The app's own language setting (Settings > Language): `Fmt.localized` and `AppLocale` read it.
        if language == .pl { defaults.set("pl", forKey: "nt.language") } else { defaults.removeObject(forKey: "nt.language") }

        try seed.seed()

        let shooter = GymShooter(seed: seed, language: language, session: WorkoutSessionController(defaults: sessionDefaults))
        let full = language == .en
        let progress = ProgressModel()
        progress.reload(seed.context)

        // MARK: Train

        shooter.shoot("train") { TrainView() }
        if full { shooter.shoot("train-full", height: 2400) { TrainView() } }
        shooter.shoot("routine-editor") { RoutineEditorSheet(request: .edit(seed.upperRoutine)) }
        if full { shooter.shoot("routine-editor-full", height: 1500) { RoutineEditorSheet(request: .edit(seed.upperRoutine)) } }
        shooter.shoot("program-browser") { ProgramBrowserSheet() }
        shooter.shoot("program-detail", height: full ? 2000 : 852) { ProgramBrowserSheet(initialProgramID: "ppl") }
        shooter.shoot("routine-import-empty") { RoutineImportSheet() }
        shooter.shoot("routine-import-preview") { RoutineImportSheet(initialText: GymSnapshotSeed.routineText) }

        // MARK: Workout sheets

        shooter.shoot("plate-calculator") { PlateCalculatorSheet(weightKg: 102.5, unit: .kg, onUse: { _ in }) }
        if full { shooter.shoot("plate-calculator-closest") { PlateCalculatorSheet(weightKg: 101, unit: .kg, onUse: { _ in }) } }
        shooter.shoot("onerm-calculator") { OneRepMaxCalculatorSheet(unit: .kg, initialWeight: 100, initialReps: 5) }
        shooter.shoot("exercise-history") {
            ExerciseHistorySheet(exercise: seed.exercise(GymSnapshotSeed.bench), unit: .kg, excluding: nil)
        }
        // Favorites live in UserDefaults and are read when the picker's model is created (inside `shoot`).
        defaults.set([GymSnapshotSeed.bench, GymSnapshotSeed.squat, GymSnapshotSeed.pullup], forKey: FavoriteExercises.key)
        shooter.shoot("exercise-picker", settle: 0.8) { ExercisePickerView(onAdd: { _ in }) }
        if let savedFavorites { defaults.set(savedFavorites, forKey: FavoriteExercises.key) } else { defaults.removeObject(forKey: FavoriteExercises.key) }
        let custom = seed.addCustomExercise()
        shooter.shoot("custom-exercise") { CustomExerciseEditor(exercise: custom) }

        // MARK: Progress

        shooter.shoot("progress-lifts") { ProgressHomeView() }
        if full { shooter.shoot("progress-lifts-full", height: 3800) { ProgressHomeView() } }
        shooter.shoot("progress-cards", height: full ? 1800 : 852) {
            NavigationStack {
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        TrainingCalendarCard(unit: progress.unit)
                        WeeklyStatsCard(unit: progress.unit).padding(.top, NT.Spacing.section)
                        MilestonesCard(unit: progress.unit, sessions: progress.milestoneSessions)
                            .padding(.top, NT.Spacing.section)
                    }
                    .padding(.horizontal, NT.Spacing.screenH)
                    .padding(.bottom, 32)
                }
                .ntScreenBackground()
                .toolbar(.hidden, for: .navigationBar)
            }
        }
        if full { shooter.shoot("records", height: 1400) { NavigationStack { RecordsView() } } }
        shooter.shoot("milestones", height: full ? 2400 : 852) {
            NavigationStack { MilestonesView(sessions: progress.milestoneSessions) }
        }
        shooter.shoot("lift-page") { NavigationStack { ExerciseProgressView(exercise: seed.exercise(GymSnapshotSeed.bench)) } }
        if full {
            shooter.shoot("lift-page-full", height: 3200) {
                NavigationStack { ExerciseProgressView(exercise: seed.exercise(GymSnapshotSeed.bench)) }
            }
        }
        // Progress > Body: the tab's Lifts / Body switch is private state, so its sections are laid out here as the
        // Body tab does (without the screen's title row).
        shooter.shoot("progress-body", height: full ? 1900 : 852, settle: 1.2) {
            NavigationStack {
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        BodyTabView(stats: progress.body, unit: progress.unit, onLog: {}).padding(.top, 18)
                        MeasurementsSection(unit: progress.unit).padding(.top, NT.Spacing.section)
                        ProgressPhotosSection().padding(.top, NT.Spacing.section)
                    }
                    .padding(.horizontal, NT.Spacing.screenH)
                    .padding(.bottom, 32)
                }
                .ntScreenBackground()
                .toolbar(.hidden, for: .navigationBar)
            }
        }
        if full {
            shooter.shoot("measurements-log-sheet") { LogMeasurementsSheet(unit: .kg, last: seed.latestMeasurements) }
            shooter.shoot("progress-photos-compare", settle: 1.2) { ProgressPhotoCompareView(store: .standard) }
        }

        // MARK: Finish and share

        shooter.shoot("workout-done", height: full ? 1300 : 852, settle: 0.8) {
            WorkoutDoneView(workout: seed.lastWorkout, unit: .kg, onDone: {}, onEditSets: {})
        }
        shooter.shoot("share-card") {
            ZStack {
                NT.Colors.ground.ignoresSafeArea()
                WorkoutShareCard(
                    title: seed.lastWorkout.name,
                    subtitle: "\(Fmt.longDay(seed.lastWorkout.startedAt)) · \(Fmt.time(seed.lastWorkout.startedAt))",
                    volume: Fmt.volume(seed.lastWorkout.totalVolumeKg, unit: .kg),
                    time: Fmt.duration(seed.lastWorkout.duration),
                    sets: "\(seed.lastWorkout.completedSetCount)",
                    prs: seed.lastWorkout.prCount,
                    lines: WorkoutShareCard.lines(for: seed.lastWorkout, unit: .kg)
                )
            }
        }

        // MARK: Settings

        if full {
            shooter.shoot("settings-import-empty") { NavigationStack { ImportView() } }
            if let parsed = try? WorkoutImport.parse(GymSnapshotSeed.strongCSV, unit: .kg) {
                shooter.shoot("settings-import-preview") {
                    NavigationStack { ImportView(initialPreview: parsed, initialFileName: "strong-export.csv") }
                }
            } else {
                shooter.skip("settings-import-preview", reason: "the sample Strong CSV did not parse")
            }
        }

        // MARK: Active workout (last: it adds an unfinished workout to the store)

        let active = seed.seedActiveWorkout()
        shooter.shoot("active-workout", settle: 0.8) { ActiveWorkoutView(workout: active) }
        if full { shooter.shoot("active-workout-full", height: 1500, settle: 0.8) { ActiveWorkoutView(workout: active) } }
        // The superset open: the session hands the view its (cached) model, so open the incline press on it first.
        let model = shooter.session.model(for: active, context: seed.context)
        model.expandedExerciseID = active.sortedExercises.first { $0.supersetGroup != nil }?.persistentModelID
        shooter.shoot("active-workout-superset", settle: 0.8) { ActiveWorkoutView(workout: active) }

        shooter.finish()
        XCTAssertGreaterThan(shooter.count, 0, "no screen was rendered")
    }
}

// MARK: - Rendering

/// Hosts a view in its own window at iPhone 15/16 size (393 × 852 pt, or taller for whole scrolling screens), lets
/// it settle (onAppear, `.task`, `@Query`), draws it at scale 1 and prints it as an NTSHOT block.
@MainActor
final class GymShooter {
    enum Language: String { case en, pl }

    let seed: GymSnapshotSeed
    let language: Language
    let session: WorkoutSessionController
    let appState: AppState
    let restTimer: RestTimerController
    private(set) var count = 0
    private let outDir: URL?

    init(seed: GymSnapshotSeed, language: Language, session: WorkoutSessionController) {
        self.seed = seed
        self.language = language
        self.session = session
        appState = AppState()
        restTimer = RestTimerController()
        if let path = ProcessInfo.processInfo.environment["NT_SNAPSHOTS_DIR"], !path.isEmpty {
            let url = URL(fileURLWithPath: path, isDirectory: true)
            try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
            outDir = url
        } else {
            outDir = nil
        }
    }

    private var locale: Locale { AppLocale.effective(languageOverride: language == .pl ? "pl" : nil) }

    private func fileBase(_ name: String) -> String { language == .pl ? "\(name)-pl" : name }

    func shoot<Content: View>(_ name: String, height: CGFloat = 852, settle: TimeInterval = 0.5,
                              @ViewBuilder _ content: () -> Content) {
        let root = content()
            .modelContainer(seed.container)
            .environment(appState)
            .environment(restTimer)
            .environment(session)
            .environment(\.locale, locale)
            .preferredColorScheme(.dark)
            .tint(NT.Colors.ink)
            .ignoresSafeArea(.keyboard)
        render(fileBase(name), height: height, settle: settle, view: AnyView(root))
    }

    func skip(_ name: String, reason: String) {
        print("NTSHOT-SKIP \(fileBase(name)): \(reason)")
        fflush(stdout)
    }

    func finish() {
        print("NTSHOT-DONE \(count) images (\(language.rawValue))")
        fflush(stdout)
    }

    private func render(_ name: String, height: CGFloat, settle: TimeInterval, view: AnyView) {
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else {
            print("NTSHOT-SKIP \(name): no window scene (is NoTomorrowTests hosted in the app?)")
            fflush(stdout)
            return
        }
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 393, height: height)
        window.overrideUserInterfaceStyle = .dark
        let host = UIHostingController(rootView: view)
        host.view.backgroundColor = UIColor(NT.Colors.ground)
        window.rootViewController = host
        window.isHidden = false
        host.view.frame = window.bounds
        host.view.layoutIfNeeded()
        Self.spin(settle)
        window.layoutIfNeeded()
        Self.spin(0.1)

        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let bounds = window.bounds
        let image = UIGraphicsImageRenderer(bounds: bounds, format: format).image { _ in
            _ = window.drawHierarchy(in: bounds, afterScreenUpdates: true)
        }

        window.endEditing(true)
        window.isHidden = true
        window.rootViewController = nil
        Self.spin(0.05)
        emit(name, image)
    }

    /// PNG when it stays small, else JPEG; printed as 76-character base64 lines between BEGIN / END markers.
    private func emit(_ name: String, _ image: UIImage) {
        var data = image.pngData() ?? Data()
        var file = "\(name).png"
        if data.count > 150_000, let jpeg = image.jpegData(compressionQuality: 0.7) {
            data = jpeg
            file = "\(name).jpg"
        }
        guard !data.isEmpty else {
            print("NTSHOT-SKIP \(name): encoding failed")
            fflush(stdout)
            return
        }
        if let outDir {
            try? data.write(to: outDir.appendingPathComponent(file))
        }
        let base64 = Array(data.base64EncodedString().utf8)
        var out = "NTSHOT-BEGIN \(file)\n"
        out.reserveCapacity(base64.count + base64.count / 76 * 8 + 64)
        var start = 0
        while start < base64.count {
            let end = min(start + 76, base64.count)
            out += "NTSHOT:"
            out += String(decoding: base64[start..<end], as: UTF8.self)
            out += "\n"
            start = end
        }
        out += "NTSHOT-END \(file)"
        print(out)
        fflush(stdout)
        count += 1
    }

    /// Turns the main run loop (and with it the main actor's queue) for `seconds`.
    static func spin(_ seconds: TimeInterval) {
        let end = Date().addingTimeInterval(seconds)
        while Date() < end {
            _ = RunLoop.main.run(mode: .default, before: min(end, Date().addingTimeInterval(0.05)))
        }
    }
}

// MARK: - Seed

/// Ten weeks of a Push / Pull / Legs split with climbing loads, yesterday's Pull A with PRs, routines (one with a
/// superset), a body-weight trend, tape measurements and three progress photos. Same shape as Android's `GymSeed`.
@MainActor
final class GymSnapshotSeed {
    struct SeedError: Error, CustomStringConvertible {
        let description: String
    }

    static let bench = "Barbell_Bench_Press_-_Medium_Grip"
    static let ohp = "Barbell_Shoulder_Press"
    static let incline = "Incline_Dumbbell_Press"
    static let pushdown = "Triceps_Pushdown"
    static let lateral = "Side_Lateral_Raise"
    static let deadlift = "Barbell_Deadlift"
    static let row = "Bent_Over_Barbell_Row"
    static let pullup = "Pullups"
    static let curl = "Barbell_Curl"
    static let squat = "Barbell_Squat"
    static let rdl = "Romanian_Deadlift"
    static let legPress = "Leg_Press"

    static let allIDs = [bench, ohp, incline, pushdown, lateral, deadlift, row, pullup, curl, squat, rdl, legPress]

    /// A shared routine as numbered lines only (no `nt1:` line), one exercise unknown to the library.
    static let routineText = """
    No Tomorrow routine: Leg day (heavy)
    1. Barbell Squat — 5 × 5, rest 3:00
    2. Romanian Deadlift — 3 × 8, rest 2:00
    3. Leg Press — 3 × 12 [Superset A]
    4. Standing Calf Raises — 4 × 15 [Superset A]
    5. Sissy squat on the Smith machine — 3 × 10
    """

    /// A two-workout Strong export (semicolons, a warm-up, an RPE, a rest-timer row).
    static let strongCSV = """
    Workout #;Date;Workout Name;Duration (sec);Exercise Name;Set Order;Weight (kg);Reps;RPE;Distance (meters);Seconds;Notes;Workout Notes
    1;2026-08-10 18:00:00;Push day;3600;Bench Press (Barbell);W;40;10;;;;;
    1;2026-08-10 18:00:00;Push day;3600;Bench Press (Barbell);1;80;8;8,5;;;Wide grip;
    1;2026-08-10 18:00:00;Push day;3600;Bench Press (Barbell);2;80;7;9;;;;
    1;2026-08-10 18:00:00;Push day;3600;Bench Press (Barbell);Rest Timer;;;;;90;;
    1;2026-08-10 18:00:00;Push day;3600;Cable Crossover Deluxe;1;20;12;;;;;
    2;2026-08-12 07:30:00;Legs;2700;Squat (Barbell);1;100;5;;;;;
    2;2026-08-12 07:30:00;Legs;2700;Squat (Barbell);2;100;5;;;;;
    2;2026-08-12 07:30:00;Legs;2700;Leg Press;1;160;10;;;;;
    """

    private struct Lift {
        let id: String
        let baseKg: Double
        let gainKg: Double
    }

    private struct Plan {
        let name: String
        let lifts: [Lift]
    }

    private static let plans: [Plan] = [
        Plan(name: "Push A", lifts: [Lift(id: bench, baseKg: 70, gainKg: 17.5), Lift(id: ohp, baseKg: 40, gainKg: 10),
                                     Lift(id: incline, baseKg: 24, gainKg: 6), Lift(id: pushdown, baseKg: 25, gainKg: 7.5)]),
        Plan(name: "Pull A", lifts: [Lift(id: deadlift, baseKg: 120, gainKg: 30), Lift(id: row, baseKg: 60, gainKg: 12.5),
                                     Lift(id: pullup, baseKg: 0, gainKg: 0), Lift(id: curl, baseKg: 30, gainKg: 5)]),
        Plan(name: "Legs", lifts: [Lift(id: squat, baseKg: 90, gainKg: 25), Lift(id: rdl, baseKg: 80, gainKg: 15),
                                   Lift(id: legPress, baseKg: 140, gainKg: 40)]),
    ]

    let container: ModelContainer
    var context: ModelContext { container.mainContext }
    let calendar = Calendar.current
    let today: Date
    private let photoStore = ProgressPhotoStore.standard
    private var photoFiles: [String] = []
    private var exercises: [String: Exercise] = [:]
    private(set) var upperRoutine: Routine!
    private(set) var lastWorkout: Workout!
    private(set) var latestMeasurements: [MeasurementKind: Double] = [:]

    init() throws {
        let schema = Schema(NoTomorrowSchema.models)
        container = try ModelContainer(for: schema, configurations: [ModelConfiguration(isStoredInMemoryOnly: true)])
        today = Calendar.current.startOfDay(for: .now)
    }

    func exercise(_ id: String) -> Exercise {
        exercises[id]!
    }

    func seed() throws {
        // The bundled library straight in: `importIfNeeded` would skip while the host app imports into its own store.
        guard let bundled = ExerciseLibrary.loadBundled() else { throw SeedError(description: "exercise library not in the bundle") }
        ExerciseLibrary.apply(bundled, into: context)
        let all = try context.fetch(FetchDescriptor<Exercise>())
        exercises = Dictionary(all.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        for id in Self.allIDs where exercises[id] == nil {
            throw SeedError(description: "library exercise \(id) missing")
        }

        context.insert(UserProfile(name: "Alex", bodyWeightKg: 82.4, defaultRestSeconds: 120))
        seedHistory()
        seedLastWorkout()
        seedRoutines()
        seedBody()
        seedPhotos()
        try context.save()
    }

    // MARK: Dates

    private func dayOffset(_ days: Int) -> Date {
        calendar.date(byAdding: .day, value: days, to: today) ?? today
    }

    private func at(_ day: Date, hour: Int, minute: Int = 0) -> Date {
        calendar.date(bySettingHour: hour, minute: minute, second: 0, of: day) ?? day
    }

    private func daysBetween(_ from: Date, _ to: Date) -> Int {
        calendar.dateComponents([.day], from: from, to: to).day ?? 0
    }

    // MARK: Rows

    @discardableResult
    private func addExercise(_ id: String, order: Int, to workout: Workout, rest: Int, superset: Int? = nil) -> WorkoutExercise {
        let entry = WorkoutExercise(order: order, exercise: exercise(id), restSeconds: rest)
        entry.supersetGroup = superset
        context.insert(entry)
        entry.workout = workout
        return entry
    }

    @discardableResult
    private func addSet(to entry: WorkoutExercise, order: Int, kind: SetKind = .normal, kg: Double, reps: Int,
                        completedAt: Date?) -> SetEntry {
        let set = SetEntry(order: order, kind: kind, weightKg: kg, reps: reps)
        set.completedAt = completedAt
        context.insert(set)
        set.workoutExercise = entry
        return set
    }

    // MARK: History

    /// Mon / Wed / Fri for ten weeks, a missed Wednesday every third week and a bonus Saturday every fourth.
    private func seedHistory() {
        var best: [String: Double] = [:]
        let start = calendar.startOfISOWeek(for: dayOffset(-70))
        let totalDays = Double(max(1, daysBetween(start, today)))
        let stop = dayOffset(-1)
        var index = 0
        var current = start
        while current < stop {
            let iso = calendar.isoWeekday(for: current)
            let offset = daysBetween(start, current)
            let week = offset / 7
            let trains = iso == 1 || iso == 5 || (iso == 3 && week % 3 != 1) || (iso == 6 && week % 4 == 2)
            if trains {
                let plan = Self.plans[index % Self.plans.count]
                let progress = Double(offset) / totalDays
                let startedAt = at(current, hour: 18, minute: 5)
                let workout = Workout(name: plan.name, startedAt: startedAt)
                workout.endedAt = startedAt.addingTimeInterval(64 * 60)
                if index % 5 == 2 { workout.notes = "Felt strong, short rests." }
                context.insert(workout)
                for (order, lift) in plan.lifts.enumerated() {
                    let top = ((lift.baseKg + lift.gainKg * progress) / 2.5).rounded() * 2.5
                    let reps = [8, 8, 6, 6]
                    let topE1RM = top * (1 + Double(reps[2]) / 30)
                    let isPR = top > 0 && topE1RM > (best[lift.id] ?? 0) + 0.01
                    if isPR { best[lift.id] = topE1RM }
                    let entry = addExercise(lift.id, order: order, to: workout, rest: 120)
                    if lift.id == Self.bench && index % 2 == 0 { entry.notes = "Pause the first rep. Elbows at 45°." }
                    for rowIndex in 0..<4 {
                        let kg = rowIndex >= 2 ? top : max(0, top - 5)
                        let setReps = lift.id == Self.pullup ? 8 + Int(progress * 4) - rowIndex : reps[rowIndex]
                        let done = startedAt.addingTimeInterval(Double(order * 4 + rowIndex + 1) * 150)
                        let set = addSet(to: entry, order: rowIndex, kg: kg, reps: setReps, completedAt: done)
                        set.isPR = isPR && rowIndex == 2
                        if rowIndex == 3 { set.rpe = 8.5 }
                    }
                }
                index += 1
            }
            current = calendar.date(byAdding: .day, value: 1, to: current) ?? stop
        }
        // What the app stamps when an exercise goes into a workout: the picker lists these first.
        let used = at(dayOffset(-1), hour: 18, minute: 5)
        for (i, id) in Self.plans.flatMap({ $0.lifts.map(\.id) }).enumerated() {
            exercise(id).lastUsedAt = used.addingTimeInterval(-Double(i) * 86_400)
        }
    }

    /// Yesterday's Pull A: a deadlift PR at 167.5 kg (past 2× body weight, so the finish screen has a milestone)
    /// and a row PR.
    private func seedLastWorkout() {
        let startedAt = at(dayOffset(-1), hour: 18, minute: 5)
        let workout = Workout(name: "Pull A", startedAt: startedAt)
        workout.endedAt = startedAt.addingTimeInterval(71 * 60)
        workout.notes = "Deadlift finally moved. Grip held."
        context.insert(workout)
        let lifts: [(String, [(Double, Int)])] = [
            (Self.deadlift, [(140, 5), (155, 3), (167.5, 3), (150, 5)]),
            (Self.row, [(65, 8), (70, 8), (75, 6), (75, 6)]),
            (Self.pullup, [(0, 12), (0, 10), (0, 9)]),
            (Self.curl, [(32.5, 8), (35, 6), (35, 6)]),
        ]
        for (order, lift) in lifts.enumerated() {
            let entry = addExercise(lift.0, order: order, to: workout, rest: lift.0 == Self.deadlift ? 180 : 120)
            if lift.0 == Self.deadlift { entry.notes = "Mixed grip on the top set." }
            for (rowIndex, values) in lift.1.enumerated() {
                let done = startedAt.addingTimeInterval(Double(order * 5 + rowIndex + 1) * 180)
                let set = addSet(to: entry, order: rowIndex, kg: values.0, reps: values.1, completedAt: done)
                set.isPR = (lift.0 == Self.deadlift && values.0 == 167.5) || (lift.0 == Self.row && rowIndex == 2)
                if rowIndex == 2 { set.rpe = 9 }
            }
        }
        lastWorkout = workout
    }

    // MARK: Routines

    private typealias Item = (id: String, sets: Int, reps: Int, rest: Int, superset: Int?)

    @discardableResult
    private func addRoutine(_ name: String, order: Int, _ items: [Item]) -> Routine {
        let routine = Routine(name: name, order: order)
        context.insert(routine)
        for (index, item) in items.enumerated() {
            let row = RoutineItem(order: index, exercise: exercise(item.id), targetSets: item.sets, targetReps: item.reps,
                                  restSeconds: item.rest)
            row.supersetGroup = item.superset
            context.insert(row)
            row.routine = routine
        }
        return routine
    }

    private func seedRoutines() {
        addRoutine("Push A", order: 0, [(Self.bench, 4, 6, 180, nil), (Self.ohp, 3, 8, 120, nil),
                                       (Self.incline, 3, 10, 90, nil), (Self.pushdown, 3, 12, 60, nil)])
        addRoutine("Pull A", order: 1, [(Self.deadlift, 3, 5, 180, nil), (Self.row, 4, 8, 120, nil),
                                       (Self.pullup, 3, 8, 90, nil), (Self.curl, 3, 10, 60, nil)])
        addRoutine("Legs", order: 2, [(Self.squat, 5, 5, 180, nil), (Self.rdl, 3, 8, 120, nil), (Self.legPress, 3, 12, 90, nil)])
        upperRoutine = addRoutine("Upper strength", order: 3, [
            (Self.bench, 4, 6, 180, nil), (Self.row, 4, 8, 150, nil),
            (Self.incline, 3, 10, 60, 1), (Self.pullup, 3, 8, 90, 1),
            (Self.lateral, 3, 15, 0, nil),
        ])
    }

    // MARK: Body

    private func seedBody() {
        // Every third day for twelve weeks, drifting down with some noise.
        for step in 0...28 {
            let kg = 85.6 - Double(step) * 0.1 + Double(step % 3) * 0.2
            context.insert(BodyWeightEntry(day: dayOffset(-84 + step * 3), kg: (kg * 10).rounded() / 10))
        }
        let tape: [(MeasurementKind, Double, Double)] = [
            (.waist, 88, -3.5), (.chest, 104, 2), (.arm, 37.5, 1.5), (.thigh, 60, 1), (.bodyFat, 19.5, -2.8),
        ]
        for step in 0...3 {
            let day = dayOffset(-(9 - step * 3) * 7)
            for (kind, start, change) in tape {
                let value = ((start + change * Double(step) / 3) * 10).rounded() / 10
                context.insert(BodyMeasurement(day: day, kind: kind, value: value))
                if step == 3 { latestMeasurements[kind] = value }
            }
        }
    }

    /// Three placeholder photos written where the app keeps them (the host app's Application Support), removed
    /// again by `removePhotoFiles`. A photo whose file cannot be written is left out.
    private func seedPhotos() {
        let photos: [(Int, ProgressPose)] = [(84, .front), (42, .side), (3, .front)]
        for (i, photo) in photos.enumerated() {
            let fileName = "ntshot-\(UUID().uuidString).jpg"
            do {
                try photoStore.write(Self.placeholderPhoto(i), fileName: fileName)
            } catch {
                continue
            }
            photoFiles.append(fileName)
            context.insert(ProgressPhoto(takenAt: at(dayOffset(-photo.0), hour: 8, minute: 30), fileName: fileName,
                                         pose: photo.1))
        }
    }

    func removePhotoFiles() {
        for name in photoFiles { photoStore.remove(fileName: name) }
        photoFiles = []
    }

    /// A 3:4 gradient with a pale figure: stands in for a real photo.
    private static func placeholderPhoto(_ seed: Int) -> Data {
        let size = CGSize(width: 600, height: 800)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let tops = [
            UIColor(red: 0x3A / 255.0, green: 0x4A / 255.0, blue: 0x5C / 255.0, alpha: 1),
            UIColor(red: 0x4B / 255.0, green: 0x3F / 255.0, blue: 0x57 / 255.0, alpha: 1),
            UIColor(red: 0x3F / 255.0, green: 0x54 / 255.0, blue: 0x48 / 255.0, alpha: 1),
        ]
        let bottom = UIColor(red: 0x16 / 255.0, green: 0x18 / 255.0, blue: 0x1C / 255.0, alpha: 1)
        let skin = UIColor(red: 0xD8 / 255.0, green: 0xC3 / 255.0, blue: 0xA8 / 255.0, alpha: 1)
        let image = UIGraphicsImageRenderer(size: size, format: format).image { context in
            let colors = [tops[seed % tops.count].cgColor, bottom.cgColor] as CFArray
            let locations: [CGFloat] = [0, 1]
            if let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors, locations: locations) {
                context.cgContext.drawLinearGradient(gradient, start: .zero, end: CGPoint(x: 0, y: size.height), options: [])
            }
            skin.setFill()
            let mid = size.width / 2
            let waist = 110 - CGFloat(seed) * 8
            UIBezierPath(ovalIn: CGRect(x: mid - 62, y: 108, width: 124, height: 124)).fill()
            UIBezierPath(roundedRect: CGRect(x: mid - 150, y: 250, width: 300, height: 170), cornerRadius: 60).fill()
            UIBezierPath(roundedRect: CGRect(x: mid - waist, y: 400, width: waist * 2, height: 160), cornerRadius: 40).fill()
            UIBezierPath(roundedRect: CGRect(x: mid - 110, y: 540, width: 100, height: 260), cornerRadius: 40).fill()
            UIBezierPath(roundedRect: CGRect(x: mid + 10, y: 540, width: 100, height: 260), cornerRadius: 40).fill()
        }
        return image.jpegData(compressionQuality: 0.85) ?? Data()
    }

    // MARK: On demand

    /// A custom exercise for the editor.
    func addCustomExercise() -> Exercise {
        let exercise = Exercise(id: "custom-landmine-press", name: "Landmine press", primaryMuscles: ["shoulders"],
                                equipment: "barbell", isCustom: true)
        context.insert(exercise)
        try? context.save()
        return exercise
    }

    /// A workout in progress: bench done for two sets (RPE on both) after a warm-up, a note, then a superset.
    func seedActiveWorkout() -> Workout {
        let startedAt = Date.now.addingTimeInterval(-23 * 60)
        let workout = Workout(name: "Upper strength", startedAt: startedAt)
        context.insert(workout)
        let bench = addExercise(Self.bench, order: 0, to: workout, rest: 180)
        bench.notes = "Pause the first rep on the chest. Elbows at 45°."
        addSet(to: bench, order: 0, kind: .warmup, kg: 40, reps: 10, completedAt: startedAt.addingTimeInterval(60))
        addSet(to: bench, order: 1, kg: 85, reps: 6, completedAt: startedAt.addingTimeInterval(300)).rpe = 7.5
        addSet(to: bench, order: 2, kg: 87.5, reps: 6, completedAt: startedAt.addingTimeInterval(520)).rpe = 9
        addSet(to: bench, order: 3, kg: 87.5, reps: 6, completedAt: nil)
        let incline = addExercise(Self.incline, order: 1, to: workout, rest: 60, superset: 1)
        for i in 0..<3 { addSet(to: incline, order: i, kg: 30, reps: 10, completedAt: nil) }
        let pullups = addExercise(Self.pullup, order: 2, to: workout, rest: 90, superset: 1)
        for i in 0..<3 { addSet(to: pullups, order: i, kg: 0, reps: 10, completedAt: nil) }
        let lateral = addExercise(Self.lateral, order: 3, to: workout, rest: 60)
        for i in 0..<3 { addSet(to: lateral, order: i, kg: 10, reps: 15, completedAt: nil) }
        try? context.save()
        return workout
    }
}
