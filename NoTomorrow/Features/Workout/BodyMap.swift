import SwiftUI

/// The front/back body map from `muscle_model.json` (drawn by `scripts/anatomy/muscle_map.py`): the body
/// underneath ("outline") first, then each muscle's shapes, in a 200 × 300 box with the front figure on the left.
enum BodyMap {
    struct Region {
        let muscle: String
        let view: String
        /// In the model's 200 × 300 units.
        let path: Path
    }

    static let size = CGSize(width: 200, height: 300)

    static let regions: [Region] = ExerciseMedia.regions.map {
        Region(muscle: $0.muscle, view: $0.view, path: SVGPath.parse($0.d))
    }

    /// Every muscle the map can highlight, in the model's order.
    static let muscles: [String] = {
        var seen = Set<String>()
        return regions.map(\.muscle).filter { $0 != "outline" && seen.insert($0).inserted }
    }()

    /// The muscle under `point` (in a map drawn at `size`), topmost shape first; nil over the body or background.
    static func muscle(at point: CGPoint, in size: CGSize) -> String? {
        let unit = CGPoint(x: point.x / size.width * Self.size.width, y: point.y / size.height * Self.size.height)
        return regions.last { $0.muscle != "outline" && $0.path.contains(unit) }?.muscle
    }
}

/// Parses the SVG path data the body map uses: absolute M, L, C and Z.
enum SVGPath {
    static func parse(_ d: String) -> Path {
        var path = Path()
        var numbers: [CGFloat] = []
        var command: Character?
        var token = ""

        func flushNumber() {
            if let value = Double(token) { numbers.append(CGFloat(value)) }
            token = ""
        }
        func apply() {
            guard let command else { return }
            switch command {
            case "M" where numbers.count >= 2:
                path.move(to: CGPoint(x: numbers[0], y: numbers[1]))
                var i = 2
                while i + 1 < numbers.count { path.addLine(to: CGPoint(x: numbers[i], y: numbers[i + 1])); i += 2 }
            case "L":
                var i = 0
                while i + 1 < numbers.count { path.addLine(to: CGPoint(x: numbers[i], y: numbers[i + 1])); i += 2 }
            case "C":
                var i = 0
                while i + 5 < numbers.count {
                    path.addCurve(to: CGPoint(x: numbers[i + 4], y: numbers[i + 5]),
                                  control1: CGPoint(x: numbers[i], y: numbers[i + 1]),
                                  control2: CGPoint(x: numbers[i + 2], y: numbers[i + 3]))
                    i += 6
                }
            case "Z", "z":
                path.closeSubpath()
            default:
                break
            }
            numbers.removeAll()
        }

        for char in d {
            if char.isLetter && char != "e" && char != "E" {
                flushNumber()
                apply()
                command = char
            } else if char == " " || char == "," {
                flushNumber()
            } else if char == "-" && !token.isEmpty && token.last != "e" && token.last != "E" {
                flushNumber()
                token = "-"
            } else {
                token.append(char)
            }
        }
        flushNumber()
        apply()
        return path
    }
}

/// Draws the body map with each muscle filled by `fill`, the body underneath in `body`, and a gap in `gap` between
/// shapes (the colour behind the map, so the gaps read as separations). `selected` gets a light outline.
struct BodyMapView: View {
    var fill: (String) -> Color
    var bodyColor: Color = NT.Colors.surface2
    var gap: Color = NT.Colors.surface
    var selected: String? = nil
    var onTap: ((String?) -> Void)? = nil

    var body: some View {
        GeometryReader { proxy in
            let size = proxy.size
            Canvas { context, canvasSize in
                context.scaleBy(x: canvasSize.width / BodyMap.size.width, y: canvasSize.height / BodyMap.size.height)
                let line = BodyMap.size.width / max(canvasSize.width, 1)
                for region in BodyMap.regions {
                    let color = region.muscle == "outline" ? bodyColor : fill(region.muscle)
                    context.fill(region.path, with: .color(color))
                    context.stroke(region.path, with: .color(gap), style: StrokeStyle(lineWidth: 1.4 * line, lineJoin: .round))
                }
                if let selected {
                    for region in BodyMap.regions where region.muscle == selected {
                        context.stroke(region.path, with: .color(NT.Colors.ink), style: StrokeStyle(lineWidth: 1.6 * line, lineJoin: .round))
                    }
                }
            }
            .contentShape(Rectangle())
            .onTapGesture(coordinateSpace: .local) { point in
                onTap?(BodyMap.muscle(at: point, in: size))
            }
        }
        .aspectRatio(BodyMap.size.width / BodyMap.size.height, contentMode: .fit)
    }
}

/// Exercise sheet: primary muscles in ember, secondary in a darker ember (heat step 2), the rest neutral; tap names a muscle.
struct MuscleModelView: View {
    let primary: [String]
    let secondary: [String]
    @Binding var selected: String?

    static func color(for muscle: String, primary: [String], secondary: [String]) -> Color {
        if primary.contains(muscle) { return NT.Colors.ember }
        if secondary.contains(muscle) { return NT.Colors.heat[2] }
        return NT.Colors.surface3
    }

    var body: some View {
        VStack(spacing: 6) {
            BodyMapView(fill: { Self.color(for: $0, primary: primary, secondary: secondary) },
                        selected: selected,
                        onTap: { muscle in
                            withAnimation(.easeOut(duration: 0.15)) { selected = muscle == selected ? nil : muscle }
                        })
                .frame(maxHeight: 340)
            HStack {
                Text("exercises.front"); Spacer(); Text("exercises.back")
            }
            .font(NT.Fonts.caption).foregroundStyle(NT.Colors.ink3)
            .padding(.horizontal, 48)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(Self.accessibilityText(primary: primary, secondary: secondary)))
    }

    static func accessibilityText(primary: [String], secondary: [String]) -> String {
        var parts: [String] = []
        if !primary.isEmpty {
            parts.append(String(localized: "exercises.primary") + ": " + primary.map(WorkoutStrings.muscle).joined(separator: ", "))
        }
        if !secondary.isEmpty {
            parts.append(String(localized: "exercises.secondary") + ": " + secondary.map(WorkoutStrings.muscle).joined(separator: ", "))
        }
        return parts.joined(separator: ". ")
    }
}

/// The body map with each muscle filled with the `NT.Colors.heat` step for its sets: Progress → "Muscles this week".
/// Untrained muscles stay neutral so the figure still reads. No labels; the card lists the numbers beside it.
struct MuscleHeatView: View {
    let setsByMuscle: [String: Int]

    /// Step of `NT.Colors.heat` for a set count: 0 for none, then 1–3, 4–6, 7–9 and 10+.
    static func level(sets: Int) -> Int {
        switch sets {
        case ...0: return 0
        case 1...3: return 1
        case 4...6: return 2
        case 7...9: return 3
        default: return 4
        }
    }

    var body: some View {
        BodyMapView(fill: { muscle in
            let level = Self.level(sets: setsByMuscle[muscle] ?? 0)
            return level == 0 ? NT.Colors.surface3 : NT.Colors.heat[level]
        })
        .accessibilityHidden(true)
    }
}
