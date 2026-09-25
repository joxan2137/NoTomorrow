import SwiftUI
import WidgetKit

/// Gym week: the next session and this week's gym days, like the Today screen's card and strip.
/// `docs/widgets.md`, "Gym week".
struct WeekWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: WidgetKind.week, provider: SnapshotProvider(extraDates: Self.sessionRollover)) { entry in
            WeekView(entry: entry)
                .widgetGround()
                .widgetURL(URL(string: "notomorrow://today"))
        }
        .configurationDisplayName("widget.week.name")
        .description("widget.week.description")
        .supportedFamilies([.systemSmall, .systemMedium, .accessoryRectangular, .accessoryInline])
    }

    /// The next session moves on two hours after it starts (`AttendanceService.nextSession`).
    static func sessionRollover(_ snapshot: WidgetSnapshot?, _ now: Date) -> [Date] {
        guard let week = snapshot?.week, let next = WeekModel.nextSession(gymMinutes: week.gymMinutes, now: now) else { return [] }
        return [next.addingTimeInterval(WeekModel.grace + 1)]
    }
}

enum WeekModel {
    static let grace: TimeInterval = 2 * 3600

    /// Today's session while it is at most 2 h in the past, otherwise the next gym day (within a week).
    static func nextSession(gymMinutes: [Int: Int], now: Date) -> Date? {
        guard !gymMinutes.isEmpty else { return nil }
        let cal = Calendar.current
        for offset in 0...7 {
            guard let day = cal.date(byAdding: .day, value: offset, to: cal.startOfDay(for: now)),
                  let minute = gymMinutes[cal.widgetISOWeekday(for: day)],
                  let at = cal.date(byAdding: .minute, value: minute, to: cal.startOfDay(for: day)) else { continue }
            if offset == 0, at.addingTimeInterval(grace) < now { continue }
            return at
        }
        return nil
    }

    /// The seven days of `now`'s week. The snapshot's own states while it is from this week; after a Monday the app
    /// has not seen yet, the schedule alone (gym days ahead planned, the rest blank).
    static func days(_ week: WidgetSnapshot.Week, now: Date) -> [WidgetSnapshot.Week.Day] {
        let cal = Calendar.current
        let today = cal.startOfDay(for: now)
        let monday = cal.date(byAdding: .day, value: -(cal.widgetISOWeekday(for: today) - 1), to: today) ?? today
        if let first = week.days.first, cal.isDate(first.date, inSameDayAs: monday), week.days.count == 7 {
            return week.days
        }
        return (0..<7).map { i in
            let date = cal.date(byAdding: .day, value: i, to: monday) ?? monday
            let gym = week.gymMinutes[i + 1] != nil
            let state: WidgetSnapshot.Week.Status = gym && date >= today ? .planned : .rest
            return .init(date: date, isGymDay: gym, me: state, partner: week.isPaired ? state : .rest)
        }
    }

    static let letterKeys = ["weekday.mon", "weekday.tue", "weekday.wed", "weekday.thu", "weekday.fri", "weekday.sat", "weekday.sun"]
}

struct WeekView: View {
    let entry: SnapshotEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        if let snapshot = entry.snapshot, entry.isReady {
            let week = snapshot.week
            let next = WeekModel.nextSession(gymMinutes: week.gymMinutes, now: entry.date)
            let days = WeekModel.days(week, now: entry.date)
            switch family {
            case .accessoryInline:
                inline(next, week)
            case .accessoryRectangular:
                rectangular(next, week)
            case .systemMedium:
                HStack(alignment: .center, spacing: 16) {
                    nextBlock(next, week, timeSize: 44).frame(maxWidth: .infinity, alignment: .leading)
                    VStack(alignment: .leading, spacing: 8) {
                        strip(days, circle: 22, letters: true, dots: week.isPaired)
                        let gymDays = days.filter(\.isGymDay).count
                        if gymDays > 0 {
                            Text(verbatim: WidgetText.format("widget.week.done %lld %lld",
                                                             days.filter { $0.me == .attended }.count, gymDays))
                                .font(W.caption).monospacedDigit().foregroundStyle(W.ink2)
                        }
                    }
                    .fixedSize()
                }
            default:
                VStack(alignment: .leading, spacing: 0) {
                    nextBlock(next, week, timeSize: 40)
                    Spacer(minLength: 6)
                    strip(days, circle: 14, letters: false, dots: false)
                }
            }
        } else if family == .accessoryInline || family == .accessoryRectangular {
            Text(verbatim: WidgetText.string("widget.week.name"))
        } else {
            WidgetSetupView()
        }
    }

    // MARK: Next session

    @ViewBuilder
    private func nextBlock(_ next: Date?, _ week: WidgetSnapshot.Week, timeSize: CGFloat) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(verbatim: WidgetText.string("dashboard.nextSession")).eyebrowStyle(W.ember)
            if let next {
                if let routine = week.routineName {
                    Text(verbatim: routine).font(W.headline).foregroundStyle(W.ink).lineLimit(1)
                }
                Text(verbatim: WidgetText.time(next))
                    .font(W.display(timeSize)).monospacedDigit().foregroundStyle(W.ink)
                    .lineLimit(1).minimumScaleFactor(0.6)
                Text(verbatim: WidgetText.relativeDay(next, now: entry.date))
                    .font(W.footnote).foregroundStyle(W.ink2).lineLimit(1)
            } else {
                Text(verbatim: WidgetText.string("widget.week.noSchedule"))
                    .font(W.footnote).foregroundStyle(W.ink2).padding(.top, 4)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func inline(_ next: Date?, _ week: WidgetSnapshot.Week) -> some View {
        let text: String
        if let next {
            text = ([WidgetText.relativeDay(next, now: entry.date) + " " + WidgetText.time(next)] + [week.routineName].compactMap { $0 })
                .joined(separator: " · ")
        } else {
            text = WidgetText.string("dashboard.restDay")
        }
        return Label { Text(verbatim: text) } icon: { Image(systemName: "dumbbell.fill") }
    }

    private func rectangular(_ next: Date?, _ week: WidgetSnapshot.Week) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(verbatim: WidgetText.string("dashboard.nextSession")).font(W.eyebrow).textCase(.uppercase)
                .widgetAccentable()
            if let next {
                Text(verbatim: WidgetText.relativeDay(next, now: entry.date) + " · " + WidgetText.time(next))
                    .font(.system(size: 15, weight: .semibold)).lineLimit(1)
                if let routine = week.routineName { Text(verbatim: routine).font(W.footnote).lineLimit(1) }
            } else {
                Text(verbatim: WidgetText.string("widget.week.noSchedule")).font(W.footnote).lineLimit(2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: Strip

    private func strip(_ days: [WidgetSnapshot.Week.Day], circle: CGFloat, letters: Bool, dots: Bool) -> some View {
        HStack(spacing: letters ? 5 : 4) {
            ForEach(Array(days.enumerated()), id: \.offset) { index, day in
                VStack(spacing: 4) {
                    if letters {
                        Text(verbatim: WidgetText.string(WeekModel.letterKeys[index]))
                            .font(.system(size: 10, weight: .semibold)).foregroundStyle(W.ink3)
                    }
                    DayCircle(day: day, isToday: Calendar.current.isDate(day.date, inSameDayAs: entry.date),
                              size: circle, showsNumber: letters)
                    if dots {
                        HStack(spacing: 3) {
                            Circle().fill(day.me == .attended ? W.ember : Color.clear).frame(width: 5, height: 5)
                            Circle().fill(partnerDot(day.partner)).frame(width: 5, height: 5)
                        }
                    }
                }
                .frame(maxWidth: letters ? nil : .infinity)
            }
        }
    }

    private func partnerDot(_ status: WidgetSnapshot.Week.Status) -> Color {
        switch status {
        case .attended: W.good
        case .missed, .cancelled: W.bad
        default: .clear
        }
    }
}

/// One day of the strip, in the Dashboard's `WeekStripView` states.
struct DayCircle: View {
    let day: WidgetSnapshot.Week.Day
    let isToday: Bool
    let size: CGFloat
    let showsNumber: Bool

    var body: some View {
        let number = Text(verbatim: "\(Calendar.current.component(.day, from: day.date))")
            .font(.system(size: size * 0.42, weight: .semibold)).monospacedDigit()
        ZStack {
            if day.me == .attended {
                Circle().fill(W.ember)
                Image(systemName: "checkmark").font(.system(size: size * 0.42, weight: .heavy)).foregroundStyle(W.ground)
            } else if day.me.isMissedOrCancelled {
                Circle().strokeBorder(W.bad, lineWidth: 1.5)
                Image(systemName: "xmark").font(.system(size: size * 0.36, weight: .bold)).foregroundStyle(W.bad)
            } else if isToday {
                Circle().fill(W.ink)
                if showsNumber { number.foregroundStyle(W.ground) }
            } else if day.isGymDay, day.me == .planned || day.me == .confirmed {
                Circle().strokeBorder(W.border, lineWidth: 1)
                if showsNumber { number.foregroundStyle(W.ink) }
            } else {
                if showsNumber {
                    number.foregroundStyle(W.ink3)
                } else {
                    Circle().fill(W.surface2)
                }
            }
        }
        .frame(width: size, height: size)
    }
}
