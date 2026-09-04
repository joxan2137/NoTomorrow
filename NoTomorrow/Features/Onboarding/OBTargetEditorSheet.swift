import SwiftUI

/// Edit the suggested daily target by hand (kcal + macros). "Use the suggestion" goes back to the calculator.
struct OBTargetEditorSheet: View {
    @Bindable var model: OnboardingModel
    @Environment(\.dismiss) private var dismiss

    @State private var kcal = ""
    @State private var protein = ""
    @State private var carbs = ""
    @State private var fat = ""

    private enum Field { case kcal, protein, carbs, fat }
    @FocusState private var focus: Field?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Grabber().frame(maxWidth: .infinity)

            Text("target.daily")
                .font(NT.Fonts.title2)
                .foregroundStyle(NT.Colors.ink)
                .padding(.top, 18)

            VStack(spacing: 12) {
                field("unit.kcal", text: $kcal, unit: "unit.kcal", field: .kcal)
                HStack(spacing: 10) {
                    field("macro.protein", text: $protein, unit: nil, field: .protein)
                    field("macro.carbs", text: $carbs, unit: nil, field: .carbs)
                    field("macro.fat", text: $fat, unit: nil, field: .fat)
                }
            }
            .padding(.top, 18)

            Spacer(minLength: 16)

            VStack(spacing: 10) {
                GhostButton(title: "onboarding.you.suggested") {
                    model.customTargets = nil
                    load(model.suggestedTargets)
                }
                PrimaryButton(title: "common.save") {
                    save()
                    dismiss()
                }
            }
            .padding(.bottom, 8)
        }
        .padding(.horizontal, NT.Spacing.screenH)
        .ntScreenBackground()
        .presentationDetents([.medium])
        .presentationDragIndicator(.hidden)
        .presentationBackground(NT.Colors.ground)
        .onAppear { load(model.targets) }
    }

    private func field(_ label: LocalizedStringKey, text: Binding<String>, unit: LocalizedStringKey?, field: Field) -> some View {
        HStack(spacing: 8) {
            TextField("0", text: text)
                .font(NT.Fonts.body)
                .foregroundStyle(NT.Colors.ink)
                .tabular()
                .keyboardType(.numberPad)
                .focused($focus, equals: field)
            if let unit {
                Text(unit).font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2)
            } else {
                Text(verbatim: "g").font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2)
            }
        }
        .obField(isFocused: focus == field)
        .obLabeled(label)
    }

    private func load(_ t: TargetCalculator.Targets) {
        kcal = String(t.kcal)
        protein = String(t.proteinG)
        carbs = String(t.carbsG)
        fat = String(t.fatG)
    }

    private func save() {
        let edited = TargetCalculator.Targets(
            kcal: Int(kcal.filter(\.isNumber)) ?? model.targets.kcal,
            proteinG: Int(protein.filter(\.isNumber)) ?? model.targets.proteinG,
            carbsG: Int(carbs.filter(\.isNumber)) ?? model.targets.carbsG,
            fatG: Int(fat.filter(\.isNumber)) ?? model.targets.fatG
        )
        model.customTargets = edited == model.suggestedTargets ? nil : edited
    }
}
