package com.blockparty.app.ui.street

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.blockparty.app.ui.theme.Paper
import com.blockparty.core.anim.CutoutPose
import com.blockparty.core.anim.ProceduralAnimator
import com.blockparty.core.art.CutoutSizing
import com.blockparty.core.geometry.Affine2D
import com.blockparty.core.geometry.GridPanel
import com.blockparty.core.geometry.IsoProjection
import com.blockparty.core.geometry.Storefront
import com.blockparty.core.geometry.Vec2
import com.blockparty.core.model.FurniturePlacement
import com.blockparty.core.model.GridRect
import com.blockparty.core.model.SpriteAsset
import com.blockparty.core.model.Lot
import com.blockparty.core.model.LotState
import com.blockparty.core.model.WorldState
import com.blockparty.core.sim.Placement
import com.blockparty.core.npc.ServicePhase
import com.blockparty.core.npc.ServiceView
import com.blockparty.core.npc.StaffMember
import com.blockparty.core.npc.Pedestrian
import kotlin.math.min

enum class EffectType(val durationSec: Float) { HOP(0.55f), WOBBLE(0.6f) }

data class Effect(val type: EffectType, val startSec: Float)

/** Brief footprint highlight, e.g. red when a placement doesn't fit. */
data class Flash(val rect: GridRect, val valid: Boolean, val startSec: Float) {
    companion object {
        const val DURATION_SEC = 0.6f
    }
}

const val SHOPKEEPER_ID = "shopkeeper"

/** Everything needed to draw one frame of the street. */
class StreetScene(
    val world: WorldState,
    val iso: IsoProjection,
    val images: Map<String, ImageBitmap>,
    val interiorLotId: String?,
    val timeSec: Float,
    /** Staff working in the shops (they serve customers and hand over service items). */
    val staff: List<StaffMember> = emptyList(),
    /** Services in progress, for the item handover. */
    val services: List<ServiceView> = emptyList(),
    val effects: Map<String, Effect>,
    val selectedId: String?,
    val freeTileHints: Set<Pair<Int, Int>>?,
    val flash: Flash?,
    val crowd: List<Pedestrian> = emptyList(),
) {
    val storefront = Storefront(iso)

    fun effectPose(id: String): CutoutPose {
        val e = effects[id] ?: return CutoutPose.Rest
        val p = (timeSec - e.startSec) / e.type.durationSec
        return when (e.type) {
            EffectType.HOP -> ProceduralAnimator.hop(p)
            EffectType.WOBBLE -> ProceduralAnimator.placeWobble(p)
        }
    }
}

/** A cutout positioned in the scene, shared by drawing and hit testing. */
class CutoutItem(
    val id: String,
    val image: ImageBitmap,
    val foot: Offset,
    val size: Size,
    val pose: CutoutPose,
    val mirrored: Boolean,
    val depth: Float,
    val shadow: DrawScope.(Float) -> Unit,
    /** Set when the drawing is leaned into the grid instead of standing upright. */
    val panel: GridPanel.Panel? = null,
    /** Point of the image (fractions) that rests on [foot]. */
    val anchorX: Float = 0.5f,
    val anchorY: Float = 1f,
    /** Products standing on a display piece, drawn right after it. */
    val displayed: List<DisplayedItem> = emptyList(),
    /** For someone sitting: only the part above this screen y is drawn (legs hidden by the seat). */
    val clipBottom: Float? = null,
    /** A drawing held in the character's hand (e.g. a service item). */
    val held: ImageBitmap? = null,
    /** Whether the character faces right on screen (for which hand holds things). */
    val facesRightOnScreen: Boolean = true,
) {
    /** Where a held item sits: at the front hand, about halfway up the visible body. */
    fun handPoint(): Offset {
        val dir = if (facesRightOnScreen) 1f else -1f
        val liftPx = pose.lift * size.height
        return Offset(foot.x + dir * size.width * 0.34f, foot.y - size.height * 0.5f - liftPx)
    }

    /** Screen area covered before animation, for hit testing. */
    val bounds: Rect
        get() = panel?.let { p ->
            Rect(p.corners.minOf { it.x }, p.corners.minOf { it.y }, p.corners.maxOf { it.x }, p.corners.maxOf { it.y })
        } ?: cutoutBounds(foot, size, anchorX, anchorY, mirrored)
}

/** Cutouts inside the open (interior) lot, sorted back to front. */
fun StreetScene.interiorCutouts(): List<CutoutItem> {
    val lot = interiorLotId?.let(world::lot) ?: return emptyList()
    val items = ArrayList<CutoutItem>()
    for ((index, piece) in lot.furniture.withIndex()) {
        val sprite = world.sprite(piece.spriteId) ?: continue
        val image = images[sprite.id] ?: continue
        val rect = Placement.footprint(sprite, piece.x, piece.y, piece.flipped)
        // Footprint is transposed when flipped, so size the cutout from the placed rect.
        val sized = sprite.copy(footprintW = rect.w, footprintH = rect.h)
        val (w, h) = CutoutSizing.displaySize(sized, iso)
        val center = iso.rectCenter(rect.x.toFloat(), rect.y.toFloat(), rect.w.toFloat(), rect.h.toFloat())
        val diamond = iso.rectCorners(rect.x.toFloat(), rect.y.toFloat(), rect.w.toFloat(), rect.h.toFloat())
        val panel = if (sprite.alignToGrid) {
            GridPanel.standing(
                iso, rect, alongY = piece.flipped,
                imageW = image.width.toFloat(), imageH = image.height.toFloat(),
                maxHeight = iso.tileHeight * FURNITURE_MAX_HEIGHT_TILES,
            )
        } else null
        items += CutoutItem(
            id = piece.id,
            image = image,
            foot = center.toOffset(),
            size = Size(w, h),
            pose = ProceduralAnimator.idle(timeSec, seed = index * 1.9f, intensity = 0.3f) + effectPose(piece.id),
            mirrored = piece.flipped,
            depth = rect.x + rect.w / 2f + rect.y + rect.h / 2f,
            shadow = footprintShadow(diamond),
            panel = panel,
            anchorX = sprite.groundX,
            anchorY = sprite.groundY,
            displayed = displayedItems(sprite, piece, lot, Size(w, h), center.toOffset(), image, panel),
        )
    }
    for (member in staff.filter { it.lotId == lot.id }) staffItem(member, lot)?.let(items::add)
    val furnitureById = items.associateBy { it.id }
    for (p in crowd.filter { it.insideLotId == lot.id }) {
        val seat = p.seatedOn?.let { furnitureById[it] }
        val item = if (seat != null) seatedItem(p, seat, lot) else pedestrianItem(p)
        item?.let(items::add)
    }
    items.sortBy { it.depth }
    return items
}

/** A product standing on a display spot, positioned before the furniture's animation. */
class DisplayedItem(val image: ImageBitmap, val point: Offset, val size: Size)

/** Products on a placed display piece, at its drawing's spots. */
private fun StreetScene.displayedItems(
    sprite: SpriteAsset,
    piece: FurniturePlacement,
    lot: Lot,
    size: Size,
    foot: Offset,
    image: ImageBitmap,
    panel: GridPanel.Panel?,
): List<DisplayedItem> {
    if (sprite.displaySpots.isEmpty() || piece.stock.isEmpty()) return emptyList()
    val offerings = lot.business?.offerings.orEmpty()
    val bounds = cutoutBounds(foot, size, sprite.groundX, sprite.groundY, piece.flipped)
    val furnitureHeight = panel?.let { it.corners[3].y - it.corners[0].y } ?: size.height
    return sprite.displaySpots.mapNotNull { spot ->
        val offering = offerings.firstOrNull { it.id == piece.stock[spot.id] } ?: return@mapNotNull null
        val itemImage = offering.spriteId?.let { images[it] } ?: return@mapNotNull null
        val point = if (panel != null) {
            panel.transform.apply(Vec2(spot.x * image.width, spot.y * image.height)).toOffset()
        } else {
            val fx = if (piece.flipped) 1f - spot.x else spot.x
            Offset(bounds.left + fx * bounds.width, bounds.top + spot.y * bounds.height)
        }
        val h = spot.size * furnitureHeight
        val w = h * itemImage.width / itemImage.height.coerceAtLeast(1)
        DisplayedItem(itemImage, point, Size(w, h))
    }
}

/**
 * Draws a display piece's products so they ride along with the furniture's animation: each
 * item's resting point is moved the way the furniture is (sway, squash, lift), and the item is
 * drawn upright there, never mirrored so drawn text stays readable.
 */
private fun DrawScope.drawDisplayed(item: CutoutItem) {
    if (item.displayed.isEmpty()) return
    val pose = item.pose
    val foot = item.panel?.foot?.toOffset() ?: item.foot
    val height = item.panel?.let { it.corners[3].y - it.corners[0].y } ?: item.size.height
    val rad = Math.toRadians(pose.rotationDeg.toDouble())
    val cos = kotlin.math.cos(rad).toFloat()
    val sin = kotlin.math.sin(rad).toFloat()
    for (d in item.displayed) {
        val dx = (d.point.x - foot.x) * pose.scaleX
        val dy = (d.point.y - foot.y) * pose.scaleY
        val moved = Offset(foot.x + dx * cos - dy * sin, foot.y + dx * sin + dy * cos - pose.lift * height)
        drawCutout(
            d.image, moved, d.size,
            CutoutPose(scaleX = pose.scaleX, scaleY = pose.scaleY, rotationDeg = pose.rotationDeg),
            mirrored = false,
        )
    }
}

/** Drawing used for a member of staff (the shopkeeper's drawing for now). */
private fun StreetScene.staffSprite(member: StaffMember, lot: Lot): SpriteAsset? = when (member.id) {
    SHOPKEEPER_ID -> world.sprite(lot.business?.shopkeeperSpriteId)
    else -> null
}

private fun StreetScene.offeringImage(lot: Lot, offeringId: String?): ImageBitmap? =
    offeringId?.let { id -> lot.business?.offerings?.firstOrNull { it.id == id }?.spriteId }?.let { images[it] }

private fun StreetScene.staffItem(member: StaffMember, lot: Lot): CutoutItem? {
    val sprite = staffSprite(member, lot) ?: return null
    val image = images[sprite.id] ?: return null
    val (w, h) = CutoutSizing.displaySize(sprite, iso)
    val foot = iso.gridToScreen(member.position.x, member.position.y).toOffset()
    val base = if (member.isWalking) ProceduralAnimator.walk(member.distanceWalked) else ProceduralAnimator.idle(timeSec)
    val mirrored = member.facingRight != sprite.facesRight
    return CutoutItem(
        id = member.id,
        image = image,
        foot = foot,
        size = Size(w, h),
        pose = base + effectPose(member.id),
        mirrored = mirrored,
        depth = member.position.x + member.position.y,
        shadow = ovalShadow(foot, w * 0.8f),
        held = offeringImage(lot, member.heldItem),
        facesRightOnScreen = member.facingRight,
    )
}

fun npcEffectId(p: Pedestrian) = "npc:${p.id}"

/** A customer on the street (or in a shop) as a positioned cutout. */
fun StreetScene.pedestrianItem(p: Pedestrian): CutoutItem? {
    val sprite = world.sprite(p.profile.spriteId) ?: return null
    val image = images[sprite.id] ?: return null
    val (w, h) = CutoutSizing.displaySize(sprite, iso)
    val foot = iso.gridToScreen(p.position.x, p.position.y).toOffset()
    val id = npcEffectId(p)
    val base = if (p.isWalking) ProceduralAnimator.walk(p.distanceWalked, seed = p.id * 0.37f)
    else ProceduralAnimator.idle(timeSec, seed = p.id * 1.3f)
    return CutoutItem(
        id = id,
        image = image,
        foot = foot,
        size = Size(w, h),
        pose = base + effectPose(id),
        mirrored = p.facingRight != sprite.facesRight,
        depth = p.position.x + p.position.y,
        shadow = ovalShadow(foot, w * 0.8f),
        held = p.insideLotId?.let(world::lot)?.let { offeringImage(it, p.heldItem) },
        facesRightOnScreen = p.facingRight,
    )
}

/** Part of a seated character's height hidden below the seat. */
private const val SEATED_HIDDEN = 0.32f

/**
 * Someone sitting on a seat or service station: drawn at the furniture's seat height, just in
 * front of it, with the bottom of the drawing (legs) hidden so they look seated.
 */
private fun StreetScene.seatedItem(p: Pedestrian, seat: CutoutItem, lot: Lot): CutoutItem? {
    val base = pedestrianItem(p) ?: return null
    val seatSprite = lot.furniture.firstOrNull { it.id == seat.id }?.let { world.sprite(it.spriteId) } ?: return base
    val ground = seat.panel?.foot?.toOffset() ?: seat.foot
    val height = seat.panel?.let { it.corners[3].y - it.corners[0].y } ?: seat.size.height
    val seatY = ground.y - seatSprite.seatHeight * height - seat.pose.lift * height
    val foot = Offset(ground.x, seatY + base.size.height * SEATED_HIDDEN)
    return CutoutItem(
        id = base.id,
        image = base.image,
        foot = foot,
        size = base.size,
        pose = ProceduralAnimator.idle(timeSec, seed = p.id * 1.3f) + effectPose(base.id),
        mirrored = base.mirrored,
        depth = seat.depth + 0.01f,
        shadow = {},
        held = base.held,
        facesRightOnScreen = base.facesRightOnScreen,
        clipBottom = seatY,
    )
}

/** Customers out on the street (not inside any shop), back to front. */
fun StreetScene.outdoorCutouts(): List<CutoutItem> =
    crowd.filter { it.insideLotId == null }.mapNotNull { pedestrianItem(it) }.sortedBy { it.depth }

sealed interface Hit {
    data class Cutout(val id: String) : Hit
    data class Tile(val x: Int, val y: Int) : Hit
}

/** What's under a point in world (unzoomed) coordinates. Front-most cutouts win. */
fun StreetScene.hitTest(point: Offset): Hit {
    outdoorCutouts().asReversed().firstOrNull { it.bounds.contains(point) }?.let { return Hit.Cutout(it.id) }
    interiorCutouts().asReversed().firstOrNull { it.bounds.contains(point) }
        ?.let { return Hit.Cutout(it.id) }
    val tile = iso.screenToTile(point.x, point.y)
    return Hit.Tile(tile.x, tile.y)
}

/** World-space bounding box of the whole street, including building heights. */
fun StreetScene.worldBounds(): androidx.compose.ui.geometry.Rect {
    val s = world.street
    val corners = iso.rectCorners(0f, 0f, s.widthTiles.toFloat(), s.totalDepth.toFloat())
    val top = corners.minOf { it.y } - storefront.wallHeight
    return androidx.compose.ui.geometry.Rect(corners.minOf { it.x }, top, corners.maxOf { it.x }, corners.maxOf { it.y })
}

fun StreetScene.lotBounds(lot: Lot): androidx.compose.ui.geometry.Rect {
    val r = lot.rect
    val c = iso.rectCorners(r.x.toFloat(), r.y.toFloat(), r.w.toFloat(), r.h.toFloat())
    return androidx.compose.ui.geometry.Rect(c.minOf { it.x }, c.minOf { it.y } - storefront.wallHeight, c.maxOf { it.x }, c.maxOf { it.y })
}

// --- Drawing ---------------------------------------------------------------------------------

private val outline = Stroke(width = 2f)
private val thinLine = Stroke(width = 1.2f)
private val dashed = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))

/** Draws the street in world coordinates (the caller applies pan and zoom). */
fun DrawScope.drawStreet(scene: StreetScene, textMeasurer: TextMeasurer) {
    val iso = scene.iso
    val street = scene.world.street
    fun strip(y: Int, rows: Int, color: Color) {
        val c = iso.rectCorners(0f, y.toFloat(), street.widthTiles.toFloat(), rows.toFloat())
        drawPath(polygonPath(c), color)
    }

    // Ground, sidewalks and road.
    strip(0, street.lotDepth, Paper.Ground)
    strip(street.lotDepth, street.sidewalkRows, Paper.Sidewalk)
    strip(street.roadStartRow, street.roadRows, Paper.Road)
    strip(street.roadStartRow + street.roadRows, street.sidewalkRows, Paper.Sidewalk)
    val midRoad = street.roadStartRow + street.roadRows / 2f
    var x = 0.5f
    while (x < street.widthTiles - 0.5f) {
        drawLine(
            Paper.RoadLine, iso.gridToScreen(x, midRoad).toOffset(), iso.gridToScreen(x + 0.6f, midRoad).toOffset(),
            strokeWidth = 4f, cap = StrokeCap.Round,
        )
        x += 1.2f
    }

    for (lot in street.lots.sortedBy { it.rect.x }) {
        when {
            lot.state == LotState.LOCKED || lot.state == LotState.VACANT -> drawEmptyLot(scene, lot, textMeasurer)
            lot.id == scene.interiorLotId -> drawInterior(scene, lot)
            else -> drawBuilding(scene, lot, textMeasurer)
        }
    }

    // People on the sidewalks are always in front of the shops.
    for (item in scene.outdoorCutouts()) {
        drawCutout(item.image, item.foot, item.size, item.pose, item.mirrored, item.shadow)
    }

    scene.flash?.let { f ->
        val p = (scene.timeSec - f.startSec) / Flash.DURATION_SEC
        if (p in 0f..1f) {
            val c = iso.rectCorners(f.rect.x.toFloat(), f.rect.y.toFloat(), f.rect.w.toFloat(), f.rect.h.toFloat())
            drawPath(polygonPath(c), (if (f.valid) Paper.Valid else Paper.Invalid).copy(alpha = 0.45f * (1f - p)))
        }
    }
}

private fun DrawScope.drawEmptyLot(scene: StreetScene, lot: Lot, textMeasurer: TextMeasurer) {
    val r = lot.rect
    val c = scene.iso.rectCorners(r.x.toFloat(), r.y.toFloat(), r.w.toFloat(), r.h.toFloat())
    val path = polygonPath(c)
    drawPath(path, Paper.LockedLot)
    drawPath(path, Paper.Ink.copy(alpha = 0.45f), style = dashed)
    val label = if (lot.state == LotState.LOCKED) "Locked" else "For sale"
    val layout = textMeasurer.measure(label, TextStyle(fontSize = 18.sp, color = Paper.Ink.copy(alpha = 0.6f)))
    val center = scene.iso.rectCenter(r.x.toFloat(), r.y.toFloat(), r.w.toFloat(), r.h.toFloat())
    drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
}

private fun DrawScope.drawBuilding(scene: StreetScene, lot: Lot, textMeasurer: TextMeasurer) {
    val store = scene.storefront
    val r = lot.rect
    drawPath(polygonPath(scene.iso.rectCorners(r.x.toFloat(), r.y.toFloat(), r.w.toFloat(), r.h.toFloat())), Paper.Lot)

    val side = polygonPath(store.sideFace(r))
    drawPath(side, Paper.WallSide)
    drawPath(side, Paper.Ink, style = outline)

    val front = polygonPath(store.frontFace(r))
    drawPath(front, Paper.Wall)
    val business = lot.business
    val facade = scene.world.sprite(business?.facadeSpriteId)?.let { scene.images[it.id] }
    if (facade != null) {
        drawMapped(store.facadeTransform(r, facade.width.toFloat(), facade.height.toFloat())) { canvas ->
            canvas.drawBitmap(facade.asAndroidBitmap(), 0f, 0f, bitmapPaint)
        }
    } else {
        drawDefaultStorefront(store, r, business?.name.orEmpty())
    }
    drawPath(front, Paper.Ink, style = outline)

    val roof = polygonPath(store.roof(r))
    drawPath(roof, Paper.Roof)
    drawPath(roof, Paper.Ink, style = outline)

    // Name tag floating above the roof.
    val name = business?.name?.takeIf { it.isNotBlank() } ?: return
    val layout = textMeasurer.measure(name, TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Paper.Ink), maxLines = 1)
    val top = scene.iso.gridToScreen(r.x + r.w / 2f, r.y + r.h / 2f, store.wallHeight)
    val pad = 10f
    val tl = Offset(top.x - layout.size.width / 2f - pad, top.y - layout.size.height - 30f)
    drawRoundRect(
        Color.White.copy(alpha = 0.92f), topLeft = tl,
        size = Size(layout.size.width + pad * 2, layout.size.height + pad),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f),
    )
    drawText(layout, topLeft = Offset(tl.x + pad, tl.y + pad / 2f))
}

private fun DrawScope.drawInterior(scene: StreetScene, lot: Lot) {
    val iso = scene.iso
    val store = scene.storefront
    val r = lot.rect

    // Back walls first: they are behind everything inside the shop.
    val (backAlong, backSide) = store.backWalls(r)
    drawPath(polygonPath(backSide), Paper.BackWallSide)
    drawPath(polygonPath(backSide), Paper.Ink, style = outline)
    drawPath(polygonPath(backAlong), Paper.BackWall)
    drawPath(polygonPath(backAlong), Paper.Ink, style = outline)

    // Floor with a faint tile grid.
    val floor = polygonPath(iso.rectCorners(r.x.toFloat(), r.y.toFloat(), r.w.toFloat(), r.h.toFloat()))
    drawPath(floor, Paper.Floor)
    for (i in 1 until r.w) {
        drawLine(Paper.FloorLine, iso.gridToScreen(r.x + i.toFloat(), r.y.toFloat()).toOffset(),
            iso.gridToScreen(r.x + i.toFloat(), r.bottom.toFloat()).toOffset(), strokeWidth = 1.5f)
    }
    for (j in 1 until r.h) {
        drawLine(Paper.FloorLine, iso.gridToScreen(r.x.toFloat(), r.y + j.toFloat()).toOffset(),
            iso.gridToScreen(r.right.toFloat(), r.y + j.toFloat()).toOffset(), strokeWidth = 1.5f)
    }

    scene.freeTileHints?.let { free ->
        for ((tx, ty) in free) {
            val c = iso.rectCorners(tx + 0.12f, ty + 0.12f, 0.76f, 0.76f)
            drawPath(polygonPath(c), Paper.Valid.copy(alpha = 0.16f))
        }
    }

    scene.selectedId?.let { id ->
        lot.furniture.firstOrNull { it.id == id }?.let { piece ->
            val fr = Placement.footprint(piece, scene.world::sprite)
            val c = iso.rectCorners(fr.x.toFloat(), fr.y.toFloat(), fr.w.toFloat(), fr.h.toFloat())
            drawPath(polygonPath(c), Paper.Accent.copy(alpha = 0.25f))
            drawPath(polygonPath(c), Paper.Accent, style = outline)
        }
    }
    drawPath(floor, Paper.Ink, style = thinLine)

    val cutouts = scene.interiorCutouts()
    for (item in cutouts) {
        val panel = item.panel
        if (panel != null) drawPanelCutout(item.image, panel, item.pose, item.shadow)
        else if (item.clipBottom != null) {
            clipRect(left = -1e6f, top = -1e6f, right = 1e6f, bottom = item.clipBottom) {
                drawCutout(item.image, item.foot, item.size, item.pose, item.mirrored, null)
            }
        } else drawCutout(
            item.image, item.foot, item.size, item.pose, item.mirrored, item.shadow,
            anchorX = item.anchorX, anchorY = item.anchorY,
        )
        drawDisplayed(item)
        drawHeld(item)
        if (item.id == scene.selectedId && item.id == SHOPKEEPER_ID) {
            drawCircle(Paper.Accent, radius = 6f, center = item.foot.copy(y = item.foot.y - item.size.height - 14f))
        }
    }
    drawHandovers(scene, lot, cutouts)

    // Low stubs where the front walls were cut away.
    val stub = 10f
    val frontLeft = listOf(
        iso.gridToScreen(r.x.toFloat(), r.bottom.toFloat()), iso.gridToScreen(r.right.toFloat(), r.bottom.toFloat()),
        iso.gridToScreen(r.right.toFloat(), r.bottom.toFloat(), stub), iso.gridToScreen(r.x.toFloat(), r.bottom.toFloat(), stub),
    )
    val frontRight = listOf(
        iso.gridToScreen(r.right.toFloat(), r.bottom.toFloat()), iso.gridToScreen(r.right.toFloat(), r.y.toFloat()),
        iso.gridToScreen(r.right.toFloat(), r.y.toFloat(), stub), iso.gridToScreen(r.right.toFloat(), r.bottom.toFloat(), stub),
    )
    for (wall in listOf(frontRight, frontLeft)) {
        drawPath(polygonPath(wall), Paper.Wall)
        drawPath(polygonPath(wall), Paper.Ink, style = thinLine)
    }
}

private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

/** Held items are drawn this tall relative to the character holding them. */
private const val HELD_SCALE = 0.36f

private fun heldSize(image: ImageBitmap, holderHeight: Float): Size {
    val h = holderHeight * HELD_SCALE
    return Size(h * image.width / image.height.coerceAtLeast(1), h)
}

/** Draws whatever a character is holding at their front hand, with a gentle bob. */
private fun DrawScope.drawHeld(item: CutoutItem) {
    val image = item.held ?: return
    val size = heldSize(image, item.size.height)
    val hand = item.handPoint()
    drawCutout(image, Offset(hand.x, hand.y + size.height * 0.5f), size, CutoutPose.Rest, mirrored = false)
}

/**
 * Service item handover: while a service starts or ends, the service's drawing arcs between the
 * staff member's hand and the customer's.
 */
private fun DrawScope.drawHandovers(scene: StreetScene, lot: Lot, cutouts: List<CutoutItem>) {
    val byId = cutouts.associateBy { it.id }
    for (v in scene.services) {
        if (v.customer.insideLotId != lot.id) continue
        val giving = v.phase == ServicePhase.HANDING_OVER
        if (!giving && v.phase != ServicePhase.HANDING_BACK) continue
        val image = scene.offeringImage(lot, v.offeringId) ?: continue
        val staffItem = v.staff?.let { byId[it.id] }
        val customerItem = byId[npcEffectId(v.customer)]
        val staffHand = staffItem?.handPoint() ?: v.staff?.let {
            scene.iso.gridToScreen(it.position.x, it.position.y).toOffset() - Offset(0f, scene.iso.tileHeight)
        } ?: continue
        val customerHand = customerItem?.handPoint() ?: continue
        val (from, to) = if (giving) staffHand to customerHand else customerHand to staffHand
        val t = v.progress
        val eased = t * t * (3f - 2f * t)
        val arc = scene.iso.tileHeight * 0.8f * kotlin.math.sin(Math.PI.toFloat() * t)
        val point = Offset(from.x + (to.x - from.x) * eased, from.y + (to.y - from.y) * eased - arc)
        val holderHeight = customerItem.size.height
        val size = heldSize(image, holderHeight)
        val spin = 20f * kotlin.math.sin(Math.PI.toFloat() * 2f * t)
        drawCutout(image, Offset(point.x, point.y + size.height * 0.5f), size, CutoutPose(rotationDeg = spin), mirrored = false)
    }
}

/** Matches the upright furniture height cap in CutoutSizing. */
private const val FURNITURE_MAX_HEIGHT_TILES = 4.2f

/**
 * Draws a drawing leaned into the grid as a flat standing panel. The procedural pose is
 * applied around the middle of the panel's bottom edge, like an upright cutout's feet.
 */
private fun DrawScope.drawPanelCutout(
    image: ImageBitmap,
    panel: GridPanel.Panel,
    pose: CutoutPose,
    shadow: DrawScope.(Float) -> Unit,
) {
    val height = panel.corners[3].y - panel.corners[0].y
    shadow(min(1f, pose.lift * 3f))
    val foot = panel.foot.toOffset()
    translate(foot.x, foot.y - pose.lift * height) {
        rotate(pose.rotationDeg, pivot = Offset.Zero) {
            scale(scaleX = pose.scaleX, scaleY = pose.scaleY, pivot = Offset.Zero) {
                translate(-foot.x, -foot.y) {
                    drawMapped(panel.transform) { canvas ->
                        canvas.drawBitmap(image.asAndroidBitmap(), 0f, 0f, bitmapPaint)
                    }
                }
            }
        }
    }
}

/** Runs [block] on the native canvas with an affine transform concatenated onto the current one. */
private inline fun DrawScope.drawMapped(m: Affine2D, crossinline block: (android.graphics.Canvas) -> Unit) {
    drawIntoCanvas { c ->
        val canvas = c.nativeCanvas
        canvas.save()
        canvas.concat(Matrix().apply { setValues(floatArrayOf(m.a, m.c, m.tx, m.b, m.d, m.ty, 0f, 0f, 1f)) })
        block(canvas)
        canvas.restore()
    }
}

/** A plain starter storefront (door, window and sign) shown until the player draws their own. */
private fun DrawScope.drawDefaultStorefront(store: Storefront, lot: GridRect, name: String) {
    val h = 100f
    val w = store.facadeAspect(lot.w) * h
    drawMapped(store.facadeTransform(lot, w, h)) { canvas ->
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.5f; color = Paper.Ink.toArgb()
        }
        // Sign board.
        fill.color = Color(0xFFF6E7C8).toArgb()
        canvas.drawRect(w * 0.08f, 8f, w * 0.92f, 26f, fill)
        canvas.drawRect(w * 0.08f, 8f, w * 0.92f, 26f, line)
        if (name.isNotBlank()) {
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Paper.Ink.toArgb(); textSize = 12f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
            }
            val maxW = w * 0.8f
            val shown = if (text.measureText(name) <= maxW) name
            else name.take(text.breakText(name, true, maxW - text.measureText("…"), null)) + "…"
            canvas.drawText(shown, w / 2f, 21.5f, text)
        }
        // Door.
        fill.color = Paper.Accent.toArgb()
        canvas.drawRect(w * 0.14f, 40f, w * 0.14f + 24f, 100f, fill)
        canvas.drawRect(w * 0.14f, 40f, w * 0.14f + 24f, 100f, line)
        fill.color = Color.White.toArgb()
        canvas.drawCircle(w * 0.14f + 19f, 72f, 1.8f, fill)
        // Window.
        fill.color = Color(0xFFCFE3F2).toArgb()
        canvas.drawRect(w * 0.42f, 40f, w * 0.88f, 78f, fill)
        canvas.drawRect(w * 0.42f, 40f, w * 0.88f, 78f, line)
        canvas.drawLine(w * 0.65f, 40f, w * 0.65f, 78f, line)
    }
}

/** Center of a grid rect in screen space. */
fun IsoProjection.center(r: GridRect): Vec2 = rectCenter(r.x.toFloat(), r.y.toFloat(), r.w.toFloat(), r.h.toFloat())
