import SwiftUI

/// The one card on the Bro tab, and its focal card (v2): Mon–Sun letters, a row of cells for me and one for the
/// partner, then the next session line with who confirmed when.
struct BroSharedWeekCard: View {
    var week: [WeekDay]
    var partnerName: String
    var session: BroSessionLine?

    private let labelWidth: CGFloat = 52

    var body: some View {
        FocalCard(padding: 0) {
            VStack(alignment: .leading, spacing: 10) {
                lettersRow
                cellsRow(label: Text("bro.you")) { $0.myState }
                cellsRow(label: Text(partnerName)) { $0.partnerState }
                if let session {
                    Hairline().padding(.top, 4)
                    sessionRow(session)
                }
            }
            .padding(EdgeInsets(top: 14, leading: 16, bottom: 16, trailing: 16))
        }
    }

    private var lettersRow: some View {
        HStack(spacing: 10) {
            Color.clear.frame(width: labelWidth, height: 1)
            HStack(spacing: 0) {
                ForEach(week) { day in
                    Text(LocalizedStringKey(AttendanceService.labelKey(isoWeekday: day.isoWeekday)))
                        .font(NT.Fonts.caption)
                        .foregroundStyle(day.isToday ? NT.Colors.ink : NT.Colors.ink2)
                        .frame(maxWidth: .infinity)
                }
            }
        }
    }

    private func cellsRow(label: Text, state: @escaping (WeekDay) -> DayState) -> some View {
        HStack(spacing: 10) {
            label
                .font(NT.Fonts.footnote)
                .foregroundStyle(NT.Colors.ink)
                .lineLimit(1)
                .frame(width: labelWidth, alignment: .leading)
            HStack(spacing: 0) {
                ForEach(week) { day in
                    BroStatusCell(state: state(day), size: 30)
                        .frame(maxWidth: .infinity)
                }
            }
        }
    }

    private func sessionRow(_ session: BroSessionLine) -> some View {
        HStack(alignment: .center, spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                Text(headline(session))
                    .font(NT.Fonts.headline)
                    .foregroundStyle(NT.Colors.ink)
                    .lineLimit(1)
                Text(confirmations(session))
                    .font(NT.Fonts.footnote)
                    .foregroundStyle(NT.Colors.ink2)
                    .tabular()
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            statusBadge(session)
        }
    }

    @ViewBuilder
    private func statusBadge(_ session: BroSessionLine) -> some View {
        if session.bothIn {
            HStack(spacing: 6) {
                Circle().fill(NT.Colors.good).frame(width: 8, height: 8)
                Text("bro.bothIn").font(NT.Fonts.footnoteBold).foregroundStyle(NT.Colors.ink)
            }
        } else if session.partnerState.isMissedOrCancelled {
            HStack(spacing: 6) {
                Circle().fill(NT.Colors.bad).frame(width: 8, height: 8)
                Text("bro.today.out \(partnerName)").font(NT.Fonts.footnoteBold).foregroundStyle(NT.Colors.ink)
            }
        } else if session.myState.isMissedOrCancelled {
            HStack(spacing: 6) {
                Circle().fill(NT.Colors.bad).frame(width: 8, height: 8)
                Text("bro.today.youOut").font(NT.Fonts.footnoteBold).foregroundStyle(NT.Colors.ink)
            }
        }
    }

    /// "Today · 18:00 · Push A"
    private func headline(_ session: BroSessionLine) -> String {
        var parts = [Fmt.relativeDay(session.day), Fmt.time(minuteOfDay: session.minuteOfDay)]
        if let routine = session.routineName { parts.append(routine) }
        return parts.joined(separator: " · ")
    }

    /// "You 09:12 · Tomek 12:40", or "No one's in yet".
    private func confirmations(_ session: BroSessionLine) -> String {
        var parts: [String] = []
        if let at = session.myConfirmedAt { parts.append("\(String(localized: "bro.you")) \(Fmt.time(at))") }
        if let at = session.partnerConfirmedAt { parts.append("\(partnerName) \(Fmt.time(at))") }
        return parts.isEmpty ? String(localized: "bro.noOneYet") : parts.joined(separator: " · ")
    }
}
