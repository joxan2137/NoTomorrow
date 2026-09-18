import SwiftUI
import SwiftData

/// Fuel tab: day header, kcal ring + macros, four meal slots, and the add bar (AI photo / Barcode / Search).
struct FuelHomeView: View {
    @Environment(\.modelContext) private var modelContext
    @State private var model = FuelModel()
    @State private var sheet: FuelSheet?
    @State private var portionFood: PortionFood?
    @State private var isLookingUpBarcode = false
    @State private var missingBarcode = ""
    @State private var showsLabel = false
    @State private var lookupError = false
    @State private var barcodeNotFound = false
    @State private var lookupMeal: MealSlot = .suggested()

    enum FuelSheet: Identifiable {
        case search(MealSlot)
        case aiScan(MealSlot)
        case barcode(MealSlot)
        case quickAdd(MealSlot)
        case edit(MealEntry)

        var id: String {
            switch self {
            case .search(let m): "search-\(m.rawValue)"
            case .aiScan(let m): "ai-\(m.rawValue)"
            case .barcode(let m): "barcode-\(m.rawValue)"
            case .quickAdd(let m): "quick-\(m.rawValue)"
            case .edit(let entry): "edit-\(entry.id.uuidString)"
            }
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            header
                .padding(.horizontal, NT.Spacing.screenH)
                .padding(.top, 8)
                .contentShape(Rectangle())
                .gesture(dayNavigationSwipe)

            List {
                FuelHeroView(model: model)
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                    .listRowInsets(EdgeInsets(top: 20, leading: NT.Spacing.screenH, bottom: 4, trailing: NT.Spacing.screenH))
                    .contentShape(Rectangle())
                    .gesture(dayNavigationSwipe)

                ForEach(MealSlot.ordered, id: \.rawValue) { slot in
                    FuelMealRows(
                        slot: slot,
                        entries: model.entries(for: slot),
                        kcal: model.kcal(for: slot),
                        proteinRemaining: MealSlot.ordered.first { model.entries(for: $0).isEmpty } == slot ? model.proteinRemaining : 0,
                        isLast: slot == MealSlot.ordered.last,
                        onOpen: { sheet = .search(slot) },
                        onEdit: { sheet = .edit($0) },
                        onDelete: { model.delete($0, in: modelContext) }
                    )
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .scrollIndicators(.hidden)
        }
        .ntScreenBackground()
        .safeAreaInset(edge: .bottom, spacing: 0) {
            FuelAddBar(
                onAIPhoto: { sheet = .aiScan(.suggested()) },
                onBarcode: { sheet = .barcode(.suggested()) },
                onSearch: { sheet = .search(.suggested()) }
            )
        }
        .overlay(alignment: .bottom) { if isLookingUpBarcode { lookupPill } }
        .task { model.refresh(in: modelContext) }
        .onChange(of: model.day) { _, _ in model.refresh(in: modelContext) }
        .sheet(item: $sheet, onDismiss: { model.refresh(in: modelContext) }) { sheet in
            sheetContent(sheet)
        }
        .sheet(item: $portionFood, onDismiss: { model.refresh(in: modelContext) }) { food in
            PortionSheet(food: food, meal: lookupMeal, day: model.day) { portionFood = nil }
        }
        .sheet(isPresented: $showsLabel) {
            ProductLabelSheet(barcode: missingBarcode) { item in
                showsLabel = false
                portionFood = .item(item)
            }
        }
        .alert("fuel.search.error.network", isPresented: $lookupError) {
            Button("common.cancel", role: .cancel) {}
        }
        .alert("fuel.barcodeNotFound", isPresented: $barcodeNotFound) {
            Button("fuel.label.title") { showsLabel = true }
            Button("fuel.quickAdd") { sheet = .quickAdd(lookupMeal) }
            Button("common.cancel", role: .cancel) {}
        }
    }

    // MARK: - Header

    private var header: some View {
        HStack(alignment: .bottom, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 0) {
                    dayChevron("chevron.left", label: "fuel.previousDay", enabled: true) { model.goPreviousDay() }
                    Text(Fmt.shortDay(model.day)).eyebrow().lineLimit(1)
                    dayChevron("chevron.right", label: "fuel.nextDay", enabled: model.canGoForward) { model.goNextDay() }
                }
                .frame(height: NT.Size.control)
                Text("fuel.title").font(NT.Fonts.largeTitle).foregroundStyle(NT.Colors.ink)
            }
            Spacer(minLength: 0)
            streakChip.padding(.bottom, 6)
        }
    }

    private func dayChevron(_ symbol: String, label: LocalizedStringKey, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(enabled ? NT.Colors.ink2 : NT.Colors.ink3.opacity(0.4))
                .frame(width: 28, height: NT.Size.control)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityLabel(Text(label))
    }

    private var streakChip: some View {
        HStack(spacing: 6) {
            Image(systemName: "target")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(NT.Colors.ember)
            Text(streakText).font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink).tabular().lineLimit(1)
        }
        .padding(.horizontal, 12)
        .frame(height: 32)
        .background(NT.Colors.surface, in: Capsule())
    }

    private var streakText: String {
        switch model.proteinStreak {
        case 0: String(localized: "fuel.streak.zero")
        case 1: String(localized: "fuel.streak.one")
        default: FuelText.format("fuel.streak.other", model.proteinStreak)
        }
    }

    private var dayNavigationSwipe: some Gesture {
        DragGesture(minimumDistance: 40)
            .onEnded { value in
                guard abs(value.translation.width) > abs(value.translation.height) * 1.5 else { return }
                withAnimation(.easeOut(duration: 0.2)) {
                    if value.translation.width > 0 { model.goPreviousDay() } else { model.goNextDay() }
                }
            }
    }

    // MARK: - Sheets

    @ViewBuilder
    private func sheetContent(_ sheet: FuelSheet) -> some View {
        switch sheet {
        case .search(let meal):
            FoodSearchView(meal: meal, day: model.day)
        case .aiScan(let meal):
            AIScanView(meal: meal, day: model.day)
        case .barcode(let meal):
            BarcodeScannerView { code in
                self.sheet = nil
                lookupMeal = meal
                Task { await lookup(barcode: code) }
            }
        case .quickAdd(let meal):
            QuickAddSheet(meal: meal, day: model.day)
        case .edit(let entry):
            // Food-backed rows re-size through the portion sheet; quick-add and AI rows edit their figures directly.
            if let food = entry.food {
                PortionSheet(editing: entry, food: food) { self.sheet = nil }
            } else {
                QuickAddSheet(editing: entry) { self.sheet = nil }
            }
        }
    }

    private var lookupPill: some View {
        HStack(spacing: 10) {
            ProgressView().tint(NT.Colors.ink)
            Text("fuel.lookingUp").font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink)
        }
        .padding(.horizontal, 16)
        .frame(height: 40)
        .background(NT.Colors.surface2, in: Capsule())
        .padding(.bottom, 80)
        .transition(.opacity)
    }

    @MainActor
    private func lookup(barcode: String) async {
        isLookingUpBarcode = true
        defer { isLookingUpBarcode = false }
        let forms = FoodSearchService.barcodeForms(barcode)
        for code in forms {
            let request = FetchDescriptor<FoodItem>(predicate: #Predicate { $0.barcode == code })
            if let saved = try? modelContext.fetch(request).first { portionFood = .item(saved); return }
        }
        do {
            if let found = try await FoodSearchService.shared.lookup(barcode: barcode) { portionFood = .candidate(found) }
            else { missingBarcode = forms.first ?? barcode; barcodeNotFound = true }
        } catch is CancellationError { }
        catch { lookupError = true }
    }
}
