import SwiftUI
import SwiftData
import Observation

enum OnboardingStep: Equatable {
    case welcome, you, schedule, pair
}

/// Draft values for the whole flow. Nothing is persisted until `finish`.
@Observable
@MainActor
final class OnboardingModel {

    // MARK: Navigation

    var step: OnboardingStep = .welcome
    /// Insertion direction for the step transition; the caller sets it before changing `step`.
    var movesForward = true
    private(set) var order: [OnboardingStep] = [.you, .schedule, .pair]
    let stepCount = 3

    var stepIndex: Int? { order.firstIndex(of: step) }
    var isLastStep: Bool { stepIndex == order.count - 1 }

    func startStandard() {
        order = [.you, .schedule, .pair]
        go(to: .you, forward: true)
    }

    /// "Got a code from your bro? Pair now" — pairing first, the rest afterwards.
    func startWithPair() {
        order = [.pair, .you, .schedule]
        go(to: .pair, forward: true)
    }

    func next() {
        guard let index = stepIndex, index + 1 < order.count else { return }
        go(to: order[index + 1], forward: true)
    }

    func back() {
        guard let index = stepIndex else { return }
        go(to: index == 0 ? .welcome : order[index - 1], forward: false)
    }

    private func go(to target: OnboardingStep, forward: Bool) {
        movesForward = forward
        withAnimation(.easeInOut(duration: 0.28)) { step = target }
    }

    // MARK: You

    var name = ""
    var weightText = ""
    var unit: WeightUnit = .kg
    var goal: TrainingGoal = .buildMuscle
    /// Set when the user edits the suggestion; cleared with "Use the suggestion".
    var customTargets: TargetCalculator.Targets?

    var trimmedName: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }
    var canContinueFromYou: Bool { !trimmedName.isEmpty }

    var enteredWeight: Double? {
        let cleaned = weightText.replacingOccurrences(of: ",", with: ".").trimmingCharacters(in: .whitespaces)
        guard let value = Double(cleaned), value > 0 else { return nil }
        return value
    }

    var bodyWeightKg: Double? {
        guard let value = enteredWeight else { return nil }
        return unit == .kg ? value : value / Self.lbPerKg
    }

    var suggestedTargets: TargetCalculator.Targets {
        TargetCalculator.targets(bodyWeightKg: bodyWeightKg, goal: goal)
    }

    var targets: TargetCalculator.Targets { customTargets ?? suggestedTargets }

    /// Switching units converts what was typed so the number stays the same weight.
    func setUnit(_ newUnit: WeightUnit) {
        guard newUnit != unit else { return }
        if let value = enteredWeight {
            let converted = newUnit == .lb ? value * Self.lbPerKg : value / Self.lbPerKg
            weightText = Fmt.weight(converted, unit: .kg, withUnit: false)
        }
        unit = newUnit
    }

    static let lbPerKg = 2.2046226218

    // MARK: Schedule

    var weekdays: Set<Int> = [1, 3, 5]
    var usualTime: Date = OnboardingModel.date(minuteOfDay: 18 * 60)
    var overrides: [Int: Int] = [:]
    var remindHourBefore = true
    var askIfSkippedAt21 = true

    var usualMinuteOfDay: Int { Self.minuteOfDay(usualTime) }

    func toggle(day: Int) {
        if weekdays.contains(day) { weekdays.remove(day) } else { weekdays.insert(day) }
    }

    static func minuteOfDay(_ date: Date) -> Int {
        let c = Calendar.current.dateComponents([.hour, .minute], from: date)
        return (c.hour ?? 0) * 60 + (c.minute ?? 0)
    }

    static func date(minuteOfDay: Int) -> Date {
        var comps = Calendar.current.dateComponents([.year, .month, .day], from: .now)
        comps.hour = minuteOfDay / 60
        comps.minute = minuteOfDay % 60
        return Calendar.current.date(from: comps) ?? .now
    }

    // MARK: Pair

    let bro = BroService()
    var myCode: String = OnboardingModel.localCode()
    var codeEntry = ""
    var isPairing = false
    var pairFailed = false
    var partnerName: String? { bro.partner?.name }
    var isPaired: Bool { bro.isPaired }

    var normalizedCode: String {
        var code = codeEntry.uppercased().trimmingCharacters(in: .whitespacesAndNewlines)
        if code.count == 4, !code.hasPrefix("NT") { code = "NT-" + code }
        return code
    }
    var canPair: Bool { normalizedCode.count == 7 && !isPairing }

    private static func localCode() -> String {
        let alphabet = Array("ABCDEFGHJKLMNPQRSTUVWXYZ23456789")
        return "NT-" + String((0..<4).map { _ in alphabet.randomElement()! })
    }

    /// Replaces the locally generated code with the backend's one when reachable.
    func loadCode() async {
        if let code = await bro.createCode() { myCode = code }
    }

    func pair(in context: ModelContext) async {
        guard canPair else { return }
        LocalDataWipe.runPending(in: context)   // before the pairing row is written, see `finish`
        isPairing = true
        pairFailed = false
        let ok = await bro.pair(code: normalizedCode, in: context)
        isPairing = false
        pairFailed = !ok
        if ok { codeEntry = "" }
    }

    // MARK: Finish

    func finish(in context: ModelContext, appState: AppState) async {
        // A deleted account's data goes before the new profile is written, never after it.
        LocalDataWipe.runPending(in: context)
        let t = targets
        let profileName = trimmedName.isEmpty ? String(localized: "bro.you") : trimmedName
        let profile = UserProfile(name: profileName, bodyWeightKg: bodyWeightKg, goal: goal, units: unit,
                                  calorieGoal: t.kcal, proteinGoalG: t.proteinG, carbsGoalG: t.carbsG, fatGoalG: t.fatG)
        context.insert(profile)

        let schedule = GymSchedule(weekdays: Array(weekdays), defaultMinuteOfDay: usualMinuteOfDay,
                                   overrides: overrides, remindHourBefore: remindHourBefore,
                                   askIfSkippedAt21: askIfSkippedAt21)
        context.insert(schedule)

        if let kg = bodyWeightKg {
            context.insert(BodyWeightEntry(day: .now, kg: kg, source: .manual))
        }
        try? context.save()

        RoutineSeeder.seedIfNeeded(context: context)
        if isPaired { try? await bro.client.pushSchedule(ScheduleDTO(schedule)) }

        appState.hasOnboarded = true
    }
}
