package app.notomorrow.feature.fuel

import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.PrimaryButton
import app.notomorrow.designsystem.SecondaryButton
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.model.MealSlot
import app.notomorrow.util.NtKeys
import app.notomorrow.util.S

/**
 * `AIScanSourceView` (`Features/Fuel/AIScanSourceView.swift`) — step 1: where the photo comes
 * from. Camera when the device has one, photo library always.
 *
 * The library button needs no permission at all: `PickVisualMedia` runs out of process
 * (research §, "never declare READ_MEDIA_IMAGES").
 */
@Composable
fun AIScanSourceView(
    meal: MealSlot,
    notes: String = "",
    onNotes: (String) -> Unit = {},
    onPickedFromLibrary: (Uri) -> Unit,
    onCaptured: (ByteArray?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cameraAvailable = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    var showCamera by remember { mutableStateOf(false) }

    val library = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onPickedFromLibrary(uri)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = NT.Spacing.screenH)
            .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(NT.Spacing.section),
        horizontalAlignment = Alignment.Start,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Eyebrow(stringResource(NtKeys.meal(meal)))
            NtText(
                text = stringResource(S.fuel_ai_source_subtitle),
                style = NT.Fonts.subheadline,
                color = NT.Colors.ink2,
            )
        }

        AIMealNotes(notes, onNotes)
        AIScanPlateIllustration()

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (cameraAvailable) {
                PrimaryButton(
                    title = stringResource(S.fuel_ai_takePhoto),
                    icon = NtIcons.Camera,
                ) { showCamera = true }
            }
            // Same pill either way; it is the primary one only when there is no camera.
            val chooseLibrary = stringResource(S.fuel_ai_chooseLibrary)
            val launchLibrary = {
                library.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            }
            if (cameraAvailable) {
                SecondaryButton(
                    title = chooseLibrary,
                    icon = NtIcons.PhotoOnRectangle,
                    onClick = launchLibrary,
                )
            } else {
                PrimaryButton(
                    title = chooseLibrary,
                    icon = NtIcons.PhotoOnRectangle,
                    onClick = launchLibrary,
                )
            }
        }
    }

    if (showCamera) {
        CameraCaptureCover(
            onCaptured = { jpeg ->
                showCamera = false
                onCaptured(jpeg)
            },
            onCancel = { showCamera = false },
        )
    }
}

/**
 * `AIScanSourceView.plateIllustration` — the empty-state visual: a plate outline in the photo
 * frame, 210 dp like the result photo.
 */
@Composable
private fun AIScanPlateIllustration(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(AIScanPhotoHeight)
            .background(NT.Colors.surface, NtShapes.card),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val centre = Offset(size.width / 2f, size.height / 2f)
            drawCircle(
                color = NT.Colors.surface3,
                radius = 75.dp.toPx(),
                center = centre,
                style = Stroke(width = 2.dp.toPx()),
            )
            drawCircle(
                color = NT.Colors.surface2,
                radius = 59.dp.toPx(),
                center = centre,
                style = Stroke(width = 2.dp.toPx()),
            )
        }
        NtIcon(NtIcons.ForkKnife, size = sfIconSize(30f), tint = NT.Colors.ink3)
    }
}

@Composable
fun AIMealNotes(notes: String, onNotes: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        NtText(stringResource(app.notomorrow.R.string.fuel_ai_accuracyHint), style = NT.Fonts.footnote, color = NT.Colors.ink2)
        androidx.compose.material3.OutlinedTextField(
            value = notes, onValueChange = onNotes, modifier = Modifier.fillMaxWidth(),
            label = { NtText(stringResource(app.notomorrow.R.string.fuel_ai_details), style = NT.Fonts.footnote) },
            placeholder = { NtText(stringResource(app.notomorrow.R.string.fuel_ai_detailsPlaceholder), style = NT.Fonts.footnote) },
            minLines = 2, maxLines = 4,
        )
    }
}
