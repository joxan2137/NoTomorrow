import SwiftUI

/// Mon…Sun strip: letter label, 36 pt state circle, 5 pt gym-day dot.
/// Attended = ember ring + check, missed/cancelled = rose ring + x, today = white filled circle, else the day number.
struct WeekStripView: View {
    var days: [WeekDay]

    var body: some View {
        HStack(spacing: 0) {
            ForEach(days) { day in
                WeekDayCell(day: day)
                    .frame(maxWidth: .infinity)
            }
        }
        .accessibilityElement(children: .contain)
    }
}

private struct WeekDayCell: View {
    let day: WeekDay

    var body: some View {
        VStack(spacing: 8) {
            Text(LocalizedStringKey(AttendanceService.labelKey(isoWeekday: day.isoWeekday)))
                .font(NT.Fonts.caption)
                .foregroundStyle(day.isToday ? NT.Colors.ink : NT.Colors.ink2)
            circle
                .frame(width: 36, height: 36)
            Circle()
                .fill(day.isGymDay ? NT.Colors.ember : .clear)
                .frame(width: 5, height: 5)
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
                Text(dayNumber)
                    .font(NT.Fonts.subheadline)
                    .foregroundStyle(NT.Colors.ink2)
                    .tabular()
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

    /// Day-of-month via the locale-aware date formatter (no dedicated `Fmt` helper exists for a bare day number).
    private var dayNumber: String {
        day.date.formatted(.dateTime.day())
    }

    private var accessibilityText: String {
        Fmt.dayMonth(day.date)
    }
}
