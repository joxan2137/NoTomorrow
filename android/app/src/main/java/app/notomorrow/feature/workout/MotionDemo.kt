package app.notomorrow.feature.workout

import android.content.Context
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.effects.rememberReduceMotion
import app.notomorrow.designsystem.ntClickable
import app.notomorrow.util.S
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * `MotionDemo.swift`: animated form demos for exercises without photos, an original mannequin looping
 * between two key poses of a movement pattern (`motions.json`, from `scripts/anatomy/motions.py`).
 * A port of `scripts/anatomy/motion_engine.js`; keep the three in step.
 */
internal object MotionLibrary {
    /** A number for both sides, or [near, far]. */
    data class Side(val near: Double, val far: Double) {
        fun mix(other: Side, t: Double) = Side(near + (other.near - near) * t, far + (other.far - far) * t)
    }

    data class Pt(val x: Double, val y: Double)

    /** A joint name ("wristn") or a fixed point. */
    sealed interface Ref {
        data class Joint(val name: String) : Ref
        data class Fixed(val point: Pt) : Ref
    }

    data class Pose(
        val anchor: String,
        val at: Pt,
        val torso: Double,
        val neck: Double = 0.0,
        val lift: Double = 0.0,
        val thigh: Side,
        val shin: Side,
        val foot: Side = Side(90.0, 90.0),
        val upper: Side,
        val fore: Side,
        val farFoot: Pt? = null,
    ) {
        fun mix(o: Pose, t: Double): Pose {
            fun m(a: Double, b: Double) = a + (b - a) * t
            return copy(
                at = Pt(m(at.x, o.at.x), m(at.y, o.at.y)),
                torso = m(torso, o.torso),
                neck = m(neck, o.neck),
                lift = m(lift, o.lift),
                thigh = thigh.mix(o.thigh, t),
                shin = shin.mix(o.shin, t),
                foot = foot.mix(o.foot, t),
                upper = upper.mix(o.upper, t),
                fore = fore.mix(o.fore, t),
                farFoot = if (farFoot != null && o.farFoot != null) Pt(m(farFoot.x, o.farFoot.x), m(farFoot.y, o.farFoot.y)) else farFoot,
            )
        }
    }

    data class Prop(
        val type: String,
        val at: String? = null,
        val from: Ref? = null,
        val to: Ref? = null,
        val offset: Pt? = null,
        val r: Double? = null,
        val width: Double? = null,
        val layer: String = "back",
    )

    data class Pattern(val front: Boolean, val props: List<Prop>, val frames: List<Pose>)

    data class Library(val box: List<Double>, val patterns: Map<String, Pattern>, val exercises: Map<String, String>) {
        fun pattern(exerciseId: String): Pattern? = exercises[exerciseId]?.let { patterns[it] }
    }

    @Volatile var cached: Library? = null
        private set

    fun load(context: Context): Library? = cached ?: runCatching {
        context.assets.open("motions.json").bufferedReader().use { decode(it.readText()) }
    }.getOrNull()?.also { cached = it }

    fun decode(text: String): Library {
        val root = Json.parseToJsonElement(text).jsonObject
        return Library(
            box = root["box"]!!.jsonArray.map { it.jsonPrimitive.doubleOrNull ?: 0.0 },
            patterns = root["patterns"]!!.jsonObject.mapValues { (_, v) -> pattern(v.jsonObject) },
            exercises = root["exercises"]!!.jsonObject.mapValues { (_, v) -> v.jsonPrimitive.content },
        )
    }

    private fun num(e: JsonElement?): Double? = (e as? JsonPrimitive)?.doubleOrNull
    private fun side(e: JsonElement?, default: Double = 0.0): Side = when (e) {
        is JsonArray -> Side(num(e.getOrNull(0)) ?: default, num(e.getOrNull(1)) ?: num(e.getOrNull(0)) ?: default)
        null -> Side(default, default)
        else -> num(e).let { Side(it ?: default, it ?: default) }
    }
    private fun pt(e: JsonElement?): Pt? = (e as? JsonArray)?.let { Pt(num(it.getOrNull(0)) ?: 0.0, num(it.getOrNull(1)) ?: 0.0) }
    private fun ref(e: JsonElement?): Ref? = when (e) {
        is JsonPrimitive -> if (e.isString) Ref.Joint(e.content) else null
        is JsonArray -> pt(e)?.let { Ref.Fixed(it) }
        else -> null
    }

    private fun pattern(o: JsonObject): Pattern = Pattern(
        front = (o["view"] as? JsonPrimitive)?.content == "front",
        props = (o["props"] as? JsonArray).orEmpty().map { p ->
            val obj = p.jsonObject
            Prop(
                type = obj["type"]!!.jsonPrimitive.content,
                at = (obj["at"] as? JsonPrimitive)?.content,
                from = ref(obj["from"]),
                to = ref(obj["to"]),
                offset = pt(obj["offset"]),
                r = num(obj["r"]),
                width = num(obj["width"]),
                layer = (obj["layer"] as? JsonPrimitive)?.content ?: "back",
            )
        },
        frames = o["frames"]!!.jsonArray.map { f ->
            val obj = f.jsonObject
            Pose(
                anchor = obj["anchor"]!!.jsonPrimitive.content,
                at = pt(obj["at"]) ?: Pt(0.0, 0.0),
                torso = num(obj["torso"]) ?: 0.0,
                neck = num(obj["neck"]) ?: 0.0,
                lift = num(obj["lift"]) ?: 0.0,
                thigh = side(obj["thigh"]),
                shin = side(obj["shin"]),
                foot = side(obj["foot"], 90.0),
                upper = side(obj["upper"]),
                fore = side(obj["fore"]),
                farFoot = pt(obj["farFoot"]),
            )
        },
    )

    /** `hotSegments(for:)`: the limb segments the exercise's primary muscles move. */
    fun hotSegments(muscles: List<String>): Set<String> = muscles.mapNotNull { muscle ->
        when (muscle) {
            "quadriceps", "hamstrings", "glutes", "adductors", "abductors" -> "thigh"
            "calves", "tibialis anterior" -> "shin"
            "shoulders", "biceps", "triceps" -> "upper"
            "forearms" -> "fore"
            "chest", "abdominals", "lats", "middle back", "lower back", "traps", "neck" -> "torso"
            else -> null
        }
    }.toSet()

    // MARK: Pose maths

    const val TORSO = 50.0
    const val NECK = 7.0
    const val HEAD = 10.5
    const val UPPER = 29.0
    const val FORE = 26.0
    const val THIGH = 44.0
    const val SHIN = 43.0
    const val FOOT = 17.0

    private fun rad(d: Double) = d * PI / 180
    private fun back(p: Pt, deg: Double, len: Double) = Pt(p.x - sin(rad(deg)) * len, p.y - cos(rad(deg)) * len)

    /** Two-bone IK: the knee for a hip and a planted foot, bending forward. */
    fun knee(hip: Pt, foot: Pt): Pt {
        val a = THIGH
        val b = SHIN
        val dx = foot.x - hip.x
        val dy = foot.y - hip.y
        val d = min(hypot(dx, dy), a + b - 0.01)
        val cosA = (a * a + d * d - b * b) / (2 * a * d)
        val angle = atan2(dy, dx) - acos(max(-1.0, min(1.0, cosA)))
        return Pt(hip.x + cos(angle) * a, hip.y + sin(angle) * a)
    }

    /** Every joint of [pose], named as in `MotionLibrary.solve` (Swift). */
    fun solve(pose: Pose, front: Boolean): Map<String, Pt> {
        val j = HashMap<String, Pt>()
        val at = pose.at
        val hip = when (pose.anchor) {
            "foot" -> back(back(at, pose.shin.near, SHIN), pose.thigh.near, THIGH).let { if (front) Pt(at.x - 7, it.y) else it }
            "hand" -> {
                val shoulder = back(back(at, pose.fore.near, FORE), pose.upper.near, UPPER)
                val h = Pt(shoulder.x - sin(rad(pose.torso)) * TORSO, shoulder.y + cos(rad(pose.torso)) * TORSO)
                if (front) Pt(shoulder.x - 15, h.y) else h
            }
            "knee" -> back(at, pose.thigh.near, THIGH)
            else -> at
        }
        val t = pose.torso
        val n = t + pose.neck
        val spine = Pt(hip.x + sin(rad(t)) * TORSO, hip.y - cos(rad(t)) * TORSO)
        val neck = Pt(spine.x + sin(rad(n)) * NECK, spine.y - cos(rad(n)) * NECK)
        j["hip"] = hip
        j["spine"] = spine
        j["neck"] = neck
        j["head"] = Pt(neck.x + sin(rad(n)) * HEAD, neck.y - cos(rad(n)) * HEAD)
        val shoulderOffset = if (front) 15.0 else 0.0
        val hipOffset = if (front) 7.0 else 0.0
        for (near in listOf(true, false)) {
            val s = if (near) "n" else "f"
            val sign = if (near) 1.0 else -1.0
            val m = if (front) sign else 1.0
            fun pick(v: Side) = if (near) v.near else v.far
            val sideHip = Pt(hip.x + sign * hipOffset, hip.y)
            val shoulder = Pt(spine.x + sign * shoulderOffset, spine.y + (if (front) 4.0 else 0.0) - pose.lift)
            j["hip$s"] = sideHip
            j["shoulder$s"] = shoulder
            val far = pose.farFoot
            val (knee, ankle) = if (!near && far != null) {
                knee(sideHip, far) to far
            } else {
                val k = Pt(sideHip.x + m * sin(rad(pick(pose.thigh))) * THIGH, sideHip.y + cos(rad(pick(pose.thigh))) * THIGH)
                k to Pt(k.x + m * sin(rad(pick(pose.shin))) * SHIN, k.y + cos(rad(pick(pose.shin))) * SHIN)
            }
            j["knee$s"] = knee
            j["ankle$s"] = ankle
            j["toe$s"] = if (front) Pt(ankle.x + m * 4, ankle.y + 2)
            else Pt(ankle.x + sin(rad(pick(pose.foot))) * FOOT, ankle.y + cos(rad(pick(pose.foot))) * FOOT)
            val elbow = Pt(shoulder.x + m * sin(rad(pick(pose.upper))) * UPPER, shoulder.y + cos(rad(pick(pose.upper))) * UPPER)
            j["elbow$s"] = elbow
            j["wrist$s"] = Pt(elbow.x + m * sin(rad(pick(pose.fore))) * FORE, elbow.y + cos(rad(pick(pose.fore))) * FORE)
        }
        return j
    }

    fun pose(pattern: Pattern, t: Double): Pose? {
        val first = pattern.frames.firstOrNull() ?: return null
        return if (pattern.frames.size > 1) first.mix(pattern.frames[1], t) else first
    }

    /** 0 → 1 → 0 over [period], eased (a cosine), so the figure lingers at each end. */
    fun phase(elapsed: Double, period: Double): Double = (1 - cos(2 * PI * elapsed / period)) / 2
}

private val Near = Color(0xFF9A9AA2)
private val Far = Color(0xFF55555C)
private val HotFar = Color(0xFFB0532C)
private val PropColor = Color(0xFF3A3A40)
private val Metal = Color(0xFF77777F)
private val PlateFill = Color(0xFF26262A)

/** `MotionRenderer.draw`: one frame of [pattern] at [t], fitted into the canvas. */
internal fun DrawScope.drawMotion(library: MotionLibrary.Library, pattern: MotionLibrary.Pattern, t: Double, hot: Set<String>) {
    val pose = MotionLibrary.pose(pattern, t) ?: return
    val front = pattern.front
    val j = MotionLibrary.solve(pose, front)
    val (bx, by, bw, bh) = library.box.let { if (it.size == 4) it else listOf(-30.0, -20.0, 260.0, 220.0) }
    val scale = min(size.width / bw, size.height / bh).toFloat()
    val dx = (size.width - bw.toFloat() * scale) / 2
    val dy = (size.height - bh.toFloat() * scale) / 2

    fun o(p: MotionLibrary.Pt) = Offset(p.x.toFloat(), p.y.toFloat())
    fun capsule(a: MotionLibrary.Pt?, b: MotionLibrary.Pt?, width: Double, color: Color) {
        if (a == null || b == null) return
        drawLine(color, o(a), o(b), strokeWidth = width.toFloat(), cap = StrokeCap.Round)
    }
    fun resolve(r: MotionLibrary.Ref?): MotionLibrary.Pt? = when (r) {
        is MotionLibrary.Ref.Joint -> j[r.name]
        is MotionLibrary.Ref.Fixed -> r.point
        null -> null
    }
    fun color(segment: String, s: String): Color {
        val isFar = s == "f" && !front
        return if (segment in hot) (if (isFar) HotFar else NT.Colors.ember) else (if (isFar) Far else Near)
    }
    fun props(layer: String) {
        for (prop in pattern.props) {
            if (prop.layer != layer) continue
            when (prop.type) {
                "floor" -> capsule(MotionLibrary.Pt(-20.0, 200.0), MotionLibrary.Pt(220.0, 200.0), 2.0, PropColor)
                "pad" -> capsule(resolve(prop.from), resolve(prop.to), prop.width ?: 8.0, PropColor)
                "post" -> capsule(resolve(prop.from), resolve(prop.to), 5.0, PropColor)
                "plate" -> {
                    val p = prop.at?.let { j[it] } ?: continue
                    val c = Offset((p.x + (prop.offset?.x ?: 0.0)).toFloat(), (p.y + (prop.offset?.y ?: 0.0)).toFloat())
                    val r = (prop.r ?: 16.0).toFloat()
                    drawCircle(PlateFill, r, c)
                    drawCircle(Metal, r, c, style = Stroke(3f))
                    drawCircle(Metal, 2.5f, c)
                }
                "dumbbell" -> {
                    val p = prop.at?.let { j[it] } ?: continue
                    drawRoundRect(Metal, Offset(p.x.toFloat() - 9, p.y.toFloat() - 5), Size(18f, 10f), CornerRadius(3f))
                }
                "cable" -> {
                    val from = resolve(prop.from) ?: continue
                    val to = resolve(prop.to) ?: continue
                    capsule(from, to, 1.5, Metal)
                    drawCircle(PropColor, 4f, o(to))
                }
                "footplate" -> {
                    val at = prop.at ?: continue
                    val a = j[at] ?: continue
                    val b = j[at.replace("ankle", "toe")] ?: continue
                    val k = j[at.replace("ankle", "knee")] ?: continue
                    val vx = b.x - a.x
                    val vy = b.y - a.y
                    val sx = a.x - k.x
                    val sy = a.y - k.y
                    val l = max(hypot(sx, sy), 0.001)
                    val ox = sx / l * 6
                    val oy = sy / l * 6
                    capsule(
                        MotionLibrary.Pt(a.x - vx * 0.6 + ox, a.y - vy * 0.6 + oy),
                        MotionLibrary.Pt(b.x + vx * 0.5 + ox, b.y + vy * 0.5 + oy),
                        6.0, PropColor,
                    )
                }
                "bar" -> {
                    val p = prop.at?.let { j[it] } ?: continue
                    drawCircle(Metal, 3.5f, o(p))
                }
            }
        }
    }
    fun leg(s: String) {
        capsule(j["hip$s"], j["knee$s"], 16.0, color("thigh", s))
        capsule(j["knee$s"], j["ankle$s"], 12.0, color("shin", s))
        capsule(j["ankle$s"], j["toe$s"], 7.0, if (s == "f" && !front) Far else Near)
    }
    fun arm(s: String) {
        capsule(j["shoulder$s"], j["elbow$s"], if (s == "n") 12.0 else 11.0, color("upper", s))
        capsule(j["elbow$s"], j["wrist$s"], 9.0, color("fore", s))
    }

    withTransform({
        translate(dx, dy)
        scale(scale, scale, pivot = Offset.Zero)
        translate(-bx.toFloat(), -by.toFloat())
    }) {
        props("back")
        leg("f")
        arm("f")
        props("middle")
        val trunk = if ("torso" in hot) NT.Colors.ember else Near
        val hip = j.getValue("hip")
        val spine = j.getValue("spine")
        if (front) {
            capsule(MotionLibrary.Pt(hip.x - 5, hip.y - 2), MotionLibrary.Pt(spine.x - 7, spine.y + 6), 20.0, trunk)
            capsule(MotionLibrary.Pt(hip.x + 5, hip.y - 2), MotionLibrary.Pt(spine.x + 7, spine.y + 6), 20.0, trunk)
            capsule(j["shoulderf"], j["shouldern"], 12.0, trunk)
        } else {
            val mid = MotionLibrary.Pt(hip.x * 0.45 + spine.x * 0.55, hip.y * 0.45 + spine.y * 0.55)
            capsule(hip, mid, 21.0, trunk)
            capsule(mid, spine, 24.0, trunk)
        }
        capsule(spine, j["neck"], 9.0, Near)
        j["head"]?.let { drawCircle(Near, MotionLibrary.HEAD.toFloat(), o(it)) }
        leg("n")
        arm("n")
        props("front")
    }
}

/**
 * `MotionDemoView`: the looping mannequin with a pause button, in the same 3:2 frame as the photo
 * demo. Eases between the two key poses over [MOTION_PERIOD_S]; "Remove animations" shows the
 * finishing pose, still, until play is pressed.
 */
@Composable
internal fun MotionDemo(library: MotionLibrary.Library, pattern: MotionLibrary.Pattern, hot: Set<String>, name: String) {
    val reduceMotion = rememberReduceMotion()
    var playing by remember(pattern) { mutableStateOf(!reduceMotion) }
    var t by remember(pattern) { mutableDoubleStateOf(1.0) }
    var elapsed by remember(pattern) { mutableDoubleStateOf(if (reduceMotion) MOTION_PERIOD_S / 2 else 0.0) }
    LaunchedEffect(playing, pattern) {
        if (!playing) return@LaunchedEffect
        var last = -1L
        while (true) {
            withInfiniteAnimationFrameMillis { now ->
                if (last >= 0) elapsed += (now - last) / 1000.0
                last = now
                t = MotionLibrary.phase(elapsed, MOTION_PERIOD_S)
            }
        }
    }
    Box(
        Modifier.fillMaxWidth().aspectRatio(3f / 2f).clip(RoundedCornerShape(NT.Radius.tile)).background(NT.Colors.surface),
    ) {
        Canvas(Modifier.fillMaxSize().padding(12.dp).semantics { contentDescription = name }) {
            drawMotion(library, pattern, t, hot)
        }
        Box(
            Modifier.align(Alignment.BottomEnd).padding(10.dp).size(36.dp).clip(CircleShape).background(NT.Colors.surface2)
                .ntClickable(onClickLabel = stringResource(if (playing) S.exercises_pause else S.exercises_play)) { playing = !playing },
            contentAlignment = Alignment.Center,
        ) {
            NtIcon(if (playing) NtIcons.PauseFill else NtIcons.PlayFill, size = 16.dp, tint = NT.Colors.ink)
        }
    }
}

private const val MOTION_PERIOD_S = 2.6
