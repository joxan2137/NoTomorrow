import SwiftUI
import WidgetKit

/// Fuel calendar: the Fuel history heat grid, with a dot on every day you trained. `docs/widgets.md`, "Fuel calendar".
struct FuelCalendarWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: WidgetKind.history, provider: SnapshotProvider()) { entry in
            FuelCalendarView(entry: entry)
                .widgetGround()
                .widgetURL(URL(string: "notomorrow://fuel"))
        }
        .configurationDisplayName("widget.history.name")
        .description("widget.history.description")
        .supportedFamilies([.systemMedium, .systemLarge])
    }
}

/// The grid's data for one render: levels and trained flags by day, and the stats under it.
struct FuelCalendarModel {
    let today: Date
    let byDay: [Date: WidgetSnapshot.CalendarGrid.Day]
    let grid: WidgetSnapshot.CalendarGrid

    init(snapshot: WidgetSnapshot, now: Date) {
        let cal = Calendar.current
        today = cal.startOfDay(for: now)
        grid = snapshot.calendar
        byDay = Dictionary(snapshot.calendar.days.map { (cal.startOfDay(for: $0.date), $0) }, uniquingKeysWith: { _, last in last })
    }

    func level(_ day: Date) -> Int {
        let kcal = byDay[day]?.kcal
        return FuelHeat.level(kcalEaten: kcal ?? 0, kcalGoal: grid.kcalGoal, under: grid.under, over: grid.over,
                              hasEntries: kcal != nil, isToday: day == today)
    }

    func trained(_ day: Date) -> Bool { byDay[day]?.trained ?? false }

    /// Monday of the column `index` of `count`, the last one holding today.
    func monday(column index: Int, of count: Int) -> Date {
        let cal = Calendar.current
        let thisMonday = cal.date(byAdding: .day, value: -(cal.widgetISOWeekday(for: today) - 1), to: today) ?? today
        return cal.date(byAdding: .day, value: -7 * (count - 1 - index), to: thisMonday) ?? thisMonday
    }

    /// The logged days among the `n` before today (today is still in progress).
    private func loggedBeforeToday(_ n: Int) -> [Double] {
        let cal = Calendar.current
        return (1...n).compactMap { offset in
            cal.date(byAdding: .day, value: -offset, to: today).flatMap { byDay[cal.startOfDay(for: $0)]?.kcal }
        }
    }

    func average(_ n: Int) -> Double? {
        let values = loggedBeforeToday(n)
        return values.isEmpty ? nil : values.reduce(0, +) / Double(values.count)
    }

    /// Days at level 4 among the 30 before today (`FuelCalendar.stats`).
    var onTarget30: Int {
        let cal = Calendar.current
        return (1...30).filter { offset in
            guard let day = cal.date(byAdding: .day, value: -offset, to: today).map({ cal.startOfDay(for: $0) }),
                  let kcal = byDay[day]?.kcal else { return false }
            return FuelHeat.level(kcalEaten: kcal, kcalGoal: grid.kcalGoal, under: grid.under, over: grid.over,
                                  hasEntries: true, isToday: false) == 4
        }.count
    }
}

struct FuelCalendarView: View {
    let entry: SnapshotEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        if let snapshot = entry.snapshot, entry.isReady {
            let model = FuelCalendarModel(snapshot: snapshot, now: entry.date)
            VStack(alignment: .leading, spacing: 8) {
                header(model)
                if family == .systemLarge {
                    HeatGrid(model: model, minColumns: 16, showsMonths: true)
                    legend
                    stats(model)
                } else {
                    HeatGrid(model: model, minColumns: 0, showsMonths: false)
                }
            }
        } else {
            WidgetSetupView()
        }
    }

    private func header(_ model: FuelCalendarModel) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(verbatim: WidgetText.string("widget.history.name")).eyebrowStyle()
            Spacer(minLength: 8)
            Text(verbatim: WidgetText.format("widget.history.onTarget %lld", model.onTarget30))
                .font(W.caption).monospacedDigit().foregroundStyle(W.ink2).lineLimit(1)
        }
    }

    private var legend: some View {
        HStack(spacing: 5) {
            ForEach(0..<5, id: \.self) { level in
                RoundedRectangle(cornerRadius: 2.5).fill(W.heat[level]).frame(width: 10, height: 10)
            }
            Text(verbatim: WidgetText.string("fuel.calendar.onTarget")).font(W.caption).foregroundStyle(W.ink2)
                .padding(.leading, 2)
            Spacer(minLength: 8)
            Circle().fill(W.ink).frame(width: 5, height: 5)
            Text(verbatim: WidgetText.string("widget.history.trained")).font(W.caption).foregroundStyle(W.ink2)
        }
        .lineLimit(1)
    }

    private func stats(_ model: FuelCalendarModel) -> some View {
        HStack(alignment: .top, spacing: 12) {
            stat(model.average(7).map(WidgetText.kcal) ?? "–", "fuel.calendar.avg7")
            stat(model.average(30).map(WidgetText.kcal) ?? "–", "fuel.calendar.avg30")
            stat("\(model.grid.sessions30)", "widget.history.sessions30")
        }
    }

    private func stat(_ value: String, _ labelKey: String) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(verbatim: value).font(W.display(28)).monospacedDigit().foregroundStyle(W.ink).lineLimit(1)
                .minimumScaleFactor(0.6)
            Text(verbatim: WidgetText.string(labelKey)).font(W.caption).foregroundStyle(W.ink2).lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Monday-first week columns, oldest left, as many whole weeks as fit (at most 26). Cells are square with a gap of a
/// fifth of their side; `minColumns` trades height for bigger cells on the large size.
struct HeatGrid: View {
    let model: FuelCalendarModel
    let minColumns: Int
    let showsMonths: Bool

    var body: some View {
        GeometryReader { geo in
            let labelHeight: CGFloat = showsMonths ? 14 : 0
            let height = geo.size.height - labelHeight
            let byHeight = height / (7 * 1.2 - 0.2)
            let byWidth = minColumns > 0 ? geo.size.width / (CGFloat(minColumns) * 1.2 - 0.2) : .infinity
            let side = max(4, min(byHeight, byWidth))
            let gap = side * 0.2
            let columns = min(26, max(1, Int((geo.size.width + gap) / (side + gap))))
            let gridWidth = CGFloat(columns) * side + CGFloat(columns - 1) * gap

            VStack(alignment: .leading, spacing: 0) {
                if showsMonths { monthLabels(columns: columns, side: side, gap: gap).frame(height: labelHeight, alignment: .top) }
                HStack(alignment: .top, spacing: gap) {
                    ForEach(0..<columns, id: \.self) { column in
                        let monday = model.monday(column: column, of: columns)
                        VStack(spacing: gap) {
                            ForEach(0..<7, id: \.self) { row in
                                cell(Calendar.current.date(byAdding: .day, value: row, to: monday) ?? monday, side: side)
                            }
                        }
                    }
                }
            }
            .frame(width: gridWidth, alignment: .leading)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
        }
    }

    @ViewBuilder
    private func cell(_ date: Date, side: CGFloat) -> some View {
        let day = Calendar.current.startOfDay(for: date)
        if day > model.today {
            Color.clear.frame(width: side, height: side)
        } else {
            ZStack {
                RoundedRectangle(cornerRadius: side / 4, style: .continuous).fill(W.heat[model.level(day)])
                if model.trained(day) {
                    Circle().fill(W.ink).frame(width: side * 0.38, height: side * 0.38)
                }
                if day == model.today {
                    RoundedRectangle(cornerRadius: side / 4, style: .continuous).strokeBorder(W.ink, lineWidth: 1.5)
                }
            }
            .frame(width: side, height: side)
        }
    }

    /// Short month names over the columns that hold a 1st.
    private func monthLabels(columns: Int, side: CGFloat, gap: CGFloat) -> some View {
        let cal = Calendar.current
        let labels: [(Int, Date)] = (0..<columns).compactMap { column in
            let monday = model.monday(column: column, of: columns)
            for row in 0..<7 {
                guard let date = cal.date(byAdding: .day, value: row, to: monday), date <= model.today else { continue }
                if cal.component(.day, from: date) == 1 { return (column, date) }
            }
            return nil
        }
        return ZStack(alignment: .topLeading) {
            ForEach(labels, id: \.0) { column, date in
                Text(verbatim: WidgetText.monthShort(date))
                    .font(W.caption).foregroundStyle(W.ink3).fixedSize()
                    .offset(x: CGFloat(column) * (side + gap))
            }
        }
        .frame(maxWidth: .infinity, alignment: .topLeading)
    }
}
