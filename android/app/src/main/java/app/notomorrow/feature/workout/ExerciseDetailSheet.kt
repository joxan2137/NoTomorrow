package app.notomorrow.feature.workout

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.notomorrow.R
import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.designsystem.*
import app.notomorrow.designsystem.effects.rememberReduceMotion
import app.notomorrow.service.ExerciseLibrary
import app.notomorrow.service.localizedName
import app.notomorrow.util.NtKeys
import app.notomorrow.util.S
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * `ExerciseDetailView`: what the exercise is (equipment, level, compound or isolation), the looping form
 * demo (the public-domain free-exercise-db photos, or the app's own mannequin for exercises without
 * them), the body map of the muscles it works, the app's
 * own form cues and common mistakes where it has them, and the numbered steps.
 */
@Composable
fun ExerciseDetailSheet(exercise: ExerciseEntity, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val images by produceState(ExerciseAssets.images?.get(exercise.id).orEmpty(), exercise.id) {
        value = withContext(Dispatchers.IO) { ExerciseAssets.images(context)[exercise.id].orEmpty() }
    }
    val language = appLocale().language
    val cues by produceState<FormCueEntry?>(null, exercise.id, language) {
        value = withContext(Dispatchers.IO) { FormCues.cues(FormCues.all(context), exercise.id, language) }
    }
    var selected by remember(exercise.id) { mutableStateOf<String?>(null) }
    val motions by produceState<MotionLibrary.Library?>(MotionLibrary.cached, exercise.id) {
        value = withContext(Dispatchers.IO) { MotionLibrary.load(context) }
    }

    NtSheet(onDismiss = onDismiss, showsHandle = true, containerColor = NT.Colors.ground) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = NT.Spacing.screenH, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(NT.Spacing.section),
        ) {
            Header(exercise)
            val pattern = motions?.pattern(exercise.id)
            if (images.isNotEmpty()) {
                FormDemo(images, exercise.localizedName())
            } else if (pattern != null) {
                MotionDemo(motions!!, pattern, MotionLibrary.hotSegments(exercise.primaryMuscles), exercise.localizedName())
            }
            MusclesCard(exercise, selected) { muscle -> selected = if (muscle == selected) null else muscle }
            cues?.let { CuesCard(it) }
            if (exercise.instructions.isNotEmpty()) StepsCard(exercise.instructions)
            if (images.isNotEmpty()) {
                NtText("free-exercise-db · Public domain", style = NT.Fonts.footnote, color = NT.Colors.ink3)
            }
            SecondaryButton(title = stringResource(R.string.common_done), onClick = onDismiss)
        }
    }
}

// MARK: - Header

@Composable
private fun Header(exercise: ExerciseEntity) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NtText(exercise.localizedName(), style = NT.Fonts.title2)
        val facts = exerciseFacts(exercise)
        if (facts.isNotEmpty()) {
            NtFlowLayout(spacing = 6.dp) {
                facts.forEach { fact ->
                    Box(
                        Modifier.height(26.dp).background(NT.Colors.surface, CircleShape).padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) { NtText(fact, style = NT.Fonts.caption, color = NT.Colors.ink2, maxLines = 1) }
                }
            }
        }
    }
}

/** `ExerciseFacts.labels`: equipment, level, compound/isolation, push/pull/hold; unknown values skipped. */
@Composable
private fun exerciseFacts(exercise: ExerciseEntity): List<String> = buildList {
    exercise.equipment?.takeIf { it.isNotEmpty() }?.let { raw ->
        add(NtKeys.equipment(raw)?.let { stringResource(it) } ?: raw.replaceFirstChar { it.titlecase() })
    }
    exerciseFactKey(exercise.level, exercise.mechanic, exercise.force).forEach { add(stringResource(it)) }
}

/** The string ids for level, mechanic and force, in that order; "advanced" reads as expert. */
internal fun exerciseFactKey(level: String?, mechanic: String?, force: String?): List<Int> = listOfNotNull(
    when (level) {
        "beginner" -> S.exercises_level_beginner
        "intermediate" -> S.exercises_level_intermediate
        "expert", "advanced" -> S.exercises_level_expert
        else -> null
    },
    when (mechanic) {
        "compound" -> S.exercises_mechanic_compound
        "isolation" -> S.exercises_mechanic_isolation
        else -> null
    },
    when (force) {
        "push" -> S.exercises_force_push
        "pull" -> S.exercises_force_pull
        "static" -> S.exercises_force_static
        else -> null
    },
)

// MARK: - Muscles

@Composable
private fun MusclesCard(exercise: ExerciseEntity, selected: String?, onSelect: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(stringResource(S.exercises_musclesWorked))
        NTCard {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    BodyMapCanvas(
                        fill = { muscleModelColor(it, exercise.primaryMuscles, exercise.secondaryMuscles) },
                        modifier = Modifier.heightIn(max = 340.dp).semantics {
                            contentDescription = exercise.primaryMuscles.joinToString(", ")
                        },
                        selected = selected,
                        onTap = { onSelect(it) },
                    )
                    Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 6.dp)) {
                        NtText(stringResource(R.string.exercises_front), style = NT.Fonts.caption, color = NT.Colors.ink3)
                        Spacer(Modifier.weight(1f))
                        NtText(stringResource(R.string.exercises_back), style = NT.Fonts.caption, color = NT.Colors.ink3)
                    }
                }
                val line = selected?.let { muscle ->
                    val role = when (muscle) {
                        in exercise.primaryMuscles -> S.exercises_role_primary
                        in exercise.secondaryMuscles -> S.exercises_role_secondary
                        else -> S.exercises_role_notUsed
                    }
                    workoutMuscleName(muscle) + " · " + stringResource(role)
                }
                NtText(
                    line ?: stringResource(S.exercises_tapMuscle),
                    modifier = Modifier.fillMaxWidth(),
                    style = NT.Fonts.footnote,
                    color = if (line != null) NT.Colors.ink else NT.Colors.ink3,
                    textAlign = TextAlign.Center,
                )
                Hairline()
                MuscleLegend(stringResource(R.string.exercises_primary), exercise.primaryMuscles, NT.Colors.ember, selected, onSelect)
                if (exercise.secondaryMuscles.isNotEmpty()) {
                    MuscleLegend(stringResource(R.string.exercises_secondary), exercise.secondaryMuscles, NT.Colors.heat[2], selected, onSelect)
                }
                NtText(stringResource(R.string.exercises_muscleNote), style = NT.Fonts.footnote, color = NT.Colors.ink3)
            }
        }
    }
}

@Composable
private fun MuscleLegend(title: String, muscles: List<String>, color: Color, selected: String?, onSelect: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow(title)
        NtFlowLayout(spacing = 6.dp) {
            muscles.forEach { muscle ->
                Row(
                    Modifier.height(32.dp)
                        .clip(CircleShape)
                        .background(if (muscle == selected) NT.Colors.surface3 else NT.Colors.surface2)
                        .ntClickable { onSelect(muscle) }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(8.dp).background(color, CircleShape))
                    NtText(workoutMuscleName(muscle), style = NT.Fonts.subheadline, maxLines = 1)
                }
            }
        }
    }
}

// MARK: - Form cues

@Composable
private fun CuesCard(entry: FormCueEntry) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(stringResource(S.exercises_formCues))
        NTCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                entry.cues.forEach { CueRow(it, NtIcons.Checkmark, NT.Colors.good) }
                if (entry.mistakes.isNotEmpty()) {
                    Hairline()
                    Eyebrow(stringResource(S.exercises_mistakes))
                    entry.mistakes.forEach { CueRow(it, NtIcons.Xmark, NT.Colors.bad) }
                }
            }
        }
    }
}

@Composable
private fun CueRow(text: String, icon: NtIcons, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(top = 1.dp).size(18.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
            NtIcon(icon, size = 12.dp, tint = NT.Colors.ground)
        }
        NtText(text, style = NT.Fonts.subheadline)
    }
}

// MARK: - Steps

@Composable
private fun StepsCard(steps: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(stringResource(R.string.exercises_instructions))
        NTCard {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                steps.forEachIndexed { i, text ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(24.dp).background(NT.Colors.emberTint, CircleShape), contentAlignment = Alignment.Center) {
                            TabularText("${i + 1}", style = NT.Fonts.footnoteBold, color = NT.Colors.ember)
                        }
                        NtText(text, style = NT.Fonts.subheadline)
                    }
                }
            }
        }
    }
}

// MARK: - Form demo

/**
 * `FormDemoView`: the start and end photos played as a loop (a crossfade every [FORM_DEMO_INTERVAL_MS]),
 * a pause button and a dot per frame; tapping a dot pauses on it. Starts paused under "Remove animations".
 */
@Composable
private fun FormDemo(paths: List<String>, name: String) {
    val context = LocalContext.current
    val reduceMotion = rememberReduceMotion()
    var playing by remember(paths) { mutableStateOf(!reduceMotion) }
    var index by remember(paths) { mutableIntStateOf(0) }
    var failed by remember(paths) { mutableStateOf(false) }
    val frames by produceState(paths.map { ExercisePhotos.cached(it) }, paths) {
        if (value.all { it != null }) return@produceState
        val loaded = coroutineScope { paths.map { async { ExercisePhotos.load(context, it) } }.awaitAll() }
        value = loaded
        failed = loaded.any { it == null }
    }
    val ready = frames.isNotEmpty() && frames.all { it != null }
    LaunchedEffect(playing, ready, frames.size) {
        if (!playing || !ready || frames.size < 2) return@LaunchedEffect
        while (true) {
            delay(FORM_DEMO_INTERVAL_MS)
            index = (index + 1) % frames.size
        }
    }
    val shape = RoundedCornerShape(NT.Radius.tile)
    Box(
        Modifier.fillMaxWidth().aspectRatio(3f / 2f).clip(shape).background(NT.Colors.surface2),
        contentAlignment = Alignment.Center,
    ) {
        when {
            ready -> {
                frames.forEachIndexed { i, bitmap ->
                    val alpha by animateFloatAsState(if (i == index) 1f else 0f, tween(350), label = "frame")
                    Image(bitmap!!, name, Modifier.fillMaxSize().alpha(alpha), contentScale = ContentScale.Crop)
                }
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier.height(28.dp).background(NT.Colors.ground.copy(alpha = 0.55f), CircleShape).padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        frames.indices.forEach { i ->
                            val width by animateDpAsState(if (i == index) 18.dp else 7.dp, label = "dot")
                            Box(
                                Modifier.size(width = width, height = 7.dp)
                                    .background(if (i == index) NT.Colors.ink else NT.Colors.ink3, CircleShape)
                                    .ntPlainClickable { playing = false; index = i },
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (frames.size > 1) {
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(NT.Colors.ground.copy(alpha = 0.55f))
                                .ntClickable(onClickLabel = stringResource(if (playing) S.exercises_pause else S.exercises_play)) { playing = !playing },
                            contentAlignment = Alignment.Center,
                        ) {
                            NtIcon(if (playing) NtIcons.PauseFill else NtIcons.PlayFill, size = 16.dp, tint = NT.Colors.ink)
                        }
                    }
                }
            }
            failed -> NtText(
                stringResource(R.string.exercises_photoUnavailable),
                modifier = Modifier.padding(20.dp),
                style = NT.Fonts.footnote,
                color = NT.Colors.ink2,
                textAlign = TextAlign.Center,
            )
            else -> NtText(stringResource(R.string.exercises_photoLoading), style = NT.Fonts.footnote, color = NT.Colors.ink2)
        }
    }
}

private const val FORM_DEMO_INTERVAL_MS = 1100L

/**
 * `ExercisePhotoStore`: free-exercise-db photos downloaded once and kept in `cacheDir/exercise_photos`
 * and in memory, so a demo opened again plays at once and works offline.
 */
internal object ExercisePhotos {
    private val memory = LruCache<String, ImageBitmap>(24)

    private fun download(path: String): ByteArray? {
        val connection = URL(ExerciseAssets.PHOTO_BASE + path).openConnection() as HttpURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        return try {
            if (connection.responseCode != 200) null else connection.inputStream.use { it.readNBytes(2 * 1024 * 1024) }
        } finally {
            connection.disconnect()
        }
    }

    fun cached(path: String): ImageBitmap? = memory.get(path)

    suspend fun load(context: Context, path: String): ImageBitmap? = withContext(Dispatchers.IO) {
        memory.get(path)?.let { return@withContext it }
        if (path.contains("..") || path.contains(":")) return@withContext null
        val file = File(File(context.cacheDir, "exercise_photos"), path.replace("/", "__"))
        try {
            val bytes = if (file.isFile) {
                file.readBytes()
            } else {
                val downloaded = download(path) ?: return@withContext null
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeBytes(downloaded)
                tmp.renameTo(file)
                downloaded
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()?.also { memory.put(path, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}

/** The bundled `exercises.json` photo paths by id, read once. */
internal object ExerciseAssets {
    const val PHOTO_BASE = "https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/exercises/"
    val json = Json { ignoreUnknownKeys = true }
    @Volatile var images: Map<String, List<String>>? = null

    fun images(context: Context): Map<String, List<String>> = images ?: context.assets.open("exercises.json").bufferedReader().use {
        json.decodeFromString<List<ExerciseLibrary.Record>>(it.readText()).associate { row -> row.id to row.images.orEmpty() }
    }.also { images = it }
}

@Serializable
internal data class FormCueEntry(val cues: List<String>, val mistakes: List<String>)

/** `FormCues`: the app's own cues and common mistakes (`form_cues.json`) by exercise id, then language. */
internal object FormCues {
    @Volatile private var all: Map<String, Map<String, FormCueEntry>>? = null

    fun decode(text: String): Map<String, Map<String, FormCueEntry>> = ExerciseAssets.json.decodeFromString(text)

    fun all(context: Context): Map<String, Map<String, FormCueEntry>> = all ?: runCatching {
        context.assets.open("form_cues.json").bufferedReader().use { decode(it.readText()) }
    }.getOrDefault(emptyMap()).also { all = it }

    /** The entry in [language], else English. */
    fun cues(all: Map<String, Map<String, FormCueEntry>>, id: String, language: String): FormCueEntry? {
        val byLanguage = all[id] ?: return null
        return byLanguage[language] ?: byLanguage["en"]
    }
}
