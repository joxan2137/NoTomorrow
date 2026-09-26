import SwiftUI

// MARK: - Buttons

/// White pill, black label. One per screen, the thing the user came to do.
struct PrimaryButton: View {
    var title: LocalizedStringKey
    var systemImage: String? = nil
    var height: CGFloat = NT.Size.primaryButton
    var isEnabled: Bool = true
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let systemImage { Image(systemName: systemImage).font(.system(size: 16, weight: .semibold)) }
                Text(title).font(NT.Fonts.headline).lineLimit(1)
            }
            .foregroundStyle(NT.Colors.onPrimary)
            .frame(maxWidth: .infinity)
            .frame(height: height)
            .padding(.horizontal, 16)
            .background(NT.Colors.ink, in: Capsule())
            .opacity(isEnabled ? 1 : 0.4)
        }
        .buttonStyle(PressScale())
        .disabled(!isEnabled)
    }
}

/// Surface-2 pill, ink label. The other thing.
struct SecondaryButton: View {
    var title: LocalizedStringKey
    var systemImage: String? = nil
    var height: CGFloat = NT.Size.primaryButton
    var tint: Color = NT.Colors.ink
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let systemImage { Image(systemName: systemImage).font(.system(size: 16, weight: .semibold)) }
                Text(title).font(NT.Fonts.headline).lineLimit(1)
            }
            .foregroundStyle(tint)
            .frame(maxWidth: .infinity)
            .frame(height: height)
            .padding(.horizontal, 16)
            .background(NT.Colors.surface2, in: Capsule())
        }
        .buttonStyle(PressScale())
    }
}

/// Hairline outlined pill for tertiary actions ("Add exercise").
struct GhostButton: View {
    var title: LocalizedStringKey
    var systemImage: String? = nil
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let systemImage { Image(systemName: systemImage).font(.system(size: 14, weight: .bold)) }
                Text(title).font(NT.Fonts.subheadlineBold)
            }
            .foregroundStyle(NT.Colors.ink)
            .frame(maxWidth: .infinity)
            .frame(height: NT.Size.control)
            .background(Capsule().strokeBorder(NT.Colors.border, lineWidth: 1))
        }
        .buttonStyle(PressScale())
    }
}

struct PressScale: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .opacity(configuration.isPressed ? 0.9 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

// MARK: - Containers

/// Elevated surface card, 22pt radius. Reserve for the one object that needs lifting.
struct NTCard<Content: View>: View {
    var padding: CGFloat = NT.Spacing.cardPadding
    @ViewBuilder var content: () -> Content

    var body: some View {
        content()
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(NT.Colors.surface, in: RoundedRectangle(cornerRadius: NT.Radius.card, style: .continuous))
    }
}

/// The one card per screen that holds what the user came to do (v2): the surface with a faint ember wash from the
/// top-trailing corner and an ember hairline. Everything else on the screen stays on plain `NTCard`s or none.
struct FocalCard<Content: View>: View {
    var padding: CGFloat = NT.Spacing.cardPadding
    @ViewBuilder var content: () -> Content

    private var shape: RoundedRectangle { RoundedRectangle(cornerRadius: NT.Radius.card, style: .continuous) }

    var body: some View {
        content()
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background {
                ZStack {
                    NT.Colors.surface
                    RadialGradient(colors: [NT.Colors.ember.opacity(0.13), NT.Colors.ember.opacity(0)],
                                   center: .topTrailing, startRadius: 0, endRadius: 320)
                }
            }
            .clipShape(shape)
            .overlay(shape.strokeBorder(NT.Colors.ember.opacity(0.12), lineWidth: 1))
    }
}

/// Small stat tile (label + value).
struct StatTile: View {
    var label: LocalizedStringKey
    var value: String
    var valueColor: Color = NT.Colors.ink

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).eyebrow()
            // Shrinks rather than truncates: three tiles in a row leave ~77 pt for "1 h 12 min" on a 375 pt phone.
            Text(value).font(NT.Fonts.headline).foregroundStyle(valueColor).tabular().lineLimit(1)
                .minimumScaleFactor(0.6).allowsTightening(true)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(NT.Colors.surface, in: RoundedRectangle(cornerRadius: NT.Radius.tile, style: .continuous))
    }
}

struct Hairline: View {
    var body: some View {
        Rectangle().fill(NT.Colors.hairline).frame(height: 1)
    }
}

/// Section header row: title left, optional trailing text.
struct SectionHeader: View {
    var title: LocalizedStringKey
    var trailing: Text? = nil

    var body: some View {
        HStack {
            Text(title).font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink)
            Spacer()
            if let trailing { trailing.font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2) }
        }
    }
}

// MARK: - Chips

struct Chip: View {
    var title: String
    var isSelected: Bool = false
    var systemImage: String? = nil
    var tint: Color? = nil
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let systemImage {
                    Image(systemName: systemImage)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(tint ?? (isSelected ? NT.Colors.onPrimary : NT.Colors.ink))
                }
                Text(title)
                    .font(isSelected ? NT.Fonts.subheadlineBold : NT.Fonts.subheadline)
                    .foregroundStyle(isSelected ? NT.Colors.onPrimary : NT.Colors.ink)
                    .lineLimit(1)
            }
            .padding(.horizontal, 16)
            .frame(height: NT.Size.chip)
            .background(isSelected ? NT.Colors.ink : NT.Colors.surface, in: Capsule())
            .overlay(Capsule().strokeBorder(tint?.opacity(0.4) ?? .clear, lineWidth: 1))
        }
        .buttonStyle(PressScale())
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

/// Small tinted badge chip (PR, "guess").
struct Badge: View {
    var text: LocalizedStringKey
    var color: Color = NT.Colors.ember
    var body: some View {
        Text(text)
            .font(NT.Fonts.caption)
            .foregroundStyle(color)
            .padding(.horizontal, 8)
            .frame(height: 22)
            .background(color.opacity(0.12), in: RoundedRectangle(cornerRadius: 6, style: .continuous))
    }
}

// MARK: - Rings & bars

struct ProgressRing: View {
    var progress: Double          // 0...1
    var lineWidth: CGFloat = 6
    var color: Color = NT.Colors.ember
    var track: Color = NT.Colors.surface2

    var body: some View {
        ZStack {
            Circle().stroke(track, lineWidth: lineWidth)
            Circle()
                .trim(from: 0, to: max(0, min(1, progress)))
                .stroke(color, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .animation(.easeOut(duration: 0.6), value: progress)
        }
    }
}

/// The kcal ring split by where the calories came from (v2): protein, carbs and fat arcs in their own hues, each
/// sized by its share of the kcal goal (4 / 4 / 9 kcal per gram), clockwise from 12 o'clock with a small gap between
/// arcs. Past the goal the arcs scale down together so the ring reads full rather than wrapping.
struct MacroRing: View {
    var protein: Double
    var carbs: Double
    var fat: Double
    var kcalGoal: Double
    var lineWidth: CGFloat = 6
    var track: Color = NT.Colors.surface2

    struct Arc: Equatable {
        var start: Double
        var end: Double
        var colorIndex: Int
    }

    /// Arcs as trim fractions. `gap` is the fraction of the circumference left clear between arcs, measured between
    /// the round caps.
    static func arcs(protein: Double, carbs: Double, fat: Double, kcalGoal: Double, gap: Double) -> [Arc] {
        guard kcalGoal > 0 else { return [] }
        let shares = [protein * 4, carbs * 4, fat * 9].map { max(0, $0) / kcalGoal }
        let total = shares.reduce(0, +)
        let scale = total > 1 ? 1 / total : 1
        var arcs: [Arc] = []
        var cursor = 0.0
        for (index, share) in shares.enumerated() {
            let length = share * scale
            if length > gap {
                arcs.append(Arc(start: cursor, end: cursor + length - gap, colorIndex: index))
            }
            cursor += length
        }
        return arcs
    }

    private static let colors = [NT.Colors.protein, NT.Colors.carbs, NT.Colors.fat]

    var body: some View {
        GeometryReader { geo in
            let circumference = Double.pi * min(geo.size.width, geo.size.height)
            let gap = circumference > 0 ? Double(lineWidth + 3) / circumference : 0
            let arcs = Self.arcs(protein: protein, carbs: carbs, fat: fat, kcalGoal: kcalGoal, gap: gap)
            ZStack {
                Circle().stroke(track, lineWidth: lineWidth)
                ForEach(arcs.indices, id: \.self) { i in
                    Circle()
                        .trim(from: arcs[i].start, to: arcs[i].end)
                        .stroke(Self.colors[arcs[i].colorIndex], style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                }
            }
            .frame(width: geo.size.width, height: geo.size.height)
            .animation(.easeOut(duration: 0.6), value: arcs)
        }
    }
}

struct MacroBar: View {
    var label: LocalizedStringKey
    var value: Double
    var goal: Double
    var unit: String = "g"
    var fill: Color = NT.Colors.ink2

    var body: some View {
        VStack(spacing: 4) {
            HStack {
                Text(label).font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2)
                Spacer()
                Text("\(Int(value.rounded())) / \(Int(goal.rounded())) \(unit)")
                    .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink).tabular()
            }
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(NT.Colors.surface2)
                    Capsule().fill(fill)
                        .frame(width: goal > 0 ? geo.size.width * min(1, value / goal) : 0)
                }
            }
            .frame(height: 4)
        }
    }
}

// MARK: - Avatar

struct Avatar: View {
    var initial: String
    var size: CGFloat = 38
    var background: Color = NT.Colors.surface2
    var dimmed: Bool = false

    var body: some View {
        Text(initial.prefix(1).uppercased())
            .font(size >= 36 ? NT.Fonts.headline : NT.Fonts.caption)
            .foregroundStyle(dimmed ? NT.Colors.ink3 : NT.Colors.ink)
            .frame(width: size, height: size)
            .background(background, in: Circle())
    }
}

// MARK: - Sheet grabber

struct Grabber: View {
    var body: some View {
        Capsule().fill(NT.Colors.ink3).frame(width: 36, height: 5).padding(.top, 8)
    }
}

// MARK: - Row

/// Settings-style list row: label, value, chevron.
struct ValueRow: View {
    var label: LocalizedStringKey
    var value: String
    var valueColor: Color = NT.Colors.ink2
    var showsChevron: Bool = true
    var leading: AnyView? = nil

    var body: some View {
        HStack(spacing: 12) {
            if let leading { leading }
            Text(label).font(NT.Fonts.body).foregroundStyle(NT.Colors.ink)
            Spacer(minLength: 8)
            Text(value).font(NT.Fonts.body).foregroundStyle(valueColor).tabular().lineLimit(1)
            if showsChevron {
                Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundStyle(NT.Colors.ink3)
            }
        }
        .frame(minHeight: NT.Size.control)
        .contentShape(Rectangle())
    }
}
