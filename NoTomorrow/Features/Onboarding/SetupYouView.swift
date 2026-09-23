import SwiftUI

/// Step "You": name, body weight + unit, goal chips, suggested daily target.
struct SetupYouView: View {
    @Environment(AppState.self) private var appState
    @Bindable var model: OnboardingModel

    private enum Field { case name, weight }
    @FocusState private var focus: Field?
    @State private var showsTargetEditor = false

    var body: some View {
        OBStepScaffold(index: model.stepIndex ?? 0, count: model.stepCount, onBack: { model.back() }) {
            VStack(alignment: .leading, spacing: 0) {
                OBStepTitle(title: "onboarding.you.title", subtitle: "onboarding.you.subtitle")

                nameField
                    .obLabeled("common.name")
                    .padding(.horizontal, NT.Spacing.screenH)
                    .padding(.top, 28)

                weightRow
                    .obLabeled("common.bodyWeight")
                    .padding(.horizontal, NT.Spacing.screenH)
                    .padding(.top, 18)

                VStack(alignment: .leading, spacing: 10) {
                    Text("common.goal").eyebrow().padding(.horizontal, NT.Spacing.screenH)
                    goalChips
                }
                .padding(.top, 18)

                OBTargetCard(model: model) { showsTargetEditor = true }
                    .padding(.horizontal, NT.Spacing.screenH)
                    .padding(.top, NT.Spacing.section)
            }
        } footer: {
            PrimaryButton(title: "common.continue", isEnabled: model.canContinueFromYou) {
                focus = nil
                model.next()
            }
        }
        .sheet(isPresented: $showsTargetEditor) {
            OBTargetEditorSheet(model: model)
        }
    }

    private var nameField: some View {
        TextField("onboarding.you.namePlaceholder", text: $model.name)
            .font(NT.Fonts.body)
            .foregroundStyle(NT.Colors.ink)
            .textContentType(.givenName)
            .textInputAutocapitalization(.words)
            .autocorrectionDisabled()
            .submitLabel(.next)
            .focused($focus, equals: .name)
            .onSubmit { focus = .weight }
            .obField(isFocused: focus == .name)
    }

    private var weightRow: some View {
        HStack(spacing: 10) {
            HStack(spacing: 12) {
                TextField(Fmt.weight(82.4, unit: .kg, withUnit: false), text: $model.weightText)
                    .font(NT.Fonts.body)
                    .foregroundStyle(NT.Colors.ink)
                    .tabular()
                    .keyboardType(.decimalPad)
                    .focused($focus, equals: .weight)
                Text(model.unit == .kg ? "unit.kg" : "unit.lb")
                    .font(NT.Fonts.subheadline)
                    .foregroundStyle(NT.Colors.ink2)
            }
            .obField(isFocused: focus == .weight)

            OBSegmented(
                options: [.init(value: WeightUnit.kg, title: "unit.kg"), .init(value: WeightUnit.lb, title: "unit.lb")],
                selection: Binding(get: { model.unit }, set: { model.setUnit($0) })
            )
            .frame(width: 120)
        }
    }

    private var goalChips: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                ForEach(TrainingGoal.allCases, id: \.self) { goal in
                    Chip(title: OBL10n.string(Self.goalKey(goal), language: appState.languageOverride),
                         isSelected: model.goal == goal) {
                        withAnimation(.easeOut(duration: 0.15)) { model.goal = goal }
                    }
                }
            }
            .padding(.horizontal, NT.Spacing.screenH)
        }
        .scrollIndicators(.hidden)
        .scrollClipDisabled()
    }

    static func goalKey(_ goal: TrainingGoal) -> String {
        switch goal {
        case .buildMuscle: "goal.buildMuscle"
        case .loseFat: "goal.loseFat"
        case .maintain: "goal.maintain"
        }
    }
}

extension TrainingGoal {
    /// "Build muscle" / "Masa". Looked up by the literal key: an interpolated `String.LocalizationValue("goal.\(x)")`
    /// becomes the key "goal.%@", which the catalog does not have, so the raw key would show.
    var localizedName: String { String(localized: String.LocalizationValue(SetupYouView.goalKey(self))) }
}

/// The one card on the screen: suggested kcal + protein line + formula note.
struct OBTargetCard: View {
    var model: OnboardingModel
    var onEdit: () -> Void

    var body: some View {
        let t = model.targets
        NTCard {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Text("target.daily").eyebrow(NT.Colors.ember)
                    Spacer()
                    Button(action: onEdit) {
                        Text("common.edit")
                            .font(NT.Fonts.footnote)
                            .foregroundStyle(NT.Colors.ink2)
                            .frame(minWidth: NT.Size.control, minHeight: NT.Size.control, alignment: .trailing)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .padding(.trailing, -4)
                }
                .frame(height: 20)

                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(Fmt.kcal(Double(t.kcal), withUnit: false))
                        .font(NT.Fonts.display(56))
                        .foregroundStyle(NT.Colors.ink)
                        .tabular()
                        .contentTransition(.numericText())
                    Text("unit.kcal").font(NT.Fonts.title2).foregroundStyle(NT.Colors.ink2)
                }

                HStack(alignment: .firstTextBaseline) {
                    Text("target.proteinPerKg \(Fmt.grams(Double(t.proteinG))) \(perKgText)")
                        .font(NT.Fonts.subheadline)
                        .foregroundStyle(NT.Colors.ink)
                        .tabular()
                    Spacer(minLength: 8)
                    formulaNote
                        .font(NT.Fonts.footnote)
                        .foregroundStyle(NT.Colors.ink3)
                        .tabular()
                }
            }
        }
        .animation(.easeOut(duration: 0.2), value: t)
    }

    private var perKgText: String {
        let weight = model.bodyWeightKg ?? TargetCalculator.assumedBodyWeightKg
        let perKg = model.customTargets == nil
            ? TargetCalculator.proteinGramsPerKg(for: model.goal)
            : Double(model.targets.proteinG) / weight
        return "\(perKg.formatted(.number.precision(.fractionLength(1))))\u{00A0}g"
    }

    private var formulaNote: Text {
        if model.customTargets != nil { return Text("onboarding.you.custom") }
        let adjustment = TargetCalculator.kcalAdjustment(for: model.goal)
        let signed = adjustment.formatted(.number.sign(strategy: .always(includingZero: false)).precision(.fractionLength(0)))
        return Text("onboarding.you.formula \(signed)")
    }
}
