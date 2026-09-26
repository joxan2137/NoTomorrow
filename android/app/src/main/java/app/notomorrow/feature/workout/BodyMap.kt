package app.notomorrow.feature.workout

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.NT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `BodyMap.swift`: the front/back body map from `muscle_model.json` (drawn by
 * `scripts/anatomy/muscle_map.py`) in a 200 × 300 box, front figure on the left. "outline" is the body
 * underneath, drawn first; every other region is a free-exercise-db muscle ("chest", "middle back").
 */
@Serializable
internal data class MuscleRegion(val muscle: String, val view: String, val d: String) {
    @kotlinx.serialization.Transient
    val shape: SvgShape = SvgShape.parse(d)
}

internal object BodyMap {
    const val WIDTH = 200f
    const val HEIGHT = 300f

    private val json = Json { ignoreUnknownKeys = true }

    /** Read once per process, like the Swift `static let`. */
    @Volatile var regions: List<MuscleRegion>? = null

    fun decode(text: String): List<MuscleRegion> = json.decodeFromString(text)

    /** `BodyMap.muscle(at:in:)`: the muscle under a point of a map drawn at [width] × [height]. */
    fun muscleAt(regions: List<MuscleRegion>, x: Float, y: Float, width: Float, height: Float): String? {
        val ux = x / width * WIDTH
        val uy = y / height * HEIGHT
        return regions.lastOrNull { it.muscle != "outline" && it.shape.contains(ux, uy) }?.muscle
    }
}

/** The regions, read off the main thread the first time a map is drawn and kept for the process. */
@Composable
internal fun rememberMuscleRegions(): List<MuscleRegion> {
    val context = LocalContext.current
    val regions by produceState(BodyMap.regions.orEmpty()) {
        if (value.isNotEmpty()) return@produceState
        value = withContext(Dispatchers.IO) {
            BodyMap.regions ?: context.assets.open("muscle_model.json").bufferedReader().use {
                BodyMap.decode(it.readText())
            }.also { BodyMap.regions = it }
        }
    }
    return regions
}

/**
 * `SVGPath` (`BodyMap.swift`): the absolute M, L, C and Z path data the body map uses, kept as
 * contours of points and cubic segments so it can both draw and hit-test without Android classes.
 */
internal class SvgShape private constructor(private val contours: List<FloatArray>, private val polygons: List<FloatArray>) {

    fun toPath(): Path = Path().apply {
        for (contour in contours) {
            // [x0, y0, then 6 floats per cubic]
            moveTo(contour[0], contour[1])
            var i = 2
            while (i + 5 < contour.size) {
                cubicTo(contour[i], contour[i + 1], contour[i + 2], contour[i + 3], contour[i + 4], contour[i + 5])
                i += 6
            }
            close()
        }
    }

    /** Even-odd point-in-polygon over the flattened curves. */
    fun contains(x: Float, y: Float): Boolean {
        var inside = false
        for (poly in polygons) {
            var j = poly.size - 2
            var i = 0
            while (i < poly.size) {
                val xi = poly[i]; val yi = poly[i + 1]; val xj = poly[j]; val yj = poly[j + 1]
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                j = i
                i += 2
            }
        }
        return inside
    }

    fun bounds(): FloatArray {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (poly in polygons) {
            var i = 0
            while (i < poly.size) {
                minX = minOf(minX, poly[i]); maxX = maxOf(maxX, poly[i])
                minY = minOf(minY, poly[i + 1]); maxY = maxOf(maxY, poly[i + 1])
                i += 2
            }
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    companion object {
        private const val STEPS = 8

        fun parse(d: String): SvgShape {
            val contours = mutableListOf<FloatArray>()
            var current: MutableList<Float>? = null
            var cursorX = 0f
            var cursorY = 0f

            fun finish() {
                current?.let { if (it.size >= 2) contours += it.toFloatArray() }
                current = null
            }

            for ((command, numbers) in tokenize(d)) {
                when (command) {
                    'M' -> {
                        finish()
                        if (numbers.size < 2) continue
                        cursorX = numbers[0]; cursorY = numbers[1]
                        current = mutableListOf(cursorX, cursorY)
                        var i = 2
                        while (i + 1 < numbers.size) {
                            current!!.addLine(cursorX, cursorY, numbers[i], numbers[i + 1])
                            cursorX = numbers[i]; cursorY = numbers[i + 1]
                            i += 2
                        }
                    }
                    'L' -> {
                        var i = 0
                        while (i + 1 < numbers.size) {
                            current?.addLine(cursorX, cursorY, numbers[i], numbers[i + 1])
                            cursorX = numbers[i]; cursorY = numbers[i + 1]
                            i += 2
                        }
                    }
                    'C' -> {
                        var i = 0
                        while (i + 5 < numbers.size) {
                            current?.addAll(numbers.subList(i, i + 6))
                            cursorX = numbers[i + 4]; cursorY = numbers[i + 5]
                            i += 6
                        }
                    }
                    'Z', 'z' -> finish()
                }
            }
            finish()
            return SvgShape(contours, contours.map(::flatten))
        }

        /** A straight line as a cubic, so every contour is one kind of segment. */
        private fun MutableList<Float>.addLine(x0: Float, y0: Float, x1: Float, y1: Float) {
            addAll(listOf(x0 + (x1 - x0) / 3, y0 + (y1 - y0) / 3, x0 + (x1 - x0) * 2 / 3, y0 + (y1 - y0) * 2 / 3, x1, y1))
        }

        private fun flatten(contour: FloatArray): FloatArray {
            val out = mutableListOf(contour[0], contour[1])
            var x0 = contour[0]
            var y0 = contour[1]
            var i = 2
            while (i + 5 < contour.size) {
                for (s in 1..STEPS) {
                    val t = s.toFloat() / STEPS
                    val u = 1 - t
                    val a = u * u * u; val b = 3 * u * u * t; val c = 3 * u * t * t; val e = t * t * t
                    out += a * x0 + b * contour[i] + c * contour[i + 2] + e * contour[i + 4]
                    out += a * y0 + b * contour[i + 1] + c * contour[i + 3] + e * contour[i + 5]
                }
                x0 = contour[i + 4]; y0 = contour[i + 5]
                i += 6
            }
            return out.toFloatArray()
        }

        private fun tokenize(d: String): List<Pair<Char, List<Float>>> {
            val out = mutableListOf<Pair<Char, List<Float>>>()
            var command: Char? = null
            val numbers = mutableListOf<Float>()
            val token = StringBuilder()
            fun flushNumber() {
                token.toString().toFloatOrNull()?.let { numbers += it }
                token.clear()
            }
            fun flushCommand() {
                command?.let { out += it to numbers.toList() }
                numbers.clear()
            }
            for (ch in d) {
                when {
                    ch.isLetter() && ch != 'e' && ch != 'E' -> { flushNumber(); flushCommand(); command = ch }
                    ch == ' ' || ch == ',' -> flushNumber()
                    ch == '-' && token.isNotEmpty() && token.last() != 'e' && token.last() != 'E' -> {
                        flushNumber(); token.append(ch)
                    }
                    else -> token.append(ch)
                }
            }
            flushNumber()
            flushCommand()
            return out
        }
    }
}

/**
 * `BodyMapView`: each muscle filled by [fill], the body underneath in [bodyColor], a [gap]-coloured
 * line between shapes (the colour behind the map), and [selected] outlined in `ink`. [onTap] gets the
 * muscle under the finger, or null over the body or background.
 */
@Composable
internal fun BodyMapCanvas(
    fill: (String) -> Color,
    modifier: Modifier = Modifier,
    bodyColor: Color = NT.Colors.surface2,
    gap: Color = NT.Colors.surface,
    selected: String? = null,
    onTap: ((String?) -> Unit)? = null,
) {
    val regions = rememberMuscleRegions()
    val paths = remember(regions) { regions.map { it to it.shape.toPath() } }
    val tap by rememberUpdatedState(onTap)
    val tapModifier = if (onTap != null) {
        Modifier.pointerInput(regions) {
            detectTapGestures { offset: Offset ->
                tap?.invoke(BodyMap.muscleAt(regions, offset.x, offset.y, size.width.toFloat(), size.height.toFloat()))
            }
        }
    } else Modifier
    Canvas(modifier.aspectRatio(BodyMap.WIDTH / BodyMap.HEIGHT).then(tapModifier)) {
        val unit = size.width / BodyMap.WIDTH
        scale(scaleX = unit, scaleY = size.height / BodyMap.HEIGHT, pivot = Offset.Zero) {
            val line = 1.4.dp.toPx() / unit
            for ((region, path) in paths) {
                drawPath(path, if (region.muscle == "outline") bodyColor else fill(region.muscle))
                drawPath(path, gap, style = Stroke(width = line, join = StrokeJoin.Round))
            }
            if (selected != null) {
                for ((region, path) in paths) {
                    if (region.muscle == selected) {
                        drawPath(path, NT.Colors.ink, style = Stroke(width = 1.6.dp.toPx() / unit, join = StrokeJoin.Round))
                    }
                }
            }
        }
    }
}

/** `MuscleModelView.color(for:primary:secondary:)`: ember, heat step 2, or neutral. */
internal fun muscleModelColor(muscle: String, primary: List<String>, secondary: List<String>): Color = when {
    muscle in primary -> NT.Colors.ember
    muscle in secondary -> NT.Colors.heat[2]
    else -> NT.Colors.surface3
}

/**
 * `MuscleHeatView`: the body map with each muscle filled with the [NT.Colors.heat] step for its sets
 * ([muscleHeatLevel]); untrained muscles stay neutral so the figure still reads. Progress →
 * "Muscles this week".
 */
@Composable
fun MuscleHeatView(setsByMuscle: Map<String, Int>, modifier: Modifier = Modifier) {
    BodyMapCanvas(
        fill = { muscle ->
            val level = muscleHeatLevel(setsByMuscle[muscle] ?: 0)
            if (level == 0) NT.Colors.surface3 else NT.Colors.heat[level]
        },
        modifier = modifier,
    )
}

/** `MuscleHeatView.level(sets:)` — the [NT.Colors.heat] step: 0 for none, then 1–3, 4–6, 7–9 and 10+. */
fun muscleHeatLevel(sets: Int): Int = when {
    sets <= 0 -> 0
    sets <= 3 -> 1
    sets <= 6 -> 2
    sets <= 9 -> 3
    else -> 4
}
