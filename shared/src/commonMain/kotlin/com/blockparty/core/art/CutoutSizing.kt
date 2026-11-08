package com.blockparty.core.art

import com.blockparty.core.geometry.IsoProjection
import com.blockparty.core.model.SpriteAsset
import com.blockparty.core.model.SpriteKind
import kotlin.math.min

/** Auto-scales drawings so that any drawing, at any size, fits the world. */
object CutoutSizing {
    /** Height of shop walls in screen pixels, relative to tile height. */
    const val WALL_HEIGHT_TILES = 3f

    /** Returns the on-screen size (unzoomed world pixels) for a cutout sprite. */
    fun displaySize(sprite: SpriteAsset, iso: IsoProjection): Pair<Float, Float> {
        val aspect = sprite.heightPx.toFloat() / sprite.widthPx.coerceAtLeast(1)
        return when (sprite.kind) {
            SpriteKind.FURNITURE -> fit(
                maxW = iso.footprintScreenWidth(sprite.footprintW, sprite.footprintH) * 0.92f,
                maxH = iso.tileHeight * 4.2f,
                aspect = aspect,
            )
            SpriteKind.CHARACTER -> fit(maxW = iso.tileWidth * 1.1f, maxH = iso.tileHeight * 2f, aspect = aspect)
            SpriteKind.PRODUCT -> fit(maxW = iso.tileWidth * 0.45f, maxH = iso.tileHeight * 0.9f, aspect = aspect)
            SpriteKind.FACADE -> fit(maxW = iso.tileWidth * 3f, maxH = iso.tileHeight * WALL_HEIGHT_TILES, aspect = aspect)
        }
    }

    /** Largest size with the given aspect (h / w) that fits inside maxW x maxH. */
    fun fit(maxW: Float, maxH: Float, aspect: Float): Pair<Float, Float> {
        val w = min(maxW, maxH / aspect)
        return w to w * aspect
    }
}
