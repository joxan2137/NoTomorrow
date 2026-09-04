import Foundation
import Observation

enum HealthKitError: LocalizedError {
    case unavailable
    case notAuthorized
    case saveFailed

    var errorDescription: String? {
        switch self {
        case .unavailable: String(localized: "health.error.unavailable")
        case .notAuthorized: String(localized: "health.error.notAuthorized")
        case .saveFailed: String(localized: "health.error.saveFailed")
        }
    }
}

#if canImport(HealthKit)
import HealthKit

/// Body weight in, meals and strength workouts out. Every call is a no-op (throws `.unavailable`) when Health is not
/// available on this device; the simulator has HealthKit, so errors there are handled rather than avoided.
@Observable @MainActor
final class HealthKitService {
    let isAvailable: Bool
    /// Best effort: HealthKit only reveals *write* authorization; read status is reported as "not determined" to protect privacy.
    private(set) var isAuthorized: Bool = false
    private(set) var lastError: String?

    private let store: HKHealthStore?

    private static let bodyMass = HKQuantityType(.bodyMass)
    private static let energy = HKQuantityType(.dietaryEnergyConsumed)
    private static let protein = HKQuantityType(.dietaryProtein)
    private static let carbs = HKQuantityType(.dietaryCarbohydrates)
    private static let fat = HKQuantityType(.dietaryFatTotal)
    private static let activeEnergy = HKQuantityType(.activeEnergyBurned)

    private static var shareTypes: Set<HKSampleType> {
        [bodyMass, energy, protein, carbs, fat, activeEnergy, HKWorkoutType.workoutType()]
    }
    private static var readTypes: Set<HKObjectType> { [bodyMass] }

    init() {
        isAvailable = HKHealthStore.isHealthDataAvailable()
        store = isAvailable ? HKHealthStore() : nil
        refreshAuthorization()
    }

    // MARK: - Authorization

    func requestAuthorization() async {
        guard let store else { return }
        do {
            try await store.requestAuthorization(toShare: Self.shareTypes, read: Self.readTypes)
            lastError = nil
        } catch {
            lastError = error.localizedDescription
        }
        refreshAuthorization()
    }

    private func refreshAuthorization() {
        guard let store else { isAuthorized = false; return }
        isAuthorized = store.authorizationStatus(for: Self.bodyMass) == .sharingAuthorized
    }

    // MARK: - Body weight

    /// Most recent body-mass sample from any source.
    func readLatestBodyWeight() async -> (kg: Double, date: Date)? {
        guard let store else { return nil }
        let sort = NSSortDescriptor(key: HKSampleSortIdentifierEndDate, ascending: false)
        return await withCheckedContinuation { continuation in
            let query = HKSampleQuery(sampleType: Self.bodyMass, predicate: nil, limit: 1, sortDescriptors: [sort]) { _, samples, _ in
                guard let sample = samples?.first as? HKQuantitySample else {
                    continuation.resume(returning: nil)
                    return
                }
                let kg = sample.quantity.doubleValue(for: .gramUnit(with: .kilo))
                continuation.resume(returning: (kg, sample.endDate))
            }
            store.execute(query)
        }
    }

    func saveBodyWeight(kg: Double, date: Date = .now) async throws {
        guard let store else { throw HealthKitError.unavailable }
        let quantity = HKQuantity(unit: .gramUnit(with: .kilo), doubleValue: kg)
        let sample = HKQuantitySample(type: Self.bodyMass, quantity: quantity, start: date, end: date)
        try await save([sample], to: store)
    }

    // MARK: - Meals

    /// One food correlation so the four nutrients show up as a single meal in Health.
    func saveMeal(kcal: Double, protein: Double, carbs: Double, fat: Double, date: Date = .now) async throws {
        guard let store else { throw HealthKitError.unavailable }
        var objects: Set<HKSample> = []
        func add(_ type: HKQuantityType, _ value: Double, _ unit: HKUnit) {
            guard value > 0 else { return }
            let q = HKQuantity(unit: unit, doubleValue: value)
            objects.insert(HKQuantitySample(type: type, quantity: q, start: date, end: date))
        }
        add(Self.energy, kcal, .kilocalorie())
        add(Self.protein, protein, .gram())
        add(Self.carbs, carbs, .gram())
        add(Self.fat, fat, .gram())
        guard !objects.isEmpty else { return }
        let food = HKCorrelation(type: HKCorrelationType(.food), start: date, end: date, objects: objects)
        try await save([food], to: store)
    }

    // MARK: - Workouts

    func saveWorkout(start: Date, end: Date, kcal: Double? = nil) async throws {
        guard let store else { throw HealthKitError.unavailable }
        guard end > start else { return }
        let config = HKWorkoutConfiguration()
        config.activityType = .traditionalStrengthTraining
        config.locationType = .indoor
        let builder = HKWorkoutBuilder(healthStore: store, configuration: config, device: .local())
        do {
            try await builder.beginCollection(at: start)
            if let kcal, kcal > 0 {
                let q = HKQuantity(unit: .kilocalorie(), doubleValue: kcal)
                let sample = HKQuantitySample(type: Self.activeEnergy, quantity: q, start: start, end: end)
                try await builder.addSamples([sample])
            }
            try await builder.endCollection(at: end)
            _ = try await builder.finishWorkout()
            lastError = nil
        } catch {
            lastError = error.localizedDescription
            builder.discardWorkout()
            throw Self.map(error)
        }
    }

    // MARK: - Helpers

    private func save(_ objects: [HKObject], to store: HKHealthStore) async throws {
        do {
            try await store.save(objects)
            lastError = nil
        } catch {
            lastError = error.localizedDescription
            throw Self.map(error)
        }
    }

    private static func map(_ error: Error) -> HealthKitError {
        let ns = error as NSError
        if ns.domain == HKError.errorDomain, ns.code == HKError.errorAuthorizationDenied.rawValue { return .notAuthorized }
        if ns.domain == HKError.errorDomain, ns.code == HKError.errorHealthDataUnavailable.rawValue { return .unavailable }
        return .saveFailed
    }
}

#else

/// HealthKit is not linkable on this platform; keep the same surface so callers compile.
@Observable @MainActor
final class HealthKitService {
    let isAvailable = false
    private(set) var isAuthorized = false
    private(set) var lastError: String?

    init() {}
    func requestAuthorization() async {}
    func readLatestBodyWeight() async -> (kg: Double, date: Date)? { nil }
    func saveBodyWeight(kg: Double, date: Date = .now) async throws { throw HealthKitError.unavailable }
    func saveMeal(kcal: Double, protein: Double, carbs: Double, fat: Double, date: Date = .now) async throws { throw HealthKitError.unavailable }
    func saveWorkout(start: Date, end: Date, kcal: Double? = nil) async throws { throw HealthKitError.unavailable }
}

#endif
