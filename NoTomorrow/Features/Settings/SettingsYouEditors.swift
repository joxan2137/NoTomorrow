import SwiftUI
import SwiftData

// MARK: - Name

struct NameEditor: View {
    @Bindable var profile: UserProfile
    @State private var draft = ""
    @FocusState private var focused: Bool

    var body: some View {
        STEditorScreen(title: "common.name") {
            TextField(String(localized: "onboarding.you.namePlaceholder"), text: $draft)
                .font(NT.Fonts.body)
                .foregroundStyle(NT.Colors.ink)
                .textInputAutocapitalization(.words)
                .autocorrectionDisabled()
                .submitLabel(.done)
                .focused($focused)
                .onSubmit(commit)
                .stField(isFocused: focused)
                .stLabeled("common.name")
        }
        .onAppear { draft = profile.name; focused = true }
        .onDisappear(perform: commit)
    }

    private func commit() {
        let trimmed = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed != profile.name else { return }
        profile.name = trimmed
    }
}

// MARK: - Body weight

/// Number field in the profile unit; saving updates the profile and logs today's `BodyWeightEntry`.
struct BodyWeightEditor: View {
    @Bindable var profile: UserProfile
    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss
    @State private var draft = ""
    @FocusState private var focused: Bool

    private var entered: Double? { STNumber.parse(draft).flatMap { $0 > 0 ? $0 : nil } }
    private var enteredKg: Double? {
        guard let value = entered else { return nil }
        return profile.units == .kg ? value : value / 2.2046226218
    }

    var body: some View {
        STEditorScreen(title: "common.bodyWeight") {
            HStack(spacing: 12) {
                TextField(String(localized: "settings.bodyWeight.placeholder"), text: $draft)
                    .font(NT.Fonts.body)
                    .foregroundStyle(NT.Colors.ink)
                    .keyboardType(.decimalPad)
                    .focused($focused)
                    .tabular()
                Text(profile.units.rawValue).font(NT.Fonts.body).foregroundStyle(NT.Colors.ink2)
            }
            .stField(isFocused: focused)
            .stLabeled("common.bodyWeight")
            .stFootnote("settings.bodyWeight.footnote")

            PrimaryButton(title: "common.save", isEnabled: enteredKg != nil) { save() }
        }
        .onAppear {
            if let kg = profile.bodyWeightKg { draft = Fmt.weight(kg, unit: profile.units, withUnit: false) }
            focused = true
        }
    }

    private func save() {
        guard let kg = enteredKg else { return }
        profile.bodyWeightKg = kg
        let today = Calendar.current.startOfDay(for: .now)
        let tomorrow = Calendar.current.date(byAdding: .day, value: 1, to: today) ?? today
        let descriptor = FetchDescriptor<BodyWeightEntry>(predicate: #Predicate { $0.day >= today && $0.day < tomorrow })
        if let existing = try? modelContext.fetch(descriptor).first {
            existing.kg = kg
            existing.source = .manual
        } else {
            modelContext.insert(BodyWeightEntry(day: today, kg: kg, source: .manual))
        }
        try? modelContext.save()
        dismiss()
    }
}

// MARK: - Daily target

/// kcal / protein / carbs / fat fields plus "Suggest" from `TargetCalculator`.
struct DailyTargetEditor: View {
    @Bindable var profile: UserProfile
    @Environment(\.dismiss) private var dismiss

    @State private var kcal = ""
    @State private var protein = ""
    @State private var carbs = ""
    @State private var fat = ""
    @FocusState private var focus: Field?

    private enum Field: Hashable { case kcal, protein, carbs, fat }

    private var isValid: Bool {
        STNumber.parseInt(kcal) != nil && STNumber.parseInt(protein) != nil
            && STNumber.parseInt(carbs) != nil && STNumber.parseInt(fat) != nil
    }

    var body: some View {
        STEditorScreen(title: "settings.dailyTarget") {
            field("settings.target.kcal", text: $kcal, unit: "kcal", field: .kcal)
                .stLabeled("settings.target.kcal")

            VStack(spacing: 10) {
                field("macro.protein", text: $protein, unit: "g", field: .protein)
                field("macro.carbs", text: $carbs, unit: "g", field: .carbs)
                field("macro.fat", text: $fat, unit: "g", field: .fat)
            }
            .stLabeled("settings.target.macros")

            goalChips.stLabeled("common.goal")

            SecondaryButton(title: "settings.target.suggest", height: NT.Size.cardButton) { suggest() }
                .stFootnote("settings.target.suggestFootnote")

            PrimaryButton(title: "common.save", isEnabled: isValid) { save() }
        }
        .onAppear(perform: load)
    }

    private func field(_ label: LocalizedStringKey, text: Binding<String>, unit: String, field: Field) -> some View {
        HStack(spacing: 12) {
            Text(label).font(NT.Fonts.body).foregroundStyle(NT.Colors.ink)
            TextField("", text: text)
                .font(NT.Fonts.body)
                .foregroundStyle(NT.Colors.ink)
                .multilineTextAlignment(.trailing)
                .keyboardType(.numberPad)
                .focused($focus, equals: field)
                .tabular()
            Text(unit).font(NT.Fonts.body).foregroundStyle(NT.Colors.ink2)
        }
        .stField(isFocused: focus == field)
    }

    private var goalChips: some View {
        HStack(spacing: 8) {
            ForEach(TrainingGoal.allCases, id: \.self) { goal in
                Chip(title: String(localized: String.LocalizationValue("goal.\(goal.rawValue)")),
                     isSelected: profile.goal == goal) {
                    profile.goal = goal
                }
            }
        }
    }

    private func load() {
        kcal = String(profile.calorieGoal)
        protein = String(profile.proteinGoalG)
        carbs = String(profile.carbsGoalG)
        fat = String(profile.fatGoalG)
    }

    private func suggest() {
        let t = TargetCalculator.targets(bodyWeightKg: profile.bodyWeightKg, goal: profile.goal)
        withAnimation(.easeOut(duration: 0.2)) {
            kcal = String(t.kcal)
            protein = String(t.proteinG)
            carbs = String(t.carbsG)
            fat = String(t.fatG)
        }
        focus = nil
    }

    private func save() {
        guard let k = STNumber.parseInt(kcal), let p = STNumber.parseInt(protein),
              let c = STNumber.parseInt(carbs), let f = STNumber.parseInt(fat) else { return }
        profile.calorieGoal = k
        profile.proteinGoalG = p
        profile.carbsGoalG = c
        profile.fatGoalG = f
        dismiss()
    }
}
