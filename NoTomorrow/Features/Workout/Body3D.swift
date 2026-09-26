import SwiftUI
import UIKit

/// The muscles-worked body: front and back renders of the app's 3D body (`scripts/anatomy/body3d/render_body.py`),
/// plus one glowing layer per muscle and view, cropped to the muscle with its offset in `Body3D/body3d.json`. A
/// muscle is shown by drawing its layers over the neutral body at some strength; a label map per view (muscle i
/// drawn as grey (i + 1) × `labelStep`) answers which muscle is under a finger.
enum Body3D {
    struct Meta: Decodable {
        let size: [Int]
        let labelStep: Int
        let muscles: [String]
        /// muscle → view → [x, y, width, height] in the render's pixels.
        let layers: [String: [String: [Int]]]
    }

    static let views = ["front", "back"]

    static let meta: Meta? = {
        guard let url = Bundle.main.url(forResource: "body3d", withExtension: "json", subdirectory: "Body3D"),
              let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(Meta.self, from: data)
    }()

    /// Every muscle the body can highlight, in the model's order.
    static var muscles: [String] { meta?.muscles ?? [] }

    /// One render's size in pixels (both views share it).
    static var size: CGSize {
        guard let s = meta?.size, s.count == 2 else { return CGSize(width: 520, height: 1000) }
        return CGSize(width: s[0], height: s[1])
    }

    static func frame(of muscle: String, view: String) -> CGRect? {
        guard let r = meta?.layers[muscle]?[view], r.count == 4 else { return nil }
        return CGRect(x: r[0], y: r[1], width: r[2], height: r[3])
    }

    private static let cache = NSCache<NSString, UIImage>()

    /// "front", "back-chest" and the like; decoded once and kept while memory allows.
    static func image(_ name: String) -> UIImage? {
        if let image = cache.object(forKey: name as NSString) { return image }
        guard let path = Bundle.main.path(forResource: name.replacingOccurrences(of: " ", with: "_"), ofType: "png",
                                          inDirectory: "Body3D"),
              let image = UIImage(contentsOfFile: path)?.preparingForDisplay() ?? UIImage(contentsOfFile: path) else { return nil }
        cache.setObject(image, forKey: name as NSString)
        return image
    }

    /// A view's label map as one grey byte per pixel.
    struct LabelMap {
        let width: Int
        let height: Int
        let bytes: [UInt8]

        init?(_ image: CGImage) {
            width = image.width; height = image.height
            var buffer = [UInt8](repeating: 0, count: width * height)
            let drawn = buffer.withUnsafeMutableBytes { raw -> Bool in
                guard let context = CGContext(data: raw.baseAddress, width: width, height: height, bitsPerComponent: 8,
                                              bytesPerRow: width, space: CGColorSpaceCreateDeviceGray(),
                                              bitmapInfo: CGImageAlphaInfo.none.rawValue) else { return false }
                context.interpolationQuality = .none
                context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
                return true
            }
            guard drawn else { return nil }
            bytes = buffer
        }

        init(width: Int, height: Int, bytes: [UInt8]) {
            self.width = width; self.height = height; self.bytes = bytes
        }

        /// The muscle index (into `muscles`) at a pixel, or nil for the body and the background.
        func index(x: Int, y: Int, step: Int) -> Int? {
            guard x >= 0, y >= 0, x < width, y < height, step > 0 else { return nil }
            let label = (Int(bytes[y * width + x]) + step / 2) / step
            return label > 0 ? label - 1 : nil
        }
    }

    private static let labelMaps: [String: LabelMap] = {
        var maps: [String: LabelMap] = [:]
        for view in views {
            if let image = image("\(view)-labels")?.cgImage, let map = LabelMap(image) { maps[view] = map }
        }
        return maps
    }()

    /// The muscle under `point` in one view drawn at `size`; nil over the body or background.
    static func muscle(at point: CGPoint, view: String, in size: CGSize) -> String? {
        guard let meta, let map = labelMaps[view], size.width > 0, size.height > 0 else { return nil }
        let x = Int(point.x / size.width * CGFloat(map.width))
        let y = Int(point.y / size.height * CGFloat(map.height))
        guard let i = map.index(x: x, y: y, step: meta.labelStep), i < meta.muscles.count else { return nil }
        return meta.muscles[i]
    }
}

/// The front and back body side by side, each muscle glowing at `strength` (0 hides it, 1 is full). `selected`
/// is drawn lit up; `onTap` gets the muscle under the finger, or nil.
struct Body3DView: View {
    var strength: (String) -> Double
    var selected: String? = nil
    var onTap: ((String?) -> Void)? = nil

    var body: some View {
        HStack(spacing: 0) {
            ForEach(Body3D.views, id: \.self) { view in
                figure(view)
            }
        }
        .aspectRatio(2 * Body3D.size.width / Body3D.size.height, contentMode: .fit)
    }

    private func figure(_ view: String) -> some View {
        GeometryReader { proxy in
            let size = proxy.size
            Canvas { context, canvasSize in
                let scale = canvasSize.width / Body3D.size.width
                if let base = Body3D.image(view) {
                    context.draw(Image(uiImage: base), in: CGRect(origin: .zero, size: canvasSize))
                }
                for muscle in Body3D.muscles {
                    let amount = strength(muscle)
                    let isSelected = muscle == selected
                    guard amount > 0 || isSelected,
                          let rect = Body3D.frame(of: muscle, view: view),
                          let layer = Body3D.image("\(view)-\(muscle)") else { continue }
                    let target = CGRect(x: rect.minX * scale, y: rect.minY * scale,
                                        width: rect.width * scale, height: rect.height * scale)
                    let image = context.resolve(Image(uiImage: layer))
                    var glow = context
                    glow.opacity = isSelected ? max(amount, 0.6) : amount
                    glow.draw(image, in: target)
                    if isSelected {
                        var lift = context
                        lift.blendMode = .plusLighter
                        lift.opacity = 0.35
                        lift.draw(image, in: target)
                    }
                }
            }
            .contentShape(Rectangle())
            .onTapGesture(coordinateSpace: .local) { point in
                onTap?(Body3D.muscle(at: point, view: view, in: size))
            }
        }
        .aspectRatio(Body3D.size.width / Body3D.size.height, contentMode: .fit)
    }
}

/// Exercise sheet: primary muscles glow in full, secondary ones softer, the rest stay neutral; tap names a muscle.
struct MuscleModelView: View {
    let primary: [String]
    let secondary: [String]
    @Binding var selected: String?

    static func strength(for muscle: String, primary: [String], secondary: [String]) -> Double {
        if primary.contains(muscle) { return 1 }
        if secondary.contains(muscle) { return 0.45 }
        return 0
    }

    var body: some View {
        VStack(spacing: 6) {
            Body3DView(strength: { Self.strength(for: $0, primary: primary, secondary: secondary) },
                       selected: selected,
                       onTap: { muscle in
                           withAnimation(.easeOut(duration: 0.15)) { selected = muscle == selected ? nil : muscle }
                       })
                .frame(maxHeight: 360)
            HStack {
                Text("exercises.front").frame(maxWidth: .infinity)
                Text("exercises.back").frame(maxWidth: .infinity)
            }
            .font(NT.Fonts.caption).foregroundStyle(NT.Colors.ink3)
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

/// The body with each muscle glowing by its sets this week: Progress → "Muscles this week". Untrained muscles stay
/// neutral so the figure still reads. No labels; the card lists the numbers beside it.
struct MuscleHeatView: View {
    let setsByMuscle: [String: Int]

    /// Heat step for a set count: 0 for none, then 1–3, 4–6, 7–9 and 10+.
    static func level(sets: Int) -> Int {
        switch sets {
        case ...0: return 0
        case 1...3: return 1
        case 4...6: return 2
        case 7...9: return 3
        default: return 4
        }
    }

    /// How brightly a heat step glows.
    static func strength(level: Int) -> Double { [0, 0.3, 0.52, 0.76, 1][max(0, min(4, level))] }

    var body: some View {
        Body3DView(strength: { Self.strength(level: Self.level(sets: setsByMuscle[$0] ?? 0)) })
            .accessibilityHidden(true)
    }
}
