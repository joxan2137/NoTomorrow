import Foundation
import SwiftData

/// All persisted types, in one place so the container and tests agree.
enum NoTomorrowSchema {
    static let models: [any PersistentModel.Type] = [
        UserProfile.self, GymSchedule.self,
        Exercise.self, Routine.self, RoutineItem.self,
        Workout.self, WorkoutExercise.self, SetEntry.self,
        FoodItem.self, MealEntry.self, BodyWeightEntry.self,
        BroPairing.self, AttendanceRecord.self, HeadsUp.self,
    ]
}

// MARK: - Enums (stored as raw strings)

enum TrainingGoal: String, Codable, CaseIterable { case buildMuscle, loseFat, maintain }
enum WeightUnit: String, Codable, CaseIterable { case kg, lb }
enum SetKind: String, Codable, CaseIterable { case normal, warmup, drop, failure }
/// What a set of an exercise records: weight × reps, reps at body weight (optional added weight), or seconds held
/// (optional added weight). See `Exercise.tracking`.
enum ExerciseTracking: String, Codable, CaseIterable { case weightReps, bodyweightReps, duration }
enum MealSlot: String, Codable, CaseIterable { case breakfast, lunch, snack, dinner }
enum FoodSource: String, Codable { case openFoodFacts, usda, custom, aiEstimate, quickAdd }
enum AttendanceStatus: String, Codable { case planned, confirmed, attended, missed, cancelled }
enum HeadsUpKind: String, Codable { case cantMakeIt, runningLate, letsGo, custom, makeUpProposal }
enum Participant: String, Codable { case me, partner }
enum BodyWeightSource: String, Codable { case manual, healthKit }

// MARK: - Profile & schedule

@Model
final class UserProfile {
    @Attribute(.unique) var id: UUID
    var name: String
    var bodyWeightKg: Double?
    var goal: TrainingGoal
    var units: WeightUnit
    var calorieGoal: Int
    var proteinGoalG: Int
    var carbsGoalG: Int
    var fatGoalG: Int
    var defaultRestSeconds: Int
    var createdAt: Date

    init(name: String, bodyWeightKg: Double? = nil, goal: TrainingGoal = .buildMuscle, units: WeightUnit = .kg,
         calorieGoal: Int = 2600, proteinGoalG: Int = 180, carbsGoalG: Int = 300, fatGoalG: Int = 80,
         defaultRestSeconds: Int = 90) {
        self.id = UUID()
        self.name = name
        self.bodyWeightKg = bodyWeightKg
        self.goal = goal
        self.units = units
        self.calorieGoal = calorieGoal
        self.proteinGoalG = proteinGoalG
        self.carbsGoalG = carbsGoalG
        self.fatGoalG = fatGoalG
        self.defaultRestSeconds = defaultRestSeconds
        self.createdAt = .now
    }
}

/// Which weekdays are gym days and at what time. Weekdays use ISO numbering: 1 = Monday … 7 = Sunday.
@Model
final class GymSchedule {
    var weekdays: [Int]
    /// Minutes since midnight, e.g. 18:00 → 1080.
    var defaultMinuteOfDay: Int
    /// Per-weekday overrides, keyed by ISO weekday.
    var overrides: [Int: Int]
    var remindHourBefore: Bool
    var askIfSkippedAt21: Bool
    var updatedAt: Date

    init(weekdays: [Int] = [1, 3, 5], defaultMinuteOfDay: Int = 18 * 60, overrides: [Int: Int] = [:],
         remindHourBefore: Bool = true, askIfSkippedAt21: Bool = true) {
        self.weekdays = weekdays.sorted()
        self.defaultMinuteOfDay = defaultMinuteOfDay
        self.overrides = overrides
        self.remindHourBefore = remindHourBefore
        self.askIfSkippedAt21 = askIfSkippedAt21
        self.updatedAt = .now
    }

    func minuteOfDay(for isoWeekday: Int) -> Int { overrides[isoWeekday] ?? defaultMinuteOfDay }
    func isGymDay(_ isoWeekday: Int) -> Bool { weekdays.contains(isoWeekday) }
}

// MARK: - Exercises & routines

@Model
final class Exercise {
    /// Stable id: free-exercise-db id ("Barbell_Bench_Press_-_Medium_Grip") or "custom-<uuid>".
    @Attribute(.unique) var id: String
    var name: String
    var namePL: String?
    var primaryMuscles: [String]
    var secondaryMuscles: [String]
    var equipment: String?
    var category: String
    var force: String?
    var mechanic: String?
    var level: String?
    var instructions: [String]
    var isCustom: Bool
    var lastUsedAt: Date?
    /// The user's choice of `ExerciseTracking` (raw value); nil follows `ExerciseTracking.inferred`. Optional so
    /// stores from before it open with a lightweight migration and nothing to rewrite.
    var trackingRaw: String?

    @Relationship(deleteRule: .nullify, inverse: \WorkoutExercise.exercise) var usages: [WorkoutExercise] = []

    init(id: String, name: String, namePL: String? = nil, primaryMuscles: [String], secondaryMuscles: [String] = [],
         equipment: String? = nil, category: String = "strength", force: String? = nil, mechanic: String? = nil,
         level: String? = nil, instructions: [String] = [], isCustom: Bool = false) {
        self.id = id
        self.name = name
        self.namePL = namePL
        self.primaryMuscles = primaryMuscles
        self.secondaryMuscles = secondaryMuscles
        self.equipment = equipment
        self.category = category
        self.force = force
        self.mechanic = mechanic
        self.level = level
        self.instructions = instructions
        self.isCustom = isCustom
    }

    var localizedName: String {
        if Locale.current.language.languageCode?.identifier == "pl", let namePL, !namePL.isEmpty { return namePL }
        return name
    }

    /// What its sets record: the user's choice, else what the library data implies.
    var tracking: ExerciseTracking {
        get {
            trackingRaw.flatMap(ExerciseTracking.init(rawValue:))
                ?? ExerciseTracking.inferred(id: id, equipment: equipment, category: category, isCustom: isCustom)
        }
        set {
            let inferred = ExerciseTracking.inferred(id: id, equipment: equipment, category: category, isCustom: isCustom)
            trackingRaw = newValue == inferred ? nil : newValue.rawValue
        }
    }
}

extension ExerciseTracking {
    /// Library exercises that are held for time although their data says strength (planks, hangs, isometrics).
    static let timedIDs: Set<String> = [
        "Plank", "Side_Bridge", "nt_copenhagen_side_plank", "One_Handed_Hang",
        "Isometric_Neck_Exercise_-_Front_And_Back", "Isometric_Neck_Exercise_-_Sides",
    ]

    /// Body-weight movements the data files under "other" equipment (a bar, rings, a band).
    static let bodyweightIDs: Set<String> = [
        "Parallel_Bar_Dip", "Ring_Dips", "Dips_-_Chest_Version", "Muscle_Up", "Kipping_Muscle_Up", "One_Arm_Chin-Up",
        "Band_Assisted_Pull-Up", "Rocky_Pull-Ups_Pulldowns", "Suspended_Push-Up", "Suspended_Reverse_Crunch",
    ]

    /// The default for an exercise nobody chose a type for. Custom exercises start as weight × reps (they carry no
    /// equipment to go by); the library goes by id, then category (stretches and cardio are timed), then equipment
    /// (body only, or none, is reps at body weight).
    static func inferred(id: String, equipment: String?, category: String, isCustom: Bool) -> ExerciseTracking {
        if isCustom { return .weightReps }
        if timedIDs.contains(id) { return .duration }
        if bodyweightIDs.contains(id) { return .bodyweightReps }
        switch category {
        case "stretching", "cardio": return .duration
        default: break
        }
        switch equipment {
        case nil, "body only": return .bodyweightReps
        default: return .weightReps
        }
    }

    /// Seconds for a timed exercise, reps otherwise.
    static func amount(reps: Int, seconds: Int, tracking: ExerciseTracking) -> Int {
        tracking == .duration ? seconds : reps
    }

    /// Weight is what the exercise is about (a weighted set without it isn't one); otherwise it's optional extra load.
    var weightIsAdded: Bool { self != .weightReps }
}

@Model
final class Routine {
    @Attribute(.unique) var id: UUID
    var name: String            // "Push A"
    var order: Int
    @Relationship(deleteRule: .cascade, inverse: \RoutineItem.routine) var items: [RoutineItem] = []
    var createdAt: Date

    init(name: String, order: Int = 0) {
        self.id = UUID()
        self.name = name
        self.order = order
        self.createdAt = .now
    }

    var sortedItems: [RoutineItem] { items.sorted { $0.order < $1.order } }
}

@Model
final class RoutineItem {
    var order: Int
    var exercise: Exercise?
    var targetSets: Int
    var targetReps: Int
    var restSeconds: Int
    var routine: Routine?

    init(order: Int, exercise: Exercise, targetSets: Int = 3, targetReps: Int = 8, restSeconds: Int = 90) {
        self.order = order
        self.exercise = exercise
        self.targetSets = targetSets
        self.targetReps = targetReps
        self.restSeconds = restSeconds
    }
}

// MARK: - Workouts

@Model
final class Workout {
    @Attribute(.unique) var id: UUID
    var name: String            // routine name at the time, or "Workout"
    var startedAt: Date
    var endedAt: Date?
    var notes: String
    @Relationship(deleteRule: .cascade, inverse: \WorkoutExercise.workout) var exercises: [WorkoutExercise] = []

    init(name: String, startedAt: Date = .now) {
        self.id = UUID()
        self.name = name
        self.startedAt = startedAt
        self.notes = ""
    }

    var sortedExercises: [WorkoutExercise] { exercises.sorted { $0.order < $1.order } }
    var isActive: Bool { endedAt == nil }
    var duration: TimeInterval { (endedAt ?? .now).timeIntervalSince(startedAt) }

    /// Sum of weight × reps over completed working sets (warm-ups excluded).
    var totalVolumeKg: Double {
        exercises.flatMap(\.sets).filter { $0.isCompleted && $0.kind != .warmup }
            .reduce(0) { $0 + $1.weightKg * Double($1.reps) }
    }
    var completedSetCount: Int { exercises.flatMap(\.sets).filter(\.isCompleted).count }
    var prCount: Int { exercises.flatMap(\.sets).filter(\.isPR).count }
}

@Model
final class WorkoutExercise {
    var order: Int
    var exercise: Exercise?
    var restSeconds: Int
    var notes: String
    var workout: Workout?
    @Relationship(deleteRule: .cascade, inverse: \SetEntry.workoutExercise) var sets: [SetEntry] = []

    init(order: Int, exercise: Exercise, restSeconds: Int = 90) {
        self.order = order
        self.exercise = exercise
        self.restSeconds = restSeconds
        self.notes = ""
    }

    var sortedSets: [SetEntry] { sets.sorted { $0.order < $1.order } }
    var isDone: Bool { !sets.isEmpty && sets.allSatisfy(\.isCompleted) }
}

@Model
final class SetEntry {
    var order: Int
    var kind: SetKind
    var weightKg: Double
    var reps: Int
    var completedAt: Date?
    var isPR: Bool
    var isSetRecord: Bool
    var rpe: Double?
    /// Seconds held, for a timed exercise. Optional so stores from before it open with a lightweight migration;
    /// read and write it through `seconds`.
    var durationSeconds: Int?
    var workoutExercise: WorkoutExercise?

    init(order: Int, kind: SetKind = .normal, weightKg: Double = 0, reps: Int = 0, seconds: Int = 0) {
        self.order = order
        self.kind = kind
        self.weightKg = weightKg
        self.reps = reps
        self.durationSeconds = seconds > 0 ? seconds : nil
        self.isPR = false
        self.isSetRecord = false
    }

    var isCompleted: Bool { completedAt != nil }

    var seconds: Int {
        get { durationSeconds ?? 0 }
        set { durationSeconds = newValue > 0 ? newValue : nil }
    }

    /// Its exercise's type (weight × reps when it has none).
    var tracking: ExerciseTracking { workoutExercise?.exercise?.tracking ?? .weightReps }

    /// What makes the set count, in its exercise's type: seconds for a timed exercise, reps otherwise. A row logged
    /// before the exercise's type changed (a plank logged as "0 × 60") keeps its numbers but has no amount now.
    var amount: Int { ExerciseTracking.amount(reps: reps, seconds: seconds, tracking: tracking) }

    /// Epley estimated one-rep max. Returns weight for a single. None for a timed set.
    var estimatedOneRepMax: Double {
        guard reps > 0, weightKg > 0, tracking != .duration else { return 0 }
        if reps == 1 { return weightKg }
        return weightKg * (1 + Double(reps) / 30)
    }
}

// MARK: - Nutrition

@Model
final class FoodItem {
    @Attribute(.unique) var id: String     // "off:<barcode>", "usda:<fdcId>", "custom:<uuid>"
    var name: String
    var brand: String?
    var source: FoodSource
    var barcode: String?
    var kcalPer100: Double
    var proteinPer100: Double
    var carbsPer100: Double
    var fatPer100: Double
    var fiberPer100: Double?
    var servingSizeG: Double?
    var servingLabel: String?
    var imageURL: String?
    var isFavorite: Bool
    var useCount: Int
    var lastUsedAt: Date?

    init(id: String, name: String, brand: String? = nil, source: FoodSource, barcode: String? = nil,
         kcalPer100: Double, proteinPer100: Double, carbsPer100: Double, fatPer100: Double,
         fiberPer100: Double? = nil, servingSizeG: Double? = nil, servingLabel: String? = nil, imageURL: String? = nil) {
        self.id = id
        self.name = name
        self.brand = brand
        self.source = source
        self.barcode = barcode
        self.kcalPer100 = kcalPer100
        self.proteinPer100 = proteinPer100
        self.carbsPer100 = carbsPer100
        self.fatPer100 = fatPer100
        self.fiberPer100 = fiberPer100
        self.servingSizeG = servingSizeG
        self.servingLabel = servingLabel
        self.imageURL = imageURL
        self.isFavorite = false
        self.useCount = 0
    }
}

@Model
final class MealEntry {
    @Attribute(.unique) var id: UUID
    /// Start of day (local calendar) the entry belongs to.
    var day: Date
    var slot: MealSlot
    var food: FoodItem?
    var customName: String?
    var grams: Double
    var kcal: Double
    var proteinG: Double
    var carbsG: Double
    var fatG: Double
    var isAIEstimate: Bool
    var confidence: Double?
    var loggedAt: Date

    init(day: Date, slot: MealSlot, food: FoodItem? = nil, customName: String? = nil, grams: Double,
         kcal: Double, proteinG: Double, carbsG: Double, fatG: Double, isAIEstimate: Bool = false, confidence: Double? = nil) {
        self.id = UUID()
        self.day = Calendar.current.startOfDay(for: day)
        self.slot = slot
        self.food = food
        self.customName = customName
        self.grams = grams
        self.kcal = kcal
        self.proteinG = proteinG
        self.carbsG = carbsG
        self.fatG = fatG
        self.isAIEstimate = isAIEstimate
        self.confidence = confidence
        self.loggedAt = .now
    }

    var displayName: String { food?.name ?? customName ?? "" }
}

@Model
final class BodyWeightEntry {
    @Attribute(.unique) var day: Date
    var kg: Double
    var source: BodyWeightSource

    init(day: Date, kg: Double, source: BodyWeightSource = .manual) {
        self.day = Calendar.current.startOfDay(for: day)
        self.kg = kg
        self.source = source
    }
}

// MARK: - Gym bro

@Model
final class BroPairing {
    var partnerId: String
    var partnerName: String
    var myCode: String
    var pairedAt: Date

    init(partnerId: String, partnerName: String, myCode: String) {
        self.partnerId = partnerId
        self.partnerName = partnerName
        self.myCode = myCode
        self.pairedAt = .now
    }
}

/// One row per (day, participant): what was planned and what happened.
@Model
final class AttendanceRecord {
    @Attribute(.unique) var id: UUID
    var day: Date
    var participant: Participant
    var scheduledMinuteOfDay: Int
    var status: AttendanceStatus
    var reason: String?
    var note: String?
    var makeUpDay: Date?
    var updatedAt: Date

    init(day: Date, participant: Participant, scheduledMinuteOfDay: Int, status: AttendanceStatus = .planned) {
        self.id = UUID()
        self.day = Calendar.current.startOfDay(for: day)
        self.participant = participant
        self.scheduledMinuteOfDay = scheduledMinuteOfDay
        self.status = status
        self.updatedAt = .now
    }
}

@Model
final class HeadsUp {
    @Attribute(.unique) var id: UUID
    var fromMe: Bool
    var kind: HeadsUpKind
    var text: String
    var sessionDay: Date
    var sentAt: Date
    var readAt: Date?

    init(fromMe: Bool, kind: HeadsUpKind, text: String, sessionDay: Date) {
        self.id = UUID()
        self.fromMe = fromMe
        self.kind = kind
        self.text = text
        self.sessionDay = Calendar.current.startOfDay(for: sessionDay)
        self.sentAt = .now
    }
}
