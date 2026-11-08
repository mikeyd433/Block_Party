package com.blockparty.core.geometry

/**
 * 2D affine transform: maps (x, y) to (a*x + c*y + tx, b*x + d*y + ty).
 * Field layout matches android.graphics.Matrix as [a c tx; b d ty; 0 0 1].
 */
data class Affine2D(
    val a: Float = 1f,
    val b: Float = 0f,
    val c: Float = 0f,
    val d: Float = 1f,
    val tx: Float = 0f,
    val ty: Float = 0f,
) {
    fun apply(p: Vec2): Vec2 = Vec2(a * p.x + c * p.y + tx, b * p.x + d * p.y + ty)

    companion object {
        /**
         * Maps a flat image of size [imageW] x [imageH] onto a parallelogram:
         * image top-left goes to [topLeft], the image x-axis runs to [topRight]
         * and the image y-axis runs to [bottomLeft].
         */
        fun mapRectTo(imageW: Float, imageH: Float, topLeft: Vec2, topRight: Vec2, bottomLeft: Vec2): Affine2D {
            val xAxis = topRight - topLeft
            val yAxis = bottomLeft - topLeft
            return Affine2D(
                a = xAxis.x / imageW,
                b = xAxis.y / imageW,
                c = yAxis.x / imageH,
                d = yAxis.y / imageH,
                tx = topLeft.x,
                ty = topLeft.y,
            )
        }
    }
}
