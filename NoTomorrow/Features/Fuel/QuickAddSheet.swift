import SwiftUI
import SwiftData

/// Manual entry for foods the database does not know: name, kcal and optional P/C/F → a `MealEntry` with `customName`.
struct QuickAddSheet: View {
    let meal: MealSlot
    var day: Date = .now
    var initialName: String = ""
    var onAdded: (() -> Void)? = nil

    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var modelContext
    @State private var name: String
    @State private var kcalText = ""
    @State private var proteinText = ""
    @State private var carbsText = ""
    @State private var fatText = ""
    @FocusState private var focus: Field?

    enum Field: Hashable { case name, kcal, protein, carbs, fat }

    init(meal: MealSlot, day: Date = .now, initialName: String = "", onAdded: (() -> Void)? = nil) {
        self.meal = meal
        self.day = day
        self.initialName = initialName
        self.onAdded = onAdded
        _name = State(initialValue: initialName)
    }

    private var kcal: Double? { Self.number(kcalText) }
    private var canAdd: Bool { !name.trimmingCharacters(in: .whitespaces).isEmpty && (kcal ?? 0) > 0 }

    var body: some View {
        VStack(spacing: 0) {
            header
            ScrollView {
                VStack(spacing: 10) {
                    textRow("fuel.foodName", text: $name, field: .name)
                    numberRow("unit.kcal", text: $kcalText, field: .kcal, unit: "unit.kcal")
                    HStack(spacing: 10) {
                        numberRow("fuel.macro.p", text: $proteinText, field: .protein, unit: "unit.g")
                        numberRow("fuel.macro.c", text: $carbsText, field: .carbs, unit: "unit.g")
                        numberRow("fuel.macro.f", text: $fatText, field: .fat, unit: "unit.g")
                    }
                    Text("fuel.quickAdd.hint").font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.top, 4)
                }
                .padding(.horizontal, NT.Spacing.screenH)
                .padding(.top, 16)
            }
            .scrollDismissesKeyboard(.interactively)
            PrimaryButton(title: FuelText.verbatim(FuelText.addTo(meal)), isEnabled: canAdd) { add() }
                .padding(.horizontal, NT.Spacing.screenH)
                .padding(.bottom, 12)
        }
        .ntScreenBackground()
        .onAppear { focus = name.isEmpty ? .name : .kcal }
    }

    private var header: some View {
        HStack {
            Text("fuel.quickAdd").font(NT.Fonts.title2).foregroundStyle(NT.Colors.ink)
            Spacer()
            Button { dismiss() } label: {
                Text("common.cancel").font(NT.Fonts.body).foregroundStyle(NT.Colors.ink2)
                    .frame(minHeight: NT.Size.control)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .frame(height: NT.Size.control)
        .padding(.horizontal, NT.Spacing.screenH)
        .padding(.top, 12)
    }

    private func textRow(_ label: LocalizedStringKey, text: Binding<String>, field: Field) -> some View {
        HStack(spacing: 12) {
            Text(label).font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2)
            TextField("", text: text)
                .font(NT.Fonts.body).foregroundStyle(NT.Colors.ink).tint(NT.Colors.ink)
                .multilineTextAlignment(.trailing)
                .submitLabel(.next)
                .focused($focus, equals: field)
                .onSubmit { focus = .kcal }
        }
        .padding(.horizontal, 14)
        .frame(height: 52)
        .background(NT.Colors.surface, in: RoundedRectangle(cornerRadius: NT.Radius.field, style: .continuous))
    }

    private func numberRow(_ label: LocalizedStringKey, text: Binding<String>, field: Field, unit: LocalizedStringKey) -> some View {
        HStack(spacing: 8) {
            Text(label).font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2)
            TextField("0", text: text)
                .font(NT.Fonts.body).foregroundStyle(NT.Colors.ink).tint(NT.Colors.ink).tabular()
                .multilineTextAlignment(.trailing)
                .keyboardType(.decimalPad)
                .focused($focus, equals: field)
            Text(unit).font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2)
        }
        .padding(.horizontal, 14)
        .frame(height: 52)
        .frame(maxWidth: .infinity)
        .background(NT.Colors.surface, in: RoundedRectangle(cornerRadius: NT.Radius.field, style: .continuous))
        .contentShape(Rectangle())
        .onTapGesture { focus = field }
    }

    private static func number(_ text: String) -> Double? {
        Double(text.replacingOccurrences(of: ",", with: ".").trimmingCharacters(in: .whitespaces))
    }

    private func add() {
        guard canAdd, let kcal else { return }
        let entry = MealEntry(day: day, slot: meal, customName: name.trimmingCharacters(in: .whitespaces),
                              grams: 0, kcal: kcal,
                              proteinG: Self.number(proteinText) ?? 0,
                              carbsG: Self.number(carbsText) ?? 0,
                              fatG: Self.number(fatText) ?? 0)
        modelContext.insert(entry)
        try? modelContext.save()
        if let onAdded { onAdded() } else { dismiss() }
    }
}
