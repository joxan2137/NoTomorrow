package app.notomorrow.feature.fuel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.notomorrow.R
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtFullScreenCover
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.SecondaryButton
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.util.ImageDownscaler
import app.notomorrow.util.S
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The camera half of `AIScanSourceView` — iOS opens `UIImagePickerController` in camera mode;
 * Android has no equivalent system UI that returns a full-resolution image without a
 * `FileProvider`, so this is CameraX `ImageCapture` under the app's own chrome.
 *
 * The capture is downscaled here (the rotation only exists on the `ImageProxy`), so the caller
 * receives exactly what the provider will upload: ≤[maxLongEdge] px long edge (1024 for a plate,
 * [ImageDownscaler.LABEL_LONG_EDGE] for a nutrition label), JPEG 0.8.
 */
@Composable
fun CameraCaptureScreen(
    onCaptured: (ByteArray?) -> Unit,
    onClose: () -> Unit,
    maxLongEdge: Int = ImageDownscaler.PLATE_LONG_EDGE,
) {
    CameraCaptureContent(onCaptured = onCaptured, onCancel = onClose, maxLongEdge = maxLongEdge)
}

/** `.fullScreenCover(isPresented: $showCamera) { CameraPicker … }`. */
@Composable
fun CameraCaptureCover(
    onCaptured: (ByteArray?) -> Unit,
    onCancel: () -> Unit,
    maxLongEdge: Int = ImageDownscaler.PLATE_LONG_EDGE,
) {
    NtFullScreenCover(visible = true, onDismiss = onCancel) {
        CameraCaptureContent(onCaptured = onCaptured, onCancel = onCancel, maxLongEdge = maxLongEdge)
    }
}

@Composable
private fun CameraCaptureContent(
    onCaptured: (ByteArray?) -> Unit,
    onCancel: () -> Unit,
    maxLongEdge: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val deliver by rememberUpdatedState(onCaptured)

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var capturing by remember { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result -> granted = result }

    LaunchedEffect(Unit) {
        if (!granted) permission.launch(Manifest.permission.CAMERA)
    }

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            // The upload is ≤ 1600 px; a 2048×1536 capture keeps the decode small (the sensor's
            // 12–50 MP maximum would be decoded only to be thrown away).
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            CAPTURE_SIZE,
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        ),
                    )
                    .build(),
            )
            .build()
    }
    val executor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(executor) { onDispose { executor.shutdown() } }

    LaunchedEffect(granted) {
        if (!granted) return@LaunchedEffect
        val provider = runCatching { awaitCameraProvider(context) }.getOrNull()
            ?: return@LaunchedEffect
        val preview = Preview.Builder().build()
            .apply { setSurfaceProvider(previewView.surfaceProvider) }
        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture,
            )
        }
    }

    Box(modifier.fillMaxSize().background(NT.Colors.ground)) {
        // The preview is full-bleed (`.ignoresSafeArea()`); only the chrome clears the insets.
        if (granted) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                // UIImagePickerController lays its controls out clear of the home indicator;
                // on gesture navigation the shutter would otherwise sit on the handle.
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            if (granted) {
                val shutterLabel = stringResource(S.fuel_ai_takePhoto)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 36.dp)
                        .size(72.dp)
                        .border(4.dp, NT.Colors.ink, CircleShape)
                        .padding(6.dp)
                        .background(NT.Colors.ink, CircleShape)
                        .ntPlainClickable(enabled = !capturing, role = Role.Button) {
                            capturing = true
                            capture(imageCapture, executor, maxLongEdge) { jpeg ->
                                capturing = false
                                deliver(jpeg)
                            }
                        }
                        // The shutter is a bare circle: without a label TalkBack says only "Button".
                        .semantics { contentDescription = shutterLabel },
                )
            } else {
                NtText(
                    text = stringResource(R.string.fuel_ai_cameraDenied),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = NT.Spacing.screenH),
                    style = NT.Fonts.subheadline,
                    color = NT.Colors.ink2,
                    textAlign = TextAlign.Center,
                )
            }

            SecondaryButton(
                title = stringResource(S.common_cancel),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = NT.Spacing.screenH, bottom = 44.dp)
                    .size(width = 120.dp, height = NT.Size.control),
                height = NT.Size.control,
                onClick = onCancel,
            )
        }
    }
}

/** The capture resolution asked of CameraX (closest lower, then higher). */
private val CAPTURE_SIZE = Size(2048, 1536)

/** `takePicture` → upright, downscaled JPEG bytes on the main thread (the work runs on [executor]). */
private fun capture(
    imageCapture: ImageCapture,
    executor: ExecutorService,
    maxLongEdge: Int,
    onResult: (ByteArray?) -> Unit,
) {
    val main = Handler(Looper.getMainLooper())
    imageCapture.takePicture(
        executor,
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rotation = image.imageInfo.rotationDegrees
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                image.close()
                // Sub-sampled decode, scale, then rotate: never a full-resolution bitmap.
                val jpeg = runCatching { ImageDownscaler.jpeg(bytes, rotation, maxLongEdge) }.getOrNull()
                main.post { onResult(jpeg) }
            }

            override fun onError(exception: ImageCaptureException) {
                main.post { onResult(null) }
            }
        },
    )
}

private suspend fun awaitCameraProvider(context: Context): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                try {
                    continuation.resume(future.get())
                } catch (error: Throwable) {
                    continuation.resumeWithException(error)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }
