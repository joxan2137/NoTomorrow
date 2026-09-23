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
    /// Edit mode: the texts the number fields started with. Fields still showing them save the entry's exact figures,
    /// so a rename, slot or day move keeps an AI row's badge and numbers.
    private let prefill: MealEntry.EditTexts?
    /// Edit mode: Delete in the header. The presenter closes the sheet and deletes through `FuelModel`, so Undo shows.
    private let onDelete: (() -> Void)?

    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var modelContext
    @State private var name: String
    @State private var gramsText = ""
    @State private var kcalText = ""
    @State private var proteinText = ""
    @State private var carbsText = ""
    @State private var fatText = ""
    @State private var slot: MealSlot
    /// Edit mode only: the day the entry is listed under, movable with `EntryDayStepper`.
    @State private var entryDay: Date
    /// Edit mode: the figure texts the sheet itself last put in the fields (the prefill, then each rescale). While the
    /// fields still show them, changing the grams rescales kcal and macros; once the user types a figure, it stays.
    @State private var writtenFigures: MealEntry.EditTexts?
    @FocusState private var focus: Field?

    enum Field: Hashable { case name, grams, kcal, protein, carbs, fat }

    init(meal: MealSlot, day: Date = .now, initialName: String = "", onAdded: (() -> Void)? = nil) {
        self.meal = meal
        self.day = day
        self.initialName = initialName
        self.onAdded = onAdded
        self.editing = nil
        self.prefill = nil
        self.onDelete = nil
        _name = State(initialValue: initialName)
        _slot = State(initialValue: meal)
        _entryDay = State(initialValue: Calendar.current.startOfDay(for: day))
    }

    /// Edit mode: every field starts at the entry's figures, the day and slot can be changed, and Save rewrites the
    /// entry in place.
    init(editing entry: MealEntry, onSaved: @escaping () -> Void, onDelete: (() -> Void)? = nil) {
        self.meal = entry.slot
        self.day = entry.day
        self.initialName = entry.displayName
        self.onAdded = onSaved
        self.onDelete = onDelete
        self.editing = entry
        let texts = entry.editTexts()
        self.prefill = texts
        _name = State(initialValue: entry.displayName)
        _gramsText = State(initialValue: texts.grams)
        _kcalText = State(initialValue: texts.kcal)
        _proteinText = State(initialValue: texts.protein)
        _carbsText = State(initialValue: texts.carbs)
        _fatText = State(initialValue: texts.fat)
        _slot = State(initialValue: entry.slot)
        _entryDay = State(initialValue: FuelCalendar.dayKey(entry.day))
        _writtenFigures = State(initialValue: texts)
    }

    private var isEditing: Bool { editing != nil }
    /// AI rows carry a portion; quick-add rows do not, so the grams field only shows when there is something to edit.
    private var showsGrams: Bool { (editing?.grams ?? 0) > 0 }
    private var kcal: Double? { Self.number(kcalText) }
    private var currentTexts: MealEntry.EditTexts {
        MealEntry.EditTexts(grams: gramsText, kcal: kcalText, protein: proteinText, carbs: carbsText, fat: fatText)
    }
    /// The kcal and macro fields still show what the sheet filled in.
    private var figuresUntouched: Bool { writtenFigures.map { currentTexts.sameFigures(as: $0) } ?? false }
    /// A new quick add needs a name and some kcal. An edit needs a name and a kcal figure that parses, 0 included.
    private var canAdd: Bool {
        if let editing, let prefill { return editing.canSaveEdit(name: name, kcalText: kcalText, prefill: prefill) }
        return !name.trimmingCharacters(in: .whitespaces).isEmpty && (kcal ?? 0) > 0
    }

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
                        EntryDayStepper(day: $entryDay, height: 52, background: NT.Colors.surface).padding(.top, 4)
                        MealSlotPicker(slot: $slot)
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
        .onChange(of: gramsText) { _, text in rescaleFigures(for: text) }
    }

    /// Weighing an AI or quick-add portion afterwards: kcal and macros follow the grams proportionally, unless the user
    /// already typed over one of them.
    private func rescaleFigures(for text: String) {
        guard let editing, let prefill, showsGrams, figuresUntouched,
              let next = editing.rescaledTexts(gramsText: text, prefill: prefill) else { return }
        kcalText = next.kcal
        proteinText = next.protein
        carbsText = next.carbs
        fatText = next.fat
        writtenFigures = next
    }

    private var header: some View {
        HStack {
            Text(isEditing ? "fuel.editEntry" : "fuel.quickAdd").font(NT.Fonts.title2).foregroundStyle(NT.Colors.ink)
            Spacer()
            if isEditing, let onDelete {
                Button(action: onDelete) {
                    Text("common.delete").font(NT.Fonts.body).foregroundStyle(NT.Colors.bad)
                        .padding(.horizontal, 8)
                        .frame(minHeight: NT.Size.control)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
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
        NumberInput.nonNegative(text)
    }

    private func add() {
        guard canAdd else { return }
        if let editing, let prefill {
            guard editing.applyEdit(name: name, texts: currentTexts, prefill: prefill,
                                    figuresUntouched: figuresUntouched, slot: slot, day: entryDay) else { return }
        } else {
            guard let kcal else { return }
            let trimmedName = name.trimmingCharacters(in: .whitespaces)
            let protein = Self.number(proteinText) ?? 0
            let carbs = Self.number(carbsText) ?? 0
            let fat = Self.number(fatText) ?? 0
            let entry = MealEntry(day: day, slot: slot, customName: trimmedName,
                                  grams: 0, kcal: kcal, proteinG: protein, carbsG: carbs, fatG: fat)
            modelContext.insert(entry)
        }
        try? modelContext.save()
        if let onAdded { onAdded() } else { dismiss() }
    }
}
