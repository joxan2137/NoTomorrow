package app.notomorrow.feature.workout

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.notomorrow.R
import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.designsystem.*
import app.notomorrow.service.ExerciseLibrary
import app.notomorrow.service.localizedName
import app.notomorrow.util.NtKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URL

@Serializable
internal data class MuscleRegion(val muscle: String, val points: List<List<Float>>)
private object ExerciseImages {
    val cache = LruCache<String, ImageBitmap>(8)
    val json = Json { ignoreUnknownKeys = true }
    /** `muscle_model.json`, once read — `ExerciseMedia.regions` is a Swift `static let`. */
    @Volatile var regions: List<MuscleRegion>? = null
}

/**
 * `ExerciseMedia.regions` (`ExerciseDetailView.swift:83`): the front/back muscle model, read off the main
 * thread the first time a map is drawn and kept for the process, so a map shown again draws at once.
 */
@Composable
internal fun rememberMuscleRegions(): List<MuscleRegion> {
    val context = LocalContext.current
    val regions by produceState(ExerciseImages.regions.orEmpty()) {
        if (value.isNotEmpty()) return@produceState
        value = withContext(Dispatchers.IO) {
            ExerciseImages.regions ?: context.assets.open("muscle_model.json").bufferedReader().use {
                ExerciseImages.json.decodeFromString<List<MuscleRegion>>(it.readText())
            }.also { ExerciseImages.regions = it }
        }
    }
    return regions
}

@Composable
fun ExerciseDetailSheet(exercise: ExerciseEntity, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val regions = rememberMuscleRegions()
    val images by produceState<List<String>>(emptyList(), exercise.id) {
        value = withContext(Dispatchers.IO) {
            context.assets.open("exercises.json").bufferedReader().use {
                ExerciseImages.json.decodeFromString<List<ExerciseLibrary.Record>>(it.readText())
                    .firstOrNull { row -> row.id == exercise.id }?.images.orEmpty()
            }
        }
    }
    NtSheet(onDismiss = onDismiss, showsHandle = true) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            NtText(exercise.localizedName(), style = NT.Fonts.title2)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                NtText(stringResource(R.string.exercises_front), style = NT.Fonts.footnote)
                NtText(stringResource(R.string.exercises_back), style = NT.Fonts.footnote)
            }
            Canvas(Modifier.fillMaxWidth().height(360.dp)) {
                for (region in regions) {
                    val path = Path()
                    region.points.forEachIndexed { i, point ->
                        if (i == 0) path.moveTo(point[0] / 200 * size.width, point[1] / 300 * size.height)
                        else path.lineTo(point[0] / 200 * size.width, point[1] / 300 * size.height)
                    }
                    path.close()
                    val color = when {
                        region.muscle in exercise.primaryMuscles -> Color(0xFFFF9800)
                        region.muscle in exercise.secondaryMuscles -> Color.Cyan
                        region.muscle == "outline" -> NT.Colors.surface3
                        else -> NT.Colors.ink3.copy(alpha = 0.4f)
                    }
                    drawPath(path, color)
                    drawPath(path, NT.Colors.ground, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                }
            }
            MuscleLegend(R.string.exercises_primary, exercise.primaryMuscles, Color(0xFFFF9800))
            MuscleLegend(R.string.exercises_secondary, exercise.secondaryMuscles, Color.Cyan)
            NtText(stringResource(R.string.exercises_muscleNote), style = NT.Fonts.footnote, color = NT.Colors.ink2)
            if (images.isNotEmpty()) {
                NtText(stringResource(R.string.exercises_demonstration), style = NT.Fonts.headline)
                images.forEach { ExercisePhoto(it, exercise.localizedName()) }
            }
            NtText(stringResource(R.string.exercises_instructions), style = NT.Fonts.headline)
            exercise.instructions.forEachIndexed { i, text -> NtText("${i + 1}. $text", style = NT.Fonts.body) }
            if (images.isNotEmpty()) NtText("free-exercise-db · Public domain", style = NT.Fonts.footnote, color = NT.Colors.ink2)
            SecondaryButton(title = stringResource(R.string.common_done), onClick = onDismiss)
        }
    }
}

/**
 * `MuscleHeatView` (`ExerciseDetailView.swift`): the same front/back regions as the sheet's map, each
 * muscle filled with the [NT.Colors.heat] step for its sets ([muscleHeatLevel]), outlines in `surface3`,
 * every region stroked in `ground` — Progress → "Muscles this week". 2:3, like the model's 200 × 300 units.
 */
@Composable
fun MuscleHeatView(setsByMuscle: Map<String, Int>, modifier: Modifier = Modifier) {
    val regions = rememberMuscleRegions()
    Canvas(modifier.aspectRatio(2f / 3f)) {
        for (region in regions) {
            val path = Path()
            region.points.forEachIndexed { i, point ->
                if (i == 0) path.moveTo(point[0] / 200 * size.width, point[1] / 300 * size.height)
                else path.lineTo(point[0] / 200 * size.width, point[1] / 300 * size.height)
            }
            path.close()
            val color = if (region.muscle == "outline") {
                NT.Colors.surface3
            } else {
                NT.Colors.heat[muscleHeatLevel(setsByMuscle[region.muscle] ?: 0)]
            }
            drawPath(path, color)
            drawPath(path, NT.Colors.ground, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        }
    }
}

/** `MuscleHeatView.level(sets:)` — the [NT.Colors.heat] step: 0 for none, then 1–3, 4–6, 7–9 and 10+. */
fun muscleHeatLevel(sets: Int): Int = when {
    sets <= 0 -> 0
    sets <= 3 -> 1
    sets <= 6 -> 2
    sets <= 9 -> 3
    else -> 4
}

@Composable
private fun MuscleLegend(title: Int, muscles: List<String>, color: Color) {
    val names = muscles.map { raw -> NtKeys.muscle(raw)?.let { stringResource(it) } ?: raw }
    NtText(stringResource(title) + ": " + names.joinToString(", "), style = NT.Fonts.footnote, color = color)
}

@Composable
private fun ExercisePhoto(path: String, name: String) {
    var failed by remember(path) { mutableStateOf(false) }
    val bitmap by produceState<ImageBitmap?>(ExerciseImages.cache.get(path), path) {
        if (value != null) return@produceState
        try {
            value = withContext(Dispatchers.IO) {
                require(!path.contains("..") && !path.contains(":"))
                val connection = URL("https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/exercises/$path").openConnection()
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                val bytes = connection.getInputStream().use { it.readNBytes(2 * 1024 * 1024) }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                bitmap?.also { ExerciseImages.cache.put(path, it) }
            }
            failed = value == null
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
    bitmap?.let { Image(it, name, Modifier.fillMaxWidth().heightIn(max = 300.dp)) }
        ?: NtText(stringResource(if (failed) R.string.exercises_photoUnavailable else R.string.exercises_photoLoading), style = NT.Fonts.footnote)
}
