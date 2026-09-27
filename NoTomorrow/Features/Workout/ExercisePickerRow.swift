import SwiftUI
import MetalFxKit

/// One search result as a card: the picker row and a details button on a `surface` tile. A selected exercise's tile
/// turns liquid metal — a silver MetalFx edge (`MetalFxKit`, libraries.dev) — so the picks stand out while scrolling.
struct ExerciseResultCard: View {
    var exercise: Exercise
    var unit: WeightUnit
    var state: ExercisePickerRow.State
    /// Starred from the long-press menu: a small star after the name.
    var isFavorite = false
    var onToggle: () -> Void
    var onDetails: () -> Void

    /// Trailing inset of the last-set column inside a card: details button + the 26 pt selection ring + spacing.
    static let lastColumnTrailing: CGFloat = detailsWidth + 26 + 12
    static let detailsWidth: CGFloat = 44

    var body: some View {
        if state == .selected {
            // No tilt bend or glow: several of these can be on screen at once, and the edge alone reads as "picked".
            MetalFx(variant: .button, preset: .silver, theme: .dark, ringWidth: 1,
                    cornerRadius: Double(NT.Radius.tile), glow: false, tilt: false, fill: NT.Colors.surface) {
                content
            }
        } else {
            content
                .background(NT.Colors.surface, in: RoundedRectangle(cornerRadius: NT.Radius.tile, style: .continuous))
        }
    }

    private var content: some View {
        HStack(spacing: 0) {
            ExercisePickerRow(exercise: exercise, unit: unit, state: state, isFavorite: isFavorite, action: onToggle)
                .padding(.leading, 14)
            Button(action: onDetails) {
                Image(systemName: "info.circle")
                    .font(.system(size: 17, weight: .regular))
                    .foregroundStyle(NT.Colors.ink2)
                    .frame(width: ExerciseResultCard.detailsWidth, height: 64)
                    .contentShape(Rectangle())
            }
            .buttonStyle(PressScale())
            .accessibilityLabel(Text("exercises.details"))
        }
    }
}

/// 64 pt picker row: name + muscles, last set (or "Never done"), then a selection ring or "In".
struct ExercisePickerRow: View {
    enum State { case available, selected, alreadyIn }

    var exercise: Exercise
    var unit: WeightUnit = .kg
    var state: State
    var isFavorite = false
    var action: () -> Void

    private var lastSetLabel: String? {
        guard let set = RecordService.lastSet(for: exercise) else { return nil }
        return Fmt.set(set.weightKg, set.reps, unit: unit)
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 5) {
                        Text(exercise.localizedName)
                            .font(NT.Fonts.headline)
                            .foregroundStyle(state == .alreadyIn ? NT.Colors.ink2 : NT.Colors.ink)
                            .lineLimit(1)
                        if isFavorite {
                            Image(systemName: "star.fill")
                                .font(.system(size: 10, weight: .semibold))
                                .foregroundStyle(NT.Colors.ember)
                                .accessibilityLabel(Text("exercises.starred"))
                        }
                    }
                    Text(WorkoutStrings.subtitle(for: exercise))
                        .font(NT.Fonts.footnote)
                        .foregroundStyle(NT.Colors.ink2)
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
                if let lastSetLabel {
                    Text(lastSetLabel)
                        .font(NT.Fonts.footnote)
                        .foregroundStyle(NT.Colors.ink2)
                        .tabular()
                } else {
                    Text("exercises.neverDone")
                        .font(NT.Fonts.footnote)
                        .foregroundStyle(NT.Colors.ink2)
                }
                trailing
                    .frame(width: 26, height: NT.Size.control)
            }
            .frame(height: 64)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressScale())
        .disabled(state == .alreadyIn)
    }

    @ViewBuilder
    private var trailing: some View {
        switch state {
        case .alreadyIn:
            Text("exercises.inWorkout")
                .font(NT.Fonts.footnote)
                .foregroundStyle(NT.Colors.ink3)
        case .selected:
            ZStack {
                Circle().fill(NT.Colors.ink)
                Image(systemName: "checkmark")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(NT.Colors.onPrimary)
            }
            .frame(width: 26, height: 26)
        case .available:
            Circle()
                .strokeBorder(NT.Colors.ink3, lineWidth: 1.5)
                .frame(width: 26, height: 26)
        }
    }
}

/// "+ Create "bench" as a new exercise" — 48 pt row under the results.
struct CreateExerciseRow: View {
    var query: String
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Image(systemName: "plus")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(NT.Colors.ink2)
                Text(WorkoutStrings.create(query))
                    .font(NT.Fonts.subheadline)
                    .foregroundStyle(NT.Colors.ink2)
                    .lineLimit(1)
                Spacer(minLength: 0)
            }
            .frame(height: 48)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressScale())
    }
}
