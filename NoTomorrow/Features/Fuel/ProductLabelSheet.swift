import SwiftUI
import SwiftData
import PhotosUI

/// Saves a product the database cannot size (not in Open Food Facts, a name without nutrition, or an in-store code)
/// from its printed label, so the next scan finds it on this device. `code` is the scanned code; the item is filed under
/// `BarcodeKey.storageKey(for:)`, which is the 7-digit item key for in-store weight labels. A stub from Open Food Facts
/// pre-fills the name, brand and serving. "Photograph the label" lets the AI read the nutrition table into the fields.
struct ProductLabelSheet: View {
    let code: String
    let stub: ProductStub?
    let onSaved: (FoodItem) -> Void

    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var kcalText = ""
    @State private var proteinText = ""
    @State private var carbsText = ""
    @State private var fatText = ""
    @State private var fiberText = ""
    @State private var servingText: String
    @State private var failed = false
    /// A brand printed on the label, used when Open Food Facts gave none.
    @State private var readBrand: String?
    @State private var reader = LabelPhotoReader()
    @State private var showsCamera = false
    @State private var showsLibrary = false
    @State private var libraryItem: PhotosPickerItem?
    /// The first appearance put the cursor in the form. The camera's full-screen cover makes the sheet appear again
    /// when it closes, and that must not bring the keyboard back over "Reading the label…".
    @State private var didFocusOnce = false
    @FocusState private var focus: Field?

    enum Field: Hashable { case name, kcal, protein, carbs, fat, fiber, serving }

    init(code: String, stub: ProductStub? = nil, onSaved: @escaping (FoodItem) -> Void) {
        self.code = code
        self.stub = stub
        self.onSaved = onSaved
        _name = State(initialValue: stub?.name ?? "")
        _servingText = State(initialValue: stub?.servingSizeG.map { FuelText.fieldText($0) } ?? "")
    }

    private var key: String { BarcodeKey.storageKey(for: code) }
    private var trimmedName: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var values: LabelValues? {
        LabelValues.parse(kcal: kcalText, protein: proteinText, carbs: carbsText, fat: fatText,
                          fiber: fiberText, serving: servingText)
    }
    private var canSave: Bool { values != nil && !trimmedName.isEmpty }
    private var missing: [LabelValues.Field] {
        LabelValues.missingRequired(name: trimmedName, kcal: kcalText, protein: proteinText, carbs: carbsText, fat: fatText)
    }

    /// Why Save is disabled: the required fields still empty, or, with all of them filled, a figure out of range
    /// (unless the photo read is already saying so).
    private var saveHint: String? {
        if !missing.isEmpty {
            return FuelText.format("fuel.label.missing", missing.map(\.title).joined(separator: ", "))
        }
        if values == nil, reader.status != .filled(needsReview: true) {
            return String(localized: "fuel.label.checkMacros")
        }
        return nil
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    productLine
                    photoRow
                    textRow("fuel.foodName", text: $name, field: .name)
                    Text("fuel.per100").eyebrow(NT.Colors.ink3).padding(.top, 8)
                    numberRow("unit.kcal", text: $kcalText, field: .kcal, unit: "unit.kcal")
                    HStack(spacing: 10) {
                        numberRow("fuel.macro.p", accessibilityLabel: "macro.protein", text: $proteinText, field: .protein,
                                  unit: "unit.g")
                        numberRow("fuel.macro.c", accessibilityLabel: "macro.carbs", text: $carbsText, field: .carbs,
                                  unit: "unit.g")
                        numberRow("fuel.macro.f", accessibilityLabel: "macro.fat", text: $fatText, field: .fat,
                                  unit: "unit.g")
                    }
                    HStack(spacing: 10) {
                        numberRow("macro.fiber", text: $fiberText, field: .fiber, unit: "unit.g")
                        numberRow("fuel.label.servingSize", text: $servingText, field: .serving, unit: nil)
                    }
                    Text("fuel.label.hint").font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 4)
                    if failed {
                        Text("fuel.label.saveFailed").font(NT.Fonts.footnote).foregroundStyle(NT.Colors.bad)
                    }
                }
                .padding(.horizontal, NT.Spacing.screenH)
                .padding(.top, 16)
            }
            .scrollDismissesKeyboard(.interactively)
            if let saveHint {
                Text(saveHint)
                    .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, NT.Spacing.screenH)
                    .padding(.top, 8)
                    .padding(.bottom, 10)
            }
            PrimaryButton(title: "common.save", isEnabled: canSave) { save() }
                .padding(.horizontal, NT.Spacing.screenH)
                .padding(.bottom, 12)
        }
        .ntScreenBackground()
        .onAppear {
            guard !didFocusOnce else { return }
            didFocusOnce = true
            focus = name.isEmpty ? .name : .kcal
        }
        // The camera's full-screen cover (and the library picker) hides the sheet too; the read they start must
        // survive that. Only the sheet going away (Cancel, Save, a swipe down) stops it.
        .onDisappear { if !showsCamera && !showsLibrary { reader.cancel() } }
        .fullScreenCover(isPresented: $showsCamera) {
            CameraPicker { image in
                showsCamera = false
                if let image { readLabel(.image(image)) }
            }
            .ignoresSafeArea()
        }
        .photosPicker(isPresented: $showsLibrary, selection: $libraryItem, matching: .images, photoLibrary: .shared())
        .onChange(of: libraryItem) { _, item in
            guard let item else { return }
            Task {
                let data = try? await item.loadTransferable(type: Data.self)
                await MainActor.run {
                    libraryItem = nil
                    if let data { readLabel(.data(data)) }
                }
            }
        }
        .alert(Text(String(format: String(localized: "fuel.ai.consent.title"), reader.providerName)),
               isPresented: $reader.showConsent) {
            Button("fuel.ai.consent.accept") { reader.acceptConsent() }
            Button("common.cancel", role: .cancel) { reader.declineConsent() }
        } message: {
            Text(String(format: String(localized: "fuel.ai.consent.body"), reader.providerName))
        }
    }

    // MARK: Photo read

    private var cameraAvailable: Bool { UIImagePickerController.isSourceTypeAvailable(.camera) }

    /// "Photograph the label" (camera, or the library without one) plus a library shortcut; while reading, a spinner;
    /// afterwards, what happened.
    private var photoRow: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                SecondaryButton(title: "fuel.label.photo", systemImage: cameraAvailable ? "camera" : "photo.on.rectangle",
                                height: NT.Size.cardButton) {
                    focus = nil
                    if cameraAvailable { showsCamera = true } else { showsLibrary = true }
                }
                .disabled(reader.isReading)
                if cameraAvailable {
                    Button {
                        focus = nil
                        showsLibrary = true
                    } label: {
                        Image(systemName: "photo.on.rectangle")
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundStyle(NT.Colors.ink)
                            .frame(width: NT.Size.cardButton, height: NT.Size.cardButton)
                            .background(NT.Colors.surface2, in: Circle())
                    }
                    .buttonStyle(PressScale())
                    .disabled(reader.isReading)
                    .accessibilityLabel(Text("fuel.ai.chooseLibrary"))
                }
            }
            .opacity(reader.isReading ? 0.5 : 1)
            readStatus
        }
        .padding(.bottom, 4)
    }

    @ViewBuilder
    private var readStatus: some View {
        switch reader.status {
        case .idle:
            EmptyView()
        case .reading:
            HStack(spacing: 8) {
                ProgressView().tint(NT.Colors.ink2)
                Text("fuel.label.reading").font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2)
            }
        case .filled(let needsReview):
            statusText(needsReview ? "fuel.label.checkMacros" : "fuel.label.aiFilled",
                       color: needsReview ? NT.Colors.ember : NT.Colors.ink2)
        case .unreadable:
            statusText("fuel.label.unreadable", color: NT.Colors.ember)
        case .failed(let message):
            statusText(FuelText.verbatim(message), color: NT.Colors.ember)
        }
    }

    private func statusText(_ key: LocalizedStringKey, color: Color) -> some View {
        Text(key).font(NT.Fonts.footnote).foregroundStyle(color).fixedSize(horizontal: false, vertical: true)
    }

    private func readLabel(_ photo: PickedPhoto) {
        reader.read(photo) { reading in
            let fill = LabelFill.from(reading, currentName: name)
            if let value = fill.name { name = value }
            if let value = fill.brand { readBrand = value }
            if let value = fill.kcal { kcalText = value }
            if let value = fill.protein { proteinText = value }
            if let value = fill.carbs { carbsText = value }
            if let value = fill.fat { fatText = value }
            if let value = fill.fiber { fiberText = value }
            if let value = fill.serving { servingText = value }
        }
    }

    private var header: some View {
        HStack {
            Text("fuel.label.title").font(NT.Fonts.title2).foregroundStyle(NT.Colors.ink)
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

    /// "MOWI · 150 g · 2050401935713", plus the store-label note when the item is filed under its 7-digit key.
    private var productLine: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(([stub?.brand ?? readBrand, stub?.quantity].compactMap { $0 } + [code]).joined(separator: " · "))
                .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2).tabular()
                .lineLimit(1)
            if key != code {
                Text("fuel.label.storeCode").font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ember)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.bottom, 4)
    }

    /// The caption is the field's VoiceOver label, so the caption itself is not read twice.
    private func textRow(_ label: LocalizedStringKey, text: Binding<String>, field: Field) -> some View {
        HStack(spacing: 12) {
            Text(label).font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2)
                .accessibilityHidden(true)
            TextField("", text: text)
                .font(NT.Fonts.body).foregroundStyle(NT.Colors.ink).tint(NT.Colors.ink)
                .multilineTextAlignment(.trailing)
                .submitLabel(.next)
                .focused($focus, equals: field)
                .onSubmit { focus = .kcal }
                .accessibilityLabel(Text(label))
        }
        .padding(.horizontal, 14)
        .frame(height: 52)
        .background(NT.Colors.surface, in: RoundedRectangle(cornerRadius: NT.Radius.field, style: .continuous))
    }

    /// Every number field shows a dash while empty: a grey "0" in a required field read as a value already there.
    /// `accessibilityLabel` names the field for VoiceOver ("B" / "W" / "T" on screen are Białko / Węglowodany /
    /// Tłuszcze).
    private func numberRow(_ label: LocalizedStringKey, accessibilityLabel: LocalizedStringKey? = nil,
                           text: Binding<String>, field: Field, unit: LocalizedStringKey?) -> some View {
        HStack(spacing: 8) {
            Text(label).font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2).lineLimit(1)
                .accessibilityHidden(true)
            TextField(Self.emptyPlaceholder, text: text)
                .font(NT.Fonts.body).foregroundStyle(NT.Colors.ink).tint(NT.Colors.ink).tabular()
                .multilineTextAlignment(.trailing)
                .keyboardType(.decimalPad)
                .focused($focus, equals: field)
                .accessibilityLabel(Text(accessibilityLabel ?? label))
            if let unit { Text(unit).font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2).accessibilityHidden(true) }
        }
        .padding(.horizontal, 14)
        .frame(height: 52)
        .frame(maxWidth: .infinity)
        .background(NT.Colors.surface, in: RoundedRectangle(cornerRadius: NT.Radius.field, style: .continuous))
        .contentShape(Rectangle())
        .onTapGesture { focus = field }
    }

    private static let emptyPlaceholder = "–"

    private func save() {
        guard let values, !trimmedName.isEmpty else { return }
        do {
            let item = try ProductLabel.save(key: key, name: trimmedName, stub: stub, brand: readBrand, values: values,
                                             in: context)
            onSaved(item)
        } catch {
            context.rollback()
            failed = true
        }
    }
}

/// The figures typed from a pack's nutrition table, per 100 g, with the form's rules.
struct LabelValues: Equatable {
    var kcal: Double
    var protein: Double
    var carbs: Double
    var fat: Double
    var fiber: Double?
    var servingG: Double?

    /// The fields Save needs, in form order.
    enum Field: CaseIterable {
        case name, kcal, protein, carbs, fat

        /// How the "fill in" hint names the field.
        var title: String {
            switch self {
            case .name: String(localized: "fuel.foodName")
            case .kcal: String(localized: "unit.kcal")
            case .protein: String(localized: "macro.protein")
            case .carbs: String(localized: "macro.carbs")
            case .fat: String(localized: "macro.fat")
            }
        }
    }

    /// The required fields still empty, in form order: what the hint above a disabled Save names.
    static func missingRequired(name: String, kcal: String, protein: String, carbs: String, fat: String) -> [Field] {
        let texts: [(Field, String)] = [(.name, name), (.kcal, kcal), (.protein, protein), (.carbs, carbs), (.fat, fat)]
        return texts.filter { isBlank($0.1) }.map { $0.0 }
    }

    /// Protein + carbs + fat can pass 100 g only through label rounding or a per-100 ml table; the backend's
    /// `nutritionFromProduct` uses the same bound.
    static let maxMacroSum: Double = 105
    static let maxServingG: Double = 5000

    /// Nil when kcal or a macro is empty or not a number, kcal is over 950, the macros add up past `maxMacroSum`,
    /// fiber is over 100, or the serving is not a positive weight. Fiber and serving may stay empty.
    static func parse(kcal: String, protein: String, carbs: String, fat: String,
                      fiber: String, serving: String) -> LabelValues? {
        guard let k = NumberInput.nonNegative(kcal), let p = NumberInput.nonNegative(protein),
              let c = NumberInput.nonNegative(carbs), let f = NumberInput.nonNegative(fat),
              k <= 950, p + c + f <= maxMacroSum else { return nil }
        var values = LabelValues(kcal: k, protein: p, carbs: c, fat: f)
        if !isBlank(fiber) {
            guard let value = NumberInput.nonNegative(fiber), value <= 100 else { return nil }
            values.fiber = value
        }
        if !isBlank(serving) {
            guard let value = NumberInput.nonNegative(serving), value > 0, value <= maxServingG else { return nil }
            values.servingG = value
        }
        return values
    }

    private static func isBlank(_ text: String) -> Bool {
        text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}

enum ProductLabel {
    /// Writes the label as the custom food `label:<key>` filed under barcode `key`, updating it when the user saves
    /// the same code again (logged entries keep their own figures). Brand and photo come from the stub; `brand` (read
    /// from the label photo) fills in when the stub has none.
    @discardableResult
    static func save(key: String, name: String, stub: ProductStub?, brand: String? = nil, values: LabelValues,
                     in context: ModelContext) throws -> FoodItem {
        let id = "label:\(key)"
        let existing = try context.fetch(FetchDescriptor<FoodItem>(predicate: #Predicate { $0.id == id })).first
        let item = existing ?? FoodItem(id: id, name: name, source: .custom,
                                        kcalPer100: 0, proteinPer100: 0, carbsPer100: 0, fatPer100: 0)
        item.name = name
        item.brand = stub?.brand ?? brand ?? item.brand
        item.source = .custom
        item.barcode = key
        item.kcalPer100 = values.kcal
        item.proteinPer100 = values.protein
        item.carbsPer100 = values.carbs
        item.fatPer100 = values.fat
        item.fiberPer100 = values.fiber
        item.servingSizeG = values.servingG
        item.servingLabel = nil
        item.imageURL = stub?.imageURL ?? item.imageURL
        if existing == nil { context.insert(item) }
        try context.save()
        return item
    }
}
