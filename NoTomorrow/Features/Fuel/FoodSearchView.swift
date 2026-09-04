import SwiftUI
import SwiftData

/// "Add to <meal>" sheet: search field + barcode, Recent foods, Open Food Facts results, portion sheet on tap.
struct FoodSearchView: View {
    let meal: MealSlot
    var day: Date = .now

    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var modelContext
    @State private var model = FoodSearchModel()
    @State private var portionFood: PortionFood?
    @State private var showsScanner = false
    @State private var showsQuickAdd = false
    @State private var barcodeNotFound = false
    @FocusState private var searchFocused: Bool

    @Query(filter: #Predicate<FoodItem> { $0.lastUsedAt != nil }, sort: \FoodItem.lastUsedAt, order: .reverse)
    private var recentItems: [FoodItem]

    private var recent: [FoodItem] {
        let q = model.trimmedQuery.lowercased()
        let filtered = q.isEmpty ? recentItems : recentItems.filter { $0.name.lowercased().contains(q) }
        return Array(filtered.prefix(10))
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            searchRow.padding(.top, 12)
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    content
                }
                .padding(.horizontal, NT.Spacing.screenH)
                .padding(.top, 16)
                .padding(.bottom, 40)
            }
            .scrollDismissesKeyboard(.immediately)
        }
        .ntScreenBackground()
        .onAppear { searchFocused = true }
        .onChange(of: model.query) { _, _ in model.queryChanged() }
        .sheet(item: $portionFood) { food in
            PortionSheet(food: food, meal: meal, day: day) {
                portionFood = nil
                dismiss()
            }
        }
        .sheet(isPresented: $showsScanner) {
            BarcodeScannerView { code in
                showsScanner = false
                Task { await lookup(barcode: code) }
            }
        }
        .sheet(isPresented: $showsQuickAdd) {
            QuickAddSheet(meal: meal, day: day, initialName: model.trimmedQuery) {
                showsQuickAdd = false
                dismiss()
            }
        }
        .alert("fuel.barcodeNotFound", isPresented: $barcodeNotFound) {
            Button("fuel.quickAdd") { showsQuickAdd = true }
            Button("common.cancel", role: .cancel) {}
        }
        .overlay(alignment: .bottom) {
            if model.isLookingUpBarcode {
                HStack(spacing: 10) {
                    ProgressView().tint(NT.Colors.ink)
                    Text("fuel.lookingUp").font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink)
                }
                .padding(.horizontal, 16)
                .frame(height: 40)
                .background(NT.Colors.surface2, in: Capsule())
                .padding(.bottom, 24)
            }
        }
    }

    // MARK: - Header + search

    private var header: some View {
        HStack {
            Text(FuelText.addTo(meal)).font(NT.Fonts.title2).foregroundStyle(NT.Colors.ink).lineLimit(1)
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

    private var searchRow: some View {
        HStack(spacing: 10) {
            HStack(spacing: 10) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(NT.Colors.ink2)
                TextField("fuel.searchProduct", text: $model.query)
                    .font(NT.Fonts.body)
                    .foregroundStyle(NT.Colors.ink)
                    .tint(NT.Colors.ink)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.search)
                    .focused($searchFocused)
                    .onSubmit { model.retry() }
                if !model.query.isEmpty {
                    Button { model.query = "" } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 9, weight: .heavy))
                            .foregroundStyle(NT.Colors.ground)
                            .frame(width: 20, height: 20)
                            .background(NT.Colors.ink3, in: Circle())
                            .frame(width: 32, height: NT.Size.control)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 14)
            .frame(height: NT.Size.control)
            .background(NT.Colors.surface, in: RoundedRectangle(cornerRadius: NT.Radius.field, style: .continuous))

            Button { showsScanner = true } label: {
                Image(systemName: "barcode.viewfinder")
                    .font(.system(size: 20, weight: .regular))
                    .foregroundStyle(NT.Colors.ink)
                    .frame(width: NT.Size.control, height: NT.Size.control)
                    .background(NT.Colors.surface, in: RoundedRectangle(cornerRadius: NT.Radius.field, style: .continuous))
            }
            .buttonStyle(PressScale())
            .accessibilityLabel(Text("fuel.barcode"))
        }
        .padding(.horizontal, NT.Spacing.screenH)
    }

    // MARK: - Content

    @ViewBuilder
    private var content: some View {
        if !recent.isEmpty {
            FoodSectionLabel(text: String(localized: "fuel.recent"))
            ForEach(recent, id: \.id) { item in
                FoodRecentRow(item: item) { portionFood = .item(item) }
            }
        }

        if model.canSearch {
            switch model.phase {
            case .idle, .loading:
                FoodSectionLabel(text: String(localized: "fuel.products")).padding(.top, recent.isEmpty ? 0 : 16)
                FoodStateRow(kind: .loading)
            case .results(let hits):
                FoodSectionLabel(text: "\(String(localized: "fuel.products")) · \(FuelText.format("fuel.found", hits.count))")
                    .padding(.top, recent.isEmpty ? 0 : 16)
                ForEach(hits) { hit in
                    FoodResultRow(candidate: hit) { portionFood = .candidate(hit) }
                }
            case .empty:
                FoodStateRow(kind: .notFound, onQuickAdd: { showsQuickAdd = true })
                    .padding(.top, recent.isEmpty ? 0 : 16)
            case .error(let message):
                FoodStateRow(kind: .error(message), onRetry: { model.retry() }, onQuickAdd: { showsQuickAdd = true })
                    .padding(.top, recent.isEmpty ? 0 : 16)
            }
        } else if recent.isEmpty {
            FoodStateRow(kind: .hint, onQuickAdd: { showsQuickAdd = true })
        }
    }

    private func lookup(barcode: String) async {
        if let found = await model.lookup(barcode: barcode) {
            await MainActor.run { portionFood = .candidate(found) }
        } else {
            await MainActor.run { barcodeNotFound = true }
        }
    }
}
