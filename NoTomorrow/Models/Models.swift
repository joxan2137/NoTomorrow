import Foundation
import SwiftData

/// All persisted types, in one place so the container and tests agree.
enum NoTomorrowSchema {
    static let models: [any PersistentModel.Type] = [
        UserProfile.self, GymSchedule.self,
        Exercise.self, Routine.self, RoutineItem.self,
        Workout.self, WorkoutExercise.self, SetEntry.self,
        FoodItem.self, MealEntry.self, BodyWeightEntry.self, BodyMeasurement.self, ProgressPhoto.self,
        BroPairing.self, AttendanceRecord.self, HeadsUp.self,
    ]
}

// MARK: - Enums (stored as raw strings)

enum TrainingGoal: String, Codable, CaseIterable { case buildMuscle, loseFat, maintain }
enum WeightUnit: String, Codable, CaseIterable { case kg, lb }
enum SetKind: String, Codable, CaseIterable { case normal, warmup, drop, failure }
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
    /// Neighbouring items with the same id form a superset (`Superset`); nil = on its own.
    var supersetGroup: Int?
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
    /// Neighbouring exercises with the same id form a superset (`Superset`); nil = on its own.
    var supersetGroup: Int?
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
    var workoutExercise: WorkoutExercise?

    init(order: Int, kind: SetKind = .normal, weightKg: Double = 0, reps: Int = 0) {
        self.order = order
        self.kind = kind
        self.weightKg = weightKg
        self.reps = reps
        self.isPR = false
        self.isSetRecord = false
    }

    var isCompleted: Bool { completedAt != nil }

    /// Epley estimated one-rep max. Returns weight for a single.
    var estimatedOneRepMax: Double {
        guard reps > 0, weightKg > 0 else { return 0 }
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

/// Tape measurements and body fat (Progress > Body > Measurements). One row per (day, kind); lengths in cm.
enum MeasurementKind: String, Codable, CaseIterable {
    case waist, chest, hips, arm, thigh, neck, bodyFat
}

@Model
final class BodyMeasurement {
    @Attribute(.unique) var id: UUID
    /// Start of day (local calendar).
    var day: Date
    var kindRaw: String
    /// Centimetres, or percent for body fat.
    var value: Double

    init(day: Date, kind: MeasurementKind, value: Double) {
        self.id = UUID()
        self.day = Calendar.current.startOfDay(for: day)
        self.kindRaw = kind.rawValue
        self.value = value
    }

    var kind: MeasurementKind? { MeasurementKind(rawValue: kindRaw) }
}

/// Which way the body faces in a progress photo; optional.
enum ProgressPose: String, Codable, CaseIterable {
    case front, side, back
}

/// A private progress photo (Progress > Body > Photos). The JPEG lives on this phone only, in
/// Application Support/ProgressPhotos/`fileName` (`ProgressPhotoStore`); it is never exported or synced.
@Model
final class ProgressPhoto {
    @Attribute(.unique) var id: UUID
    var takenAt: Date
    /// File name inside the photos directory, e.g. "3f2c….jpg".
    var fileName: String
    var poseRaw: String?

    init(id: UUID = UUID(), takenAt: Date = .now, fileName: String, pose: ProgressPose? = nil) {
        self.id = id
        self.takenAt = takenAt
        self.fileName = fileName
        self.poseRaw = pose?.rawValue
    }

    var pose: ProgressPose? { poseRaw.flatMap(ProgressPose.init(rawValue:)) }
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
