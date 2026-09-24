import SwiftUI

/// Today's intake summed from `MealEntry` rows.
struct FuelTotals: Equatable {
    var kcal: Double = 0
    var protein: Double = 0
    var carbs: Double = 0
    var fat: Double = 0

    init(entries: [MealEntry]) {
        for entry in entries {
            kcal += entry.kcal
            protein += entry.proteinG
            carbs += entry.carbsG
            fat += entry.fatG
        }
    }
}

/// Daily goals from the profile; sensible defaults when onboarding has not created one yet.
struct FuelGoals: Equatable {
    var kcal: Double
    var protein: Double
    var carbs: Double
    var fat: Double

    init(profile: UserProfile?) {
        kcal = Double(profile?.calorieGoal ?? 2600)
        protein = Double(profile?.proteinGoalG ?? 180)
        carbs = Double(profile?.carbsGoalG ?? 300)
        fat = Double(profile?.fatGoalG ?? 80)
    }
}

/// "Fuel" header with eaten / goal, a 64 pt kcal-left `MacroRing` and three macro bars in the macro hues. Tapping goes
/// to the Fuel tab.
struct FuelSummaryRow: View {
    var totals: FuelTotals
    var goals: FuelGoals
    var action: () -> Void

    private var kcalLeft: Double { max(0, goals.kcal - totals.kcal) }

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Text("dashboard.fuel").font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink)
                    Spacer()
                    HStack(spacing: 4) {
                        Text(verbatim: "\(Fmt.kcal(totals.kcal, withUnit: false)) / \(Fmt.kcal(goals.kcal))")
                            .font(NT.Fonts.subheadline)
                            .foregroundStyle(NT.Colors.ink2)
                            .tabular()
                        Image(systemName: "chevron.right")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(NT.Colors.ink3)
                    }
                }
                HStack(spacing: 16) {
                    ring
                    VStack(spacing: 9) {
                        MacroBar(label: "macro.protein", value: totals.protein, goal: goals.protein, fill: NT.Colors.protein)
                        MacroBar(label: "macro.carbs", value: totals.carbs, goal: goals.carbs, fill: NT.Colors.carbs)
                        MacroBar(label: "macro.fat", value: totals.fat, goal: goals.fat, fill: NT.Colors.fat)
                    }
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(PressScale())
        .accessibilityElement(children: .combine)
    }

    private var ring: some View {
        ZStack {
            MacroRing(protein: totals.protein, carbs: totals.carbs, fat: totals.fat, kcalGoal: goals.kcal, lineWidth: 6)
            VStack(spacing: 0) {
                Text(Fmt.kcal(kcalLeft, withUnit: false))
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(NT.Colors.ink)
                    .tabular()
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                Text("dashboard.left")
                    .font(.system(size: 8, weight: .semibold))
                    .tracking(0.6)
                    .textCase(.uppercase)
                    .foregroundStyle(NT.Colors.ink2)
            }
            .padding(.horizontal, 8)
        }
        .frame(width: 64, height: 64)
    }
}
