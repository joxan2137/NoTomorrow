import SwiftUI

/// "Log" header with the since/sessions/missed summary and one 56 pt row per past gym day.
struct BroLogSection: View {
    var rows: [BroLogRow]
    var partnerName: String

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline) {
                Text("bro.log").font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink)
                Spacer(minLength: 12)
                if let since = rows.last?.day {
                    Text("bro.sinceMissed \(Fmt.dayMonth(since)) \(rows.count) \(missedCount)")
                        .font(NT.Fonts.footnote)
                        .foregroundStyle(NT.Colors.ink2)
                        .tabular()
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                }
            }
            .padding(.bottom, 4)

            if rows.isEmpty {
                Text("bro.log.empty")
                    .font(NT.Fonts.subheadline)
                    .foregroundStyle(NT.Colors.ink2)
                    .padding(.vertical, 14)
            } else {
                ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
                    BroLogRowView(row: row, partnerName: partnerName)
                    if index < rows.count - 1 { Hairline() }
                }
            }
        }
    }

    private var missedCount: Int { rows.filter(\.anyoneMissed).count }
}

struct BroLogRowView: View {
    var row: BroLogRow
    var partnerName: String

    var body: some View {
        HStack(alignment: .center, spacing: 8) {
            Text(BroDerived.weekdayDay(row.day))
                .font(NT.Fonts.footnote)
                .foregroundStyle(NT.Colors.ink2)
                .tabular()
                .lineLimit(1)
                .frame(width: 52, alignment: .leading)
            VStack(alignment: .leading, spacing: 2) {
                Text(headline)
                    .font(NT.Fonts.subheadline)
                    .foregroundStyle(NT.Colors.ink)
                    .lineLimit(1)
                if let note = row.note {
                    Text(note)
                        .font(NT.Fonts.footnote)
                        .foregroundStyle(NT.Colors.ink2)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 8)
            HStack(spacing: 4) {
                BroStatusCell(state: row.myState, size: 22)
                BroStatusCell(state: row.partnerState, size: 22)
            }
        }
        .frame(minHeight: 56)
    }

    /// "Pull A · both showed up"
    private var headline: String {
        var parts: [String] = []
        if let routine = row.routineName { parts.append(routine) }
        if let outcome { parts.append(outcome) }
        return parts.joined(separator: " · ")
    }

    private var outcome: String? {
        let meIn = row.myState == .attended
        let broIn = row.partnerState == .attended
        let meOut = row.myState.isMissedOrCancelled
        let broOut = row.partnerState.isMissedOrCancelled
        switch (meIn, broIn, meOut, broOut) {
        case (true, true, _, _): return String(localized: "bro.bothShowedUp")
        case (_, _, true, true): return String(localized: "bro.bothMissed")
        case (_, _, false, true): return String(format: String(localized: "bro.missed"), partnerName)
        case (_, _, true, false): return String(localized: "bro.youMissed")
        default: return nil
        }
    }
}
