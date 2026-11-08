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
