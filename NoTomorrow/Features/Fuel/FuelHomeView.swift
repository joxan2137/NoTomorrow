import SwiftUI
import SwiftData

/// Fuel tab: day header, kcal ring + macros, four meal slots, and the add bar (AI photo / Barcode / Search).
/// The header's date button opens the History sheet (`FuelCalendarSheet`). Deletes and copies to today show an
/// undo toast on top of the add bar.
struct FuelHomeView: View {
    @Environment(\.modelContext) private var modelContext
    @Environment(AppState.self) private var appState
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOverEnabled
    @State private var model = FuelModel()
    @State private var sheet: FuelSheet?
    /// Bumped on every day change the user makes; drives the selection haptic (rollovers stay silent).
    @State private var dayChanges = 0
    @State private var portionFood: PortionFood?
    @State private var barcodeFlow = BarcodeLookupFlow()
    @State private var lookupMeal: MealSlot = .suggested()
    /// The day the barcode sheet was opened on; its portion or quick-add sheet logs there.
    @State private var lookupDay: Date = Calendar.current.startOfDay(for: .now)
    /// Delete tapped in an edit sheet: the entry goes once the sheet has closed, so it never renders a deleted row.
    @State private var pendingDelete: MealEntry?

    /// Logging sheets carry the day they were opened on: the 30-minute snap-back and the midnight rollover move
    /// `model.day` while a sheet can still be up, and the food must land on the day the user picked it for.
    enum FuelSheet: Identifiable {
        case search(MealSlot, day: Date)
        case aiScan(MealSlot, day: Date)
        case barcode(MealSlot, day: Date)
        case quickAdd(MealSlot, name: String, day: Date)
        case edit(MealEntry)
        case calendar

        var id: String {
            switch self {
            case .search(let m, _): "search-\(m.rawValue)"
            case .aiScan(let m, _): "ai-\(m.rawValue)"
            case .barcode(let m, _): "barcode-\(m.rawValue)"
            case .quickAdd(let m, _, _): "quick-\(m.rawValue)"
            case .edit(let entry): "edit-\(entry.id.uuidString)"
            case .calendar: "calendar"
            }
        }

        /// The day a new entry from this sheet is written to, fixed when the sheet opened (nil: not a logging sheet).
        var logDay: Date? {
            switch self {
            case .search(_, let day), .aiScan(_, let day), .barcode(_, let day), .quickAdd(_, _, let day): day
            case .edit, .calendar: nil
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
                        onOpen: { sheet = .search(slot, day: model.day) },
                        onEdit: { sheet = .edit($0) },
                        onDelete: { entry in withAnimation { model.delete(entry, in: modelContext) } },
                        onLogAgain: model.isToday ? nil : { entry in
                            withAnimation { model.logAgainToday(entry, in: modelContext) }
                        },
                        onCopyToToday: model.isToday ? nil : {
                            withAnimation { model.copyToToday(slot, in: modelContext) }
                        }
                    )
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .scrollIndicators(.hidden)
        }
        .ntScreenBackground()
        // The undo toast and the lookup pill ride on the add bar, in the same bottom inset. Not an overlay on the
        // list: its collection view took the taps meant for Undo. Not a sibling below the list either: the list's
        // frame then changed with the toast, and when the toast went the rows stayed ~26 pt too low until a
        // scroll. In the inset only the list's bottom content inset changes, and its frame stays put.
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: 0) {
                if let undo = model.pendingUndo {
                    undoToast(undo)
                        .padding(.top, 8)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
                if barcodeFlow.isLookingUp {
                    lookupPill
                        .padding(.top, 8)
                        .transition(.opacity)
                }
                FuelAddBar(
                    onAIPhoto: { sheet = .aiScan(.suggested(), day: model.day) },
                    onBarcode: { sheet = .barcode(.suggested(), day: model.day) },
                    onSearch: { sheet = .search(.suggested(), day: model.day) }
                )
            }
        }
        // One timer per undo: a newer delete or copy restarts it, an undo or expiry ends it. It sleeps until the undo's
        // deadline, so when a tab switch cancelled it, coming back sleeps only for what is left (or ends it at once).
        .task(id: model.pendingUndo?.id) {
            guard let undo = model.pendingUndo else { return }
            if model.shouldAnnounce(undo) {
                AccessibilityNotification.Announcement(Fmt.localized(undo.messageKey)).post()
            }
            let left = undo.remaining()
            if left > 0 {
                try? await Task.sleep(for: .seconds(left))
                guard !Task.isCancelled else { return }
            }
            withAnimation(.easeOut(duration: 0.2)) { model.expireUndo(undo.id) }
        }
        // Back on the tab after the deadline: no toast at all, not a flash of one.
        .onAppear { model.expireUndoIfDue() }
        .onChange(of: voiceOverEnabled, initial: true) { _, on in
            model.undoLifetime = on ? FuelModel.undoDurationVoiceOver : FuelModel.undoDuration
        }
        .task {
            model.syncToday()
            model.refresh(in: modelContext)
        }
        .onChange(of: model.day) { _, _ in model.refresh(in: modelContext) }
        // Midnight moves the protein-streak window even when a past day stays on screen.
        .onChange(of: model.today) { _, _ in model.refresh(in: modelContext) }
        .onChange(of: appState.fuelTodayRequests) { _, _ in model.goToday() }
        .onReceive(NotificationCenter.default.publisher(for: .NSCalendarDayChanged).receive(on: RunLoop.main)) { _ in
            model.syncToday()
        }
        // The model's calendar follows the new zone; this moves `day` and `today` onto it right away.
        .onReceive(NotificationCenter.default.publisher(for: .NSSystemTimeZoneDidChange).receive(on: RunLoop.main)) { _ in
            model.syncToday()
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didEnterBackgroundNotification)) { _ in
            model.appDidEnterBackground()
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.willEnterForegroundNotification)) { _ in
            model.appWillEnterForeground()
        }
        .sensoryFeedback(.selection, trigger: dayChanges)
        .sheet(item: $sheet, onDismiss: {
            deletePending()
            model.refresh(in: modelContext)
            barcodeFlow.startPending(in: modelContext) { portionFood = $0 }
        }) { sheet in
            sheetContent(sheet)
        }
        .sheet(item: $portionFood, onDismiss: { model.refresh(in: modelContext) }) { food in
            PortionSheet(food: food, meal: lookupMeal, day: lookupDay) { portionFood = nil }
        }
        .barcodeLookupFlow(barcodeFlow, onFood: { portionFood = $0 },
                           onQuickAdd: { name in sheet = .quickAdd(lookupMeal, name: name, day: lookupDay) })
    }

    // MARK: - Header

    /// Row 1: ‹ [▦ Today ▾] ›  …  (Today) — the pill only on past days. Row 2: the title and the streak chip.
    private var header: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 8) {
                HStack(spacing: 0) {
                    dayChevron("chevron.left", label: "fuel.previousDay", enabled: true) {
                        changeDay { model.goPreviousDay() }
                    }
                    dateButton
                    dayChevron("chevron.right", label: "fuel.nextDay", enabled: model.canGoForward) {
                        changeDay { model.goNextDay() }
                    }
                }
                // Pulls the first chevron's glyph back towards the title's left edge; the 44 pt target stays whole.
                .padding(.leading, -12)
                Spacer(minLength: 0)
                if !model.isToday {
                    todayPill.transition(.opacity)
                }
            }
            .frame(height: NT.Size.control)
            .animation(.easeOut(duration: 0.2), value: model.isToday)

            HStack(alignment: .bottom, spacing: 12) {
                Text("fuel.title").font(NT.Fonts.largeTitle).foregroundStyle(NT.Colors.ink)
                Spacer(minLength: 0)
                streakChip.padding(.bottom, 6)
            }
        }
    }

    private func dayChevron(_ symbol: String, label: LocalizedStringKey, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(enabled ? NT.Colors.ink : NT.Colors.ink3.opacity(0.4))
                .frame(width: NT.Size.control, height: NT.Size.control)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityLabel(Text(label))
    }

    /// "▦ Today ▾" capsule: opens the History sheet.
    private var dateButton: some View {
        Button { sheet = .calendar } label: {
            HStack(spacing: 6) {
                Image(systemName: "calendar")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(NT.Colors.ink2)
                Text(Fmt.dayTitle(model.day))
                    .font(NT.Fonts.footnoteBold).foregroundStyle(NT.Colors.ink)
                    .lineLimit(1).minimumScaleFactor(0.8)
                Image(systemName: "chevron.down")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundStyle(NT.Colors.ink3)
            }
            .padding(.horizontal, 12)
            .frame(height: 32)
            .background(NT.Colors.surface, in: Capsule())
            .frame(height: NT.Size.control)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressScale())
        .accessibilityLabel(Text("fuel.chooseDay"))
        .accessibilityValue(Text(Fmt.longDay(model.day)))
    }

    private var todayPill: some View {
        Button { changeDay { model.goToday() } } label: {
            Text("day.today")
                .font(NT.Fonts.footnoteBold).foregroundStyle(NT.Colors.ink)
                .lineLimit(1)
                .padding(.horizontal, 14)
                .frame(height: 32)
                .background(NT.Colors.surface2, in: Capsule())
                .frame(height: NT.Size.control)
                .contentShape(Rectangle())
        }
        .buttonStyle(PressScale())
        .fixedSize()
        .accessibilityLabel(Text("fuel.goToToday"))
    }

    /// A day change the user asked for: animated, with a selection tick when the day actually moved.
    private func changeDay(_ change: () -> Void) {
        let before = model.day
        withAnimation(.easeOut(duration: 0.2)) { change() }
        if model.day != before { dayChanges += 1 }
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
                changeDay {
                    if value.translation.width > 0 { model.goPreviousDay() } else { model.goNextDay() }
                }
            }
    }

    // MARK: - Sheets

    @ViewBuilder
    private func sheetContent(_ sheet: FuelSheet) -> some View {
        switch sheet {
        case .search(let meal, let day):
            FoodSearchView(meal: meal, day: day)
        case .aiScan(let meal, let day):
            AIScanView(meal: meal, day: day)
        case .barcode(let meal, let day):
            // The lookup starts once this sheet has gone (see the sheet's onDismiss), so its result can present.
            BarcodeScannerView { code in
                lookupMeal = meal
                lookupDay = day
                barcodeFlow.pendingCode = code
                self.sheet = nil
            }
        case .quickAdd(let meal, let name, let day):
            QuickAddSheet(meal: meal, day: day, initialName: name)
        case .edit(let entry):
            // Food-backed rows re-size through the portion sheet; quick-add and AI rows edit their figures directly.
            if let food = entry.food {
                PortionSheet(editing: entry, food: food, onSaved: { self.sheet = nil },
                             onDelete: { requestDelete(entry) })
            } else {
                QuickAddSheet(editing: entry, onSaved: { self.sheet = nil }, onDelete: { requestDelete(entry) })
            }
        case .calendar:
            FuelCalendarSheet(selectedDay: model.day, kcalGoal: model.goals.kcal, goal: model.trainingGoal,
                              calendar: model.calendar) { date in
                self.sheet = nil
                changeDay { model.go(to: date) }
            }
        }
    }

    /// Delete from an edit sheet: close it, then delete from `onDismiss` through the model, so the undo toast shows.
    private func requestDelete(_ entry: MealEntry) {
        pendingDelete = entry
        sheet = nil
    }

    private func deletePending() {
        guard let entry = pendingDelete else { return }
        pendingDelete = nil
        guard entry.modelContext != nil, !entry.isDeleted else { return }
        withAnimation { model.delete(entry, in: modelContext) }
    }

    private var lookupPill: some View {
        HStack(spacing: 10) {
            ProgressView().tint(NT.Colors.ink)
            Text("fuel.lookingUp").font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink)
        }
        .padding(.horizontal, 16)
        .frame(height: 40)
        .background(NT.Colors.surface2, in: Capsule())
    }

    /// "Entry deleted   Undo" / "Added to today   Undo", above the add bar. The whole capsule takes the tap, so a
    /// near miss on Undo does nothing rather than reaching the list.
    private func undoToast(_ undo: FuelModel.Undo) -> some View {
        HStack(spacing: 4) {
            Text(Fmt.localized(undo.messageKey))
                .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2)
                .lineLimit(1)
            Button {
                withAnimation(.easeOut(duration: 0.2)) { model.undo(in: modelContext) }
            } label: {
                Text("common.undo")
                    .font(NT.Fonts.footnoteBold).foregroundStyle(NT.Colors.ink)
                    .padding(.horizontal, 12)
                    .frame(height: NT.Size.control)
                    .contentShape(Rectangle())
            }
            .buttonStyle(PressScale())
        }
        .padding(.leading, 18)
        .padding(.trailing, 4)
        .frame(height: NT.Size.control)
        .background(NT.Colors.surface2, in: Capsule())
        .contentShape(Capsule())
    }
}
