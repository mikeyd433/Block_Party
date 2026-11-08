package com.blockparty.app.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.blockparty.app.ui.draw.DrawingExport
import com.blockparty.core.geometry.Facing
import com.blockparty.core.geometry.IsoProjection
import com.blockparty.core.geometry.Storefront
import com.blockparty.core.model.CharacterRole
import com.blockparty.core.model.SpriteAsset
import com.blockparty.core.model.SpriteKind
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The small default set of sprites that ships with the game so a new street isn't empty:
 * customers, furniture and storefronts. They're drawn here in code with simple, slightly
 * wobbly strokes so they sit alongside player drawings without outshining them.
 */
object StarterSet {
    /** Bump when the set changes; worlds with an older version get the new pieces added. */
    const val VERSION = 1 // pre-release: art tweaks don't bump this
    const val ARTIST = "Starter set"

    class Item(val asset: SpriteAsset, val bitmap: Bitmap)

    private fun asset(id: String, kind: SpriteKind, name: String, bitmap: Bitmap, block: SpriteAsset.() -> SpriteAsset = { this }) =
        Item(
            SpriteAsset(
                id = "starter-$id", kind = kind, name = name,
                widthPx = bitmap.width, heightPx = bitmap.height,
                isStarter = true, artistName = ARTIST, showCredit = true,
            ).block(),
            bitmap,
        )

    /** Generates every starter sprite. CPU work: call off the main thread. */
    fun generate(lotWidth: Int): List<Item> = buildList {
        // Customers.
        val customers = listOf(
            Person("customer-sweater", "Sweater", body = 0xFF3D6FC4, skin = 0xFFE2B48A, hair = Hair.SHORT, hairColor = 0xFF4A3426),
            Person("customer-raincoat", "Raincoat kid", body = 0xFFF5C542, skin = 0xFFF1C9A5, hair = Hair.BOB, hairColor = 0xFF2E2B26, height = 0.72f, extra = Extra.BOOTS),
            Person("customer-hat", "Green coat", body = 0xFF2F8F5B, skin = 0xFF8C5A3C, hair = Hair.NONE, hairColor = 0xFF2E2B26, height = 1.1f, extra = Extra.HAT),
            Person("customer-bag", "Pink dress", body = 0xFFE58BB8, skin = 0xFFE2B48A, hair = Hair.LONG, hairColor = 0xFFB0602E, extra = Extra.BAG),
            Person("customer-cane", "Cardigan", body = 0xFF9B6FD1, skin = 0xFFF1C9A5, hair = Hair.BUN, hairColor = 0xFFC9C3B8, height = 0.92f, extra = Extra.CANE),
            Person("customer-punk", "Mohawk", body = 0xFF2E2B26, skin = 0xFFE2B48A, hair = Hair.MOHAWK, hairColor = 0xFFF08A4B, extra = Extra.GLASSES),
        )
        for (p in customers) add(asset(p.id, SpriteKind.CHARACTER, p.name, person(p)) { copy(role = CharacterRole.CUSTOMER) })

        // Furniture.
        add(turned("counter", "Counter", counterFront(), depth = 0.45f, footprint = 2 to 1))
        add(turned("bookshelf", "Bookshelf", shelfFront(), depth = 0.3f, footprint = 2 to 1))
        add(asset("plant", SpriteKind.FURNITURE, "Potted plant", plant()) { copy(alignToGrid = false) })
        add(asset("lamp", SpriteKind.FURNITURE, "Floor lamp", lamp()) { copy(alignToGrid = false) })
        add(asset("stool", SpriteKind.FURNITURE, "Stool", stool()) { copy(alignToGrid = true) })
        add(asset("display", SpriteKind.FURNITURE, "Display table", displayTable()) { copy(alignToGrid = true, footprintW = 2) })

        // Storefronts.
        val aspect = Storefront(IsoProjection()).facadeAspect(lotWidth)
        add(asset("facade-awning", SpriteKind.FACADE, "Striped awning", awningFacade(aspect)))
        add(asset("facade-brick", SpriteKind.FACADE, "Brick front", brickFacade(aspect)))
    }

    // --- Drawing helpers ---------------------------------------------------------------------

    private const val INK = 0xFF2E2B26.toInt()

    private fun pen(color: Long, width: Float = 8f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toInt()
        strokeWidth = width
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun fill(color: Long) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toInt(); style = Paint.Style.FILL }

    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK; strokeWidth = 6f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    private fun bitmap(w: Int, h: Int, block: Canvas.() -> Unit): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { Canvas(it).block() }

    /** A hand-drawn-looking straight line: subdivided with tiny deterministic wobbles. */
    private fun Canvas.wobbleLine(x0: Float, y0: Float, x1: Float, y1: Float, paint: Paint, seed: Int = 0) {
        val r = Random(seed + (x0 * 31 + y0 * 17 + x1 * 7 + y1).toInt())
        val path = Path().apply { moveTo(x0, y0) }
        val steps = 4
        for (i in 1..steps) {
            val t = i / steps.toFloat()
            val j = if (i == steps) 0f else (r.nextFloat() - 0.5f) * 3f
            path.lineTo(x0 + (x1 - x0) * t + j, y0 + (y1 - y0) * t + j)
        }
        drawPath(path, paint)
    }

    private fun Canvas.wobbleRect(l: Float, t: Float, r: Float, b: Float, fillColor: Long?, seed: Int = 0) {
        if (fillColor != null) drawRect(l, t, r, b, fill(fillColor))
        wobbleLine(l, t, r, t, ink, seed)
        wobbleLine(r, t, r, b, ink, seed + 1)
        wobbleLine(r, b, l, b, ink, seed + 2)
        wobbleLine(l, b, l, t, ink, seed + 3)
    }

    // --- Customers -----------------------------------------------------------------------------

    private const val PERSON_CANVAS_H = 500

    private enum class Hair { NONE, SHORT, BOB, LONG, BUN, MOHAWK }
    private enum class Extra { NONE, HAT, BAG, CANE, BOOTS, GLASSES }

    private class Person(
        val id: String, val name: String, val body: Long, val skin: Long, val hair: Hair, val hairColor: Long,
        val height: Float = 1f, val extra: Extra = Extra.NONE,
    )

    private fun person(p: Person): Bitmap {
        // Everyone shares one canvas height so short and tall people keep their size once
        // drawings are scaled to the world.
        val h = PERSON_CANVAS_H
        val w = 260
        return bitmap(w, h) {
            val s = p.height
            val cx = w / 2f
            val top = h - 440f * s
            val headR = 46f * (0.85f + 0.15f * s)
            val headY = top + 70f * s + 20f
            val bodyTop = headY + headR - 6f
            val legTop = h - 110f * s
            val footY = h - 12f

            // Legs.
            val legPaint = pen(0xFF2E2B26, 14f)
            wobbleLine(cx - 22f, legTop, cx - 26f, footY, legPaint, 1)
            wobbleLine(cx + 22f, legTop, cx + 28f, footY, legPaint, 2)
            if (p.extra == Extra.BOOTS) {
                drawRoundRect(RectF(cx - 42f, footY - 30f, cx - 12f, footY + 4f), 8f, 8f, fill(0xFFD9443A))
                drawRoundRect(RectF(cx + 12f, footY - 30f, cx + 44f, footY + 4f), 8f, 8f, fill(0xFFD9443A))
            }
            // Body.
            val bodyRect = RectF(cx - 62f, bodyTop, cx + 62f, legTop + 20f)
            drawRoundRect(bodyRect, 46f, 46f, fill(p.body))
            drawRoundRect(bodyRect, 46f, 46f, ink)
            // Arms.
            val arm = pen(p.body, 16f)
            wobbleLine(cx - 58f, bodyTop + 30f, cx - 84f, legTop - 10f, arm, 3)
            wobbleLine(cx + 58f, bodyTop + 30f, cx + 82f, legTop - 6f, arm, 4)
            drawCircle(cx - 84f, legTop - 6f, 9f, fill(p.skin))
            drawCircle(cx + 82f, legTop - 2f, 9f, fill(p.skin))
            when (p.extra) {
                Extra.BAG -> {
                    drawRoundRect(RectF(cx + 64f, legTop - 10f, cx + 112f, legTop + 34f), 8f, 8f, fill(0xFFF5C542))
                    drawRoundRect(RectF(cx + 64f, legTop - 10f, cx + 112f, legTop + 34f), 8f, 8f, ink)
                    wobbleLine(cx + 74f, legTop - 10f, cx + 82f, legTop - 26f, ink, 5)
                    wobbleLine(cx + 102f, legTop - 10f, cx + 82f, legTop - 26f, ink, 6)
                }
                Extra.CANE -> wobbleLine(cx + 88f, legTop - 4f, cx + 96f, footY, pen(0xFF8C5A3C, 10f), 7)
                else -> Unit
            }
            // Head.
            if (p.hair == Hair.LONG) drawRoundRect(RectF(cx - headR - 8f, headY - headR, cx + headR + 8f, headY + headR + 40f), 40f, 40f, fill(p.hairColor))
            drawCircle(cx, headY, headR, fill(p.skin))
            drawCircle(cx, headY, headR, ink)
            val eye = fill(0xFF2E2B26)
            drawCircle(cx + 12f, headY - 6f, 5.5f, eye)
            drawCircle(cx + 32f, headY - 6f, 5.5f, eye)
            drawArc(RectF(cx + 6f, headY + 4f, cx + 38f, headY + 26f), 15f, 150f, false, pen(0xFF2E2B26, 5f))
            if (p.extra == Extra.GLASSES) {
                val g = pen(0xFF2E2B26, 4f)
                drawCircle(cx + 12f, headY - 6f, 13f, g)
                drawCircle(cx + 36f, headY - 6f, 13f, g)
            }
            // Hair and hats.
            val hairPaint = fill(p.hairColor)
            when (p.hair) {
                Hair.SHORT -> drawArc(RectF(cx - headR, headY - headR - 6f, cx + headR, headY + headR - 20f), 180f, 180f, true, hairPaint)
                Hair.BOB, Hair.LONG -> drawArc(RectF(cx - headR - 6f, headY - headR - 8f, cx + headR + 6f, headY + headR - 10f), 170f, 200f, true, hairPaint)
                Hair.BUN -> {
                    drawArc(RectF(cx - headR, headY - headR - 4f, cx + headR, headY + headR - 24f), 180f, 180f, true, hairPaint)
                    drawCircle(cx - 20f, headY - headR - 10f, 20f, hairPaint)
                }
                Hair.MOHAWK -> {
                    val mohawk = Path().apply {
                        moveTo(cx - 14f, headY - headR + 4f)
                        lineTo(cx - 6f, headY - headR - 44f); lineTo(cx + 2f, headY - headR - 8f)
                        lineTo(cx + 12f, headY - headR - 48f); lineTo(cx + 18f, headY - headR + 4f); close()
                    }
                    drawPath(mohawk, hairPaint)
                }
                Hair.NONE -> Unit
            }
            if (p.extra == Extra.HAT) {
                drawRect(cx - headR - 18f, headY - headR + 6f, cx + headR + 18f, headY - headR + 18f, fill(0xFF4A3426))
                drawRoundRect(RectF(cx - headR + 6f, headY - headR - 40f, cx + headR - 6f, headY - headR + 12f), 10f, 10f, fill(0xFF4A3426))
            }
        }
    }

    // --- Furniture -----------------------------------------------------------------------------

    private fun turned(id: String, name: String, front: Bitmap, depth: Float, footprint: Pair<Int, Int>): Item {
        val turned = DrawingExport.addDepth(front, depth, Facing.LEFT) ?: error("empty starter drawing $id")
        val (bitmap, crop) = DrawingExport.cropAndScaleWithRect(turned.bitmap) ?: error("empty starter drawing $id")
        return asset(id, SpriteKind.FURNITURE, name, bitmap) {
            copy(
                alignToGrid = false,
                footprintW = footprint.first,
                footprintH = footprint.second,
                groundX = (turned.ground.x - crop.left) / crop.width,
                groundY = (turned.ground.y - crop.top) / crop.height,
            )
        }
    }

    private fun counterFront() = bitmap(1024, 1024) {
        wobbleRect(212f, 420f, 812f, 700f, 0xFFE2B48A, 10)
        wobbleRect(192f, 390f, 832f, 430f, 0xFF8C5A3C, 20) // top
        wobbleRect(262f, 470f, 492f, 650f, 0xFFD3A274, 30)
        wobbleRect(532f, 470f, 762f, 650f, 0xFFD3A274, 40)
    }

    private fun shelfFront() = bitmap(1024, 1024) {
        wobbleRect(262f, 180f, 762f, 800f, 0xFF8C5A3C, 50)
        val shelves = listOf(330f, 480f, 630f)
        val bookColors = listOf(0xFFD9443A, 0xFF3D6FC4, 0xFFF5C542, 0xFF2F8F5B, 0xFF9B6FD1, 0xFFF08A4B)
        var seed = 60
        for (top in listOf(210f) + shelves) {
            var x = 290f
            val r = Random(seed++)
            while (x < 720f) {
                val bw = 24f + r.nextInt(16)
                val bh = 70f + r.nextInt(40)
                drawRect(x, top + 120f - bh, x + bw, top + 120f, fill(bookColors[r.nextInt(bookColors.size)]))
                x += bw + 4f
            }
        }
        for (y in shelves.map { it + 0f } + listOf(780f)) wobbleLine(262f, y, 762f, y, pen(0xFF4A3426, 14f), seed++)
    }

    private fun plant() = bitmap(260, 380) {
        val leaf = pen(0xFF2F8F5B, 16f)
        wobbleLine(130f, 250f, 70f, 60f, leaf, 1)
        wobbleLine(130f, 250f, 130f, 30f, leaf, 2)
        wobbleLine(130f, 250f, 195f, 80f, leaf, 3)
        wobbleLine(130f, 250f, 40f, 150f, leaf, 4)
        wobbleLine(130f, 250f, 220f, 160f, leaf, 5)
        val pot = Path().apply { moveTo(70f, 240f); lineTo(190f, 240f); lineTo(172f, 372f); lineTo(88f, 372f); close() }
        drawPath(pot, fill(0xFFF08A4B))
        drawPath(pot, ink)
    }

    private fun lamp() = bitmap(220, 520) {
        wobbleLine(110f, 150f, 110f, 490f, pen(0xFF2E2B26, 10f), 1)
        drawOval(RectF(50f, 480f, 170f, 512f), fill(0xFF6B6459))
        val shade = Path().apply { moveTo(60f, 150f); lineTo(160f, 150f); lineTo(135f, 40f); lineTo(85f, 40f); close() }
        drawPath(shade, fill(0xFFF7E68B))
        drawPath(shade, ink)
    }

    private fun stool() = bitmap(260, 300) {
        val wood = pen(0xFF8C5A3C, 14f)
        wobbleLine(70f, 90f, 50f, 290f, wood, 1)
        wobbleLine(190f, 90f, 210f, 290f, wood, 2)
        wobbleLine(60f, 200f, 200f, 200f, wood, 3)
        drawRoundRect(RectF(30f, 60f, 230f, 100f), 16f, 16f, fill(0xFFD9443A))
        drawRoundRect(RectF(30f, 60f, 230f, 100f), 16f, 16f, ink)
    }

    private fun displayTable() = bitmap(520, 300) {
        wobbleRect(30f, 110f, 490f, 140f, 0xFF8C5A3C, 1)
        val leg = pen(0xFF4A3426, 14f)
        wobbleLine(60f, 140f, 60f, 290f, leg, 2)
        wobbleLine(460f, 140f, 460f, 290f, leg, 3)
        // Goods on display.
        drawCircle(120f, 80f, 30f, fill(0xFFE58BB8)); drawCircle(120f, 80f, 30f, ink)
        drawRect(200f, 40f, 260f, 110f, fill(0xFF5BC0D9)); wobbleRect(200f, 40f, 260f, 110f, null, 4)
        drawOval(RectF(310f, 60f, 420f, 110f), fill(0xFFF5C542)); drawOval(RectF(310f, 60f, 420f, 110f), ink)
    }

    // --- Storefronts ---------------------------------------------------------------------------

    private fun facadeCanvas(aspect: Float, block: Canvas.(w: Float, h: Float) -> Unit): Bitmap {
        val w = 1024
        val h = (1024 / aspect).roundToInt()
        return bitmap(w, h) { block(w.toFloat(), h.toFloat()) }
    }

    private fun awningFacade(aspect: Float) = facadeCanvas(aspect) { w, h ->
        // Striped awning.
        val stripes = 10
        val sw = w / stripes
        for (i in 0 until stripes) {
            drawRect(i * sw, 0f, (i + 1) * sw, h * 0.22f, fill(if (i % 2 == 0) 0xFFD9443A else 0xFFFFFFFF))
            drawArc(RectF(i * sw, h * 0.16f, (i + 1) * sw, h * 0.28f), 0f, 180f, true, fill(if (i % 2 == 0) 0xFFD9443A else 0xFFFFFFFF))
        }
        wobbleLine(0f, h * 0.22f, w, h * 0.22f, ink, 1)
        // Door and two windows.
        wobbleRect(w * 0.42f, h * 0.38f, w * 0.58f, h, 0xFF2F8F5B, 2)
        drawCircle(w * 0.555f, h * 0.7f, 8f, fill(0xFFF5C542))
        wobbleRect(w * 0.08f, h * 0.4f, w * 0.34f, h * 0.78f, 0xFFCFE3F2, 3)
        wobbleRect(w * 0.66f, h * 0.4f, w * 0.92f, h * 0.78f, 0xFFCFE3F2, 4)
        // Flower boxes.
        drawRect(w * 0.07f, h * 0.78f, w * 0.35f, h * 0.84f, fill(0xFF8C5A3C))
        drawRect(w * 0.65f, h * 0.78f, w * 0.93f, h * 0.84f, fill(0xFF8C5A3C))
        val r = Random(9)
        repeat(14) {
            val x = if (it < 7) w * (0.09f + 0.035f * it) else w * (0.67f + 0.035f * (it - 7))
            drawCircle(x, h * 0.77f, 10f, fill(listOf(0xFFE58BB8, 0xFFF5C542, 0xFFFFFFFF)[r.nextInt(3)]))
        }
    }

    private fun brickFacade(aspect: Float) = facadeCanvas(aspect) { w, h ->
        drawRect(0f, 0f, w, h, fill(0xFFC8775A))
        val mortar = pen(0xFFE8D9C8, 4f)
        val rowH = h / 12f
        for (row in 0..12) {
            val y = row * rowH
            drawLine(0f, y, w, y, mortar)
            val offset = if (row % 2 == 0) 0f else 40f
            var x = offset
            while (x < w) { drawLine(x, y, x, y + rowH, mortar); x += 80f }
        }
        // Round sign, big window and door.
        drawCircle(w * 0.5f, h * 0.16f, h * 0.12f, fill(0xFFF6E7C8))
        drawCircle(w * 0.5f, h * 0.16f, h * 0.12f, ink)
        wobbleRect(w * 0.1f, h * 0.36f, w * 0.6f, h * 0.8f, 0xFFCFE3F2, 5)
        wobbleLine(w * 0.35f, h * 0.36f, w * 0.35f, h * 0.8f, ink, 6)
        wobbleRect(w * 0.7f, h * 0.36f, w * 0.88f, h, 0xFF27387A, 7)
        drawCircle(w * 0.855f, h * 0.7f, 8f, fill(0xFFF5C542))
    }
}
