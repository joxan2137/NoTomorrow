import SwiftUI
import SwiftData
import UIKit

/// "Import routine" from the Train tab: paste the text a friend shared ("Share routine", `RoutineShare`), see the
/// routine's exercises (library matches, and the ones that become custom exercises marked New), and "Add routine",
/// which writes it through `RoutineStore.addShared` with a unique name. The clipboard is read only when Paste is
/// tapped, so the system paste banner never shows on its own.
struct RoutineImportSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var context
    @State private var text = ""
    @State private var catalog = RoutineShare.Catalog()
    @FocusState private var editorFocused: Bool

    /// The routine in the text and its lines matched to the library; nil when the text has none.
    private var parsed: (shared: RoutineShare.Shared, items: [RoutineShare.Planned])? {
        guard let shared = RoutineShare.decode(text) else { return nil }
        let items = RoutineShare.plan(shared, catalog: catalog)
        return items.isEmpty ? nil : (shared, items)
    }

    var body: some View {
        let result = parsed
        VStack(spacing: 0) {
            header
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text("routine.import.intro")
                        .font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2)
                        .fixedSize(horizontal: false, vertical: true)
                    editor
                        .padding(.top, 14)
                    pasteRow
                        .padding(.top, 8)
                    if let result {
                        preview(result.shared, items: result.items)
                    } else if !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        Text("routine.import.invalid")
                            .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ember)
                            .padding(.top, 12)
                    }
                }
                .padding(.horizontal, NT.Spacing.screenH)
                .padding(.bottom, NT.Spacing.section)
            }
            .scrollDismissesKeyboard(.interactively)
            PrimaryButton(title: "routine.import.add", isEnabled: result != nil) {
                guard let result else { return }
                RoutineStore.addShared(name: result.shared.name, items: result.items,
                                       fallbackName: String(localized: "routine.import.defaultName"), in: context)
                Haptics.tap()
                dismiss()
            }
            .padding(.horizontal, NT.Spacing.screenH)
            .padding(.vertical, 8)
            .background(NT.Colors.ground)
        }
        .ntScreenBackground()
        .presentationBackground(NT.Colors.ground)
        .presentationDragIndicator(.visible)
        .onAppear { catalog = RoutineStore.shareCatalog(in: context) }
    }

    // MARK: Header

    /// Cancel · Import routine.
    private var header: some View {
        ZStack {
            Text("routine.import")
                .font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink).lineLimit(1)
            HStack {
                Button { dismiss() } label: {
                    Text("common.cancel").font(NT.Fonts.body).foregroundStyle(NT.Colors.ink2)
                        .frame(minHeight: NT.Size.control)
                }
                Spacer()
            }
        }
        .padding(.horizontal, NT.Spacing.screenH)
        .padding(.top, 16)
        .padding(.bottom, 12)
    }

    // MARK: Text

    private var editor: some View {
        ZStack(alignment: .topLeading) {
            if text.isEmpty {
                Text("routine.import.placeholder")
                    .font(NT.Fonts.body).foregroundStyle(NT.Colors.ink3)
                    .padding(.horizontal, 5)
                    .padding(.vertical, 8)
                    .allowsHitTesting(false)
            }
            TextEditor(text: $text)
                .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink)
                .scrollContentBackground(.hidden)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .focused($editorFocused)
        }
        .frame(height: 150)
        .padding(10)
        .background(NT.Colors.surface, in: RoundedRectangle(cornerRadius: NT.Radius.tile, style: .continuous))
    }

    /// Paste (replaces the text with the clipboard's) · Clear.
    private var pasteRow: some View {
        HStack(spacing: 16) {
            Button(action: paste) {
                Label("routine.import.paste", systemImage: "doc.on.clipboard")
                    .font(NT.Fonts.subheadlineBold).foregroundStyle(NT.Colors.ink)
                    .frame(minHeight: NT.Size.control)
            }
            .buttonStyle(.plain)
            if !text.isEmpty {
                Button {
                    text = ""
                } label: {
                    Text("routine.import.clear")
                        .font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2)
                        .frame(minHeight: NT.Size.control)
                }
                .buttonStyle(.plain)
            }
            Spacer(minLength: 0)
        }
    }

    /// Only on the user's tap: reading `UIPasteboard` shows the system's paste permission, which is expected here.
    private func paste() {
        guard let string = UIPasteboard.general.string, !string.isEmpty else { return }
        editorFocused = false
        text = string
    }

    // MARK: Preview

    private func preview(_ shared: RoutineShare.Shared, items: [RoutineShare.Planned]) -> some View {
        let letters = Superset.letters(items.map(\.supersetGroup))
        let name = shared.name.isEmpty ? String(localized: "routine.import.defaultName") : shared.name
        return VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(verbatim: name)
                    .font(NT.Fonts.title2).foregroundStyle(NT.Colors.ink)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 8)
                Text(verbatim: WorkoutStrings.exercises(items.count))
                    .font(NT.Fonts.caption).foregroundStyle(NT.Colors.ink2).tabular()
            }
            .padding(.top, NT.Spacing.section)
            .padding(.bottom, 4)
            ForEach(items) { item in
                if item.index > 0 { Hairline() }
                row(item, letter: letters[item.index])
            }
        }
    }

    /// "Bench Press  [New] ······ 3 × 8  2:00", with the superset tag above the name.
    private func row(_ item: RoutineShare.Planned, letter: String?) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                if let letter { SupersetTag(letter: letter) }
                HStack(spacing: 8) {
                    Text(verbatim: item.name)
                        .font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink).lineLimit(1)
                    if item.isNew { Badge(text: "routine.import.newExercise") }
                }
            }
            Spacer(minLength: 8)
            Text(verbatim: "\(item.sets) × \(item.reps)")
                .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2).tabular()
            Text(verbatim: RoutineRest.label(item.restSeconds))
                .font(NT.Fonts.caption).foregroundStyle(NT.Colors.ink3).tabular()
                .frame(minWidth: 34, alignment: .trailing)
        }
        .padding(.vertical, 10)
    }
}
