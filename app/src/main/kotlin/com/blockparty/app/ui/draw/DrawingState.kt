package com.blockparty.app.ui.draw

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.blockparty.core.art.ImageOps
import com.blockparty.core.art.StrokeOps
import com.blockparty.core.geometry.DepthProjection
import com.blockparty.core.geometry.Facing
import com.blockparty.core.geometry.Vec2
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.math.max
import kotlin.math.min

/** One freehand stroke in canvas (logical pixel) coordinates. */
class DrawnStroke(val points: List<Offset>, val color: Color, val width: Float, val eraser: Boolean) {
    val path: Path by lazy { smoothPath(points) }
}

/** Smooth path through the points using quadratic curves between midpoints. */
fun smoothPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    val first = points.first()
    path.moveTo(first.x, first.y)
    if (points.size == 1) {
        path.lineTo(first.x + 0.1f, first.y + 0.1f) // a dot
        return path
    }
    for (i in 1 until points.size) {
        val prev = points[i - 1]
        val cur = points[i]
        path.quadTo(prev.x, prev.y, (prev.x + cur.x) / 2f, (prev.y + cur.y) / 2f)
    }
    val last = points.last()
    path.lineTo(last.x, last.y)
    return path
}

/** Editable drawing: strokes over an optional uploaded image. */
class DrawingState(val width: Int, val height: Int) {
    val strokes = mutableStateListOf<DrawnStroke>()
    private val redoStack = mutableStateListOf<DrawnStroke>()
    val current = mutableStateListOf<Offset>()

    var color by mutableStateOf(Color(0xFF2E2B26))
    var brushWidth by mutableStateOf(18f)
    var eraser by mutableStateOf(false)

    /** Uploaded image as picked, and with its background removed. */
    var uploaded by mutableStateOf<Bitmap?>(null)
    var uploadedCutout by mutableStateOf<Bitmap?>(null)
    var removeBackground by mutableStateOf(true)

    /**
     * Full-canvas layer the user keeps drawing over after "Add depth & turn". While set, the
     * flat drawing it was made from is kept in [flatSnapshot] so the turn can be undone.
     */
    var baked by mutableStateOf<Bitmap?>(null)
        private set
    private var flatSnapshot: Snapshot? = null

    val isTurned: Boolean get() = baked != null

    val baseImage: Bitmap?
        get() = baked ?: if (removeBackground) uploadedCutout ?: uploaded else uploaded

    private class Snapshot(
        val strokes: List<DrawnStroke>,
        val uploaded: Bitmap?,
        val uploadedCutout: Bitmap?,
        val removeBackground: Boolean,
    )

    val canUndo get() = strokes.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()
    val isEmpty get() = strokes.none { !it.eraser } && uploaded == null && baked == null

    fun begin(p: Offset) {
        current.clear()
        current.add(p)
    }

    fun extend(p: Offset) {
        if (current.isNotEmpty()) current.add(p)
    }

    fun end() {
        if (current.isEmpty()) return
        val thinned = StrokeOps.thin(current.map { Vec2(it.x, it.y) }, minDistance = 1.5f).map { Offset(it.x, it.y) }
        strokes.add(DrawnStroke(thinned, color, brushWidth, eraser))
        redoStack.clear()
        current.clear()
    }

    fun undo() {
        strokes.removeLastOrNull()?.let(redoStack::add)
    }

    fun redo() {
        redoStack.removeLastOrNull()?.let(strokes::add)
    }

    fun clear() {
        strokes.clear()
        redoStack.clear()
        uploaded = null
        uploadedCutout = null
        baked = null
        flatSnapshot = null
    }

    /** Replaces the flat drawing with its turned, extruded version and starts a fresh stroke layer. */
    fun applyTurned(result: Bitmap) {
        flatSnapshot = Snapshot(strokes.toList(), uploaded, uploadedCutout, removeBackground)
        baked = result
        strokes.clear()
        redoStack.clear()
        current.clear()
    }

    /** Restores the flat drawing from before the turn, dropping anything drawn since. */
    fun backToFlat() {
        val snap = flatSnapshot ?: return
        strokes.clear()
        strokes.addAll(snap.strokes)
        redoStack.clear()
        uploaded = snap.uploaded
        uploadedCutout = snap.uploadedCutout
        removeBackground = snap.removeBackground
        baked = null
        flatSnapshot = null
    }

    /** Where the uploaded image is drawn: centered and scaled to fit the canvas. */
    fun baseImageRect(bitmap: Bitmap): RectF {
        if (bitmap === baked) return RectF(0f, 0f, width.toFloat(), height.toFloat())
        val scale = min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        return RectF(left, top, left + w, top + h)
    }

    /** Renders the drawing at full canvas resolution with a transparent background. */
    fun rasterize(): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        baseImage?.let { canvas.drawBitmap(it, null, baseImageRect(it), Paint(Paint.FILTER_BITMAP_FLAG)) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val clear = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        for (s in strokes) {
            paint.color = s.color.toArgb()
            paint.strokeWidth = s.width
            paint.xfermode = if (s.eraser) clear else null
            canvas.drawPath(s.path, paint)
        }
        return bitmap
    }
}

object DrawingExport {
    /**
     * Crops a rendered drawing to its content (with a little padding) and scales it down so
     * it's at most [maxDim] pixels. Returns null if nothing was drawn.
     */
    fun cropAndScale(full: Bitmap, maxDim: Int = 512): Bitmap? {
        val pixels = IntArray(full.width * full.height)
        full.getPixels(pixels, 0, full.width, 0, 0, full.width, full.height)
        val bounds = ImageOps.contentBounds(pixels, full.width, full.height) ?: return null
        val padded = ImageOps.pad(bounds, 4, full.width, full.height)
        val cropped = Bitmap.createBitmap(full, padded.left, padded.top, padded.width, padded.height)
        return scaleWithin(cropped, maxDim)
    }

    fun scaleWithin(bitmap: Bitmap, maxDim: Int): Bitmap {
        val (w, h) = ImageOps.fitWithin(bitmap.width, bitmap.height, maxDim)
        return if (w == bitmap.width && h == bitmap.height) bitmap else Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    /** Decodes a picked image, downsampled to roughly [maxDim], as a mutable ARGB bitmap. */
    fun decodeUpload(resolver: ContentResolver, uri: Uri, maxDim: Int = 1024): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        return scaleWithin(decoded, maxDim).copy(Bitmap.Config.ARGB_8888, true)
    }

    /**
     * Turns a flat front view to the isometric angle and extrudes it backwards: shaded copies of
     * the silhouette are stacked along the depth direction, then the front is drawn on top.
     * Returns null if nothing was drawn.
     *
     * @param flat the full-canvas flat drawing.
     * @param depthFraction depth as a fraction of the drawing's width.
     */
    fun addDepth(flat: Bitmap, depthFraction: Float, facing: Facing): Bitmap? {
        val front = cropAndScale(flat, maxDim = max(flat.width, flat.height)) ?: return null
        val layout = DepthProjection.layout(
            front.width.toFloat(), front.height.toFloat(), depthFraction, facing,
            flat.width.toFloat(), flat.height.toFloat(),
        )
        val out = Bitmap.createBitmap(flat.width, flat.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val m = layout.front
        val matrix = Matrix()
        val sidePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(SIDE_SHADE, SIDE_SHADE, SIDE_SHADE, 1f) })
        }
        val backPaint = Paint(sidePaint).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(BACK_SHADE, BACK_SHADE, BACK_SHADE, 1f) })
        }
        val length = sqrt(layout.depth.x * layout.depth.x + layout.depth.y * layout.depth.y)
        val steps = ceil(length).toInt().coerceIn(1, 800)
        for (i in steps downTo 1) {
            val t = i / steps.toFloat()
            matrix.setValues(
                floatArrayOf(m.a, m.c, m.tx + layout.depth.x * t, m.b, m.d, m.ty + layout.depth.y * t, 0f, 0f, 1f)
            )
            canvas.drawBitmap(front, matrix, if (i == steps) backPaint else sidePaint)
        }
        matrix.setValues(floatArrayOf(m.a, m.c, m.tx, m.b, m.d, m.ty, 0f, 0f, 1f))
        canvas.drawBitmap(front, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    private const val SIDE_SHADE = 0.74f
    private const val BACK_SHADE = 0.62f

    /** Copy of [bitmap] with the background flood-filled to transparent. */
    fun removeBackground(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = ImageOps.removeBackground(pixels, w, h)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(out, 0, w, 0, 0, w, h) }
    }
}
