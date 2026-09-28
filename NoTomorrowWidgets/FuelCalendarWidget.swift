import SwiftUI
import WidgetKit

/// Fuel calendar: the Fuel history as a GitHub-style contribution graph, with a dot on every day you trained.
/// `docs/widgets.md`, "Fuel calendar".
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

/// The grid's data for one render: levels and trained flags by day, and the days on target.
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
                ContributionGrid(model: model, showsLegend: family == .systemLarge)
            }
        } else {
            WidgetSetupView()
        }
    }

    private func header(_ model: FuelCalendarModel) -> some View {
        HStack(alignment: .center, spacing: 6) {
            Image(systemName: "flame.fill").font(.system(size: 11, weight: .bold)).foregroundStyle(W.ember)
            Text(verbatim: WidgetText.string("widget.history.name")).eyebrowStyle().lineLimit(1)
            Spacer(minLength: 8)
            Text(verbatim: WidgetText.format("widget.history.onTarget %lld", model.onTarget30))
                .font(W.caption).monospacedDigit().foregroundStyle(W.ink2).lineLimit(1)
        }
        .frame(height: 16)
    }
}

/// The Fuel calendar as a GitHub contribution graph: one column per week, Monday on top, the current week at the
/// right edge; short month names over the weeks that start a month and Mon / Wed / Fri down the left. Each day is a
/// small rounded square in its `heat` colour, a day you trained gets an `ink` dot in the middle, today an `ink` ring;
/// days still ahead are left out. Cells are square and as big as the height allows (at most `maxPitch`); when that
/// would leave only a few weeks across, the weeks wrap into a second band under the first (the older half on top).
/// Same metrics as Android's `WidgetCharts.contributions`.
struct ContributionGrid: View {
    let model: FuelCalendarModel
    let showsLegend: Bool

    private static let gapRatio: CGFloat = 0.22
    private static let maxPitch: CGFloat = 22
    private static let minTwoBandPitch: CGFloat = 15
    private static let monthBand: CGFloat = 16
    private static let bandGap: CGFloat = 12
    private static let legendBand: CGFloat = 24
    private static let labelColumn: CGFloat = 30

    private struct Metrics {
        var width: CGFloat
        var bands: Int
        var perBand: Int
        var pitch: CGFloat
        var gap: CGFloat { pitch * ContributionGrid.gapRatio }
        var cell: CGFloat { pitch - gap }
        var weeks: Int { bands * perBand }
        /// The grid keeps to the right edge; the weekday names to the left.
        var gridLeft: CGFloat { width - (CGFloat(perBand) * pitch - gap) }
    }

    private func metrics(_ size: CGSize) -> Metrics {
        let gridHeight = size.height - (showsLegend ? Self.legendBand : 0)
        let gridWidth = size.width - Self.labelColumn
        func pitch(_ bands: Int) -> CGFloat {
            let b = CGFloat(bands)
            return (gridHeight - b * Self.monthBand - (b - 1) * Self.bandGap) / (b * 7 - Self.gapRatio)
        }
        let bands = pitch(1) > Self.maxPitch && pitch(2) >= Self.minTwoBandPitch ? 2 : 1
        let p = max(4, min(pitch(bands), Self.maxPitch))
        let fit = Int((gridWidth + p * Self.gapRatio) / p)
        let perBand = min(max(1, fit), WidgetSnapshot.CalendarGrid.weeks / bands)
        return Metrics(width: size.width, bands: bands, perBand: perBand, pitch: p)
    }

    var body: some View {
        GeometryReader { geo in
            let m = metrics(geo.size)
            VStack(alignment: .leading, spacing: 0) {
                ForEach(0..<m.bands, id: \.self) { band in
                    if band > 0 { Color.clear.frame(height: Self.bandGap) }
                    bandView(band, m)
                }
                if showsLegend { legend.frame(height: Self.legendBand) }
            }
            .frame(width: geo.size.width, height: geo.size.height)
        }
    }

    private func bandView(_ band: Int, _ m: Metrics) -> some View {
        let first = band * m.perBand
        return VStack(alignment: .leading, spacing: 0) {
            monthRow(first: first, m)
                .frame(width: m.width, height: Self.monthBand, alignment: .topLeading)
            HStack(alignment: .top, spacing: 0) {
                weekdayNames(m)
                Spacer(minLength: 0)
                HStack(alignment: .top, spacing: m.gap) {
                    ForEach(0..<m.perBand, id: \.self) { column in
                        weekColumn(model.monday(column: first + column, of: m.weeks), m)
                    }
                }
            }
            .frame(width: m.width)
        }
    }

    private func weekColumn(_ monday: Date, _ m: Metrics) -> some View {
        VStack(spacing: m.gap) {
            ForEach(0..<7, id: \.self) { row in
                cell(Calendar.current.date(byAdding: .day, value: row, to: monday) ?? monday, m)
            }
        }
    }

    @ViewBuilder
    private func cell(_ date: Date, _ m: Metrics) -> some View {
        let day = Calendar.current.startOfDay(for: date)
        if day > model.today {
            Color.clear.frame(width: m.cell, height: m.cell)
        } else {
            let shape = RoundedRectangle(cornerRadius: m.cell * 0.24, style: .continuous)
            let dot = max(3.5, m.cell * 0.4)
            ZStack {
                shape.fill(W.heat[model.level(day)])
                if model.trained(day) {
                    Circle().fill(W.ink).frame(width: dot, height: dot)
                }
                if day == model.today {
                    shape.strokeBorder(W.ink, lineWidth: 1.5)
                }
            }
            .frame(width: m.cell, height: m.cell)
        }
    }

    /// Mon / Wed / Fri beside their rows.
    private func weekdayNames(_ m: Metrics) -> some View {
        VStack(alignment: .leading, spacing: m.gap) {
            ForEach(0..<7, id: \.self) { row in
                Text(verbatim: Self.weekdayName(row))
                    .font(.system(size: 10, weight: .medium)).foregroundStyle(W.ink3)
                    .lineLimit(1).fixedSize()
                    .frame(height: m.cell)
            }
        }
    }

    private static func weekdayName(_ row: Int) -> String {
        let keys = [0: "weekday.mon.short", 2: "weekday.wed.short", 4: "weekday.fri.short"]
        guard let key = keys[row] else { return "" }
        var name = WidgetText.string(key)
        if name.hasSuffix(".") { name.removeLast() }
        return name
    }

    private struct MonthLabel {
        var column: Int
        var month: Date
    }

    /// Short month names over the weeks that hold a 1st; the band's first week gets its own month when the next name
    /// is at least two weeks away. A name that would run into the one before it, or off the edge, is left out.
    private func monthRow(first: Int, _ m: Metrics) -> some View {
        let cal = Calendar.current
        var labels: [MonthLabel] = []
        for column in 0..<m.perBand {
            let monday = model.monday(column: first + column, of: m.weeks)
            for row in 0..<7 {
                guard let date = cal.date(byAdding: .day, value: row, to: monday),
                      cal.startOfDay(for: date) <= model.today,
                      cal.component(.day, from: date) == 1 else { continue }
                labels.append(MonthLabel(column: column, month: date))
            }
        }
        if (labels.first?.column ?? .max) >= 2,
           let month = cal.date(from: cal.dateComponents([.year, .month], from: model.monday(column: first, of: m.weeks))) {
            labels.insert(MonthLabel(column: 0, month: month), at: 0)
        }
        var kept: [MonthLabel] = []
        for label in labels where label.column <= m.perBand - 2 {
            if let last = kept.last, CGFloat(label.column - last.column) * m.pitch < 30 { continue }
            kept.append(label)
        }
        return ZStack(alignment: .topLeading) {
            ForEach(kept, id: \.column) { label in
                Text(verbatim: WidgetText.monthShort(label.month).capitalized(with: WidgetText.locale))
                    .font(.system(size: 10, weight: .medium))
                    .foregroundStyle(cal.isDate(label.month, equalTo: model.today, toGranularity: .month) ? W.ink2 : W.ink3)
                    .fixedSize()
                    .offset(x: m.gridLeft + CGFloat(label.column) * m.pitch)
            }
        }
    }

    /// The key: a trained cell, then "Off target ▪▪▪▪ On target".
    private var legend: some View {
        HStack(spacing: 6) {
            swatch(level: 0, dot: true)
            Text(verbatim: WidgetText.string("widget.history.trained"))
            Spacer(minLength: 12)
            Text(verbatim: WidgetText.string("fuel.calendar.legend.off"))
            HStack(spacing: 3) {
                ForEach(1..<5, id: \.self) { level in swatch(level: level, dot: false) }
            }
            Text(verbatim: WidgetText.string("fuel.calendar.onTarget"))
        }
        .font(W.caption).foregroundStyle(W.ink2).lineLimit(1)
        .padding(.top, 8)
    }

    private func swatch(level: Int, dot: Bool) -> some View {
        ZStack {
            RoundedRectangle(cornerRadius: 2.5, style: .continuous).fill(W.heat[level])
            if dot { Circle().fill(W.ink).frame(width: 4.5, height: 4.5) }
        }
        .frame(width: 11, height: 11)
    }
}
