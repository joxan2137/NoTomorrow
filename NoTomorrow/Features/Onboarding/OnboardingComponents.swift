import SwiftUI

// Small building blocks shared by the onboarding screens. Prefixed OB to stay out of the design system's way.

// MARK: - Localization helper

enum OBL10n {
    /// "en" or "pl", whichever the device prefers; used as the initial language selection.
    static var systemLanguage: String {
        (Locale.preferredLanguages.first ?? "en").lowercased().hasPrefix("pl") ? "pl" : "en"
    }

    /// Resolves a catalog key for the language chosen on the Welcome screen (before relaunch),
    /// for the few places that need a `String` rather than a `Text`.
    static func string(_ key: String, language: String?) -> String {
        if let language,
           let path = Bundle.main.path(forResource: language, ofType: "lproj"),
           let bundle = Bundle(path: path) {
            return bundle.localizedString(forKey: key, value: nil, table: nil)
        }
        return String(localized: String.LocalizationValue(key))
    }
}

// MARK: - Progress header

/// Back arrow (44 pt hit area), three 4 pt segments, "1 / 3".
struct OBProgressHeader: View {
    var index: Int
    var count: Int
    var onBack: () -> Void

    var body: some View {
        HStack(spacing: 16) {
            Button(action: onBack) {
                Image(systemName: "arrow.left")
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(NT.Colors.ink)
                    .frame(width: NT.Size.control, height: NT.Size.control)
                    .contentShape(Rectangle())
            }
            .buttonStyle(PressScale())
            .accessibilityLabel(Text("common.back"))

            HStack(spacing: 6) {
                ForEach(0..<count, id: \.self) { i in
                    Capsule()
                        .fill(i <= index ? NT.Colors.ink : NT.Colors.surface2)
                        .frame(height: 4)
                }
            }
            .animation(.easeOut(duration: 0.2), value: index)

            Text("onboarding.step \(index + 1) \(count)")
                .font(NT.Fonts.footnote)
                .foregroundStyle(NT.Colors.ink2)
                .tabular()
        }
        .frame(height: NT.Size.control)
        .padding(.leading, NT.Spacing.screenH - 10)
        .padding(.trailing, NT.Spacing.screenH)
    }
}

// MARK: - Segmented control

/// Surface track with a surface-3 thumb, 44 pt segments (language, kg / lb).
struct OBSegmented<Value: Hashable>: View {
    struct Option: Identifiable {
        var value: Value
        var title: LocalizedStringKey
        var id: Value { value }
    }

    var options: [Option]
    @Binding var selection: Value

    var body: some View {
        HStack(spacing: 0) {
            ForEach(options) { option in
                let isOn = option.value == selection
                Button {
                    withAnimation(.easeOut(duration: 0.18)) { selection = option.value }
                } label: {
                    Text(option.title)
                        .font(isOn ? NT.Fonts.subheadlineBold : NT.Fonts.subheadline)
                        .foregroundStyle(isOn ? NT.Colors.ink : NT.Colors.ink2)
                        .frame(maxWidth: .infinity)
                        .frame(height: NT.Size.control)
                        .background(isOn ? NT.Colors.surface3 : .clear,
                                    in: RoundedRectangle(cornerRadius: 11, style: .continuous))
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(3)
        .background(NT.Colors.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

// MARK: - Field chrome

/// 52 pt surface field with a hairline border that turns ink when focused.
struct OBFieldChrome: ViewModifier {
    var isFocused: Bool

    func body(content: Content) -> some View {
        content
            .padding(.horizontal, 16)
            .frame(height: 52)
            .background(NT.Colors.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(isFocused ? NT.Colors.ink : NT.Colors.hairline, lineWidth: isFocused ? 1.5 : 1)
            )
            .animation(.easeOut(duration: 0.15), value: isFocused)
    }
}

extension View {
    func obField(isFocused: Bool) -> some View { modifier(OBFieldChrome(isFocused: isFocused)) }

    /// Eyebrow label above a field or chip row.
    func obLabeled(_ key: LocalizedStringKey, spacing: CGFloat = 8) -> some View {
        VStack(alignment: .leading, spacing: spacing) {
            Text(key).eyebrow()
            self
        }
    }
}

// MARK: - Toggle row

struct OBToggleRow: View {
    var title: LocalizedStringKey
    var detail: LocalizedStringKey? = nil
    @Binding var isOn: Bool

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 1) {
                Text(title).font(NT.Fonts.body).foregroundStyle(NT.Colors.ink)
                if let detail {
                    Text(detail).font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2)
                }
            }
            Spacer(minLength: 8)
            Toggle("", isOn: $isOn)
                .labelsHidden()
                .tint(NT.Colors.ink)
        }
        .frame(minHeight: 52)
    }
}

// MARK: - Step scaffold

/// Progress header + scrolling content + pinned 56 pt CTA inset above the home indicator.
struct OBStepScaffold<Content: View, Footer: View>: View {
    var index: Int
    var count: Int
    var onBack: () -> Void
    @ViewBuilder var content: () -> Content
    @ViewBuilder var footer: () -> Footer

    var body: some View {
        VStack(spacing: 0) {
            OBProgressHeader(index: index, count: count, onBack: onBack)
            ScrollView {
                content()
                    .padding(.bottom, 24)
            }
            .scrollDismissesKeyboard(.interactively)
            .scrollIndicators(.hidden)
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            footer()
                .padding(.horizontal, NT.Spacing.screenH)
                .padding(.top, 12)
                .padding(.bottom, 8)
                .background(NT.Colors.ground)
        }
        .ntScreenBackground()
    }
}

/// Title + subtitle block at the top of every step.
struct OBStepTitle: View {
    var title: LocalizedStringKey
    var subtitle: LocalizedStringKey

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(NT.Fonts.title1).foregroundStyle(NT.Colors.ink)
            Text(subtitle).font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, NT.Spacing.screenH)
        .padding(.top, 28)
    }
}
