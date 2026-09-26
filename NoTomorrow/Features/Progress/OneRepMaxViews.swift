import SwiftUI

/// "% · weight · reps" rows for an e1RM already in `unit` (`OneRepMax`): the lift page's Percentages and the
/// 1RM calculator.
struct PercentageTable: View {
    var e1RM: Double
    var unit: WeightUnit

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("onerm.percent").frame(width: 64, alignment: .leading)
                Text("onerm.weight").frame(maxWidth: .infinity, alignment: .leading)
                Text("workout.reps").frame(width: 64, alignment: .trailing)
            }
            .font(NT.Fonts.caption).foregroundStyle(NT.Colors.ink2)
            .padding(.bottom, 4)
            ForEach(Array(OneRepMax.rows(e1RM: e1RM, step: WarmupPlan.increment(for: unit)).enumerated()),
                    id: \.offset) { index, row in
                if index > 0 { Hairline() }
                HStack {
                    Text(verbatim: (Double(row.percent) / 100).formatted(.percent.locale(Fmt.locale)))
                        .font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink).tabular()
                        .frame(width: 64, alignment: .leading)
                    Text(verbatim: Fmt.plate(row.weight, unit: unit))
                        .font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink).tabular()
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Text(verbatim: "\(row.reps)")
                        .font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2).tabular()
                        .frame(width: 64, alignment: .trailing)
                }
                .frame(height: 44)
            }
        }
    }
}

/// "1RM calculator": a set's weight × reps → the Epley e1RM and its percentage table. Reached from a lift's
/// Percentages and from the plate calculator. Weights are typed and shown in the user's unit.
struct OneRepMaxCalculatorSheet: View {
    let unit: WeightUnit
    /// Prefilled weight in `unit` (the plate calculator's target), if any.
    var initialWeight: Double?

    @Environment(\.dismiss) private var dismiss
    @State private var weightText = ""
    @State private var repsText = ""
    @FocusState private var focusedField: Field?

    private enum Field { case weight, reps }

    private var weight: Double { min(SetInput.number(weightText), 10_000) }
    private var reps: Int { SetInput.reps(repsText) }
    private var e1RM: Double { OneRepMax.estimate(weight: weight, reps: reps) }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    HStack(spacing: 10) {
                        field("onerm.weight", text: $weightText, suffix: unit.rawValue, keyboard: .decimalPad, focus: .weight)
                        Text(verbatim: "×").font(NT.Fonts.title2).foregroundStyle(NT.Colors.ink2)
                        field("workout.reps", text: $repsText, suffix: nil, keyboard: .numberPad, focus: .reps)
                    }
                    result.padding(.top, 20)
                    if e1RM > 0 {
                        PercentageTable(e1RM: e1RM, unit: unit).padding(.top, 16)
                    }
                }
                .padding(.horizontal, NT.Spacing.screenH)
                .padding(.bottom, NT.Spacing.section)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .ntScreenBackground()
        .presentationBackground(NT.Colors.ground)
        .presentationDragIndicator(.visible)
        .presentationDetents([.medium, .large])
        .onAppear {
            if let initialWeight, initialWeight > 0, weightText.isEmpty {
                weightText = initialWeight.formatted(.number.precision(.fractionLength(0...2)).grouping(.never)
                    .locale(Fmt.locale))
            }
            focusedField = weightText.isEmpty ? .weight : .reps
        }
    }

    private var header: some View {
        HStack {
            Text("onerm.calculator").font(NT.Fonts.title2).foregroundStyle(NT.Colors.ink)
            Spacer()
            Button { dismiss() } label: {
                Text("common.done").font(NT.Fonts.body).foregroundStyle(NT.Colors.ink2)
                    .frame(minHeight: NT.Size.control)
            }
        }
        .padding(.horizontal, NT.Spacing.screenH)
        .padding(.top, 16)
        .padding(.bottom, 8)
    }

    private func field(_ label: LocalizedStringKey, text: Binding<String>, suffix: String?,
                       keyboard: UIKeyboardType, focus: Field) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).eyebrow()
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                TextField("", text: text, prompt: Text(verbatim: "0").foregroundStyle(NT.Colors.ink3))
                    .keyboardType(keyboard)
                    .font(NT.Fonts.title2)
                    .foregroundStyle(NT.Colors.ink)
                    .tabular()
                    .focused($focusedField, equals: focus)
                if let suffix {
                    Text(verbatim: suffix).font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2)
                }
            }
            .padding(.horizontal, 14)
            .frame(height: 56)
            .background(NT.Colors.surface, in: RoundedRectangle(cornerRadius: NT.Radius.field, style: .continuous))
        }
        .frame(maxWidth: .infinity)
    }

    private var result: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("onerm.estimated").eyebrow()
            if e1RM > 0 {
                Text(verbatim: Fmt.plate(OneRepMax.round(e1RM, step: 0.1), unit: unit))
                    .font(NT.Fonts.display(44)).foregroundStyle(NT.Colors.ink).tabular()
            } else {
                Text("onerm.hint")
                    .font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}
