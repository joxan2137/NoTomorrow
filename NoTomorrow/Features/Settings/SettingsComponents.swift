import SwiftUI

// Building blocks for the Settings screens. Prefixed ST to stay out of the design system's way.

// MARK: - Group

/// Eyebrow header (16 pt inset) + surface card, 16 pt radius, rows separated by hairlines.
struct STGroup<Content: View>: View {
    var title: LocalizedStringKey? = nil
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let title {
                Text(title).eyebrow().padding(.leading, 16)
            }
            _VariadicView.Tree(STGroupLayout()) { content() }
                .padding(.horizontal, 16)
                .background(NT.Colors.surface, in: RoundedRectangle(cornerRadius: NT.Radius.tile, style: .continuous))
        }
    }
}

private struct STGroupLayout: _VariadicView_UnaryViewRoot {
    @ViewBuilder
    func body(children: _VariadicView.Children) -> some View {
        let last = children.last?.id
        VStack(spacing: 0) {
            ForEach(children) { child in
                child
                if child.id != last { Hairline() }
            }
        }
    }
}

// MARK: - Rows

/// 44 pt row that pushes an editor.
struct STLinkRow<Destination: Hashable>: View {
    var label: LocalizedStringKey
    var value: String
    var destination: Destination
    var dot: Color? = nil
    var labelColor: Color = NT.Colors.ink

    var body: some View {
        NavigationLink(value: destination) {
            HStack(spacing: 12) {
                Text(label).font(NT.Fonts.body).foregroundStyle(labelColor)
                Spacer(minLength: 8)
                if let dot { Circle().fill(dot).frame(width: 8, height: 8) }
                Text(value).font(NT.Fonts.body).foregroundStyle(NT.Colors.ink2).tabular().lineLimit(1)
                Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundStyle(NT.Colors.ink3)
            }
            .frame(minHeight: NT.Size.control)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// 44 pt row that runs an action (sign out, delete).
struct STActionRow: View {
    var label: LocalizedStringKey
    var value: String? = nil
    var color: Color = NT.Colors.ink
    var isBusy: Bool = false
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Text(label).font(NT.Fonts.body).foregroundStyle(color)
                Spacer(minLength: 8)
                if isBusy {
                    ProgressView().tint(NT.Colors.ink2)
                } else if let value {
                    Text(value).font(NT.Fonts.body).foregroundStyle(NT.Colors.ink2).lineLimit(1)
                }
            }
            .frame(minHeight: NT.Size.control)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(isBusy)
    }
}

/// 44 pt row with a trailing toggle.
struct STToggleRow: View {
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
            Toggle("", isOn: $isOn).labelsHidden().tint(NT.Colors.ink)
        }
        .frame(minHeight: detail == nil ? NT.Size.control : 56)
        .padding(.vertical, detail == nil ? 0 : 4)
    }
}

/// 44 pt row with a trailing checkmark; tapping selects.
struct STCheckRow: View {
    var title: LocalizedStringKey
    var detail: LocalizedStringKey? = nil
    var isSelected: Bool
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 1) {
                    Text(title).font(NT.Fonts.body).foregroundStyle(NT.Colors.ink)
                    if let detail {
                        Text(detail).font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2)
                    }
                }
                Spacer(minLength: 8)
                Image(systemName: "checkmark")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(NT.Colors.ink)
                    .opacity(isSelected ? 1 : 0)
            }
            .frame(minHeight: detail == nil ? NT.Size.control : 56)
            .padding(.vertical, detail == nil ? 0 : 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// Plain label · value row (no chevron).
struct STInfoRow: View {
    var label: LocalizedStringKey
    var value: String
    var dot: Color? = nil

    var body: some View {
        HStack(spacing: 12) {
            Text(label).font(NT.Fonts.body).foregroundStyle(NT.Colors.ink)
            Spacer(minLength: 8)
            if let dot { Circle().fill(dot).frame(width: 8, height: 8) }
            Text(value).font(NT.Fonts.body).foregroundStyle(NT.Colors.ink2).tabular().lineLimit(1)
        }
        .frame(minHeight: NT.Size.control)
    }
}

// MARK: - Segmented

/// Surface track with a surface-3 thumb, 44 pt segments.
struct STSegmented<Value: Hashable>: View {
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
struct STFieldChrome: ViewModifier {
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
    func stField(isFocused: Bool) -> some View { modifier(STFieldChrome(isFocused: isFocused)) }

    /// Eyebrow label above a field.
    func stLabeled(_ key: LocalizedStringKey) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(key).eyebrow()
            self
        }
    }

    /// Footnote in ink2 under a group, inset like the eyebrow.
    func stFootnote(_ key: LocalizedStringKey) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            self
            Text(key)
                .font(NT.Fonts.footnote)
                .foregroundStyle(NT.Colors.ink2)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 16)
        }
    }
}

// MARK: - Editor scaffold

/// Ground background, inline title, scrolling content with screen padding.
struct STEditorScreen<Content: View>: View {
    var title: LocalizedStringKey
    @ViewBuilder var content: () -> Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                content()
            }
            .padding(.horizontal, NT.Spacing.screenH)
            .padding(.top, 12)
            .padding(.bottom, 32)
        }
        .scrollDismissesKeyboard(.interactively)
        .scrollIndicators(.hidden)
        .ntScreenBackground()
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(NT.Colors.ground, for: .navigationBar)
    }
}

// MARK: - Number parsing

enum STNumber {
    /// "82,4" or "82.4" → 82.4
    static func parse(_ text: String) -> Double? {
        let cleaned = text.replacingOccurrences(of: ",", with: ".").replacingOccurrences(of: " ", with: "")
            .trimmingCharacters(in: .whitespaces)
        guard let value = Double(cleaned), value.isFinite, value >= 0 else { return nil }
        return value
    }

    static func parseInt(_ text: String) -> Int? {
        parse(text).map { Int($0.rounded()) }
    }
}
