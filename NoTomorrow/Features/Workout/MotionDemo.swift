import SwiftUI

/// Animated form demos for exercises without photos: an original mannequin looping between two key poses of a
/// movement pattern (`motions.json`, from `scripts/anatomy/motions.py`). This is a port of
/// `scripts/anatomy/motion_engine.js`; keep the two in step.
enum MotionLibrary {
    /// A number for both sides, or [near, far].
    struct Pair: Decodable {
        var near: Double
        var far: Double

        init(_ value: Double) { near = value; far = value }
        init(near: Double, far: Double) { self.near = near; self.far = far }

        init(from decoder: Decoder) throws {
            let container = try decoder.singleValueContainer()
            if let value = try? container.decode(Double.self) {
                self.init(value)
            } else {
                let values = try container.decode([Double].self)
                self.init(near: values.first ?? 0, far: values.count > 1 ? values[1] : values.first ?? 0)
            }
        }

        func mixed(_ other: Pair, _ t: Double) -> Pair {
            Pair(near: near + (other.near - near) * t, far: far + (other.far - far) * t)
        }
    }

    /// A joint name ("wristn") or a fixed point.
    enum Point: Decodable {
        case joint(String)
        case fixed(CGPoint)

        init(from decoder: Decoder) throws {
            let container = try decoder.singleValueContainer()
            if let name = try? container.decode(String.self) {
                self = .joint(name)
            } else {
                let values = try container.decode([Double].self)
                self = .fixed(CGPoint(x: values[0], y: values.count > 1 ? values[1] : 0))
            }
        }

        func resolve(_ joints: [String: CGPoint]) -> CGPoint {
            switch self {
            case .joint(let name): return joints[name] ?? .zero
            case .fixed(let point): return point
            }
        }
    }

    struct Pose: Decodable {
        var anchor: String
        var at: [Double]
        var torso: Double
        var neck: Double?
        var lift: Double?
        var thigh: Pair
        var shin: Pair
        var foot: Pair?
        var upper: Pair
        var fore: Pair
        var farFoot: [Double]?

        func mixed(_ other: Pose, _ t: Double) -> Pose {
            func mix(_ a: Double, _ b: Double) -> Double { a + (b - a) * t }
            var pose = self
            pose.at = zip(at, other.at).map { mix($0, $1) }
            pose.torso = mix(torso, other.torso)
            pose.neck = mix(neck ?? 0, other.neck ?? 0)
            pose.lift = mix(lift ?? 0, other.lift ?? 0)
            pose.thigh = thigh.mixed(other.thigh, t)
            pose.shin = shin.mixed(other.shin, t)
            pose.foot = (foot ?? Pair(90)).mixed(other.foot ?? Pair(90), t)
            pose.upper = upper.mixed(other.upper, t)
            pose.fore = fore.mixed(other.fore, t)
            if let a = farFoot, let b = other.farFoot { pose.farFoot = zip(a, b).map { mix($0, $1) } }
            return pose
        }
    }

    struct Prop: Decodable {
        var type: String
        var at: String?
        var from: Point?
        var to: Point?
        var offset: [Double]?
        var r: Double?
        var width: Double?
        var layer: String?
    }

    struct Pattern: Decodable {
        var view: String?
        var props: [Prop]
        var frames: [Pose]
    }

    struct File: Decodable {
        var box: [Double]
        var patterns: [String: Pattern]
        var exercises: [String: String]
    }

    static let file: File? = ExerciseMedia.load("motions")

    /// The drawing box, [x, y, width, height].
    static var box: CGRect {
        guard let b = file?.box, b.count == 4 else { return CGRect(x: -30, y: -20, width: 260, height: 220) }
        return CGRect(x: b[0], y: b[1], width: b[2], height: b[3])
    }

    static func pattern(for exerciseID: String) -> Pattern? {
        guard let file, let name = file.exercises[exerciseID] else { return nil }
        return file.patterns[name]
    }

    /// Limb segments the exercise's primary muscles move, coloured on the mannequin.
    static func hotSegments(for muscles: [String]) -> Set<String> {
        var segments = Set<String>()
        for muscle in muscles {
            switch muscle {
            case "quadriceps", "hamstrings", "glutes", "adductors", "abductors": segments.insert("thigh")
            case "calves", "tibialis anterior": segments.insert("shin")
            case "shoulders", "biceps", "triceps": segments.insert("upper")
            case "forearms": segments.insert("fore")
            case "chest", "abdominals", "lats", "middle back", "lower back", "traps", "neck": segments.insert("torso")
            default: break
            }
        }
        return segments
    }

    // MARK: Pose maths

    static let length = (torso: 50.0, neck: 7.0, head: 10.5, upper: 29.0, fore: 26.0, thigh: 44.0, shin: 43.0, foot: 17.0)

    private static func rad(_ degrees: Double) -> Double { degrees * .pi / 180 }
    /// Limb direction: 0 = down, 90 = forward (+x).
    private static func direction(_ degrees: Double) -> CGPoint { CGPoint(x: sin(rad(degrees)), y: cos(rad(degrees))) }
    private static func back(_ p: CGPoint, _ degrees: Double, _ length: Double) -> CGPoint {
        let d = direction(degrees)
        return CGPoint(x: p.x - d.x * length, y: p.y - d.y * length)
    }

    /// Two-bone IK: the knee for a hip and a planted foot, bending forward.
    static func knee(hip: CGPoint, foot: CGPoint) -> CGPoint {
        let a = length.thigh, b = length.shin
        let dx = foot.x - hip.x, dy = foot.y - hip.y
        let d = min(hypot(dx, dy), a + b - 0.01)
        let cosA = (a * a + d * d - b * b) / (2 * a * d)
        let angle = atan2(dy, dx) - acos(max(-1, min(1, cosA)))
        return CGPoint(x: hip.x + cos(angle) * a, y: hip.y + sin(angle) * a)
    }

    /// Every joint of `pose`: hip, spine (top of the torso), neck, head, and per side ("n" near, "f" far)
    /// hip, shoulder, knee, ankle, toe, elbow, wrist.
    static func solve(_ pose: Pose, front: Bool) -> [String: CGPoint] {
        let at = CGPoint(x: pose.at.first ?? 0, y: pose.at.count > 1 ? pose.at[1] : 0)
        var j: [String: CGPoint] = [:]
        var hip: CGPoint
        switch pose.anchor {
        case "foot":
            hip = back(back(at, pose.shin.near, length.shin), pose.thigh.near, length.thigh)
            // Front view: the near ankle sits under the near hip, 7 units right of the centre line.
            if front { hip.x = at.x - 7 }
        case "hand":
            let shoulder = back(back(at, pose.fore.near, length.fore), pose.upper.near, length.upper)
            hip = CGPoint(x: shoulder.x - sin(rad(pose.torso)) * length.torso, y: shoulder.y + cos(rad(pose.torso)) * length.torso)
            if front { hip.x = shoulder.x - 15 }
        case "knee":
            hip = back(at, pose.thigh.near, length.thigh)
        default:
            hip = at
        }
        let t = pose.torso, n = t + (pose.neck ?? 0)
        let spine = CGPoint(x: hip.x + sin(rad(t)) * length.torso, y: hip.y - cos(rad(t)) * length.torso)
        let neck = CGPoint(x: spine.x + sin(rad(n)) * length.neck, y: spine.y - cos(rad(n)) * length.neck)
        j["hip"] = hip
        j["spine"] = spine
        j["neck"] = neck
        j["head"] = CGPoint(x: neck.x + sin(rad(n)) * length.head, y: neck.y - cos(rad(n)) * length.head)

        let shoulderOffset = front ? 15.0 : 0, hipOffset = front ? 7.0 : 0
        let foot = pose.foot ?? Pair(90)
        for (side, sign, thigh, shin, footAngle, upper, fore) in [
            ("n", 1.0, pose.thigh.near, pose.shin.near, foot.near, pose.upper.near, pose.fore.near),
            ("f", -1.0, pose.thigh.far, pose.shin.far, foot.far, pose.upper.far, pose.fore.far),
        ] {
            let m = front ? sign : 1
            let sideHip = CGPoint(x: hip.x + sign * hipOffset, y: hip.y)
            let shoulder = CGPoint(x: spine.x + sign * shoulderOffset, y: spine.y + (front ? 4 : 0) - (pose.lift ?? 0))
            j["hip" + side] = sideHip
            j["shoulder" + side] = shoulder
            let knee: CGPoint, ankle: CGPoint
            if side == "f", let far = pose.farFoot, far.count == 2 {
                ankle = CGPoint(x: far[0], y: far[1])
                knee = Self.knee(hip: sideHip, foot: ankle)
            } else {
                knee = CGPoint(x: sideHip.x + m * sin(rad(thigh)) * length.thigh, y: sideHip.y + cos(rad(thigh)) * length.thigh)
                ankle = CGPoint(x: knee.x + m * sin(rad(shin)) * length.shin, y: knee.y + cos(rad(shin)) * length.shin)
            }
            j["knee" + side] = knee
            j["ankle" + side] = ankle
            j["toe" + side] = front
                ? CGPoint(x: ankle.x + m * 4, y: ankle.y + 2)
                : CGPoint(x: ankle.x + sin(rad(footAngle)) * length.foot, y: ankle.y + cos(rad(footAngle)) * length.foot)
            let elbow = CGPoint(x: shoulder.x + m * sin(rad(upper)) * length.upper, y: shoulder.y + cos(rad(upper)) * length.upper)
            j["elbow" + side] = elbow
            j["wrist" + side] = CGPoint(x: elbow.x + m * sin(rad(fore)) * length.fore, y: elbow.y + cos(rad(fore)) * length.fore)
        }
        return j
    }

    /// The pose `t` of the way from the first key pose to the second.
    static func pose(_ pattern: Pattern, at t: Double) -> Pose? {
        guard let first = pattern.frames.first else { return nil }
        guard pattern.frames.count > 1 else { return first }
        return first.mixed(pattern.frames[1], t)
    }
}

/// Draws one frame of a pattern into a canvas.
enum MotionRenderer {
    static let near = Color(hex: 0x9A9AA2)
    static let far = Color(hex: 0x55555C)
    static let hotFar = Color(hex: 0xB0532C)
    static let prop = Color(hex: 0x3A3A40)
    static let metal = Color(hex: 0x77777F)
    static let plate = Color(hex: 0x26262A)

    static func draw(_ pattern: MotionLibrary.Pattern, t: Double, hot: Set<String>, in context: inout GraphicsContext, size: CGSize) {
        guard let pose = MotionLibrary.pose(pattern, at: t) else { return }
        let front = pattern.view == "front"
        let j = MotionLibrary.solve(pose, front: front)
        let box = MotionLibrary.box
        let scale = min(size.width / box.width, size.height / box.height)
        context.translateBy(x: (size.width - box.width * scale) / 2, y: (size.height - box.height * scale) / 2)
        context.scaleBy(x: scale, y: scale)
        context.translateBy(x: -box.minX, y: -box.minY)

        func capsule(_ a: CGPoint?, _ b: CGPoint?, _ width: Double, _ color: Color) {
            guard let a, let b else { return }
            var path = Path()
            path.move(to: a)
            path.addLine(to: b)
            context.stroke(path, with: .color(color), style: StrokeStyle(lineWidth: width, lineCap: .round))
        }
        func color(_ segment: String, _ side: String) -> Color {
            let isFar = side == "f" && !front
            if hot.contains(segment) { return isFar ? hotFar : NT.Colors.ember }
            return isFar ? far : near
        }
        func props(_ layer: String) {
            for prop in pattern.props where (prop.layer ?? "back") == layer {
                switch prop.type {
                case "floor":
                    capsule(CGPoint(x: -20, y: 200), CGPoint(x: 220, y: 200), 2, self.prop)
                case "pad":
                    capsule(prop.from?.resolve(j), prop.to?.resolve(j), prop.width ?? 8, self.prop)
                case "post":
                    capsule(prop.from?.resolve(j), prop.to?.resolve(j), 5, self.prop)
                case "plate":
                    guard let at = prop.at, let p = j[at] else { continue }
                    let o = prop.offset ?? [0, 0]
                    let c = CGPoint(x: p.x + (o.first ?? 0), y: p.y + (o.count > 1 ? o[1] : 0))
                    let r = prop.r ?? 16
                    let disc = Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r))
                    context.fill(disc, with: .color(plate))
                    context.stroke(disc, with: .color(metal), lineWidth: 3)
                    context.fill(Path(ellipseIn: CGRect(x: c.x - 2.5, y: c.y - 2.5, width: 5, height: 5)), with: .color(metal))
                case "dumbbell":
                    guard let at = prop.at, let p = j[at] else { continue }
                    context.fill(Path(roundedRect: CGRect(x: p.x - 9, y: p.y - 5, width: 18, height: 10), cornerRadius: 3), with: .color(metal))
                case "cable":
                    guard let from = prop.from?.resolve(j), let to = prop.to?.resolve(j) else { continue }
                    capsule(from, to, 1.5, metal)
                    context.fill(Path(ellipseIn: CGRect(x: to.x - 4, y: to.y - 4, width: 8, height: 8)), with: .color(self.prop))
                case "footplate":
                    guard let at = prop.at, let a = j[at], let b = j[at.replacingOccurrences(of: "ankle", with: "toe")],
                          let k = j[at.replacingOccurrences(of: "ankle", with: "knee")] else { continue }
                    let v = CGPoint(x: b.x - a.x, y: b.y - a.y)
                    let s = CGPoint(x: a.x - k.x, y: a.y - k.y)
                    let l = max(hypot(s.x, s.y), 0.001)
                    let o = CGPoint(x: s.x / l * 6, y: s.y / l * 6)
                    capsule(CGPoint(x: a.x - v.x * 0.6 + o.x, y: a.y - v.y * 0.6 + o.y),
                            CGPoint(x: b.x + v.x * 0.5 + o.x, y: b.y + v.y * 0.5 + o.y), 6, self.prop)
                case "bar":
                    guard let at = prop.at, let p = j[at] else { continue }
                    context.fill(Path(ellipseIn: CGRect(x: p.x - 3.5, y: p.y - 3.5, width: 7, height: 7)), with: .color(metal))
                default:
                    break
                }
            }
        }
        func leg(_ s: String) {
            capsule(j["hip" + s], j["knee" + s], 16, color("thigh", s))
            capsule(j["knee" + s], j["ankle" + s], 12, color("shin", s))
            capsule(j["ankle" + s], j["toe" + s], 7, s == "f" && !front ? far : near)
        }
        func arm(_ s: String) {
            capsule(j["shoulder" + s], j["elbow" + s], s == "n" ? 12 : 11, color("upper", s))
            capsule(j["elbow" + s], j["wrist" + s], 9, color("fore", s))
        }

        props("back")
        leg("f")
        arm("f")
        props("middle")
        let trunk = hot.contains("torso") ? NT.Colors.ember : near
        if let hip = j["hip"], let spine = j["spine"] {
            if front {
                capsule(CGPoint(x: hip.x - 5, y: hip.y - 2), CGPoint(x: spine.x - 7, y: spine.y + 6), 20, trunk)
                capsule(CGPoint(x: hip.x + 5, y: hip.y - 2), CGPoint(x: spine.x + 7, y: spine.y + 6), 20, trunk)
                capsule(j["shoulderf"], j["shouldern"], 12, trunk)
            } else {
                let mid = CGPoint(x: hip.x * 0.45 + spine.x * 0.55, y: hip.y * 0.45 + spine.y * 0.55)
                capsule(hip, mid, 21, trunk)
                capsule(mid, spine, 24, trunk)
            }
        }
        capsule(j["spine"], j["neck"], 9, near)
        if let head = j["head"] {
            let r = MotionLibrary.length.head
            context.fill(Path(ellipseIn: CGRect(x: head.x - r, y: head.y - r, width: 2 * r, height: 2 * r)), with: .color(near))
        }
        leg("n")
        arm("n")
        props("front")
    }
}

/// The looping mannequin demo with a pause button, in the same frame as the photo demo. Eases between the two key
/// poses over `period`; Reduce Motion shows the finishing pose, still, until play is pressed.
struct MotionDemoView: View {
    let pattern: MotionLibrary.Pattern
    let hot: Set<String>
    let name: String

    static let period: Double = 2.6

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var playing: Bool?
    @State private var pausedAt: Double = 1
    @State private var start = Date.now

    private var isPlaying: Bool { playing ?? !reduceMotion }

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            RoundedRectangle(cornerRadius: NT.Radius.tile, style: .continuous).fill(NT.Colors.surface)
            TimelineView(.animation(paused: !isPlaying)) { timeline in
                let t = isPlaying ? Self.phase(timeline.date.timeIntervalSince(start), period: Self.period) : pausedAt
                Canvas { context, size in
                    MotionRenderer.draw(pattern, t: t, hot: hot, in: &context, size: size)
                }
            }
            .padding(12)
            .accessibilityElement()
            .accessibilityLabel(Text(name))
            .accessibilityAddTraits(.isImage)
            Button {
                if isPlaying {
                    pausedAt = Self.phase(Date.now.timeIntervalSince(start), period: Self.period)
                    playing = false
                } else {
                    start = Date.now.addingTimeInterval(-Self.time(forPhase: pausedAt, period: Self.period))
                    playing = true
                }
            } label: {
                Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(NT.Colors.ink)
                    .frame(width: 36, height: 36)
                    .background(NT.Colors.surface2, in: Circle())
            }
            .buttonStyle(PressScale())
            .accessibilityLabel(Text(isPlaying ? "exercises.pause" : "exercises.play"))
            .padding(10)
        }
        .aspectRatio(3.0 / 2.0, contentMode: .fit)
        .frame(maxWidth: .infinity)
    }

    /// 0 → 1 → 0 over `period`, eased (a cosine), so the figure lingers at each end.
    static func phase(_ elapsed: Double, period: Double) -> Double {
        (1 - cos(2 * .pi * elapsed / period)) / 2
    }

    /// The first time in the rising half of the loop at which `phase` is reached.
    static func time(forPhase phase: Double, period: Double) -> Double {
        acos(1 - 2 * max(0, min(1, phase))) / (2 * .pi) * period
    }
}
