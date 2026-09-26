package app.notomorrow.feature.workout

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import app.notomorrow.data.relation.WorkoutWithExercises
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.Hairline
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.model.SetKind
import app.notomorrow.model.WeightUnit
import app.notomorrow.service.localizedName
import app.notomorrow.util.Fmt
import app.notomorrow.util.S
import app.notomorrow.util.rememberNtStrings
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One exercise on the share card: its name, working sets done, and its heaviest one ("100 kg × 5"). */
data class WorkoutShareLine(val name: String, val sets: Int, val best: String)

/**
 * `WorkoutShareCard.lines(for:unit:)` — each exercise with completed working sets, in workout
 * order, and its heaviest set (weight first, then reps); a set without weight reads "× 12".
 */
internal fun workoutShareLines(
    workout: WorkoutWithExercises,
    unit: WeightUnit,
    locale: Locale = app.notomorrow.util.LocaleProvider.current(),
): List<WorkoutShareLine> = workout.sortedExercises.mapNotNull { item ->
    val done = item.sortedSets.filter { it.isCompleted && it.kind != SetKind.Warmup }
    val exercise = item.exercise
    if (exercise == null || done.isEmpty()) return@mapNotNull null
    val best = done.maxWith(compareBy({ it.weightKg }, { it.reps }))
    val label = if (best.weightKg > 0) {
        Fmt.weight(best.weightKg, unit, locale = locale) + " " + Fmt.TIMES + " " + best.reps
    } else {
        Fmt.TIMES + " " + best.reps
    }
    WorkoutShareLine(exercise.localizedName(locale), done.size, label)
}

/**
 * The picture the finish screen's Share sends — port of `WorkoutShareCard.swift` (a Strong/Hevy
 * style workout card): name and day, volume, time, sets, each exercise with its best set (eight at
 * most, then "+N"), the PR count and the app's name, on the dark ground at 390 dp wide. The done
 * screen lays it out off-screen and records it into a bitmap ([shareWorkoutImage]).
 */
@Composable
fun WorkoutShareCard(
    title: String,
    subtitle: String,
    volume: String,
    time: String,
    sets: String,
    prs: Int,
    lines: List<WorkoutShareLine>,
    modifier: Modifier = Modifier,
) {
    val strings = rememberNtStrings()
    Column(
        modifier = modifier
            .requiredWidth(CARD_WIDTH)
            .background(NT.Colors.ground)
            .padding(24.dp),
    ) {
        Eyebrow(subtitle, color = NT.Colors.ember)
        NtText(
            text = title,
            modifier = Modifier.padding(top = 6.dp),
            style = NT.Fonts.display(40),
            color = NT.Colors.ink,
            maxLines = 2,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Stat(stringResource(S.workout_volume), volume, Modifier.weight(1f))
            Stat(stringResource(S.workout_time), time, Modifier.weight(1f))
            Stat(stringResource(S.workout_sets), sets, Modifier.weight(1f))
        }
        Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
            lines.take(MAX_LINES).forEachIndexed { index, line ->
                if (index > 0) Hairline()
                Row(
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TabularText(
                        text = "${line.sets} ${Fmt.TIMES}",
                        modifier = Modifier.width(30.dp),
                        style = NT.Fonts.footnote,
                        color = NT.Colors.ink2,
                    )
                    NtText(
                        text = line.name,
                        modifier = Modifier.weight(1f),
                        style = NT.Fonts.subheadline,
                        color = NT.Colors.ink,
                        maxLines = 1,
                    )
                    Spacer(Modifier.widthIn(min = 8.dp))
                    TabularText(text = line.best, style = NT.Fonts.subheadlineBold, color = NT.Colors.ink)
                }
            }
            if (lines.size > MAX_LINES) {
                NtText(
                    text = "+${lines.size - MAX_LINES}",
                    modifier = Modifier.padding(top = 6.dp),
                    style = NT.Fonts.footnote,
                    color = NT.Colors.ink2,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (prs > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    NtIcon(NtIcons.TrophyFill, size = sfIconSize(12f), tint = NT.Colors.ember)
                    NtText(WorkoutStrings.prs(prs, strings), style = NT.Fonts.footnoteBold, color = NT.Colors.ember)
                }
            }
            Spacer(Modifier.weight(1f))
            NtText(
                text = "NO TOMORROW",
                style = NT.Fonts.eyebrow.copy(letterSpacing = 2.sp),
                color = NT.Colors.ink3,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(
        modifier = modifier
            .background(NT.Colors.surface, NtShapes.tile)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Eyebrow(label)
        TabularText(value, style = NT.Fonts.headline, color = NT.Colors.ink)
    }
}

/**
 * `ShareLink(item: image, message: text)` — writes [bitmap] to the cache (the `images/` path the
 * manifest's `FileProvider` serves) and offers it with [text] to the system chooser; with no
 * bitmap, or when writing it fails, the text alone goes.
 */
internal suspend fun shareWorkoutImage(context: Context, bitmap: Bitmap?, text: String) {
    val uri = bitmap?.let { image ->
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "images").apply { mkdirs() }
                val file = File(dir, SHARE_FILE)
                file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }.getOrNull()
        }
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        putExtra(Intent.EXTRA_TEXT, text)
        if (uri != null) {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            type = "text/plain"
        }
    }
    runCatching { context.startActivity(Intent.createChooser(intent, null)) }
}

/** `.frame(width: 390)`. */
private val CARD_WIDTH = 390.dp

/** Lines shown before "+N". */
private const val MAX_LINES = 8

private const val SHARE_FILE = "workout.png"
