package app.notomorrow.feature.fuel

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.NTCard
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.PrimaryButton
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.pressScale
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.designsystem.tabular
import app.notomorrow.util.S
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode

/**
 * Rear-camera barcode scanner (EAN-13 / EAN-8 / UPC-E / UPC-A) — the port of
 * `Features/Fuel/BarcodeScannerView.swift`. Calls [onCode] once, for the first recognised
 * barcode, and falls back to a manual code field where iOS falls back on the simulator:
 * no camera hardware, or `CAMERA` denied.
 */
@Composable
fun BarcodeScannerScreen(
    onCode: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var manual by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val hasCamera = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var settled by remember { mutableStateOf(!hasCamera) }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result ->
        granted = result
        settled = true
    }

    LaunchedEffect(hasCamera, granted) {
        if (hasCamera && !granted) permission.launch(Manifest.permission.CAMERA) else settled = true
    }

    val haptics = LocalHapticFeedback.current
    var fired by remember { mutableStateOf(false) }
    val deliver: (String) -> Unit = { code ->
        if (!fired) {
            fired = true
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            onCode(code)
        }
    }

    Box(modifier.fillMaxSize().background(NT.Colors.ground)) {
        if (hasCamera && granted && !manual) {
            CameraPreview(onCode = deliver, modifier = Modifier.fillMaxSize())
            CameraChrome(onCancel = onCancel, onManual = { manual = true })
        } else if (settled) {
            ManualBarcodeEntry(onCode = deliver, onCancel = onCancel)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Camera
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CameraPreview(onCode: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { LifecycleCameraController(context) }

    DisposableEffect(controller, lifecycleOwner) {
        val executor = ContextCompat.getMainExecutor(context)
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_EAN_13,
                    Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_UPC_E,
                    Barcode.FORMAT_UPC_A,
                )
                .enableAllPotentialBarcodes()
                .build(),
        )
        controller.cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        controller.setImageAnalysisAnalyzer(
            executor,
            MlKitAnalyzer(
                listOf(scanner),
                ImageAnalysis.COORDINATE_SYSTEM_ORIGINAL,
                executor,
            ) { result ->
                val payload = result.getValue(scanner)
                    ?.firstNotNullOfOrNull { it.rawValue?.takeIf(String::isNotEmpty) }
                if (payload != null) onCode(payload)
            },
        )
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            scanner.close()
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

/** `BarcodeScannerView.cameraChrome` — the hint pill on top, Cancel on the bottom. */
@Composable
private fun CameraChrome(onCancel: () -> Unit, onManual: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 12.dp)
                .height(36.dp)
                .background(NT.Colors.ground.copy(alpha = 0.85f), CircleShape)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            NtText(
                text = stringResource(S.fuel_scan_hint),
                style = NT.Fonts.subheadline,
                color = NT.Colors.ink,
                maxLines = 1,
            )
        }
        Spacer(Modifier.weight(1f))
        androidx.compose.material3.TextButton(onClick = onManual) { NtText(stringResource(app.notomorrow.R.string.fuel_scan_manual), style = NT.Fonts.body) }
        Box(
            modifier = Modifier
                .padding(horizontal = NT.Spacing.screenH)
                .padding(bottom = 12.dp)
                .fillMaxWidth()
                .height(NT.Size.primaryButton)
                .pressScale(onClick = onCancel)
                .background(NT.Colors.surface2.copy(alpha = 0.9f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            NtText(
                text = stringResource(S.common_cancel),
                style = NT.Fonts.headline,
                color = NT.Colors.ink,
                maxLines = 1,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Manual fallback (no camera, or CAMERA denied)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ManualBarcodeEntry(onCode: (String) -> Unit, onCancel: () -> Unit) {
    var code by remember { mutableStateOf("") }
    val digits = FuelDerive.barcodeDigits(code)
    val valid = app.notomorrow.service.FoodSearchService.barcodeForms(digits).isNotEmpty()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = NT.Spacing.screenH)
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(NT.Spacing.section),
        horizontalAlignment = Alignment.Start,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(NT.Size.control),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(NT.Size.control)
                    .ntPlainClickable(
                        onClickLabel = stringResource(S.common_back),
                        onClick = onCancel,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                NtIcon(NtIcons.ArrowLeft, size = sfIconSize(20f), tint = NT.Colors.ink)
            }
            NtText(
                text = stringResource(S.fuel_scan_title),
                style = NT.Fonts.title2,
                color = NT.Colors.ink,
                maxLines = 1,
            )
        }

        NtText(
            text = stringResource(S.fuel_scan_unavailable),
            style = NT.Fonts.subheadline,
            color = NT.Colors.ink2,
        )

        NTCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Eyebrow(text = stringResource(S.fuel_barcode))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(NT.Size.control + 4.dp)
                        .background(NT.Colors.surface2, NtShapes.field)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (code.isEmpty()) {
                        NtText(
                            text = stringResource(S.fuel_scan_codePlaceholder),
                            style = NT.Fonts.title2,
                            color = NT.Colors.ink3,
                            maxLines = 1,
                        )
                    }
                    BasicTextField(
                        value = code,
                        onValueChange = { code = it },
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        textStyle = NT.Fonts.title2.tabular().copy(color = NT.Colors.ink),
                        singleLine = true,
                        cursorBrush = SolidColor(NT.Colors.ink),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { if (valid) onCode(digits) },
                        ),
                    )
                }
            }
        }

        PrimaryButton(
            title = stringResource(S.fuel_scan_useCode),
            enabled = valid,
            onClick = { onCode(digits) },
        )
        Spacer(Modifier.weight(1f))
    }
}
