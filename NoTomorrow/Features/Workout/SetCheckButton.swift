import SwiftUI

/// The ✓ column: a 36 pt rounded square, ember with a dark check when done, surface-2 with a faint check when not.
///
/// Ticking a set sets off a short ember splash (`SetCheckBurst`), built from the libraries.dev effects the app
/// uses elsewhere: droplets that squeeze out of the box and pinch off like goo (Gooey), a comet lap round its edge
/// (Border Beam), a metal glint across the fill (Liquid Metal) and a spray of dots (Thinking Orbs) — over a spring
/// squash, a fill that floods out from the centre and a check that draws itself, with a haptic "pop". Unticking is
/// instant, and Reduce Motion skips the splash.
struct SetCheckButton: View {
    let isOn: Bool
    var action: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var burstStart: Date?

    var body: some View {
        Button(action: action) {
            // The label keeps the plain checkmark for VoiceOver; the canvas draws the visible box over it.
            Image(systemName: "checkmark")
                .opacity(0)
                .frame(width: 48, height: NT.Size.control)
                .contentShape(Rectangle())
                .overlay {
                    SetCheckCanvas(isOn: isOn, start: burstStart)
                        .frame(width: SetCheckBurst.canvasSide, height: SetCheckBurst.canvasSide)
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                }
        }
        .buttonStyle(.plain)
        .onChange(of: isOn) { wasOn, nowOn in
            burstStart = (nowOn && !wasOn && !reduceMotion) ? .now : nil
        }
        .task(id: burstStart) {
            guard burstStart != nil else { return }
            // The pop lands when the fill has flooded the box.
            try? await Task.sleep(for: .milliseconds(SetCheckBurst.popMs))
            guard !Task.isCancelled else { return }
            UIImpactFeedbackGenerator(style: .rigid).impactOccurred(intensity: 0.9)
            try? await Task.sleep(for: .milliseconds(Int(SetCheckBurst.duration * 1000) - SetCheckBurst.popMs))
            guard !Task.isCancelled else { return }
            burstStart = nil
        }
    }
}

/// Redraws every frame only while a burst runs; otherwise one static frame.
private struct SetCheckCanvas: View {
    let isOn: Bool
    let start: Date?

    var body: some View {
        TimelineView(.animation(paused: start == nil)) { timeline in
            let t = start.map { timeline.date.timeIntervalSince($0) }
            Canvas { context, size in
                SetCheckBurst.draw(in: &context, size: size, isOn: isOn, t: t)
            }
        }
    }
}

/// The splash, as a function of time since the tick. Shared numbers with the Android port (`SetCheckBurst.kt`).
enum SetCheckBurst {
    static let duration = 0.85
    /// The haptic lands on the pop (`popT`).
    static let popMs = 80
    /// Room round the 36 pt box for the droplets, sparks and shockwave.
    static let canvasSide: CGFloat = 140

    private static let box: CGFloat = 36
    private static let radius: CGFloat = 10
    private static let emberLight = Color(hex: 0xFF9A5C)
    private static let spark = Color(hex: 0xFFE3D1)

    // Eight droplets between the box's corners and edges. Each has its own reach, size and delay so the splash is
    // uneven, but fixed, so every tick throws the same splash (a satisfying tick feels like the same object).
    private static let dropAngles: [Double] = [22.5, 64, 115, 157, 203, 246, 292, 338]
    private static let dropReach: [Double] = [1.0, 0.65, 0.95, 0.55, 1.0, 0.8, 0.6, 0.9]
    private static let dropSize: [Double] = [1.0, 0.6, 0.85, 0.5, 0.95, 0.7, 0.55, 0.8]
    private static let dropDelay: [Double] = [0, 0.03, 0.01, 0.05, 0.02, 0.04, 0, 0.03]
    /// The pop: the fill has flooded the box and the splash is thrown.
    private static let popT = 0.08
    private static let sparkAngles: [Double] = [0, 58, 122, 180, 236, 301]

    static func draw(in context: inout GraphicsContext, size: CGSize, isOn: Bool, t: Double?) {
        let c = CGPoint(x: size.width / 2, y: size.height / 2)
        guard let t, isOn, t < duration else {
            drawBox(in: &context, center: c, scale: 1, fill: isOn ? 1 : 0, check: 1, isOn: isOn, glint: nil)
            return
        }

        // Spring squash: pressed in to 84 %, a small overshoot, settled by ~0.5 s.
        let squash = 1 - 0.16 * exp(-8 * t) * cos(20 * t)

        // Warm glow behind everything.
        let glowA = 0.55 * smooth(0.06, 0.12, t) * (1 - smooth(0.16, 0.75, t))
        if glowA > 0.01 {
            context.fill(Path(ellipseIn: CGRect(x: c.x - 38, y: c.y - 38, width: 76, height: 76)),
                         with: .radialGradient(Gradient(colors: [NT.Colors.ember.opacity(glowA), .clear]),
                                               center: c, startRadius: 0, endRadius: 38))
        }

        // Shockwave: the box's outline expanding and thinning out.
        let wave = easeOutCubic(clamp((t - popT) / 0.5))
        if t >= popT && wave < 1 {
            let side = box + 34 * wave
            let ring = Path(roundedRect: CGRect(x: c.x - side / 2, y: c.y - side / 2, width: side, height: side),
                            cornerRadius: radius + 8 * wave, style: .continuous)
            context.stroke(ring, with: .color(emberLight.opacity(0.7 * (1 - wave))), lineWidth: 2.5 * (1 - wave) + 0.5)
        }

        // Goo: the box and eight droplets, blurred and thresholded so they stay joined by liquid bridges until they
        // pinch off.
        let gooA = 1 - smooth(0.5, 0.66, t)
        if gooA > 0.01 {
            context.drawLayer { goo in
                goo.opacity = gooA
                goo.addFilter(.alphaThreshold(min: 0.5, color: NT.Colors.ember))
                goo.addFilter(.blur(radius: 3.5))
                goo.drawLayer { shapes in
                    let s = box * squash
                    shapes.fill(Path(roundedRect: CGRect(x: c.x - s / 2, y: c.y - s / 2, width: s, height: s),
                                     cornerRadius: radius * squash, style: .continuous), with: .color(.black))
                    for i in dropAngles.indices {
                        guard let p = droplet(i, t: t, center: c), p.r > 0.2 else { continue }
                        shapes.fill(Path(ellipseIn: CGRect(x: p.x - p.r, y: p.y - p.r, width: 2 * p.r, height: 2 * p.r)),
                                    with: .color(.black))
                    }
                }
            }
        }

        // Sparks: small bright dots flying past the droplets.
        let sparkP = easeOutCubic(clamp((t - popT) / 0.55))
        let sparkFade = 1 - clamp((t - popT) / 0.62)
        let sparkR = 1.8 * sparkFade
        if t >= popT && sparkR > 0.05 {
            for degrees in sparkAngles {
                let a = degrees * .pi / 180
                let d = 20 + 36 * sparkP
                let p = CGPoint(x: c.x + cos(a) * d, y: c.y + sin(a) * d)
                context.fill(Path(ellipseIn: CGRect(x: p.x - sparkR, y: p.y - sparkR, width: 2 * sparkR, height: 2 * sparkR)),
                             with: .color(spark.opacity(sparkFade)))
            }
        }

        // The box itself: fill floods out from the centre, the check draws in, a glint sweeps across.
        let fill = easeOutCubic(clamp(t / 0.14))
        let check = easeOutCubic(clamp((t - 0.1) / 0.24))
        let glint = (t > 0.2 && t < 0.55) ? easeInOut((t - 0.2) / 0.35) : nil
        drawBox(in: &context, center: c, scale: squash, fill: fill, check: check, isOn: true, glint: glint)

        // Beam: a comet running once round the box's edge.
        let lap = clamp((t - 0.06) / 0.5)
        let beamA = smooth(0.06, 0.12, t) * (1 - smooth(0.45, 0.6, t))
        if beamA > 0.01 {
            let side: CGFloat = box + 6
            let edge = Path(roundedRect: CGRect(x: c.x - side / 2, y: c.y - side / 2, width: side, height: side),
                            cornerRadius: radius + 3, style: .continuous)
            let head = -90 + 360 * easeInOut(lap)
            let comet = GraphicsContext.Shading.conicGradient(
                Gradient(stops: [
                    .init(color: .clear, location: 0),
                    .init(color: .clear, location: 0.62),
                    .init(color: emberLight.opacity(0.6 * beamA), location: 0.86),
                    .init(color: Color.white.opacity(beamA), location: 0.985),
                    .init(color: .clear, location: 1),
                ]),
                center: c, angle: .degrees(head))
            context.drawLayer { bloom in
                bloom.addFilter(.blur(radius: 3))
                bloom.stroke(edge, with: comet, lineWidth: 4)
            }
            context.stroke(edge, with: comet, lineWidth: 1.5)
        }
    }

    /// Droplet `i`: squeezes out from under the box after its delay, travels outward and shrinks away.
    private static func droplet(_ i: Int, t: Double, center c: CGPoint) -> (x: CGFloat, y: CGFloat, r: CGFloat)? {
        let tt = t - dropDelay[i]
        guard tt >= 0 else { return nil }
        let a = dropAngles[i] * .pi / 180
        let travel = easeOutCubic(clamp(tt / 0.45))
        let d = 10 + (20 + 16 * dropReach[i]) * travel
        let r = (4.2 + 2.0 * dropSize[i]) * pow(1 - clamp((tt - 0.12) / 0.42), 0.9)
        return (c.x + CGFloat(cos(a) * d), c.y + CGFloat(sin(a) * d), CGFloat(r))
    }

    private static func drawBox(in context: inout GraphicsContext, center c: CGPoint, scale: Double,
                                fill: Double, check: Double, isOn: Bool, glint: Double?) {
        let s = box * scale
        let rect = CGRect(x: c.x - s / 2, y: c.y - s / 2, width: s, height: s)
        let shape = Path(roundedRect: rect, cornerRadius: radius * scale, style: .continuous)
        context.fill(shape, with: .color(isOn && fill >= 1 ? NT.Colors.ember : NT.Colors.surface2))
        if isOn && fill < 1 {
            context.drawLayer { flood in
                flood.clip(to: shape)
                let r = 27 * fill * scale
                flood.fill(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r)),
                           with: .color(NT.Colors.ember))
            }
        }
        if let glint {
            context.drawLayer { sheen in
                sheen.clip(to: shape)
                let f = glint
                sheen.fill(shape, with: .linearGradient(
                    Gradient(stops: [
                        .init(color: .clear, location: max(0, f - 0.16)),
                        .init(color: Color.white.opacity(0.55), location: min(1, max(0.001, f))),
                        .init(color: .clear, location: min(1, f + 0.16)),
                    ]),
                    startPoint: CGPoint(x: rect.minX - 8, y: rect.minY - 8),
                    endPoint: CGPoint(x: rect.maxX + 8, y: rect.maxY + 8)))
            }
        }
        if check > 0 {
            // The SF "checkmark" at 15 pt bold, as a stroke so it can draw itself in.
            var tick = Path()
            tick.move(to: CGPoint(x: c.x - 6.8 * scale, y: c.y + 0.4 * scale))
            tick.addLine(to: CGPoint(x: c.x - 2.2 * scale, y: c.y + 5.2 * scale))
            tick.addLine(to: CGPoint(x: c.x + 7.2 * scale, y: c.y - 5.4 * scale))
            context.stroke(tick.trimmedPath(from: 0, to: check),
                           with: .color(isOn ? NT.Colors.onPrimary : NT.Colors.ink3),
                           style: StrokeStyle(lineWidth: 2.6 * scale, lineCap: .round, lineJoin: .round))
        }
    }

    private static func clamp(_ x: Double) -> Double { min(1, max(0, x)) }
    private static func smooth(_ e0: Double, _ e1: Double, _ x: Double) -> Double {
        let t = clamp((x - e0) / (e1 - e0))
        return t * t * (3 - 2 * t)
    }
    private static func easeOutCubic(_ x: Double) -> Double { 1 - pow(1 - x, 3) }
    private static func easeInOut(_ x: Double) -> Double { x < 0.5 ? 4 * x * x * x : 1 - pow(-2 * x + 2, 3) / 2 }
}
