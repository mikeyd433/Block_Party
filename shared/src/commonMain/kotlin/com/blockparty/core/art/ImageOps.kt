package com.blockparty.core.art

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Pixel rectangle: [left, right) x [top, bottom). */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

/**
 * Platform-independent image helpers operating on ARGB_8888 pixel arrays
 * (0xAARRGGBB, row-major), the format used by android.graphics.Bitmap.getPixels.
 */
object ImageOps {
    fun alpha(c: Int) = (c ushr 24) and 0xFF
    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun green(c: Int) = (c shr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF

    /** Tight bounds of the pixels with alpha above [alphaThreshold], or null if the image is empty. */
    fun contentBounds(pixels: IntArray, width: Int, height: Int, alphaThreshold: Int = 8): PixelRect? {
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (alpha(pixels[row + x]) > alphaThreshold) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return null
        return PixelRect(minX, minY, maxX + 1, maxY + 1)
    }

    /** Adds [padding] pixels around [rect], clamped to the image. */
    fun pad(rect: PixelRect, padding: Int, width: Int, height: Int) = PixelRect(
        max(0, rect.left - padding),
        max(0, rect.top - padding),
        min(width, rect.right + padding),
        min(height, rect.bottom + padding),
    )

    /** Size that fits [width] x [height] inside a [maxDim] square, keeping aspect ratio. Never upscales. */
    fun fitWithin(width: Int, height: Int, maxDim: Int): Pair<Int, Int> {
        val largest = max(width, height)
        if (largest <= maxDim) return width to height
        val scale = maxDim.toFloat() / largest
        return max(1, (width * scale).toInt()) to max(1, (height * scale).toInt())
    }

    /**
     * Makes an uploaded photo's background transparent: estimates the background colour from
     * the image border, then flood-fills inward from the border through pixels within
     * [tolerance] (0..255 per channel) of it. Interior regions that happen to share the colour
     * but aren't connected to the border are kept, so the drawing itself is never restyled.
     */
    fun removeBackground(pixels: IntArray, width: Int, height: Int, tolerance: Int = 40): IntArray {
        val out = pixels.copyOf()
        if (width == 0 || height == 0) return out
        val bg = dominantBorderColor(pixels, width, height)
        val visited = BooleanArray(width * height)
        val stack = IntArray(width * height)
        var sp = 0

        fun matches(i: Int): Boolean {
            val c = pixels[i]
            if (alpha(c) < 16) return true
            return abs(red(c) - red(bg)) <= tolerance &&
                abs(green(c) - green(bg)) <= tolerance &&
                abs(blue(c) - blue(bg)) <= tolerance
        }

        fun push(i: Int) {
            if (!visited[i] && matches(i)) {
                visited[i] = true
                stack[sp++] = i
            }
        }

        for (x in 0 until width) {
            push(x)
            push((height - 1) * width + x)
        }
        for (y in 0 until height) {
            push(y * width)
            push(y * width + width - 1)
        }
        while (sp > 0) {
            val i = stack[--sp]
            out[i] = 0
            val x = i % width
            val y = i / width
            if (x > 0) push(i - 1)
            if (x < width - 1) push(i + 1)
            if (y > 0) push(i - width)
            if (y < height - 1) push(i + width)
        }
        return out
    }

    /**
     * Finds the centre of the base of a drawing that was turned into an isometric box, from its
     * shape alone. The bottom outline of such a box runs from one end down to its lowest corner
     * and back up, both at the isometric slope (1:2). The two ends of that outline are opposite
     * corners of the base, so the base centre is halfway between them.
     *
     * Returns pixel coordinates, or null when the bottom outline isn't box-shaped (for example
     * a flat drawing standing upright), so callers can leave those alone.
     */
    fun estimateBoxBase(pixels: IntArray, width: Int, height: Int, alphaThreshold: Int = 32): Pair<Float, Float>? {
        // Lowest opaque pixel in each column.
        val bottom = IntArray(width) { -1 }
        for (x in 0 until width) {
            for (y in height - 1 downTo 0) {
                if (alpha(pixels[y * width + x]) > alphaThreshold) { bottom[x] = y; break }
            }
        }
        val cols = bottom.indices.filter { bottom[it] >= 0 }
        if (cols.size < 8) return null
        val left = cols.first()
        val right = cols.last()
        val span = right - left
        if (span < 8) return null
        // Average a few columns at each end to steady rounded stroke ends.
        val edge = (span / 25).coerceIn(1, 6)
        fun lowestNear(range: IntRange): Float {
            val ys = range.filter { it in 0 until width && bottom[it] >= 0 }.map { bottom[it] }
            return ys.maxOrNull()?.toFloat() ?: -1f
        }
        val leftY = lowestNear(left until left + edge)
        val rightY = lowestNear(right - edge + 1..right)
        val corner = cols.maxBy { bottom[it] }
        val cornerY = bottom[corner].toFloat()
        val leftRun = corner - left
        val rightRun = right - corner
        if (leftRun < span * 0.12f || rightRun < span * 0.12f) return null
        val leftSlope = (cornerY - leftY) / leftRun
        val rightSlope = (cornerY - rightY) / rightRun
        val iso = 0.5f
        val tolerance = 0.2f
        if (abs(leftSlope - iso) > tolerance || abs(rightSlope - iso) > tolerance) return null
        return (left + right) / 2f to (leftY + rightY) / 2f
    }

    /**
     * Copy of a drawing with its dark outline (dark pixels within [reach] of the transparent
     * edge) recoloured with the nearest fill colour. Used for the sides of a 3D extrusion, which
     * repeat the edge pixels and would otherwise come out as solid outline colour. A drawing
     * with no light pixels at all is returned unchanged.
     */
    fun withoutOutline(pixels: IntArray, width: Int, height: Int, reach: Int = 14, darkLuma: Int = 80): IntArray {
        val n = width * height
        val out = pixels.copyOf()
        fun opaque(i: Int) = alpha(pixels[i]) > 32
        fun luma(c: Int) = (red(c) * 299 + green(c) * 587 + blue(c) * 114) / 1000

        // Distance (4-connected steps) from the transparent outside, capped at reach + 1.
        val dist = IntArray(n) { reach + 1 }
        val queue = IntArray(n)
        var head = 0
        var tail = 0
        for (i in 0 until n) if (!opaque(i)) { dist[i] = 0; queue[tail++] = i }
        while (head < tail) {
            val i = queue[head++]
            val d = dist[i] + 1
            if (d > reach) continue
            val x = i % width
            val y = i / width
            if (x > 0 && dist[i - 1] > d) { dist[i - 1] = d; queue[tail++] = i - 1 }
            if (x < width - 1 && dist[i + 1] > d) { dist[i + 1] = d; queue[tail++] = i + 1 }
            if (y > 0 && dist[i - width] > d) { dist[i - width] = d; queue[tail++] = i - width }
            if (y < height - 1 && dist[i + width] > d) { dist[i + width] = d; queue[tail++] = i + width }
        }

        // Outline pixels to recolour; everything else opaque is a colour source.
        val outline = BooleanArray(n) { opaque(it) && dist[it] <= reach && luma(pixels[it]) < darkLuma }
        val filled = BooleanArray(n)
        head = 0
        tail = 0
        for (i in 0 until n) if (opaque(i) && !outline[i]) { filled[i] = true; queue[tail++] = i }
        if (tail == 0) return out
        while (head < tail) {
            val i = queue[head++]
            val x = i % width
            val y = i / width
            fun spread(j: Int) {
                if (outline[j] && !filled[j]) {
                    filled[j] = true
                    out[j] = (pixels[j] and 0xFF000000.toInt()) or (out[i] and 0x00FFFFFF)
                    queue[tail++] = j
                }
            }
            if (x > 0) spread(i - 1)
            if (x < width - 1) spread(i + 1)
            if (y > 0) spread(i - width)
            if (y < height - 1) spread(i + width)
        }
        return out
    }

    /** Most common (coarsely quantised) opaque colour on the image border. */
    internal fun dominantBorderColor(pixels: IntArray, width: Int, height: Int): Int {
        val counts = HashMap<Int, Int>()
        val sample = HashMap<Int, Int>()
        fun add(c: Int) {
            if (alpha(c) < 16) return
            val key = ((red(c) shr 4) shl 8) or ((green(c) shr 4) shl 4) or (blue(c) shr 4)
            counts[key] = (counts[key] ?: 0) + 1
            if (key !in sample) sample[key] = c
        }
        for (x in 0 until width) {
            add(pixels[x]); add(pixels[(height - 1) * width + x])
        }
        for (y in 0 until height) {
            add(pixels[y * width]); add(pixels[y * width + width - 1])
        }
        val best = counts.maxByOrNull { it.value }?.key ?: return 0xFFFFFFFF.toInt()
        return sample.getValue(best)
    }
}
