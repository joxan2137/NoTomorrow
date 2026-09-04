import SwiftUI
import SwiftData

/// "Heads-up to Tomek" + a horizontally scrolling chip row that bleeds to the screen edge.
/// Owns the local `HeadsUp` rows it creates; the backend call goes through `BroService`.
struct BroHeadsUpRow: View {
    var partnerName: String
    var sessionDay: Date
    var onCantMakeIt: () -> Void

    @Environment(\.modelContext) private var modelContext
    @State private var showCustom = false
    @State private var customText = ""
    @State private var showSent = false
    @State private var sentToken = 0

    private let maxCustomLength = 80

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("bro.headsUp \(partnerName)").eyebrow()
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    HeadsUpChip(title: "dashboard.cantMakeIt", systemImage: "calendar.badge.minus",
                                tint: NT.Colors.bad, borderTint: NT.Colors.bad.opacity(0.4), action: onCantMakeIt)
                    HeadsUpChip(title: "bro.late15", systemImage: "clock") {
                        send(kind: .runningLate, text: String(localized: "bro.late15"))
                    }
                    HeadsUpChip(title: "bro.letsGo", systemImage: "bolt") {
                        send(kind: .letsGo, text: String(localized: "bro.letsGo"))
                    }
                    HeadsUpChip(title: "bro.custom", systemImage: "bubble.left") {
                        customText = ""
                        showCustom = true
                    }
                }
                .padding(.horizontal, NT.Spacing.screenH)
            }
            .padding(.horizontal, -NT.Spacing.screenH)
            if showSent {
                Text("bro.sent \(partnerName)")
                    .font(NT.Fonts.footnote)
                    .foregroundStyle(NT.Colors.ink2)
                    .transition(.opacity)
            }
        }
        .alert("bro.custom", isPresented: $showCustom) {
            TextField("bro.customPlaceholder", text: $customText)
            Button("bro.sendMessage") {
                let text = customText.trimmingCharacters(in: .whitespacesAndNewlines)
                if !text.isEmpty { send(kind: .custom, text: String(text.prefix(maxCustomLength))) }
            }
            Button("common.cancel", role: .cancel) {}
        }
        .onChange(of: customText) { _, value in
            if value.count > maxCustomLength { customText = String(value.prefix(maxCustomLength)) }
        }
    }

    private func send(kind: HeadsUpKind, text: String) {
        let row = HeadsUp(fromMe: true, kind: kind, text: text, sessionDay: sessionDay)
        modelContext.insert(row)
        try? modelContext.save()
        let day = sessionDay
        Task { await BroShared.service.sendHeadsUp(kind: kind, text: text, sessionDay: day) }

        sentToken += 1
        let token = sentToken
        withAnimation(.easeOut(duration: 0.2)) { showSent = true }
        Task {
            try? await Task.sleep(for: .seconds(2.5))
            if token == sentToken { withAnimation(.easeOut(duration: 0.3)) { showSent = false } }
        }
    }
}

/// 44 pt outlined chip with a leading icon (the Bro prototype's chip, not the 40 pt selection `Chip`).
struct HeadsUpChip: View {
    var title: LocalizedStringKey
    var systemImage: String
    var tint: Color = NT.Colors.ink
    var borderTint: Color = NT.Colors.hairline
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Image(systemName: systemImage)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(tint)
                Text(title)
                    .font(NT.Fonts.subheadline)
                    .foregroundStyle(NT.Colors.ink)
                    .lineLimit(1)
            }
            .padding(.horizontal, 14)
            .frame(height: NT.Size.control)
            .background(NT.Colors.surface, in: Capsule())
            .overlay(Capsule().strokeBorder(borderTint, lineWidth: 1))
        }
        .buttonStyle(PressScale())
    }
}
