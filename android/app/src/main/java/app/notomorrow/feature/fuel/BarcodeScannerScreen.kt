package app.notomorrow.feature.fuel

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
import androidx.compose.runtime.rememberUpdatedState
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
import app.notomorrow.service.GTINExtractor
import app.notomorrow.util.S
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions
import com.google.mlkit.vision.barcode.common.Barcode

/**
 * Rear-camera barcode scanner — the port of `Features/Fuel/BarcodeScannerView.swift`. Reads
 * EAN-13 / EAN-8 / UPC-A / UPC-E, and QR / DataMatrix only when they carry a GTIN (GS1 Digital
 * Link or element string), so a promo QR on the same pack is ignored. ML Kit cannot read GS1
 * DataBar, which iOS adds. Calls [onCode] once with the first product code ([GTINExtractor]),
 * and falls back to a manual code field where iOS falls back on the simulator: no camera
 * hardware, or `CAMERA` denied.
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

/**
 * CameraX + ML Kit. The analysis stream asks for 1920×1080: at CameraX's default size a pack's
 * EAN-13 falls under the ~190 px ML Kit needs unless the phone sits inside its focus distance.
 * ML Kit's auto-zoom then zooms in on codes that are still too small to decode. Every barcode in
 * a frame is tried, so a promo QR recognised first cannot hold the scanner while the EAN waits.
 */
@Composable
private fun CameraPreview(onCode: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { LifecycleCameraController(context) }
    val deliver by rememberUpdatedState(onCode)

    DisposableEffect(controller, lifecycleOwner) {
        val executor = ContextCompat.getMainExecutor(context)
        // Written on main, read on main (inside the posted zoom block) — the flag is only ever
        // checked where it can be trusted.
        var disposed = false
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_EAN_13,
                    Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_UPC_E,
                    Barcode.FORMAT_UPC_A,
                    Barcode.FORMAT_QR_CODE,
                    Barcode.FORMAT_DATA_MATRIX,
                )
                .enableAllPotentialBarcodes()
                // ML Kit asks for a zoom when every code in view is too small to decode, and asks
                // for 1× again (every ~500 ms) once none is. It calls this from its own worker
                // pool, NOT the main thread, and `CameraController` asserts main: called here
                // directly, every request threw and ML Kit swallowed it — so the reset to 1× never
                // happened and the preview stayed at 2–5× until the user pinched. Hop to main;
                // the camera's own range is only known once it is bound, so read it there too.
                .setZoomSuggestionOptions(
                    ZoomSuggestionOptions.Builder { ratio ->
                        executor.execute {
                            if (!disposed) {
                                controller.zoomState.value?.maxZoomRatio?.let { cameraMax ->
                                    controller.setZoomRatio(ratio.coerceAtMost(cameraMax))
                                }
                            }
                        }
                        true
                    }
                        .setMaxSupportedZoomRatio(MAX_AUTO_ZOOM)
                        .build(),
                )
                .build(),
        )
        controller.cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        controller.imageAnalysisResolutionSelector = ANALYSIS_RESOLUTION
        controller.setImageAnalysisAnalyzer(
            executor,
            MlKitAnalyzer(
                listOf(scanner),
                ImageAnalysis.COORDINATE_SYSTEM_ORIGINAL,
                executor,
            ) { result ->
                val code = result.getValue(scanner)?.firstNotNullOfOrNull { barcode ->
                    barcode.rawValue?.let { GTINExtractor.gtin(it, symbology(barcode.format)) }
                }
                if (code != null) deliver(code)
            },
        )
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            disposed = true
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

/** 1920×1080 analysis frames (16:9), else the closest size below, else above. */
private val ANALYSIS_RESOLUTION: ResolutionSelector = ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
    .setResolutionStrategy(
        ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
    )
    .build()

/**
 * The most ML Kit may ask for. Past about 5× the main camera's digital zoom only magnifies noise;
 * a camera with a shorter range clamps the request to its own maximum.
 */
private const val MAX_AUTO_ZOOM = 5f

/** ML Kit's format → what changes the payload parsing. */
private fun symbology(format: Int): GTINExtractor.Symbology = when (format) {
    Barcode.FORMAT_EAN_13 -> GTINExtractor.Symbology.Ean13
    Barcode.FORMAT_EAN_8 -> GTINExtractor.Symbology.Ean8
    Barcode.FORMAT_UPC_A -> GTINExtractor.Symbology.UpcA
    Barcode.FORMAT_UPC_E -> GTINExtractor.Symbology.UpcE
    Barcode.FORMAT_QR_CODE -> GTINExtractor.Symbology.Qr
    Barcode.FORMAT_DATA_MATRIX -> GTINExtractor.Symbology.DataMatrix
    else -> GTINExtractor.Symbology.Other
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
    // The code to look up: a GTIN whose check digit is right, or a UPC-E expanded.
    val validCode = FuelDerive.manualBarcode(digits)
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
                            onDone = { validCode?.let(onCode) },
                        ),
                    )
                }
                if (FuelDerive.showsCheckDigits(digits)) {
                    NtText(
                        text = stringResource(S.fuel_scan_checkDigits),
                        style = NT.Fonts.footnote,
                        color = NT.Colors.ember,
                    )
                }
            }
        }

        PrimaryButton(
            title = stringResource(S.fuel_scan_useCode),
            enabled = validCode != null,
            onClick = { validCode?.let(onCode) },
        )
        Spacer(Modifier.weight(1f))
    }
}
