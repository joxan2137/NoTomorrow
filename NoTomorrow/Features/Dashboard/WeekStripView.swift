import SwiftUI

/// Mon…Sun strip: letter label, 36 pt state circle, 5 pt row of who-trained dots.
/// Attended = ember ring + check, missed/cancelled = rose ring + x, today = white filled circle, a gym day still ahead =
/// thin ring around the number, else the day number. The dots (v2): ember when you trained and, when paired, green when
/// your partner trained or rose when they missed.
struct WeekStripView: View {
    var days: [WeekDay]
    var isPaired: Bool
    var partnerName: String?

    var body: some View {
        HStack(spacing: 0) {
            ForEach(days) { day in
                WeekDayCell(day: day, dots: Self.dots(for: day, isPaired: isPaired), partnerName: partnerName)
                    .frame(maxWidth: .infinity)
            }
        }
        .accessibilityElement(children: .contain)
    }

    /// One 5 pt dot under a day's circle.
    enum Dot: Equatable {
        /// You trained (ember).
        case you
        /// Your partner trained (green).
        case partnerTrained
        /// Your partner missed or cancelled (rose).
        case partnerMissed
    }

    /// Who trained that day, in order: you when you attended, then — paired only — your partner when they attended
    /// or missed/cancelled. Empty on every other day.
    static func dots(for day: WeekDay, isPaired: Bool) -> [Dot] {
        var dots: [Dot] = []
        if day.myState == .attended { dots.append(.you) }
        if isPaired {
            if day.partnerState == .attended {
                dots.append(.partnerTrained)
            } else if day.partnerState.isMissedOrCancelled {
                dots.append(.partnerMissed)
            }
        }
        return dots
    }

    /// A scheduled gym day other than today with nothing settled yet (planned or confirmed): its number gets a thin
    /// ring. Without a record a future gym day already reads `.planned`; a past one reads `.rest` and stays plain.
    static func isUpcomingGymDay(_ day: WeekDay) -> Bool {
        guard day.isGymDay, !day.isToday else { return false }
        switch day.myState {
        case .planned, .confirmed: return true
        case .rest, .attended, .missed, .cancelled: return false
        }
    }
}

private struct WeekDayCell: View {
    let day: WeekDay
    let dots: [WeekStripView.Dot]
    let partnerName: String?

    var body: some View {
        VStack(spacing: 8) {
            Text(LocalizedStringKey(AttendanceService.labelKey(isoWeekday: day.isoWeekday)))
                .font(NT.Fonts.caption)
                .foregroundStyle(day.isToday ? NT.Colors.ink : NT.Colors.ink2)
            circle
                .frame(width: 36, height: 36)
            HStack(spacing: 3) {
                ForEach(dots.indices, id: \.self) { i in
                    Circle()
                        .fill(color(for: dots[i]))
                        .frame(width: 5, height: 5)
                }
            }
            .frame(height: 5)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(accessibilityText))
    }

    @ViewBuilder
    private var circle: some View {
        if day.isToday {
            ZStack {
                Circle().fill(NT.Colors.ink)
                Text(dayNumber)
                    .font(NT.Fonts.subheadlineBold)
                    .foregroundStyle(NT.Colors.onPrimary)
                    .tabular()
            }
        } else {
            switch day.myState {
            case .attended:
                ring(color: NT.Colors.ember, symbol: "checkmark")
            case .missed, .cancelled:
                ring(color: NT.Colors.bad, symbol: "xmark")
            case .rest, .planned, .confirmed:
                if WeekStripView.isUpcomingGymDay(day) {
                    ZStack {
                        Circle().strokeBorder(NT.Colors.border, lineWidth: 1)
                        Text(dayNumber)
                            .font(NT.Fonts.subheadline)
                            .foregroundStyle(NT.Colors.ink)
                            .tabular()
                    }
                } else {
                    Text(dayNumber)
                        .font(NT.Fonts.subheadline)
                        .foregroundStyle(NT.Colors.ink2)
                        .tabular()
                }
            }
        }
    }

    private func ring(color: Color, symbol: String) -> some View {
        ZStack {
            Circle().strokeBorder(color, lineWidth: 1.5)
            Image(systemName: symbol)
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(color)
        }
    }

    private func color(for dot: WeekStripView.Dot) -> Color {
        switch dot {
        case .you: NT.Colors.ember
        case .partnerTrained: NT.Colors.good
        case .partnerMissed: NT.Colors.bad
        }
    }

    /// Day-of-month via the locale-aware date formatter (no dedicated `Fmt` helper exists for a bare day number).
    private var dayNumber: String {
        day.date.formatted(.dateTime.day())
    }

    /// The day, then "<partner> trained" when the partner's dot is the green one.
    private var accessibilityText: String {
        let label = Fmt.dayMonth(day.date)
        guard dots.contains(.partnerTrained), let partnerName, !partnerName.isEmpty else { return label }
        return label + ", " + String(localized: "dashboard.week.partnerDone \(partnerName)")
    }
}
