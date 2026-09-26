import SwiftUI
import SwiftData

/// Progress > Records: every lift with a PR, most recent PR first, with its best e1RM, heaviest weight and best
/// volume set and when each was done. A row opens the lift's `ExerciseProgressView` (the `PersistentIdentifier`
/// destination `ProgressHomeView` registers).
struct RecordsView: View {
    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss
    @State private var model = ProgressModel()

    var body: some View {
        let entries = LiftRecords.withPRs(model.lifts)
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header
                if entries.isEmpty {
                    Text("records.empty")
                        .font(NT.Fonts.subheadline)
                        .foregroundStyle(NT.Colors.ink2)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 40)
                } else {
                    VStack(spacing: 0) {
                        ForEach(Array(entries.enumerated()), id: \.element.id) { index, entry in
                            NavigationLink(value: entry.lift.id) {
                                RecordsRow(entry: entry, unit: model.unit, showsHairline: index < entries.count - 1)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.top, 8)
                }
            }
            .padding(.horizontal, NT.Spacing.screenH)
            .padding(.bottom, 32)
        }
        .ntScreenBackground()
        .toolbar(.hidden, for: .navigationBar)
        .onAppear { model.reload(modelContext) }
        .onReceive(NotificationCenter.default.publisher(for: .workoutHistoryDidChange)) { _ in model.reload(modelContext) }
    }

    private var header: some View {
        HStack(spacing: 12) {
            Button { dismiss() } label: {
                Image(systemName: "arrow.left")
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(NT.Colors.ink)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("common.back"))
            .padding(.leading, -10)

            Text("records.title")
                .font(NT.Fonts.title2)
                .foregroundStyle(NT.Colors.ink)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(height: 44)
    }
}

/// One lift: name and last-PR phrase over three record cells (best e1RM, heaviest weight, best volume set).
private struct RecordsRow: View {
    var entry: LiftRecordEntry
    var unit: WeightUnit
    var showsHairline: Bool

    var body: some View {
        let records = entry.records
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 8) {
                    Text(entry.lift.name)
                        .font(NT.Fonts.headline)
                        .foregroundStyle(NT.Colors.ink)
                        .lineLimit(1)
                    Spacer(minLength: 0)
                    Text(ProgressPhrase.lastPR(entry.lift.lastPR))
                        .font(NT.Fonts.footnote)
                        .foregroundStyle(entry.lift.prInLast30Days ? NT.Colors.ember : NT.Colors.ink2)
                        .lineLimit(1)
                    Image(systemName: "chevron.right")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(NT.Colors.ink3)
                }
                HStack(alignment: .top, spacing: 10) {
                    cell("records.bestE1RM", value: Fmt.weight(records.bestE1RMKg, unit: unit),
                         date: records.bestE1RM.date)
                    cell("records.heaviest", value: Fmt.weight(records.heaviest.weightKg, unit: unit),
                         date: records.heaviest.date)
                    cell("records.bestVolumeSet",
                         value: Fmt.set(records.bestVolume.weightKg, records.bestVolume.reps, unit: unit),
                         date: records.bestVolume.date)
                }
            }
            .padding(.vertical, 14)
            if showsHairline { Hairline() }
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    private func cell(_ label: LocalizedStringKey, value: String, date: Date) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).eyebrow().lineLimit(1).minimumScaleFactor(0.8)
            Text(verbatim: value)
                .font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink).tabular()
                .lineLimit(1).minimumScaleFactor(0.8)
            Text(verbatim: Fmt.dayMonth(date))
                .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2).tabular()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
