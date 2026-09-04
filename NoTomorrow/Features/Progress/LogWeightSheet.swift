import SwiftUI
import SwiftData

/// "Log weight": one number field, saves today's `BodyWeightEntry` (and mirrors it to Health when allowed).
struct LogWeightSheet: View {
    var unit: WeightUnit
    var suggestedKg: Double?
    var onSaved: () -> Void

    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var health = HealthKitService()
    @FocusState private var focused: Bool

    private var parsedKg: Double? {
        let normalized = text.replacingOccurrences(of: ",", with: ".").trimmingCharacters(in: .whitespaces)
        guard let value = Double(normalized), value > 0, value < 500 else { return nil }
        return unit == .kg ? value : value / 2.2046226218
    }

    var body: some View {
        VStack(spacing: 0) {
            Grabber()
            HStack {
                Text("progress.logWeight").font(NT.Fonts.title2).foregroundStyle(NT.Colors.ink)
                Spacer()
                Button { dismiss() } label: {
                    Text("common.cancel").font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2)
                        .frame(height: 44)
                }
                .buttonStyle(.plain)
            }
            .padding(.top, 10)

            VStack(alignment: .leading, spacing: 8) {
                Text("progress.todaysWeight").eyebrow()
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    TextField("", text: $text, prompt: Text(placeholder).foregroundStyle(NT.Colors.ink3))
                        .keyboardType(.decimalPad)
                        .font(NT.Fonts.display(44))
                        .foregroundStyle(NT.Colors.ink)
                        .tabular()
                        .focused($focused)
                    Text(unit.rawValue).font(NT.Fonts.title2).foregroundStyle(NT.Colors.ink2)
                }
                .padding(.horizontal, 16)
                .frame(height: 72)
                .background(NT.Colors.surface, in: RoundedRectangle(cornerRadius: NT.Radius.field, style: .continuous))
                Text(Fmt.longDay(.now)).font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2)
            }
            .padding(.top, 22)

            Spacer(minLength: 16)

            PrimaryButton(title: "common.save", isEnabled: parsedKg != nil) { save() }
                .padding(.bottom, 8)
        }
        .padding(.horizontal, NT.Spacing.screenH)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .ntScreenBackground()
        .presentationDetents([.height(340)])
        .presentationDragIndicator(.hidden)
        .presentationBackground(NT.Colors.ground)
        .onAppear { focused = true }
    }

    private var placeholder: String {
        guard let suggestedKg else { return unit == .kg ? "82,5" : "180" }
        return Fmt.weight(suggestedKg, unit: unit, withUnit: false)
    }

    private func save() {
        guard let kg = parsedKg else { return }
        let today = Calendar.current.startOfDay(for: .now)
        let existing = (try? modelContext.fetch(FetchDescriptor<BodyWeightEntry>(predicate: #Predicate { $0.day == today })))?.first
        if let existing {
            existing.kg = kg
            existing.source = .manual
        } else {
            modelContext.insert(BodyWeightEntry(day: today, kg: kg, source: .manual))
        }
        if let profile = (try? modelContext.fetch(FetchDescriptor<UserProfile>()))?.first {
            profile.bodyWeightKg = kg
        }
        try? modelContext.save()
        if health.isAvailable && health.isAuthorized {
            Task { try? await health.saveBodyWeight(kg: kg) }
        }
        onSaved()
        dismiss()
    }
}
