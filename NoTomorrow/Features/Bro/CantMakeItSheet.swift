import SwiftUI
import SwiftData

/// "Can't make it today" sheet, opened from the Dashboard and the Bro tab.
/// Sending writes my `AttendanceRecord(.cancelled)`, a `HeadsUp(.cantMakeIt)` and, when paired, tells the partner.
struct CantMakeItSheet: View {
    var sessionDay: Date
    var minuteOfDay: Int
    var routineName: String?
    var onDone: () -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var modelContext
    @Query private var pairings: [BroPairing]
    @Query private var schedules: [GymSchedule]

    @State private var reason: CantReason?
    @State private var note = ""
    @State private var makeUp: MakeUpChoice?
    @State private var isSending = false
    @FocusState private var noteFocused: Bool

    private let maxNoteLength = 80

    enum CantReason: String, CaseIterable, Identifiable {
        case sick, work, tired, family, none
        var id: String { rawValue }
        var key: LocalizedStringKey { LocalizedStringKey(String("cant.reason.\(rawValue)")) }
        var label: String { String(localized: String.LocalizationValue("cant.reason.\(rawValue)")) }
    }

    enum MakeUpChoice: Equatable {
        case day(Date)
        case skip
        var date: Date? { if case .day(let d) = self { return d } else { return nil } }
    }

    private var partnerName: String? {
        BroShared.service.partner?.name ?? pairings.first?.partnerName
    }
    private var isPaired: Bool { partnerName != nil }
    private var makeUpDays: [Date] { BroDerived.nonGymDays(after: sessionDay, schedule: schedules.first) }

    var body: some View {
        VStack(spacing: 0) {
            Grabber()
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    titleBlock
                    whySection
                    makeUpSection
                    summaryBox
                    buttons
                }
                .padding(.horizontal, NT.Spacing.screenH)
                .padding(.top, 18)
                .padding(.bottom, 12)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .background(NT.Colors.surface.ignoresSafeArea())
        .presentationDetents([.height(660), .large])
        .presentationDragIndicator(.hidden)
        .presentationBackground(NT.Colors.surface)
        .presentationCornerRadius(24)
    }

    // MARK: Sections

    private var titleBlock: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("cant.title").font(NT.Fonts.title2).foregroundStyle(NT.Colors.ink)
            Text(sessionLine).font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2).lineLimit(1)
        }
    }

    private var whySection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("cant.why").eyebrow()
            BroFlowLayout(spacing: 8) {
                ForEach(CantReason.allCases) { r in
                    SheetChip(title: r.label, isSelected: reason == r) {
                        reason = reason == r ? nil : r
                    }
                }
            }
            noteField
        }
    }

    private var noteField: some View {
        HStack(spacing: 10) {
            TextField("cant.note", text: $note)
                .font(NT.Fonts.body)
                .foregroundStyle(NT.Colors.ink)
                .focused($noteFocused)
                .submitLabel(.done)
                .onChange(of: note) { _, value in
                    if value.count > maxNoteLength { note = String(value.prefix(maxNoteLength)) }
                }
            Text("\(note.count)/\(maxNoteLength)")
                .font(NT.Fonts.footnote)
                .foregroundStyle(NT.Colors.ink3)
                .tabular()
        }
        .padding(.horizontal, 14)
        .frame(height: NT.Size.control)
        .background(NT.Colors.ground, in: RoundedRectangle(cornerRadius: NT.Radius.field, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: NT.Radius.field, style: .continuous).strokeBorder(NT.Colors.hairline, lineWidth: 1))
    }

    private var makeUpSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("cant.makeUp").eyebrow()
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(makeUpDays, id: \.self) { day in
                        SheetChip(title: BroDerived.weekdayDay(day), isSelected: makeUp == .day(day)) {
                            makeUp = makeUp == .day(day) ? nil : .day(day)
                        }
                    }
                    SheetChip(title: String(localized: "cant.skipThisOne"), isSelected: makeUp == .skip) {
                        makeUp = makeUp == .skip ? nil : .skip
                    }
                }
                .padding(.horizontal, NT.Spacing.screenH)
            }
            .padding(.horizontal, -NT.Spacing.screenH)
        }
    }

    private var summaryBox: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "calendar.badge.minus")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(NT.Colors.bad)
                .frame(width: 18, height: 18)
            Text(summaryText)
                .font(NT.Fonts.footnote)
                .foregroundStyle(NT.Colors.ink2)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(EdgeInsets(top: 12, leading: 14, bottom: 12, trailing: 14))
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(NT.Colors.ground, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private var buttons: some View {
        VStack(spacing: 12) {
            if let partnerName {
                PrimaryButton(title: "cant.send \(partnerName)", isEnabled: !isSending, action: send)
            } else {
                PrimaryButton(title: "cant.sendSolo", isEnabled: !isSending, action: send)
            }
            Button { dismiss() } label: {
                Text("cant.neverMind")
                    .font(NT.Fonts.subheadline)
                    .foregroundStyle(NT.Colors.ink2)
                    .frame(maxWidth: .infinity)
                    .frame(height: NT.Size.control)
                    .contentShape(Rectangle())
            }
            .buttonStyle(PressScale())
        }
    }

    // MARK: Copy

    /// "Friday 18:00 · Push A · Tomek"
    private var sessionLine: String {
        var parts = ["\(Fmt.relativeDay(sessionDay)) \(Fmt.time(minuteOfDay: minuteOfDay))"]
        if let routineName { parts.append(routineName) }
        if let partnerName { parts.append(partnerName) }
        return parts.joined(separator: " · ")
    }

    private var summaryText: String {
        let dayName = Fmt.relativeDay(sessionDay)
        var text: String
        if let partnerName {
            text = String(format: String(localized: "cant.summary"), dayName, partnerName)
            if let day = makeUp?.date {
                let when = "\(Fmt.relativeDay(day)) \(Fmt.time(minuteOfDay: minuteOfDay))"
                text += " " + String(format: String(localized: "cant.summaryMakeUp"), when)
            }
        } else {
            text = String(format: String(localized: "cant.summarySolo"), dayName)
        }
        return text
    }

    // MARK: Send

    private func send() {
        guard !isSending else { return }
        isSending = true
        noteFocused = false
        let day = Calendar.current.startOfDay(for: sessionDay)
        let reasonRaw = reason?.rawValue
        let trimmedNote = note.trimmingCharacters(in: .whitespacesAndNewlines)
        let noteValue: String? = trimmedNote.isEmpty ? nil : trimmedNote
        let makeUpDay = makeUp?.date

        AttendanceService.markMissed(day: day, reason: reasonRaw, note: noteValue, makeUp: makeUpDay, context: modelContext)
        let text = noteValue ?? reason?.label ?? ""
        modelContext.insert(HeadsUp(fromMe: true, kind: .cantMakeIt, text: text, sessionDay: day))
        try? modelContext.save()

        if isPaired {
            let context = modelContext
            Task { await BroShared.service.cantMakeIt(reason: reasonRaw, note: noteValue, makeUpDay: makeUpDay, sessionDay: day, in: context) }
        }
        onDone()
        dismiss()
    }
}
