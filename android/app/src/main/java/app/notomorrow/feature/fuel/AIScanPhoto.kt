package app.notomorrow.feature.fuel

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtText
import kotlin.math.roundToInt

/** `AIScanPhoto` and the source view's plate frame are both 210 pt tall. */
val AIScanPhotoHeight: Dp = 210.dp

/**
 * `AIScanPhoto` (`AIScanResultView.swift:200`) — a 210 dp photo at the 22 dp card radius, with
 * one pill per recognised food laid over a fixed anchor grid.
 *
 * SwiftUI's `.position(x:y:)` places the tag's **centre**, so the tags go through a small
 * [Layout] rather than `offset`.
 */
@Composable
fun AIScanPhoto(
    photo: ImageBitmap?,
    tags: List<String>,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(AIScanPhotoHeight)
            .clip(NtShapes.card)
            .background(NT.Colors.surface),
    ) {
        if (photo != null) {
            Image(
                bitmap = photo,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        AIScanTagLayer(tags)
    }
}

@Composable
private fun AIScanTagLayer(tags: List<String>) {
    val shown = tags.take(AIScanDerive.TagAnchors.size)
    if (shown.isEmpty()) return
    val density = LocalDensity.current
    val offsetX = with(density) { 50.dp.toPx() }
    val offsetY = with(density) { 13.dp.toPx() }

    Layout(
        content = { shown.forEach { name -> DetectionTag(name) } },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val placeables = measurables.map { it.measure(Constraints(maxWidth = width)) }
        layout(width, height) {
            placeables.forEachIndexed { index, placeable ->
                val (ax, ay) = AIScanDerive.TagAnchors[index]
                val x = width * ax + offsetX
                val y = height * ay + offsetY
                placeable.place(
                    x = (x - placeable.width / 2f).roundToInt(),
                    y = (y - placeable.height / 2f).roundToInt(),
                )
            }
        }
    }
}

/** One detection pill: an ember dot and the food name on a translucent `ground` capsule. */
@Composable
private fun DetectionTag(name: String) {
    Row(
        modifier = Modifier
            .height(26.dp)
            .widthIn(max = 150.dp)
            .background(NT.Colors.ground.copy(alpha = 0.85f), CircleShape)
            .border(1.dp, NT.Colors.border, CircleShape)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(NT.Colors.ember, CircleShape))
        NtText(
            text = name,
            style = NT.Fonts.caption,
            color = NT.Colors.ink,
            maxLines = 1,
        )
    }
}
