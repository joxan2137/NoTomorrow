import SwiftUI
import SwiftData

/// Manual entry for foods the database does not know: name, kcal and optional P/C/F → a `MealEntry` with `customName`.
struct QuickAddSheet: View {
    let meal: MealSlot
    var day: Date = .now
    var initialName: String = ""
    var onAdded: (() -> Void)? = nil
    /// Set when the sheet rewrites an entry that is already logged (quick-add or AI row) instead of inserting one.
    private let editing: MealEntry?

    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var modelContext
    @State private var name: String
    @State private var gramsText = ""
    @State private var kcalText = ""
    @State private var proteinText = ""
    @State private var carbsText = ""
    @State private var fatText = ""
    @State private var slot: MealSlot
    @FocusState private var focus: Field?

    enum Field: Hashable { case name, grams, kcal, protein, carbs, fat }

    init(meal: MealSlot, day: Date = .now, initialName: String = "", onAdded: (() -> Void)? = nil) {
        self.meal = meal
        self.day = day
        self.initialName = initialName
        self.onAdded = onAdded
        self.editing = nil
        _name = State(initialValue: initialName)
        _slot = State(initialValue: meal)
    }

    /// Edit mode: every field starts at the entry's figures, the slot can be changed, and Save rewrites the entry in place.
    init(editing entry: MealEntry, onSaved: @escaping () -> Void) {
        self.meal = entry.slot
        self.day = entry.day
        self.initialName = entry.displayName
        self.onAdded = onSaved
        self.editing = entry
        _name = State(initialValue: entry.displayName)
        _gramsText = State(initialValue: entry.grams > 0 ? FuelText.fieldText(entry.grams) : "")
        _kcalText = State(initialValue: FuelText.fieldText(entry.kcal))
        _proteinText = State(initialValue: FuelText.fieldText(entry.proteinG))
        _carbsText = State(initialValue: FuelText.fieldText(entry.carbsG))
        _fatText = State(initialValue: FuelText.fieldText(entry.fatG))
        _slot = State(initialValue: entry.slot)
    }

    private var isEditing: Bool { editing != nil }
    /// AI rows carry a portion; quick-add rows do not, so the grams field only shows when there is something to edit.
    private var showsGrams: Bool { (editing?.grams ?? 0) > 0 }
    private var kcal: Double? { Self.number(kcalText) }
    private var canAdd: Bool { !name.trimmingCharacters(in: .whitespaces).isEmpty && (kcal ?? 0) > 0 }

    var body: some View {
        VStack(spacing: 0) {
            header
            ScrollView {
                VStack(spacing: 10) {
                    textRow("fuel.foodName", text: $name, field: .name)
                    if showsGrams {
                        numberRow("fuel.grams", text: $gramsText, field: .grams, unit: "unit.g")
                    }
                    numberRow("unit.kcal", text: $kcalText, field: .kcal, unit: "unit.kcal")
                    HStack(spacing: 10) {
                        numberRow("fuel.macro.p", text: $proteinText, field: .protein, unit: "unit.g")
                        numberRow("fuel.macro.c", text: $carbsText, field: .carbs, unit: "unit.g")
                        numberRow("fuel.macro.f", text: $fatText, field: .fat, unit: "unit.g")
                    }
                    if isEditing {
                        MealSlotPicker(slot: $slot).padding(.top, 4)
                    }
                    Text("fuel.quickAdd.hint").font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.top, 4)
                }
                .padding(.horizontal, NT.Spacing.screenH)
                .padding(.top, 16)
            }
            .scrollDismissesKeyboard(.interactively)
            PrimaryButton(title: isEditing ? "common.save" : FuelText.verbatim(FuelText.addTo(slot)), isEnabled: canAdd) { add() }
                .padding(.horizontal, NT.Spacing.screenH)
                .padding(.bottom, 12)
        }
        .ntScreenBackground()
        .onAppear { if !isEditing { focus = name.isEmpty ? .name : .kcal } }
    }

    private var header: some View {
        HStack {
            Text(isEditing ? "fuel.editEntry" : "fuel.quickAdd").font(NT.Fonts.title2).foregroundStyle(NT.Colors.ink)
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
        let trimmedName = name.trimmingCharacters(in: .whitespaces)
        let protein = Self.number(proteinText) ?? 0
        let carbs = Self.number(carbsText) ?? 0
        let fat = Self.number(fatText) ?? 0
        if let editing {
            let grams = showsGrams ? max(0, Self.number(gramsText) ?? editing.grams) : 0
            editing.overwrite(name: trimmedName, grams: grams, kcal: kcal, proteinG: protein, carbsG: carbs, fatG: fat)
            editing.slot = slot
        } else {
            let entry = MealEntry(day: day, slot: slot, customName: trimmedName,
                                  grams: 0, kcal: kcal, proteinG: protein, carbsG: carbs, fatG: fat)
            modelContext.insert(entry)
        }
        try? modelContext.save()
        if let onAdded { onAdded() } else { dismiss() }
    }
}
