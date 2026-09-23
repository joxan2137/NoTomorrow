import SwiftUI
import SwiftData

/// "History": half a year of days as ember cells (brighter = closer to the kcal goal, judged for the current training
/// goal), a legend and three stat tiles. Tapping a day opens it in Fuel; the owner closes the sheet.
/// Loads its own data when opened (`FuelCalendar.kcalByDay`); nothing runs while it is closed.
struct FuelCalendarSheet: View {
    let selectedDay: Date
    let kcalGoal: Double
    let goal: TrainingGoal
    var onSelect: (Date) -> Void

    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss
    @State private var kcalByDay: [Date: Double] = [:]

    private let today: Date
    private let layout: FuelCalendar.Layout
    /// The Fuel model's calendar: the day a cell stands for is the day `FuelModel.go(to:)` opens.
    private let calendar: Calendar

    static let height: CGFloat = 450
    private static let cell: CGFloat = 24
    /// Cell pitch: every tap target is the full 28 × 28 square, the 24 pt fill centred in it.
    private static let pitch: CGFloat = 28
    private static let monthRow: CGFloat = 14
    private static let monthGap: CGFloat = 6
    private static let weekdayColumn: CGFloat = 22

    init(selectedDay: Date, kcalGoal: Double, goal: TrainingGoal, calendar: Calendar = .autoupdatingCurrent,
         onSelect: @escaping (Date) -> Void) {
        let today = calendar.startOfDay(for: .now)
        self.calendar = calendar
        self.selectedDay = FuelCalendar.dayKey(selectedDay, calendar: calendar)
        self.kcalGoal = kcalGoal
        self.goal = goal
        self.onSelect = onSelect
        self.today = today
        self.layout = FuelCalendar.layout(today: today, calendar: calendar)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Grabber().frame(maxWidth: .infinity)
            HStack {
                Text("fuel.calendar.title").font(NT.Fonts.title2).foregroundStyle(NT.Colors.ink)
                Spacer()
                Button { dismiss() } label: {
                    Text("common.done").font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2)
                        .frame(height: NT.Size.control)
                }
                .buttonStyle(.plain)
            }
            .padding(.top, 10)

            // History is scored against today's goal (no goal history is kept), so say which one.
            Text(goalNote)
                .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2)
                .lineLimit(1).minimumScaleFactor(0.8)

            grid.padding(.top, 18)
            legend.padding(.top, 10)
            statTiles.padding(.top, 18)
            Spacer(minLength: 8)
        }
        .padding(.horizontal, NT.Spacing.screenH)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .ntScreenBackground()
        .presentationDetents([.height(Self.height)])
        .presentationDragIndicator(.hidden)
        .presentationBackground(NT.Colors.ground)
        .task { kcalByDay = FuelCalendar.kcalByDay(from: layout.start, in: modelContext, calendar: calendar) }
    }

    private var goalNote: String {
        FuelText.format("fuel.calendar.goalNote",
                        goal.localizedName,
                        Fmt.kcal(kcalGoal))
    }

    // MARK: - Grid

    private var grid: some View {
        HStack(alignment: .top, spacing: 6) {
            // Fixed width: a flexible spacer here would let the HStack give this column half the row.
            VStack(alignment: .leading, spacing: 0) {
                Color.clear.frame(width: Self.weekdayColumn, height: Self.monthRow + Self.monthGap)
                ForEach(1...7, id: \.self) { isoWeekday in
                    Text(LocalizedStringKey(AttendanceService.labelKey(isoWeekday: isoWeekday)))
                        .font(NT.Fonts.caption).foregroundStyle(NT.Colors.ink3)
                        .frame(width: Self.weekdayColumn, height: Self.pitch, alignment: .leading)
                }
            }
            .fixedSize(horizontal: true, vertical: false)
            .accessibilityHidden(true)

            GeometryReader { geo in
                ScrollViewReader { proxy in
                    ScrollView(.horizontal) {
                        HStack(spacing: 0) {
                            ForEach(layout.columns.indices, id: \.self) { c in
                                column(c).id(c)
                            }
                        }
                    }
                    .scrollIndicators(.hidden)
                    .defaultScrollAnchor(.trailing)
                    .onAppear { scrollToSelection(proxy, width: geo.size.width) }
                }
            }
            .frame(maxWidth: .infinity)
            .frame(height: Self.monthRow + Self.monthGap + 7 * Self.pitch)
        }
        .accessibilityElement(children: .contain)
    }

    /// Opens on the current week; a selected day that would be off screen there is centred instead.
    private func scrollToSelection(_ proxy: ScrollViewProxy, width: CGFloat) {
        let count = layout.columns.count
        let visible = max(1, Int(width / Self.pitch))
        let first = FuelCalendar.firstVisibleColumn(selected: layout.column(of: selectedDay, calendar: calendar),
                                                    visible: visible, count: count)
        guard first < max(0, count - visible) else { return }   // the trailing anchor already shows it
        proxy.scrollTo(first, anchor: .leading)
    }

    private func column(_ c: Int) -> some View {
        VStack(spacing: 0) {
            monthLabel(c).frame(width: Self.pitch, height: Self.monthRow, alignment: .leading)
            Color.clear.frame(width: Self.pitch, height: Self.monthGap)
            ForEach(0..<7, id: \.self) { r in
                cell(layout.columns[c][r])
            }
        }
    }

    /// Labels are at least two columns apart, so a name wider than one column may run over its neighbour's empty slot.
    @ViewBuilder
    private func monthLabel(_ c: Int) -> some View {
        if let label = layout.monthLabels.first(where: { $0.column == c }) {
            Text(Fmt.monthShort(label.month))
                .font(NT.Fonts.caption).foregroundStyle(NT.Colors.ink2)
                .fixedSize()
                .accessibilityHidden(true)
        } else {
            Color.clear
        }
    }

    @ViewBuilder
    private func cell(_ day: Date?) -> some View {
        if let day {
            let kcal = kcalByDay[day]
            let isToday = day == today
            let isSelected = day == selectedDay
            let level = FuelCalendar.level(kcalEaten: kcal ?? 0, kcalGoal: kcalGoal, goal: goal,
                                           hasEntries: kcal != nil, isToday: isToday)
            Button { onSelect(day) } label: {
                ZStack {
                    RoundedRectangle(cornerRadius: 6, style: .continuous)
                        .fill(NT.Colors.heat[level])
                        .frame(width: Self.cell, height: Self.cell)
                    if isToday {
                        Circle().fill(level >= 3 ? NT.Colors.ground : NT.Colors.ink).frame(width: 5, height: 5)
                    }
                    if isSelected {
                        RoundedRectangle(cornerRadius: 9, style: .continuous)
                            .strokeBorder(NT.Colors.ink, lineWidth: 2)
                            .frame(width: Self.cell + 6, height: Self.cell + 6)
                    }
                }
                .frame(width: Self.pitch, height: Self.pitch)
                .contentShape(Rectangle())
            }
            .buttonStyle(PressScale())
            .accessibilityLabel(Text(cellLabel(day, kcal: kcal, isToday: isToday)))
            .accessibilityHint(Text("fuel.calendar.cell.hint"))
            .accessibilityAddTraits(isSelected ? .isSelected : [])
        } else {
            Color.clear.frame(width: Self.pitch, height: Self.pitch).accessibilityHidden(true)
        }
    }

    /// "Monday, 21 September: 2 140 kcal, 93% of goal" ("… so far …" for today, "… nothing logged" when empty).
    private func cellLabel(_ day: Date, kcal: Double?, isToday: Bool) -> String {
        let date = Fmt.longDay(day)
        guard let kcal else { return FuelText.format("fuel.calendar.cell.empty", date) }
        let percent = kcalGoal > 0 ? Fmt.percent(kcal / kcalGoal) : "–"
        return FuelText.format(isToday ? "fuel.calendar.cell.today" : "fuel.calendar.cell", date, Fmt.kcal(kcal), percent)
    }

    // MARK: - Legend & stats

    /// Swatch 0 means "nothing logged", not "far off", so it sits apart from the off → on target scale.
    private var legend: some View {
        HStack(spacing: 6) {
            swatch(0)
            Text("fuel.calendar.legend.none")
            Spacer(minLength: 12)
            Text("fuel.calendar.legend.off")
            HStack(spacing: 3) {
                ForEach(1...4, id: \.self) { swatch($0) }
            }
            Text("fuel.calendar.onTarget")
        }
        .font(NT.Fonts.caption)
        .foregroundStyle(NT.Colors.ink2)
        .lineLimit(1)
        .minimumScaleFactor(0.8)
        .accessibilityElement(children: .combine)
    }

    private func swatch(_ level: Int) -> some View {
        RoundedRectangle(cornerRadius: 3, style: .continuous)
            .fill(NT.Colors.heat[level])
            .frame(width: 12, height: 12)
    }

    private var statTiles: some View {
        let stats = FuelCalendar.stats(kcalByDay: kcalByDay, today: today, kcalGoal: kcalGoal, goal: goal,
                                       calendar: calendar)
        return HStack(alignment: .top, spacing: 10) {
            statTile("fuel.calendar.avg7") { kcalValue(stats.avg7) }
            statTile("fuel.calendar.avg30") { kcalValue(stats.avg30) }
            statTile("fuel.calendar.onTarget") {
                Text(FuelText.format("fuel.calendar.ofDays", stats.onTarget30, FuelCalendar.statsWindow))
                    .font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink).tabular().lineLimit(1)
            }
        }
        .fixedSize(horizontal: false, vertical: true)
    }

    @ViewBuilder
    private func kcalValue(_ kcal: Double?) -> some View {
        if let kcal {
            KcalLabel(kcal: kcal, numberFont: NT.Fonts.headline)
        } else {
            Text(verbatim: "–").font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink3)
        }
    }

    /// `StatTile` chrome with any value view. Labels may wrap to two lines ("ZGODNIE Z CELEM"); every tile then takes
    /// the tallest height and the values stay on one baseline at the bottom.
    private func statTile<Value: View>(_ label: LocalizedStringKey, @ViewBuilder value: () -> Value) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).eyebrow().lineLimit(2).fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
            value()
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .background(NT.Colors.surface, in: RoundedRectangle(cornerRadius: NT.Radius.tile, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}
