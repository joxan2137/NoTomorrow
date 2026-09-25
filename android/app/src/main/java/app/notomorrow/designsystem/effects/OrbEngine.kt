package app.notomorrow.designsystem.effects

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The thought-orb geometry engine — a Kotlin transcription of ThinkingOrbsKit
 * (`Packages/ThinkingOrbsKit`, itself transcribed from libraries.dev `thinking-orbs`, MIT,
 * © Jakub Antalik). Only the two modes the app shows are ported: `globe` (the `searching` state)
 * and `web` (`connecting`). Adding another state means transcribing its `frame…` function from
 * the Swift package and its rows from `OrbSpec.swift`.
 *
 * Doubles throughout, like the Swift and JS engines; `OrbEngineTest` checks this port against the
 * upstream golden vectors (`src/test/resources/orbs-golden-subset.json`) dot by dot.
 */

/** One dot, in the engine's point space (0…size). `white` is ink: 0 = darkest ink on paper. */
class OrbDot(
    val x: Double,
    val y: Double,
    val z: Double,
    var r: Double,
    val white: Double,
    val a: Double = 1.0,
)

/** A stroked edge between two projected points (the `connecting` web). */
class OrbLine(
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double,
    val white: Double,
    val a: Double,
    val w: Double,
)

/** One rendered instant: [dots] z-sorted far → near and radius-clamped; [lines] are drawn first. */
class OrbFrame(val dots: List<OrbDot>, val lines: List<OrbLine>)

/** Which animation to show (the subset of `OrbState` this app uses). */
enum class OrbState(val raw: String, internal val mode: OrbMode) {
    Searching("searching", OrbMode.Globe),
    Connecting("connecting", OrbMode.Web),
}

/** The two tuned sizes — separate designs with their own counts and speeds, not a scale factor. */
enum class OrbSize(val points: Int) { Px64(64), Px20(20) }

internal enum class OrbMode { Globe, Web }

/** A (state, size) pair resolved to its mode, speed and fully scaled draw options. */
class OrbPreset internal constructor(
    internal val mode: OrbMode,
    val speed: Double,
    internal val opts: Map<String, Double>,
)

/** The static frame reduced-motion users see (`OrbSpec.reducedMotionT`). */
internal const val ORB_REDUCED_MOTION_T = 0.6

// ── OrbSpec.swift (generated upstream from spec/orbs-spec.json 1.0.0, thinking-orbs 0.3.1) ──

private class SizePreset(val speed: Double, val count: Double, val size: Double, val extra: Map<String, Double>)

private val baseProfiles: Map<OrbMode, Map<String, Double>> = mapOf(
    OrbMode.Globe to mapOf(
        "latRings" to 17.0, "lonDensity" to 44.0, "rBase" to 0.6, "rDepth" to 1.7, "rBoost" to 1.0,
        "inkFar" to 0.62, "inkSpan" to 0.54, "rsPow" to 0.6, "rMin" to 0.3,
    ),
    OrbMode.Web to mapOf(
        "nodeN" to 30.0, "thr" to 0.72, "signals" to 5.0, "nodeR" to 1.4, "nodeRDepth" to 1.8,
        "lineW" to 0.8, "rsPow" to 0.6, "rMin" to 0.3,
    ),
)

private val sizePresets: Map<OrbMode, Map<OrbSize, SizePreset>> = mapOf(
    OrbMode.Globe to mapOf(
        OrbSize.Px64 to SizePreset(2.015, 0.42, 1.15, mapOf("scanMul" to 4.08, "dimBase" to 0.45)),
        OrbSize.Px20 to SizePreset(2.665, 0.105, 1.75, mapOf("scanMul" to 4.335, "dimBase" to 0.45)),
    ),
    OrbMode.Web to mapOf(
        OrbSize.Px64 to SizePreset(3.315, 1.35, 0.95, emptyMap()),
        OrbSize.Px20 to SizePreset(6.63, 0.25, 1.52, emptyMap()),
    ),
)

/** 2-D lattices scale by √count on each axis so the total scales by count. */
private val countPairs = listOf("latRings" to "lonDensity", "rings" to "lonDensity", "lanes" to "segs")
private val countKeys = listOf("orbitN", "ghostN", "nodeN", "strandN", "signals")
private val iconDensityKeys = listOf("iconD")
private val radiusKeys = listOf(
    "rBase", "rDepth", "rActive", "rDot", "ghostR", "partR", "partRDepth", "nodeR", "nodeRDepth",
)

// ── Presets.swift ──

/** Swift's `.rounded(.toNearestOrAwayFromZero)` — Kotlin's `round` is half-even, which is not. */
internal fun roundAway(x: Double): Double = if (x < 0) -floor(-x + 0.5) else floor(x + 0.5)

private fun scaleCounts(opts: Map<String, Double>, scale: Double): Map<String, Double> {
    val out = opts.toMutableMap()
    val done = HashSet<String>()
    val rt = sqrt(scale)
    for ((a, b) in countPairs) {
        val va = out[a]
        val vb = out[b]
        if (va != null && vb != null && a !in done && b !in done) {
            out[a] = max(2.0, roundAway(va * rt))
            out[b] = max(2.0, roundAway(vb * rt))
            done += a
            done += b
        }
    }
    for (k in countKeys) {
        // 0 means the mode opted out of that layer — scaling must not resurrect it as one stray dot
        val v = out[k]
        if (v != null && v != 0.0 && k !in done) out[k] = max(1.0, roundAway(v * scale))
    }
    for (k in iconDensityKeys) out[k]?.let { out[k] = max(0.02, it * scale) }
    return out
}

private fun scaleRadii(opts: Map<String, Double>, scale: Double): Map<String, Double> {
    val out = opts.toMutableMap()
    for (k in radiusKeys) out[k]?.let { out[k] = it * scale }
    out["rSizeMul"] = (out["rSizeMul"] ?: 1.0) * scale
    return out
}

private val presetCache = HashMap<Pair<OrbState, OrbSize>, OrbPreset>()

/** Resolve a (state, size) pair. Cached — the result never changes, and the draw loop must not redo it. */
fun resolveOrbPreset(state: OrbState, size: OrbSize): OrbPreset = synchronized(presetCache) {
    presetCache.getOrPut(state to size) {
        val mode = state.mode
        val preset = sizePresets.getValue(mode).getValue(size)
        var opts = baseProfiles.getValue(mode)
        if (preset.count != 1.0) opts = scaleCounts(opts, preset.count)
        if (preset.size != 1.0) opts = scaleRadii(opts, preset.size)
        OrbPreset(mode, preset.speed, opts + preset.extra)
    }
}

/** Geometry for one instant (`MODE_FRAMES` in the TS engine). */
fun orbFrame(preset: OrbPreset, size: Double, t: Double): OrbFrame = when (preset.mode) {
    OrbMode.Globe -> frameGlobe(size, t, preset.opts)
    OrbMode.Web -> frameWeb(size, t, preset.opts)
}

// ── Core.swift ──

/** Deterministic hash in [0, 1). */
internal fun hashD(a: Double, b: Double): Double {
    val h = sin(a * 12.9898 + b * 78.233) * 43758.5453
    return h - floor(h)
}

/** Value noise on a 2-D lattice — smooth, deterministic, cheap. */
internal fun vnoise(x: Double, y: Double): Double {
    val xi = floor(x)
    val yi = floor(y)
    var fx = x - xi
    var fy = y - yi
    fx = fx * fx * (3 - 2 * fx)
    fy = fy * fy * (3 - 2 * fy)
    val a = hashD(xi, yi)
    val b = hashD(xi + 1, yi)
    val c = hashD(xi, yi + 1)
    val d = hashD(xi + 1, yi + 1)
    return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy
}

/** Stable directions on a unit sphere (Fibonacci lattice). */
private fun fibDir(i: Int, n: Int): DoubleArray {
    val golden = Math.PI * (3 - sqrt(5.0))
    val y = 1 - (2 * (i + 0.5)) / n
    val rad = sqrt(1 - y * y)
    val a = i * golden
    return doubleArrayOf(rad * cos(a), y, rad * sin(a))
}

/** Shortest signed angular distance, wrapped to (-π, π]. */
private fun angleDelta(a: Double, b: Double): Double = atan2(sin(a - b), cos(a - b))

private fun lerp(a: Double, b: Double, f: Double): Double = a + (b - a) * f

private fun frac(x: Double): Double = x - floor(x)

/** Spin + tilt + orthographic projection; returns (screen x, screen y, depth z). */
private class Projector(yaw: Double, tilt: Double, val cx: Double, val cy: Double, val scale: Double) {
    private val st = sin(tilt)
    private val ct = cos(tilt)
    private val sy = sin(yaw)
    private val cyw = cos(yaw)

    fun project(x: Double, y: Double, z: Double): DoubleArray {
        val x1 = x * cyw + z * sy
        val z1 = -x * sy + z * cyw
        val y1 = y * ct - z1 * st
        val z2 = y * st + z1 * ct
        return doubleArrayOf(cx + x1 * scale, cy - y1 * scale, z2)
    }
}

/** Radii were tuned for a 300 pt frame; sub-linear scaling keeps small spinners legible. */
private fun radiusScale(size: Double, p: Double): Double = (size / 300).pow(p)

/**
 * Drop invisible marks, clamp radii to the mode's floor, z-sort far → near. Kotlin's `sortedBy`
 * is stable, which is exactly JavaScript's `Array.prototype.sort` contract.
 */
private fun finalizeFrame(dots: List<OrbDot>, lines: List<OrbLine>, rMin: Double): OrbFrame {
    val visible = ArrayList<OrbDot>(dots.size)
    for (d in dots) {
        if (d.a < 0.02) continue
        d.r = max(rMin, d.r)
        visible += d
    }
    return OrbFrame(visible.sortedBy { it.z }, lines.filter { it.a >= 0.02 })
}

// ── Lattice.swift: globe — a lat/long field, a scan meridian sweeps ──

private fun frameGlobe(size: Double, t: Double, o: Map<String, Double>): OrbFrame {
    val spin = 0.5
    val cx = size / 2
    val cy = size / 2
    val radius = (size / 2) * 0.82
    val tilt = 0.4 + 0.06 * sin(t * 0.35)
    val pt = Projector(yaw = t * spin, tilt = tilt, cx = cx, cy = cy, scale = radius)
    // the scan sweeps relative to the spin; scanMul scales that relative rate
    val scan = t * (spin + (1.7 - spin) * (o["scanMul"] ?: 1.0))
    val rs = radiusScale(size, o["rsPow"] ?: 0.6)
    val dimBase = o["dimBase"] ?: 1.0
    val rBase = o["rBase"] ?: 0.6
    val rDepth = o["rDepth"] ?: 1.7
    val rBoost = o["rBoost"] ?: 1.0
    val inkFar = o["inkFar"] ?: 0.62
    val inkSpan = o["inkSpan"] ?: 0.54

    val dots = ArrayList<OrbDot>()
    val latRings = (o["latRings"] ?: 17.0).toInt()
    val lonDensity = o["lonDensity"] ?: 44.0
    for (li in 0..latRings) {
        val lat = -Math.PI / 2 + (li.toDouble() / latRings) * Math.PI
        val cosLat = cos(lat)
        val sinLat = sin(lat)
        val lonCount = max(1, roundAway(abs(cosLat) * lonDensity).toInt())
        for (lj in 0 until lonCount) {
            val lon = (lj.toDouble() / lonCount) * 2 * Math.PI
            val p = pt.project(cosLat * cos(lon), sinLat, cosLat * sin(lon))
            val z = p[2]
            val depth = (z + 1) / 2
            // the scan: a moving meridian read as a size ripple, not a shine
            val d = angleDelta(lon + t * spin, scan)
            val boost = exp(-(d * d) / 0.18) * max(0.0, z)
            dots += OrbDot(
                x = p[0], y = p[1], z = z,
                r = (rBase + rDepth * depth + rBoost * boost) * rs,
                white = inkFar - inkSpan * depth,
                // dimBase < 1 fades un-scanned dots so the meridian reads clearly
                a = dimBase + (1 - dimBase) * min(1.0, boost),
            )
        }
    }
    return finalizeFrame(dots, emptyList(), o["rMin"] ?: 0.3)
}

// ── Web.swift: a constellation wires itself ──

private fun frameWeb(size: Double, t: Double, o: Map<String, Double>): OrbFrame {
    val cx = size / 2
    val cy = size / 2
    val radius = (size / 2) * 0.8 * (o["spread"] ?: 1.0)
    // the projector carries the radius as its scale, so node vectors stay unit length
    val pt = Projector(yaw = t * 0.12, tilt = 0.32, cx = cx, cy = cy, scale = radius)
    val rs = radiusScale(size, o["rsPow"] ?: 0.6)

    val nodeN = (o["nodeN"] ?: 30.0).toInt()
    val thr = o["thr"] ?: 0.72
    val nodeR = o["nodeR"] ?: 1.4
    val nodeRDepth = o["nodeRDepth"] ?: 1.8

    // nodes: fib lattice + slow noise wander, renormalised to the surface
    val nodes = Array(nodeN) { i ->
        val d = fibDir(i, nodeN)
        val x = d[0] + 0.3 * (vnoise(i * 0.31 + 9, t * 0.24) - 0.5) * 2
        val y = d[1] + 0.3 * (vnoise(i * 0.53 + 27, t * 0.21) - 0.5) * 2
        val z = d[2] + 0.3 * (vnoise(i * 0.77 + 55, t * 0.27) - 0.5) * 2
        val l = sqrt(x * x + y * y + z * z)
        doubleArrayOf(x / l, y / l, z / l)
    }

    val lines = ArrayList<OrbLine>()
    val dots = ArrayList<OrbDot>()

    // edges between close neighbours, alpha by proximity + depth
    for (i in 0 until nodeN) {
        for (j in i + 1 until nodeN) {
            val dx = nodes[i][0] - nodes[j][0]
            val dy = nodes[i][1] - nodes[j][1]
            val dz = nodes[i][2] - nodes[j][2]
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            if (dist >= thr) continue
            val p1 = pt.project(nodes[i][0], nodes[i][1], nodes[i][2])
            val p2 = pt.project(nodes[j][0], nodes[j][1], nodes[j][2])
            val depth = ((p1[2] + p2[2]) / 2 + 1) / 2
            lines += OrbLine(
                x1 = p1[0], y1 = p1[1], x2 = p2[0], y2 = p2[1],
                white = 0.42,
                a = (1 - dist / thr) * (0.3 + 0.55 * depth),
                w = max(0.6, (o["lineW"] ?: 0.8) * rs),
            )
        }
    }

    for (i in 0 until nodeN) {
        val p = pt.project(nodes[i][0], nodes[i][1], nodes[i][2])
        val depth = (p[2] + 1) / 2
        val pulse = 1 + 0.25 * sin(t * 1.4 + i * 2.7)
        dots += OrbDot(
            x = p[0], y = p[1], z = p[2],
            r = (nodeR + nodeRDepth * depth) * pulse * rs,
            white = 0.55 - 0.45 * depth,
        )
    }

    // signals: bright packets running between paired nodes
    val signals = (o["signals"] ?: 5.0).toInt()
    for (s in 0 until signals) {
        val seg = floor(t * 0.55 + s * 7.31)
        val a = floor(hashD(seg, s * 3.1 + 1.7) * nodeN).toInt()
        val b = floor(hashD(seg, s * 5.7 + 4.2) * nodeN).toInt()
        if (a == b) continue
        val f = frac(t * 0.55 + s * 7.31)
        val x = lerp(nodes[a][0], nodes[b][0], f)
        val y = lerp(nodes[a][1], nodes[b][1], f)
        val z = lerp(nodes[a][2], nodes[b][2], f)
        val l = max(1e-6, sqrt(x * x + y * y + z * z))
        val p = pt.project(x / l, y / l, z / l)
        val depth = (p[2] + 1) / 2
        dots += OrbDot(
            x = p[0], y = p[1], z = p[2],
            r = (nodeR * 1.5 + nodeRDepth * depth) * rs,
            white = 0.05,
            a = 0.5 + 0.5 * depth,
        )
    }

    return finalizeFrame(dots, lines, o["rMin"] ?: 0.3)
}
