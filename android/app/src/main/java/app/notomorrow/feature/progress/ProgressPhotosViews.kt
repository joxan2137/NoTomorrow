package app.notomorrow.feature.progress

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.notomorrow.data.entity.ProgressPhotoEntity
import app.notomorrow.data.files.ProgressPhotoFiles
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtActionSheet
import app.notomorrow.designsystem.NtAlert
import app.notomorrow.designsystem.NtAlertAction
import app.notomorrow.designsystem.NtAlertRole
import app.notomorrow.designsystem.NtFullScreenCover
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtMenu
import app.notomorrow.designsystem.NtMenuItem
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtSpinner
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.appLocale
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.pressScale
import app.notomorrow.di.LocalAppContainer
import app.notomorrow.model.ProgressPose
import app.notomorrow.util.ImageDownscaler
import app.notomorrow.util.NtKeys
import app.notomorrow.util.S
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val TileWidth = 84.dp
private val TileHeight = 112.dp

/**
 * Progress › Body, under the measurements — `ProgressPhotosSection`: private progress photos. A
 * strip of thumbnails (newest first) after an Add photo tile (the system Photo Picker, no storage
 * permission), a full-screen viewer and Compare (two photos side by side). Photos never leave the
 * phone. No camera here: iOS offers only the library too.
 */
@Composable
fun ProgressPhotosSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dao = LocalAppContainer.current.db.progressPhotoDao()
    val files = remember(context) { ProgressPhotoFiles.inFilesDir(context.filesDir) }
    val rows by remember(dao) { dao.observeAll() }.collectAsState(initial = emptyList())
    val photos = ProgressPhotos.newestFirst(rows)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    var importing by remember { mutableStateOf(false) }
    var pendingJpeg by remember { mutableStateOf<ByteArray?>(null) }
    var showsFailure by remember { mutableStateOf(false) }
    var viewerStart by remember { mutableStateOf<String?>(null) }
    // Kept after close so the cover's exit slide still has something to draw.
    var lastViewerStart by remember { mutableStateOf<String?>(null) }
    var showsCompare by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            importing = true
            scope.launch {
                val jpeg = withContext(Dispatchers.IO) {
                    runCatching {
                        ImageDownscaler.jpeg(context, uri, ProgressPhotos.MAX_LONG_EDGE, ProgressPhotos.QUALITY)
                    }.getOrNull()
                }
                importing = false
                if (jpeg != null) pendingJpeg = jpeg else showsFailure = true
            }
        }
    }

    fun save(jpeg: ByteArray, pose: ProgressPose?) {
        pendingJpeg = null
        scope.launch {
            val saved = runCatching { ProgressPhotos.add(jpeg, pose, dao, files) }
            if (saved.isSuccess) haptics.performHapticFeedback(HapticFeedbackType.Confirm) else showsFailure = true
        }
    }

    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = NT.Size.control),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NtText(
                text = stringResource(S.photos_title),
                modifier = Modifier.weight(1f),
                style = NT.Fonts.headline,
                color = NT.Colors.ink,
                maxLines = 1,
            )
            if (photos.size >= 2) {
                Box(
                    modifier = Modifier.heightIn(min = NT.Size.control).pressScale { showsCompare = true },
                    contentAlignment = Alignment.Center,
                ) {
                    NtText(
                        stringResource(S.photos_compare),
                        style = NT.Fonts.subheadlineBold,
                        color = NT.Colors.ember,
                        maxLines = 1,
                    )
                }
            }
        }
        NtText(stringResource(S.photos_private), style = NT.Fonts.footnote, color = NT.Colors.ink2)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AddPhotoTile(importing = importing) {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            photos.forEach { photo ->
                PhotoThumbnail(photo, files) {
                    viewerStart = photo.id
                    lastViewerStart = photo.id
                }
            }
        }
    }

    pendingJpeg?.let { jpeg ->
        NtActionSheet(
            title = stringResource(S.photos_pose_question),
            actions = ProgressPose.entries.map { pose ->
                NtAlertAction(stringResource(NtKeys.pose(pose))) { save(jpeg, pose) }
            } + NtAlertAction(stringResource(S.photos_pose_none)) { save(jpeg, null) },
            cancel = stringResource(S.common_cancel),
            onDismiss = { pendingJpeg = null },
        )
    }

    if (showsFailure) {
        NtAlert(
            title = stringResource(S.photos_failed),
            actions = listOf(NtAlertAction(stringResource(S.common_done), NtAlertRole.Cancel)),
            onDismiss = { showsFailure = false },
        )
    }

    NtFullScreenCover(visible = viewerStart != null, onDismiss = { viewerStart = null }) {
        ProgressPhotoViewer(
            photos = photos,
            startId = lastViewerStart,
            files = files,
            onDelete = { photo -> scope.launch { ProgressPhotos.delete(photo, dao, files) } },
            onClose = { viewerStart = null },
        )
    }

    NtFullScreenCover(visible = showsCompare && photos.size >= 2, onDismiss = { showsCompare = false }) {
        ProgressPhotoCompare(photos = photos, files = files, onClose = { showsCompare = false })
    }
}

@Composable
private fun AddPhotoTile(importing: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .size(TileWidth, TileHeight)
            .pressScale(enabled = !importing, onClick = onClick)
            .background(NT.Colors.surface2, NtShapes.cell)
            .padding(horizontal = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (importing) {
            NtSpinner()
        } else {
            NtIcon(NtIcons.Plus, size = 20.dp, tint = NT.Colors.ink)
        }
        NtText(
            text = stringResource(S.photos_add),
            style = NT.Fonts.caption,
            color = NT.Colors.ink,
            maxLines = 2,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PhotoThumbnail(photo: ProgressPhotoEntity, files: ProgressPhotoFiles, onClick: () -> Unit) {
    val description = photoDescription(photo)
    Box(
        modifier = Modifier
            .size(TileWidth, TileHeight)
            .pressScale(onClick = onClick)
            .semantics { contentDescription = description }
            .clip(NtShapes.cell),
    ) {
        PhotoImage(files.file(photo.fileName), maxPixel = 320, crop = true, modifier = Modifier.fillMaxSize())
        NtText(
            text = ProgressPhotos.shortDateLabel(photo.takenAt),
            modifier = Modifier
                .clearAndSetSemantics {}
                .align(Alignment.BottomStart)
                .padding(5.dp)
                .background(Color.Black.copy(alpha = 0.55f), NtShapes.capsule)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            style = NT.Fonts.caption,
            color = Color.White,
            maxLines = 1,
        )
    }
}

/** TalkBack's name for a photo: "Progress photo, front, 26 September 2026". */
@Composable
private fun photoDescription(photo: ProgressPhotoEntity): String {
    val date = ProgressPhotos.dateLabel(photo.takenAt)
    val pose = ProgressPhotos.pose(photo) ?: return stringResource(S.photos_a11y, date)
    return stringResource(S.photos_a11y_pose, stringResource(NtKeys.pose(pose)).lowercase(appLocale()), date)
}

/** A stored photo decoded at about [maxPixel] on its long edge, off the main thread. */
@Composable
private fun PhotoImage(file: File, maxPixel: Int, crop: Boolean, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, file, maxPixel) {
        value = withContext(Dispatchers.IO) { decodePhoto(file, maxPixel) }
    }
    Box(modifier.background(if (crop) NT.Colors.surface2 else Color.Transparent)) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
            )
        }
    }
}

/** Stored photos are upright already (the rotation was baked in on save), so no EXIF here. */
private fun decodePhoto(file: File, maxPixel: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    val target = ImageDownscaler.targetSize(bounds.outWidth, bounds.outHeight, maxPixel)
        ?: return@runCatching null
    val options = BitmapFactory.Options().apply {
        inSampleSize = ImageDownscaler.sampleSize(bounds.outWidth, bounds.outHeight, target.first, target.second)
    }
    BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
}.getOrNull()

// MARK: - Viewer

/** Full screen: swipe between the photos (newest first), date and pose under each, Delete with a confirmation. */
@Composable
private fun ProgressPhotoViewer(
    photos: List<ProgressPhotoEntity>,
    startId: String?,
    files: ProgressPhotoFiles,
    onDelete: (ProgressPhotoEntity) -> Unit,
    onClose: () -> Unit,
) {
    val initial = remember { photos.indexOfFirst { it.id == startId }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = initial) { photos.size }
    val scope = rememberCoroutineScope()
    var confirmsDelete by remember { mutableStateOf(false) }
    val index = pager.currentPage.coerceAtMost((photos.size - 1).coerceAtLeast(0))
    val current = photos.getOrNull(index)

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ViewerIcon(NtIcons.Xmark, stringResource(S.common_done), NT.Colors.ink, onClose)
            NtText(
                text = if (photos.size > 1) "${index + 1} / ${photos.size}" else "",
                modifier = Modifier.weight(1f),
                style = NT.Fonts.subheadline,
                color = NT.Colors.ink2,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
            ViewerIcon(NtIcons.Trash, stringResource(S.common_delete), NT.Colors.bad) {
                if (current != null) confirmsDelete = true
            }
        }
        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            key = { page -> photos.getOrNull(page)?.id ?: page },
        ) { page ->
            photos.getOrNull(page)?.let { photo ->
                val description = photoDescription(photo)
                PhotoImage(
                    files.file(photo.fileName),
                    maxPixel = 2048,
                    crop = false,
                    modifier = Modifier.fillMaxSize().semantics {
                        contentDescription = description
                        role = Role.Image
                    },
                )
            }
        }
        if (current != null) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                NtText(ProgressPhotos.dateLabel(current.takenAt), style = NT.Fonts.headline, color = NT.Colors.ink)
                ProgressPhotos.pose(current)?.let { pose ->
                    NtText(stringResource(NtKeys.pose(pose)), style = NT.Fonts.subheadline, color = NT.Colors.ink2)
                }
            }
        }
    }

    if (confirmsDelete && current != null) {
        NtAlert(
            title = stringResource(S.photos_delete_title),
            message = stringResource(S.photos_delete_message),
            actions = listOf(
                NtAlertAction(stringResource(S.common_cancel), NtAlertRole.Cancel),
                NtAlertAction(stringResource(S.common_delete), NtAlertRole.Destructive) {
                    val next = ProgressPhotos.indexAfterDeleting(index, photos.size)
                    onDelete(current)
                    if (next == null) onClose() else scope.launch { pager.scrollToPage(next) }
                },
            ),
            onDismiss = { confirmsDelete = false },
        )
    }
}

@Composable
private fun ViewerIcon(icon: NtIcons, label: String, tint: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(NT.Size.control).ntPlainClickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        NtIcon(icon, size = 20.dp, tint = tint, contentDescription = label)
    }
}

// MARK: - Compare

/** Two photos side by side: the first against the latest to start with; each side picks any photo from a menu. */
@Composable
private fun ProgressPhotoCompare(
    photos: List<ProgressPhotoEntity>,
    files: ProgressPhotoFiles,
    onClose: () -> Unit,
) {
    var beforeId by remember { mutableStateOf<String?>(null) }
    var afterId by remember { mutableStateOf<String?>(null) }
    val defaults = ProgressPhotos.defaultComparison(photos)

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.align(Alignment.CenterStart)) {
                ViewerIcon(NtIcons.Xmark, stringResource(S.common_done), NT.Colors.ink, onClose)
            }
            NtText(stringResource(S.photos_compare), style = NT.Fonts.headline, color = NT.Colors.ink, maxLines = 1)
        }
        if (defaults != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CompareColumn(
                    photo = photos.firstOrNull { it.id == beforeId } ?: defaults.first,
                    options = photos,
                    title = stringResource(S.photos_before),
                    files = files,
                    onPick = { beforeId = it },
                    modifier = Modifier.weight(1f),
                )
                CompareColumn(
                    photo = photos.firstOrNull { it.id == afterId } ?: defaults.second,
                    options = photos,
                    title = stringResource(S.photos_after),
                    files = files,
                    onPick = { afterId = it },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun CompareColumn(
    photo: ProgressPhotoEntity,
    options: List<ProgressPhotoEntity>,
    title: String,
    files: ProgressPhotoFiles,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val poseLabels = ProgressPose.entries.associateWith { stringResource(NtKeys.pose(it)) }
    fun label(row: ProgressPhotoEntity): String {
        val pose = ProgressPhotos.pose(row)?.let { poseLabels[it] }
        val date = ProgressPhotos.dateLabel(row.takenAt)
        return if (pose == null) date else "$date · $pose"
    }

    val description = title + ": " + photoDescription(photo)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        PhotoImage(
            files.file(photo.fileName),
            maxPixel = 1200,
            crop = true,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(NtShapes.cell)
                .semantics {
                    contentDescription = description
                    role = Role.Image
                },
        )
        Box(
            modifier = Modifier.fillMaxWidth().heightIn(min = NT.Size.control).ntPlainClickable { expanded = true },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Eyebrow(title)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    NtText(label(photo), style = NT.Fonts.footnoteBold, color = NT.Colors.ink, maxLines = 1)
                    NtIcon(NtIcons.ChevronDown, size = 11.dp, tint = NT.Colors.ink)
                }
            }
            NtMenu(
                expanded = expanded,
                onDismiss = { expanded = false },
                items = options.map { option ->
                    NtMenuItem(title = label(option), onClick = {
                        onPick(option.id)
                        expanded = false
                    })
                },
            )
        }
    }
}
