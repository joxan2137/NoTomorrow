import SwiftUI
import SwiftData

/// Tape measurements: conversion (cm stored, inches shown to lb users), labels and the per-kind summary.
enum Measurements {
    static let cmPerInch = 2.54

    static func usesInches(_ kind: MeasurementKind, unit: WeightUnit) -> Bool { kind != .bodyFat && unit == .lb }

    static func display(_ value: Double, kind: MeasurementKind, unit: WeightUnit) -> Double {
        usesInches(kind, unit: unit) ? value / cmPerInch : value
    }

    static func stored(_ value: Double, kind: MeasurementKind, unit: WeightUnit) -> Double {
        usesInches(kind, unit: unit) ? value * cmPerInch : value
    }

    static func unitLabel(_ kind: MeasurementKind, unit: WeightUnit) -> String {
        kind == .bodyFat ? "%" : (unit == .lb ? "in" : "cm")
    }

    /// "84,5 cm", "33.3 in", "18,5 %".
    static func label(_ value: Double, kind: MeasurementKind, unit: WeightUnit, signed: Bool = false) -> String {
        let shown = display(value, kind: kind, unit: unit)
        let number = shown.formatted(.number.precision(.fractionLength(0...1))
            .sign(strategy: signed ? .always(includingZero: false) : .automatic).locale(Fmt.locale))
        return "\(number)\u{00A0}\(unitLabel(kind, unit: unit))"
    }

    static func titleKey(_ kind: MeasurementKind) -> LocalizedStringKey {
        LocalizedStringKey("measure." + kind.rawValue)
    }

    struct Reading: Equatable {
        var kind: MeasurementKind
        var day: Date
        var value: Double
    }

    struct Summary: Equatable {
        var kind: MeasurementKind
        var latest: Reading
        /// Latest minus the first reading, when there are two or more.
        var change: Double?
    }

    /// One summary per kind that has readings, in `MeasurementKind` order.
    static func summaries(_ readings: [Reading]) -> [Summary] {
        MeasurementKind.allCases.compactMap { kind in
            let mine = readings.filter { $0.kind == kind }.sorted { $0.day < $1.day }
            guard let first = mine.first, let last = mine.last else { return nil }
            return Summary(kind: kind, latest: last, change: mine.count >= 2 ? last.value - first.value : nil)
        }
    }

    /// Today's value per kind is replaced; an empty field leaves that kind alone.
    @MainActor
    static func save(_ values: [MeasurementKind: Double], day: Date = .now, in context: ModelContext) {
        let start = Calendar.current.startOfDay(for: day)
        let existing = (try? context.fetch(FetchDescriptor<BodyMeasurement>(predicate: #Predicate { $0.day == start }))) ?? []
        for (kind, value) in values where value > 0 {
            if let row = existing.first(where: { $0.kindRaw == kind.rawValue }) {
                row.value = value
            } else {
                context.insert(BodyMeasurement(day: start, kind: kind, value: value))
            }
        }
        try? context.save()
    }
}

// MARK: - Section

/// Progress > Body, under the weight card: the latest of each measurement and its change since the first one,
/// and Log measurements.
struct MeasurementsSection: View {
    var unit: WeightUnit

    @Query(sort: \BodyMeasurement.day) private var rows: [BodyMeasurement]
    @State private var showsLog = false

    private var readings: [Measurements.Reading] {
        rows.compactMap { row in row.kind.map { Measurements.Reading(kind: $0, day: row.day, value: row.value) } }
    }

    var body: some View {
        let summaries = Measurements.summaries(readings)
        VStack(alignment: .leading, spacing: 0) {
            SectionHeader(title: "measure.title").padding(.bottom, 4)
            if summaries.isEmpty {
                Text("measure.empty")
                    .font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.vertical, 12)
            } else {
                ForEach(Array(summaries.enumerated()), id: \.offset) { index, summary in
                    if index > 0 { Hairline() }
                    row(summary)
                }
            }
            SecondaryButton(title: "measure.log", systemImage: "ruler", height: NT.Size.cardButton) { showsLog = true }
                .padding(.top, 12)
        }
        .sheet(isPresented: $showsLog) {
            LogMeasurementsSheet(unit: unit, last: Dictionary(summaries.map { ($0.kind, $0.latest.value) }, uniquingKeysWith: { a, _ in a }))
        }
    }

    private func row(_ summary: Measurements.Summary) -> some View {
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                Text(Measurements.titleKey(summary.kind)).font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink)
                Text(Fmt.dayMonth(summary.latest.day)).font(NT.Fonts.caption).foregroundStyle(NT.Colors.ink2)
            }
            Spacer()
            if let change = summary.change, abs(change) >= 0.05 {
                Text(Measurements.label(change, kind: summary.kind, unit: unit, signed: true))
                    .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2).tabular()
            }
            Text(Measurements.label(summary.latest.value, kind: summary.kind, unit: unit))
                .font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink).tabular()
                .frame(minWidth: 84, alignment: .trailing)
        }
        .frame(minHeight: 52)
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Log sheet

/// One field per measurement, last values as placeholders; Save writes today's readings for the filled fields.
struct LogMeasurementsSheet: View {
    var unit: WeightUnit
    /// Latest stored value per kind (cm / %), for the placeholders.
    var last: [MeasurementKind: Double]

    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss
    @State private var texts: [MeasurementKind: String] = [:]

    private var parsed: [MeasurementKind: Double] {
        var result: [MeasurementKind: Double] = [:]
        for (kind, text) in texts {
            let value = SetInput.number(text)
            guard value > 0, value < (kind == .bodyFat ? 80 : 400) else { continue }
            result[kind] = Measurements.stored(value, kind: kind, unit: unit)
        }
        return result
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button { dismiss() } label: {
                    Text("common.cancel").font(NT.Fonts.body).foregroundStyle(NT.Colors.ink2).frame(minHeight: NT.Size.control)
                }
                Spacer()
                Text("measure.log").font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink)
                Spacer()
                Button {
                    Measurements.save(parsed, in: modelContext)
                    Haptics.success()
                    dismiss()
                } label: {
                    Text("common.save").font(NT.Fonts.headline)
                        .foregroundStyle(parsed.isEmpty ? NT.Colors.ink3 : NT.Colors.ember)
                        .frame(minHeight: NT.Size.control)
                }
                .disabled(parsed.isEmpty)
            }
            .padding(.horizontal, NT.Spacing.screenH)
            .padding(.top, 16)
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text(Fmt.longDay(.now)).font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2)
                    STGroup {
                        ForEach(MeasurementKind.allCases, id: \.self) { kind in
                            field(kind)
                        }
                    }
                }
                .padding(.horizontal, NT.Spacing.screenH)
                .padding(.top, 12)
                .padding(.bottom, NT.Spacing.section)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .ntScreenBackground()
        .presentationBackground(NT.Colors.ground)
        .presentationDragIndicator(.visible)
    }

    private func field(_ kind: MeasurementKind) -> some View {
        let placeholder = last[kind].map {
            Measurements.display($0, kind: kind, unit: unit)
                .formatted(.number.precision(.fractionLength(0...1)).locale(Fmt.locale))
        } ?? "—"
        return HStack(spacing: 12) {
            Text(Measurements.titleKey(kind)).font(NT.Fonts.body).foregroundStyle(NT.Colors.ink)
                .accessibilityHidden(true)
            Spacer(minLength: 8)
            TextField("", text: Binding(get: { texts[kind] ?? "" }, set: { texts[kind] = $0 }),
                      prompt: Text(verbatim: placeholder).foregroundStyle(NT.Colors.ink3))
                .keyboardType(.decimalPad)
                .multilineTextAlignment(.trailing)
                .font(NT.Fonts.body).foregroundStyle(NT.Colors.ink).tabular()
                .frame(width: 90)
                .accessibilityLabel(Text(Measurements.titleKey(kind))
                    + Text(verbatim: ", " + Measurements.unitLabel(kind, unit: unit)))
            Text(verbatim: Measurements.unitLabel(kind, unit: unit))
                .font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2)
                .fixedSize()
                .frame(minWidth: 24, alignment: .leading)
                .accessibilityHidden(true)
        }
        .frame(minHeight: NT.Size.control)
    }
}
