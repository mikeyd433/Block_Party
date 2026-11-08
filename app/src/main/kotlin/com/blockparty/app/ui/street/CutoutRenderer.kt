package com.blockparty.app.ui.street

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.blockparty.core.anim.CutoutPose
import com.blockparty.core.geometry.Vec2
import kotlin.math.min
import kotlin.math.roundToInt

fun Vec2.toOffset() = Offset(x, y)

fun polygonPath(points: List<Vec2>): Path = Path().apply {
    points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
    close()
}

/**
 * Where a standing cutout's image sits (before animation), for hit testing. ([anchorX], [anchorY])
 * is the point of the image, as fractions, that rests on [foot].
 */
fun cutoutBounds(foot: Offset, size: Size, anchorX: Float = 0.5f, anchorY: Float = 1f, mirrored: Boolean = false): Rect {
    val ax = if (mirrored) 1f - anchorX else anchorX
    val left = foot.x - size.width * ax
    val top = foot.y - size.height * anchorY
    return Rect(left, top, left + size.width, top + size.height)
}

/**
 * Draws a drawing exactly as drawn, standing upright like a paper cutout with its
 * anchor point (bottom-center by default) on [foot]. The [pose] adds procedural squash/stretch, sway and lift.
 *
 * @param shadow ground shadow under the cutout (drawn before the cutout, shrinks while lifted).
 */
fun DrawScope.drawCutout(
    image: ImageBitmap,
    foot: Offset,
    size: Size,
    pose: CutoutPose,
    mirrored: Boolean,
    shadow: (DrawScope.(liftFraction: Float) -> Unit)? = null,
    alpha: Float = 1f,
    anchorX: Float = 0.5f,
    anchorY: Float = 1f,
) {
    val liftPx = pose.lift * size.height
    shadow?.invoke(this, min(1f, pose.lift * 3f))
    translate(foot.x, foot.y - liftPx) {
        rotate(pose.rotationDeg, pivot = Offset.Zero) {
            scale(scaleX = pose.scaleX * if (mirrored) -1f else 1f, scaleY = pose.scaleY, pivot = Offset.Zero) {
                val w = size.width.roundToInt().coerceAtLeast(1)
                val h = size.height.roundToInt().coerceAtLeast(1)
                drawImage(
                    image = image,
                    dstOffset = IntOffset(-(w * anchorX).roundToInt(), -(h * anchorY).roundToInt()),
                    dstSize = IntSize(w, h),
                    alpha = alpha,
                    filterQuality = FilterQuality.High,
                )
            }
        }
    }
}

/** Soft oval shadow for characters. */
fun ovalShadow(foot: Offset, width: Float): DrawScope.(Float) -> Unit = { lift ->
    val w = width * (1f - 0.4f * lift)
    drawOval(
        color = Color.Black.copy(alpha = 0.18f * (1f - 0.5f * lift)),
        topLeft = Offset(foot.x - w / 2f, foot.y - w * 0.22f),
        size = Size(w, w * 0.44f),
    )
}

/** Shadow matching a furniture footprint diamond. */
fun footprintShadow(diamond: List<Vec2>): DrawScope.(Float) -> Unit = { lift ->
    drawPath(polygonPath(diamond), Color.Black.copy(alpha = 0.13f * (1f - 0.5f * lift)))
}
