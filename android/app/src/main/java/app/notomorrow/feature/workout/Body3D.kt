package app.notomorrow.feature.workout

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `Body3D.swift`: the muscles-worked body. Front and back renders of the app's 3D body
 * (`scripts/anatomy/body3d/render_body.py`) plus one glowing layer per muscle and view, cropped to the
 * muscle with its offset in `body3d/body3d.json`. A muscle shows by drawing its layers over the neutral
 * body at some strength; a label map per view (muscle i drawn as grey (i + 1) × `labelStep`) answers
 * which muscle is under a finger.
 */
internal object Body3D {
    val VIEWS = listOf("front", "back")

    @Serializable
    data class Meta(
        val size: List<Int>,
        val labelStep: Int,
        val muscles: List<String>,
        /** muscle → view → [x, y, width, height] in the render's pixels. */
        val layers: Map<String, Map<String, List<Int>>>,
    ) {
        val width: Int get() = size.getOrElse(0) { 520 }
        val height: Int get() = size.getOrElse(1) { 1000 }
    }

    /** A view's label map, one grey byte per pixel. */
    class LabelMap(val width: Int, val height: Int, private val bytes: ByteArray) {
        /** The muscle index (into [Meta.muscles]) at a pixel, or null for the body and the background. */
        fun index(x: Int, y: Int, step: Int): Int? {
            if (x < 0 || y < 0 || x >= width || y >= height || step <= 0) return null
            val label = ((bytes[y * width + x].toInt() and 0xFF) + step / 2) / step
            return if (label > 0) label - 1 else null
        }
    }

    class Assets(
        val meta: Meta,
        val bases: Map<String, ImageBitmap>,
        /** "front-chest" and the like. */
        val layers: Map<String, ImageBitmap>,
        val labels: Map<String, LabelMap>,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun decode(text: String): Meta = json.decodeFromString(text)

    /** Read once per process, like the Swift statics. */
    @Volatile var cached: Assets? = null

    fun load(context: Context): Assets? = cached ?: runCatching {
        val assets = context.assets
        val meta = assets.open("body3d/body3d.json").bufferedReader().use { decode(it.readText()) }
        fun bitmap(name: String) = assets.open("body3d/${name.replace(' ', '_')}.png").use { BitmapFactory.decodeStream(it) }
        val bases = VIEWS.associateWith { bitmap(it).asImageBitmap() }
        val layers = buildMap {
            for ((muscle, views) in meta.layers) for (view in views.keys) put("$view-$muscle", bitmap("$view-$muscle").asImageBitmap())
        }
        val labels = VIEWS.associateWith { view ->
            val b = bitmap("$view-labels")
            val pixels = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
            LabelMap(b.width, b.height, ByteArray(pixels.size) { i -> ((pixels[i] shr 16) and 0xFF).toByte() })
        }
        Assets(meta, bases, layers, labels)
    }.getOrNull()?.also { cached = it }

    /** The muscle under ([x], [y]) in one view drawn at [width] × [height]; null over the body or background. */
    fun muscleAt(meta: Meta, map: LabelMap?, x: Float, y: Float, width: Float, height: Float): String? {
        if (map == null || width <= 0f || height <= 0f) return null
        val i = map.index((x / width * map.width).toInt(), (y / height * map.height).toInt(), meta.labelStep) ?: return null
        return meta.muscles.getOrNull(i)
    }
}

/** The body renders, read off the main thread the first time and kept for the process. */
@Composable
internal fun rememberBody3D(): Body3D.Assets? {
    val context = LocalContext.current
    val assets by produceState(Body3D.cached) {
        if (value == null) value = withContext(Dispatchers.IO) { Body3D.load(context) }
    }
    return assets
}

/**
 * `Body3DView`: the front and back body side by side, each muscle glowing at [strength] (0 hides it, 1
 * is full). [selected] is drawn lit up; [onTap] gets the muscle under the finger, or null.
 */
@Composable
internal fun Body3DCanvas(
    strength: (String) -> Float,
    modifier: Modifier = Modifier,
    selected: String? = null,
    onTap: ((String?) -> Unit)? = null,
) {
    val assets = rememberBody3D()
    val w = assets?.meta?.width ?: 520
    val h = assets?.meta?.height ?: 1000
    Row(modifier.aspectRatio(2f * w / h)) {
        for (view in Body3D.VIEWS) {
            Body3DFigure(assets, view, strength, selected, onTap, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun Body3DFigure(
    assets: Body3D.Assets?,
    view: String,
    strength: (String) -> Float,
    selected: String?,
    onTap: ((String?) -> Unit)?,
    modifier: Modifier,
) {
    val tap by rememberUpdatedState(onTap)
    val tapModifier = if (onTap != null && assets != null) {
        Modifier.pointerInput(assets, view) {
            detectTapGestures { offset ->
                tap?.invoke(Body3D.muscleAt(assets.meta, assets.labels[view], offset.x, offset.y, size.width.toFloat(), size.height.toFloat()))
            }
        }
    } else Modifier
    Canvas(modifier.then(tapModifier)) {
        assets ?: return@Canvas
        val scale = size.width / assets.meta.width
        assets.bases[view]?.let {
            drawImage(it, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Medium)
        }
        for (muscle in assets.meta.muscles) {
            val amount = strength(muscle)
            val isSelected = muscle == selected
            if (amount <= 0f && !isSelected) continue
            val rect = assets.meta.layers[muscle]?.get(view)?.takeIf { it.size == 4 } ?: continue
            val layer = assets.layers["$view-$muscle"] ?: continue
            val offset = IntOffset((rect[0] * scale).roundToInt(), (rect[1] * scale).roundToInt())
            val dst = IntSize((rect[2] * scale).roundToInt().coerceAtLeast(1), (rect[3] * scale).roundToInt().coerceAtLeast(1))
            drawImage(layer, dstOffset = offset, dstSize = dst, alpha = if (isSelected) maxOf(amount, 0.6f) else amount,
                filterQuality = FilterQuality.Medium)
            if (isSelected) {
                drawImage(layer, dstOffset = offset, dstSize = dst, alpha = 0.35f, blendMode = BlendMode.Plus,
                    filterQuality = FilterQuality.Medium)
            }
        }
    }
}

/** `MuscleModelView.strength(for:primary:secondary:)`: primary full, secondary softer, the rest off. */
internal fun muscleModelStrength(muscle: String, primary: List<String>, secondary: List<String>): Float = when (muscle) {
    in primary -> 1f
    in secondary -> 0.45f
    else -> 0f
}

/**
 * `MuscleHeatView`: the body with each muscle glowing by its sets this week ([muscleHeatLevel]);
 * untrained muscles stay neutral so the figure still reads. Progress → "Muscles this week".
 */
@Composable
fun MuscleHeatView(setsByMuscle: Map<String, Int>, modifier: Modifier = Modifier) {
    Body3DCanvas(strength = { muscleHeatStrength(muscleHeatLevel(setsByMuscle[it] ?: 0)) }, modifier = modifier)
}

/** `MuscleHeatView.level(sets:)` — the heat step: 0 for none, then 1–3, 4–6, 7–9 and 10+. */
fun muscleHeatLevel(sets: Int): Int = when {
    sets <= 0 -> 0
    sets <= 3 -> 1
    sets <= 6 -> 2
    sets <= 9 -> 3
    else -> 4
}

/** `MuscleHeatView.strength(level:)`: how brightly a heat step glows. */
fun muscleHeatStrength(level: Int): Float = floatArrayOf(0f, 0.3f, 0.52f, 0.76f, 1f)[level.coerceIn(0, 4)]
