import SwiftUI

/// Identifies one numeric cell for the shared keyboard focus. `setID` is the set's `PersistentIdentifier` in the
/// active workout and the draft row's UUID in the workout editor.
struct SetField: Hashable {
    var setID: AnyHashable
    var isReps: Bool
}

/// Text ↔ number for the set cells. Weights are stored in kg and typed / shown in the user's unit.
enum SetInput {
    /// kg → the number shown in `unit`.
    static func display(_ kg: Double, unit: WeightUnit) -> Double {
        unit == .kg ? kg : kg * Fmt.lbPerKg
    }

    /// A number typed in `unit` → kg.
    static func kg(fromDisplay value: Double, unit: WeightUnit) -> Double {
        unit == .kg ? value : value / Fmt.lbPerKg
    }

    /// Cell text for a weight: up to two decimals ("81,25", plates come in 1.25), empty for none.
    static func text(weightKg: Double, unit: WeightUnit) -> String {
        guard weightKg > 0 else { return "" }
        return display(weightKg, unit: unit)
            .formatted(.number.precision(.fractionLength(0...2)).grouping(.never).locale(Fmt.locale))
    }

    static func text(reps: Int) -> String { reps > 0 ? "\(reps)" : "" }

    /// Lenient parse of what was typed: "82,5", "82.5", "1 000". Anything else is 0.
    static func number(_ text: String) -> Double {
        let cleaned = text.replacingOccurrences(of: ",", with: ".")
            .replacingOccurrences(of: "\u{00A0}", with: "")
            .replacingOccurrences(of: "\u{202F}", with: "")
            .replacingOccurrences(of: " ", with: "")
        guard let value = Double(cleaned), value.isFinite, value > 0 else { return 0 }
        return value
    }

    /// Typed weight in `unit` → kg to store.
    static func weightKg(_ text: String, unit: WeightUnit) -> Double {
        kg(fromDisplay: min(number(text), 10_000), unit: unit)
    }

    static func reps(_ text: String) -> Int {
        Int(min(number(text), 9_999).rounded())
    }
}

// MARK: - Cells

/// The Set column: the row number (warm-ups don't count) or a W / D / F glyph, opening the set-kind menu.
struct SetKindMenu: View {
    let kind: SetKind
    let number: Int
    var onKind: (SetKind) -> Void
    /// When set, the menu ends with a destructive "Delete set".
    var onDelete: (() -> Void)? = nil

    var body: some View {
        Menu {
            Button("workout.warmup") { onKind(.warmup) }
            Button("workout.dropset") { onKind(.drop) }
            Button("workout.failure") { onKind(.failure) }
            Button("workout.normalSet") { onKind(.normal) }
            if let onDelete {
                Divider()
                Button("workout.edit.deleteSet", role: .destructive, action: onDelete)
            }
        } label: {
            Group {
                if kind == .normal {
                    Text("\(number)")
                        .font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink).tabular()
                } else {
                    Text(verbatim: Self.letter(for: kind))
                        .font(NT.Fonts.caption).foregroundStyle(NT.Colors.ink2)
                        .frame(width: 24, height: 24)
                        .background(NT.Colors.surface2, in: Circle())
                }
            }
            .frame(width: 36, height: NT.Size.control)
            .contentShape(Rectangle())
        }
        .menuIndicator(.hidden)
    }

    /// W / D / F for warm-up, drop and failure sets (the same glyphs in the table and the detail sheet).
    static func letter(for kind: SetKind) -> String {
        switch kind {
        case .warmup: "W"
        case .drop: "D"
        case .failure: "F"
        case .normal: ""
        }
    }
}

/// A 60 × 44 numeric cell. `text` is the stored value formatted for display. Only the user's own edits reach
/// `onEdit`, compared in display space, so a number shown rounded (or converted to lb) is never written back.
struct SetNumberCell: View {
    let text: String
    let field: SetField
    var focus: FocusState<SetField?>.Binding
    var keyboard: UIKeyboardType
    /// The row the user is on: a faint border even without focus.
    var isCurrent: Bool = false
    var isEnabled: Bool = true
    var onEdit: (String) -> Void

    @State private var typed = ""

    var body: some View {
        let isFocused = focus.wrappedValue == field
        let border: Color = isFocused ? NT.Colors.ink : (isCurrent ? NT.Colors.border : .clear)
        TextField("", text: $typed)
            .font(NT.Fonts.body).foregroundStyle(NT.Colors.ink).tabular()
            .multilineTextAlignment(.center)
            .keyboardType(keyboard)
            .focused(focus, equals: field)
            .disabled(!isEnabled)
            .frame(width: 60, height: NT.Size.control)
            .background(NT.Colors.surface2, in: RoundedRectangle(cornerRadius: NT.Radius.cell, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: NT.Radius.cell, style: .continuous).strokeBorder(border, lineWidth: 1.5))
            .onAppear { typed = text }
            .onChange(of: text) { _, new in
                if SetInput.number(typed) != SetInput.number(new) { typed = new }
            }
            .onChange(of: typed) { _, new in
                if SetInput.number(new) != SetInput.number(text) { onEdit(new) }
            }
    }
}

/// The ✓ column: an ink disc when done, an outlined circle when not.
struct SetCheckButton: View {
    let isOn: Bool
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                if isOn {
                    Circle().fill(NT.Colors.ink)
                    Image(systemName: "checkmark")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(NT.Colors.onPrimary)
                } else {
                    Circle().strokeBorder(NT.Colors.ink3, lineWidth: 1.5)
                }
            }
            .frame(width: 28, height: 28)
            .frame(width: 48, height: NT.Size.control)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// "+ Add set" under a set table.
struct AddSetButton: View {
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Image(systemName: "plus").font(.system(size: 14, weight: .semibold))
                Text("workout.addSet").font(NT.Fonts.subheadline)
            }
            .foregroundStyle(NT.Colors.ink2)
            .frame(height: 32)
            .frame(minWidth: NT.Size.control, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// Set · Previous · kg (or lb) · Reps · ✓ column titles. The editor leaves the Previous column blank.
struct SetColumnHeader: View {
    var unit: WeightUnit
    var showsPrevious: Bool = true

    var body: some View {
        HStack(spacing: 8) {
            Text("workout.set").frame(width: 36)
            if showsPrevious {
                Text("workout.previous").frame(maxWidth: .infinity)
            } else {
                Color.clear.frame(maxWidth: .infinity, maxHeight: 1)
            }
            Text(verbatim: unit.rawValue).frame(width: 60)
            Text("workout.reps").frame(width: 60)
            Color.clear.frame(width: 48, height: 1)
        }
        .font(NT.Fonts.caption).foregroundStyle(NT.Colors.ink2)
        .padding(.top, 4)
    }
}
