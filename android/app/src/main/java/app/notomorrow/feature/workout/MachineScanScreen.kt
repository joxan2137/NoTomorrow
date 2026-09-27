package app.notomorrow.feature.workout

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.SecondaryButton
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.pressScale
import app.notomorrow.service.MachineLabelMatcher
import app.notomorrow.service.localizedName
import app.notomorrow.util.S
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Machine scanner opened from the exercise picker — the port of
 * `Features/Workout/MachineScanView.swift`; spec in `docs/machine-scan.md`. CameraX + ML Kit text
 * recognition read the machine's placard on device, [MachineLabelMatcher] turns the text into up
 * to three exercises, and one tap adds the chosen one. Nothing leaves the phone. Without a camera,
 * or with `CAMERA` denied, a photo from the system picker does the same job.
 *
 * @param excluding already in the workout: never offered.
 * @param onSearch "Search": the most prominent text read so far, for the picker's search field.
 */
@Composable
fun MachineScanScreen(
    candidates: List<ExerciseEntity>,
    excluding: Set<String>,
    onAdd: (String) -> Unit,
    onSearch: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(hasCamera) {
        if (hasCamera && !granted) permission.launch(Manifest.permission.CAMERA)
    }

    var matcher by remember { mutableStateOf<MachineLabelMatcher?>(null) }
    LaunchedEffect(candidates) {
        val list = candidates.map { MachineLabelMatcher.Candidate(it.id, it.name, it.namePL, it.equipment) }
        matcher = withContext(Dispatchers.Default) { MachineLabelMatcher(list) }
    }
    val byId = remember(candidates) { candidates.associateBy { it.id } }

    var matches by remember { mutableStateOf<List<MachineLabelMatcher.Match>>(emptyList()) }
    var headline by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf(PhotoState.None) }
    var fired by remember { mutableStateOf(false) }

    // Live frames keep the last matches on screen while the camera briefly loses the text, so the
    // rows do not flicker away under a thumb; a photo always shows its own result.
    val receive: (List<MachineLabelMatcher.Line>) -> Unit = receive@{ lines ->
        val m = matcher ?: return@receive
        if (fired) return@receive
        lines.maxByOrNull { it.weight }?.let { headline = it.text }
        val found = m.match(lines, excluding)
        if (found.isNotEmpty() || photo != PhotoState.None) {
            if (found.isNotEmpty() && found.first().id != matches.firstOrNull()?.id) {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }
            matches = found
        }
    }
    val add: (String) -> Unit = { id ->
        if (!fired) {
            fired = true
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            onAdd(id)
        }
    }

    val library = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            photo = PhotoState.Reading
            matches = emptyList()
            scope.launch {
                val lines = MachineLabelReader.lines(context, uri)
                photo = PhotoState.Done
                receive(lines)
            }
        }
    }

    val live = hasCamera && granted && photo == PhotoState.None
    Box(modifier.fillMaxSize().background(NT.Colors.ground)) {
        if (live) LiveTextPreview(onLines = receive, modifier = Modifier.fillMaxSize())
        MachineScanChrome(
            live = live,
            status = when (photo) {
                PhotoState.Reading -> S.scan_machine_reading
                PhotoState.Done -> S.scan_machine_noMatch
                PhotoState.None -> if (live) S.scan_machine_looking else S.scan_machine_photoHint
            },
            matches = matches.mapNotNull { byId[it.id] },
            onAdd = add,
            onPhoto = { library.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onSearch = { onSearch(headline) },
            onCancel = onCancel,
        )
    }
}

/**
 * Everything drawn over the camera: the hint pill on top, and the bottom panel with the matches
 * (or [status] while there are none), Photo, Search and Cancel.
 */
@Composable
internal fun MachineScanChrome(
    live: Boolean,
    @StringRes status: Int,
    matches: List<ExerciseEntity>,
    onAdd: (String) -> Unit,
    onPhoto: () -> Unit,
    onSearch: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        NtText(
            text = stringResource(if (live) S.scan_machine_hint else S.scan_machine_photoHint),
            modifier = Modifier
                .padding(top = 12.dp, start = NT.Spacing.screenH, end = NT.Spacing.screenH)
                .background(NT.Colors.ground.copy(alpha = 0.85f), CircleShape)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            style = NT.Fonts.subheadline,
            color = NT.Colors.ink,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 8.dp)
                .fillMaxWidth()
                .background(NT.Colors.ground.copy(alpha = 0.94f), NtShapes.tile)
                .padding(NT.Spacing.cardPadding)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (matches.isEmpty()) {
                NtText(
                    text = stringResource(status),
                    modifier = Modifier.padding(vertical = 6.dp),
                    style = NT.Fonts.subheadline,
                    color = NT.Colors.ink2,
                )
            } else {
                Eyebrow(stringResource(S.scan_machine_matches))
                matches.forEachIndexed { index, exercise ->
                    MachineMatchRow(exercise = exercise, isBest = index == 0, onAdd = { onAdd(exercise.id) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(
                    title = stringResource(S.scan_machine_photo),
                    modifier = Modifier.weight(1f),
                    icon = NtIcons.PhotoOnRectangle,
                    height = NT.Size.control,
                    onClick = onPhoto,
                )
                SecondaryButton(
                    title = stringResource(S.scan_machine_search),
                    modifier = Modifier.weight(1f),
                    icon = NtIcons.MagnifyingGlass,
                    height = NT.Size.control,
                    onClick = onSearch,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(NT.Size.control)
                    .ntPlainClickable(onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) {
                NtText(text = stringResource(S.common_cancel), style = NT.Fonts.headline, color = NT.Colors.ink2)
            }
        }
    }
}

private enum class PhotoState { None, Reading, Done }

/** One match: name and muscles, and an Add pill (filled for the best match). */
@Composable
private fun MachineMatchRow(exercise: ExerciseEntity, isBest: Boolean, onAdd: () -> Unit) {
    val name = exercise.localizedName()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .background(NT.Colors.surface, NtShapes.tile)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            NtText(
                text = name,
                style = if (isBest) NT.Fonts.headline else NT.Fonts.subheadline,
                color = NT.Colors.ink,
                maxLines = 2,
            )
            NtText(text = exerciseSubtitle(exercise), style = NT.Fonts.footnote, color = NT.Colors.ink2, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        val addLabel = stringResource(S.scan_machine_add)
        Box(
            modifier = Modifier
                .height(36.dp)
                .background(if (isBest) NT.Colors.ink else NT.Colors.surface2, CircleShape)
                .pressScale(onClick = onAdd)
                .semantics { contentDescription = "$addLabel $name" }
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            NtText(text = addLabel, style = NT.Fonts.headline, color = if (isBest) NT.Colors.onPrimary else NT.Colors.ink)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Text reading
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Turns recognised text into [MachineLabelMatcher.Line]s: each line weighted by its height over
 * the tallest one's, so the machine's big name outweighs the fine print.
 */
object MachineLabelReader {

    fun weighted(raw: List<Pair<String, Double>>): List<MachineLabelMatcher.Line> {
        val kept = raw.filter { it.first.isNotBlank() && it.second > 0 }
        val tallest = kept.maxOfOrNull { it.second } ?: return emptyList()
        return kept.map { MachineLabelMatcher.Line(it.first, it.second / tallest) }
    }

    /** ML Kit's lines with their box heights. */
    fun weighted(text: Text): List<MachineLabelMatcher.Line> = weighted(
        text.textBlocks.flatMap { block ->
            block.lines.mapNotNull { line -> line.boundingBox?.let { line.text to it.height().toDouble() } }
        },
    )

    /** On-device recognition of a picked photo (the file's EXIF rotation is applied). */
    suspend fun lines(context: android.content.Context, uri: Uri): List<MachineLabelMatcher.Line> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val image = withContext(Dispatchers.IO) { InputImage.fromFilePath(context, uri) }
            weighted(recognizer.process(image).await())
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            emptyList()
        } finally {
            recognizer.close()
        }
    }
}

/** Matching every analysis frame would redo the work for text that barely changed. */
private const val LIVE_INTERVAL_MS = 300L

/** CameraX preview with an ML Kit text analyzer, delivering weighted lines at most every [LIVE_INTERVAL_MS]. */
@Composable
private fun LiveTextPreview(onLines: (List<MachineLabelMatcher.Line>) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { LifecycleCameraController(context) }
    val deliver by rememberUpdatedState(onLines)

    DisposableEffect(controller, lifecycleOwner) {
        val executor = ContextCompat.getMainExecutor(context)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        var last = 0L
        controller.cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        controller.imageAnalysisResolutionSelector = TEXT_ANALYSIS_RESOLUTION
        controller.setImageAnalysisAnalyzer(
            executor,
            MlKitAnalyzer(listOf(recognizer), ImageAnalysis.COORDINATE_SYSTEM_ORIGINAL, executor) { result ->
                val now = System.currentTimeMillis()
                val text = result.getValue(recognizer) ?: return@MlKitAnalyzer
                if (now - last < LIVE_INTERVAL_MS) return@MlKitAnalyzer
                last = now
                deliver(MachineLabelReader.weighted(text))
            },
        )
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            recognizer.close()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                this.controller = controller
            }
        },
    )
}

/** 1280×720 frames: placard names are large text, and a smaller frame keeps recognition quick. */
private val TEXT_ANALYSIS_RESOLUTION: ResolutionSelector = ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
    .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
    .build()
