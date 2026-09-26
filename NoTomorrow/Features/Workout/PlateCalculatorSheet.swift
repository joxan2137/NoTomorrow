import SwiftUI

/// Plate calculator for one set's weight (keyboard toolbar "Plates" in the active workout): bar choice, a drawing of
/// the loaded bar, the plates per side, and, when the plates cannot make the weight, the closest load with Use.
struct PlateCalculatorSheet: View {
    let weightKg: Double
    let unit: WeightUnit
    /// Replaces the set's weight (kg) with a load the plates can make.
    var onUse: (Double) -> Void

    @Environment(\.dismiss) private var dismiss
    @AppStorage("nt.plates.barKg") private var barKg: Double = 20
    @AppStorage("nt.plates.barLb") private var barLb: Double = 45
    @State private var showsOneRepMax = false

    private var target: Double { SetInput.display(weightKg, unit: unit) }
    private var bar: Double { unit == .kg ? barKg : barLb }
    private var load: PlateMath.Load { PlateMath.load(target: target, bar: bar, plates: PlateMath.plates(for: unit),
                                                                  limit: PlateMath.maxTarget(for: unit)) }

    var body: some View {
        let load = load
        VStack(alignment: .leading, spacing: 0) {
            header
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text(Fmt.plate(target, unit: unit))
                        .font(NT.Fonts.display(44)).foregroundStyle(NT.Colors.ink).tabular()
                    barPicker.padding(.top, 16)
                    BarbellDrawing(perSide: load.perSide, unit: unit)
                        .frame(height: 120)
                        .padding(.top, 20)
                        .accessibilityHidden(true)
                    perSideList(load).padding(.top, 20)
                    if load.isOverMax {
                        note("plates.overMax \(Fmt.plate(PlateMath.maxTarget(for: unit), unit: unit))")
                    } else if load.isBelowBar {
                        note("plates.belowBar")
                    } else if !load.isExact {
                        closest(load)
                    }
                    GhostButton(title: "onerm.calculator", systemImage: "percent") { showsOneRepMax = true }
                        .padding(.top, NT.Spacing.section)
                }
                .padding(.horizontal, NT.Spacing.screenH)
                .padding(.bottom, NT.Spacing.section)
            }
        }
        .ntScreenBackground()
        .presentationBackground(NT.Colors.ground)
        .presentationDragIndicator(.visible)
        .presentationDetents([.medium, .large])
        .sheet(isPresented: $showsOneRepMax) { OneRepMaxCalculatorSheet(unit: unit, initialWeight: target) }
    }

    private var header: some View {
        HStack {
            Text("plates.title").font(NT.Fonts.title2).foregroundStyle(NT.Colors.ink)
            Spacer()
            Button { dismiss() } label: {
                Text("common.done").font(NT.Fonts.body).foregroundStyle(NT.Colors.ink2)
                    .frame(minHeight: NT.Size.control)
            }
        }
        .padding(.horizontal, NT.Spacing.screenH)
        .padding(.top, 16)
        .padding(.bottom, 8)
    }

    private var barPicker: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("plates.bar").eyebrow()
            HStack(spacing: 8) {
                ForEach(PlateMath.bars(for: unit), id: \.self) { value in
                    Chip(title: Fmt.plate(value, unit: unit), isSelected: value == bar) {
                        if unit == .kg { barKg = value } else { barLb = value }
                    }
                }
            }
        }
    }

    private func perSideList(_ load: PlateMath.Load) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("plates.perSide").eyebrow()
            if load.perSide.isEmpty {
                Text("plates.emptyBar").font(NT.Fonts.body).foregroundStyle(NT.Colors.ink2)
            } else {
                VStack(spacing: 0) {
                    ForEach(Array(load.groups.enumerated()), id: \.offset) { index, group in
                        if index > 0 { Hairline() }
                        HStack {
                            Circle().fill(PlateStyle.color(group.plate, unit: unit)).frame(width: 10, height: 10)
                                .accessibilityHidden(true)
                            Text(Fmt.plate(group.plate, unit: unit))
                                .font(NT.Fonts.body).foregroundStyle(NT.Colors.ink).tabular()
                            Spacer()
                            Text(verbatim: "× \(group.count)")
                                .font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink).tabular()
                        }
                        .frame(minHeight: NT.Size.control)
                        .accessibilityElement(children: .combine)
                    }
                }
                .padding(.horizontal, 16)
                .background(NT.Colors.surface, in: RoundedRectangle(cornerRadius: NT.Radius.tile, style: .continuous))
            }
        }
    }

    private func closest(_ load: PlateMath.Load) -> some View {
        let label = Fmt.plate(load.total, unit: unit)
        return VStack(alignment: .leading, spacing: 12) {
            note("plates.notExact \(label)")
            SecondaryButton(title: "plates.use \(label)") {
                onUse(SetInput.kg(fromDisplay: load.total, unit: unit))
                dismiss()
            }
        }
    }

    private func note(_ key: LocalizedStringKey) -> some View {
        Text(key)
            .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.top, 16)
    }
}

// MARK: - Drawing

/// A barbell seen from the front: the sleeve on each side with its plates, heaviest nearest the middle,
/// taller for heavier plates, in the usual competition colours.
private struct BarbellDrawing: View {
    let perSide: [Double]
    let unit: WeightUnit

    var body: some View {
        GeometryReader { geo in
            let midY = geo.size.height / 2
            let maxPlate = PlateMath.plates(for: unit).first ?? 1
            ZStack {
                // Shaft and sleeves.
                Capsule().fill(NT.Colors.surface3)
                    .frame(width: geo.size.width, height: 8)
                    .position(x: geo.size.width / 2, y: midY)
                Rectangle().fill(NT.Colors.ink3)
                    .frame(width: 6, height: 26)
                    .position(x: geo.size.width * 0.3, y: midY)
                Rectangle().fill(NT.Colors.ink3)
                    .frame(width: 6, height: 26)
                    .position(x: geo.size.width * 0.7, y: midY)
                side(width: geo.size.width, midY: midY, height: geo.size.height, maxPlate: maxPlate, leading: true)
                side(width: geo.size.width, midY: midY, height: geo.size.height, maxPlate: maxPlate, leading: false)
            }
        }
    }

    private func side(width: CGFloat, midY: CGFloat, height: CGFloat, maxPlate: Double, leading: Bool) -> some View {
        let collarX = leading ? width * 0.3 : width * 0.7
        let room = width * 0.3 - 4
        let plateWidth = min(16, max(5, room / CGFloat(max(perSide.count, 1)) - 2))
        return ForEach(Array(perSide.enumerated()), id: \.offset) { index, plate in
            let offset = 6 + (plateWidth + 2) * CGFloat(index) + plateWidth / 2
            let ratio = CGFloat(max(0.35, sqrt(plate / maxPlate)))
            RoundedRectangle(cornerRadius: 3, style: .continuous)
                .fill(PlateStyle.color(plate, unit: unit))
                .overlay(RoundedRectangle(cornerRadius: 3, style: .continuous).strokeBorder(.black.opacity(0.35), lineWidth: 1))
                .frame(width: plateWidth, height: height * ratio)
                .position(x: leading ? collarX - offset : collarX + offset, y: midY)
        }
    }
}

/// Competition plate colours by weight (red 25 · blue 20 · yellow 15 · green 10 · white 5 · then small plates).
enum PlateStyle {
    static func color(_ plate: Double, unit: WeightUnit) -> Color {
        let kg = unit == .kg ? plate : plate / Fmt.lbPerKg
        switch kg {
        case 24...: return Color(hex: 0xE5484D)
        case 19..<24: return Color(hex: 0x3E7BFA)
        case 14..<19: return Color(hex: 0xF5C04A)
        case 9..<14: return Color(hex: 0x30A46C)
        case 4..<9: return Color(hex: 0xEDEDED)
        case 2..<4: return Color(hex: 0x6E6E73)
        default: return Color(hex: 0xB8B8BD)
        }
    }
}
