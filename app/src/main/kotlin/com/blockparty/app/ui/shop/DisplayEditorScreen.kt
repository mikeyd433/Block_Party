package com.blockparty.app.ui.shop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blockparty.app.GameViewModel
import com.blockparty.app.ui.common.Checkerboard
import com.blockparty.app.ui.common.SpriteThumbnail
import com.blockparty.app.ui.theme.Paper
import com.blockparty.core.art.CutoutSizing
import com.blockparty.core.geometry.GridPanel
import com.blockparty.core.geometry.IsoProjection
import com.blockparty.core.model.CharacterRole
import com.blockparty.core.model.DisplaySpot
import com.blockparty.core.model.FurnitureType
import com.blockparty.core.model.GridRect
import com.blockparty.core.model.SpriteAsset
import com.blockparty.core.model.Offering
import com.blockparty.core.model.OfferingKind
import com.blockparty.core.model.WorldState
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Sets up a piece of furniture: its type (checkout, service station, seating or decor), where
 * customers sit on it, and display spots with which products this particular placed piece shows.
 * Type, seat height and spots belong to the drawing, so every copy shares them.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DisplayEditorScreen(vm: GameViewModel, world: WorldState, placementId: String) {
    val lot = vm.currentLot ?: return
    val piece = lot.furniture.firstOrNull { it.id == placementId } ?: run { vm.back(); return }
    val sprite = world.sprite(piece.spriteId) ?: run { vm.back(); return }
    val image = vm.sprites.images[sprite.id]
    val spots = sprite.displaySpots
    val products = lot.business?.offerings.orEmpty().filter { it.kind == OfferingKind.PRODUCT }
    var selectedSpotId by remember { mutableStateOf(spots.firstOrNull()?.id) }
    val selected = spots.firstOrNull { it.id == selectedSpotId }

    fun saveSpots(newSpots: List<DisplaySpot>) = vm.setDisplaySpots(sprite.id, newSpots)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Setup: ${sprite.name.ifBlank { "Furniture" }}") },
                navigationIcon = { IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Text("Type", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (type in FurnitureType.entries) {
                    FilterChip(
                        selected = sprite.furnitureType == type,
                        onClick = { vm.setFurnitureType(sprite.id, type) },
                        label = { Text(type.label) },
                    )
                }
            }
            Text(
                sprite.furnitureType.description,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (sprite.furnitureType.hasSeat) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Seat height", Modifier.width(110.dp))
                    Slider(
                        value = sprite.seatHeight,
                        onValueChange = { vm.setSeatHeight(sprite.id, it) },
                        valueRange = 0.05f..1f,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Display piece", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (spots.isEmpty()) "Turn on to put products on this furniture."
                        else "Holds ${spots.size} item${if (spots.size == 1) "" else "s"}. Tap the drawing to add a spot, drag to move one.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = spots.isNotEmpty(),
                    onCheckedChange = { on ->
                        if (on) {
                            val spot = DisplaySpot(UUID.randomUUID().toString(), 0.5f, 0.45f)
                            saveSpots(listOf(spot))
                            selectedSpotId = spot.id
                        } else {
                            saveSpots(emptyList())
                            selectedSpotId = null
                        }
                    },
                )
            }
            Spacer(Modifier.height(8.dp))

            SpotCanvas(
                image = image,
                spots = spots,
                stockImages = spots.associate { spot ->
                    spot.id to piece.stock[spot.id]?.let { oid -> products.firstOrNull { it.id == oid }?.spriteId }
                        ?.let { vm.sprites.images[it] }
                },
                selectedId = selectedSpotId,
                enabled = spots.isNotEmpty(),
                onSelect = { selectedSpotId = it },
                onAdd = { x, y ->
                    val spot = DisplaySpot(UUID.randomUUID().toString(), x, y, selected?.size ?: DisplaySpot.DEFAULT_SIZE)
                    saveSpots(spots + spot)
                    selectedSpotId = spot.id
                },
                onMove = { id, x, y -> saveSpots(spots.map { if (it.id == id) it.copy(x = x, y = y) else it }) },
                seat = if (sprite.furnitureType.hasSeat) SeatPreview(
                    seatHeight = sprite.seatHeight,
                    groundX = sprite.groundX,
                    groundY = sprite.groundY,
                    person = previewPerson(world, vm),
                    personScale = personScale(sprite, world, vm),
                ) else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium),
            )

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (selected != null) {
                    val index = spots.indexOf(selected) + 1
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Spot $index size", Modifier.width(110.dp))
                        Slider(
                            value = selected.size,
                            onValueChange = { v -> saveSpots(spots.map { if (it.id == selected.id) it.copy(size = v) else it }) },
                            valueRange = DisplaySpot.MIN_SIZE..DisplaySpot.MAX_SIZE,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = {
                            saveSpots(spots - selected)
                            vm.setStock(piece.id, selected.id, null)
                            selectedSpotId = spots.firstOrNull { it.id != selected.id }?.id
                        }) { Icon(Icons.Default.Delete, "Remove spot") }
                    }
                    Text(
                        "Spots and sizes are shared by every copy of this furniture. What's on them is just for this one.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (spots.isNotEmpty()) {
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("On display", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { vm.autoFillStock(piece.id) }, enabled = products.isNotEmpty()) {
                            Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Fill automatically")
                        }
                    }
                    if (products.isEmpty()) {
                        Text(
                            "Add products in the shop editor to put them on display.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    spots.forEachIndexed { i, spot ->
                        val current = products.firstOrNull { it.id == piece.stock[spot.id] }
                        StockRow(
                            index = i + 1,
                            selected = spot.id == selectedSpotId,
                            current = current,
                            products = products,
                            image = { o -> o.spriteId?.let { vm.sprites.images[it] } },
                            onPick = { o -> vm.setStock(piece.id, spot.id, o?.id); selectedSpotId = spot.id },
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Shows a sample customer sitting at the seat height in the editor. */
private class SeatPreview(
    val seatHeight: Float,
    val groundX: Float,
    val groundY: Float,
    val person: ImageBitmap?,
    /** A standing customer's height relative to the furniture drawing's height in the shop. */
    val personScale: Float,
)

private fun previewPerson(world: WorldState, vm: GameViewModel): ImageBitmap? =
    world.sprites.firstOrNull { it.role == CharacterRole.CUSTOMER }?.let { vm.sprites.images[it.id] }

/** How tall a customer is next to this furniture in the shop, as a fraction of its drawing height. */
private fun personScale(sprite: SpriteAsset, world: WorldState, vm: GameViewModel): Float {
    val iso = IsoProjection()
    val person = world.sprites.firstOrNull { it.role == CharacterRole.CUSTOMER } ?: return 0.8f
    val personH = CutoutSizing.displaySize(person, iso).second
    val image = vm.sprites.images[sprite.id]
    val furnitureH = if (sprite.alignToGrid && image != null) {
        val p = GridPanel.standing(
            iso, GridRect(0, 0, sprite.footprintW, sprite.footprintH), alongY = false,
            image.width.toFloat(), image.height.toFloat(), iso.tileHeight * 4.2f,
        )
        p.corners[3].y - p.corners[0].y
    } else CutoutSizing.displaySize(sprite, iso).second
    return personH / furnitureH.coerceAtLeast(1f)
}

@Composable
private fun StockRow(
    index: Int,
    selected: Boolean,
    current: Offering?,
    products: List<Offering>,
    image: (Offering) -> ImageBitmap?,
    onPick: (Offering?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$index", Modifier.width(28.dp),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        SpriteThumbnail(current?.let(image), Modifier.size(44.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { open = true }, enabled = products.isNotEmpty()) {
                Text(current?.name?.ifBlank { "Untitled" } ?: "Empty", Modifier.weight(1f, fill = false))
                Icon(Icons.Default.ArrowDropDown, null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("Empty") }, onClick = { onPick(null); open = false })
                for (o in products) {
                    DropdownMenuItem(
                        text = { Text(o.name.ifBlank { "Untitled" }) },
                        leadingIcon = { SpriteThumbnail(image(o), Modifier.size(28.dp)) },
                        onClick = { onPick(o); open = false },
                    )
                }
            }
        }
    }
}

/**
 * The furniture drawing with its display spots. Each spot is shown as the product placed there,
 * or as a numbered placeholder box sized like an item would be.
 */
@Composable
private fun SpotCanvas(
    image: ImageBitmap?,
    spots: List<DisplaySpot>,
    stockImages: Map<String, ImageBitmap?>,
    selectedId: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onAdd: (Float, Float) -> Unit,
    onMove: (String, Float, Float) -> Unit,
    seat: SeatPreview? = null,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val latestSpots by rememberUpdatedState(spots)
    val latestStock by rememberUpdatedState(stockImages)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestOnSelect by rememberUpdatedState(onSelect)
    val latestOnAdd by rememberUpdatedState(onAdd)
    val latestOnMove by rememberUpdatedState(onMove)

    Box(modifier) {
        Checkerboard(Modifier.fillMaxSize())
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(image) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val img = image ?: return@awaitEachGesture
                        if (!latestEnabled) return@awaitEachGesture
                        val fit = fitRect(img, size.width.toFloat(), size.height.toFloat())
                        fun toImage(p: Offset) = Pair(
                            ((p.x - fit.left) / fit.width).coerceIn(0f, 1f),
                            ((p.y - fit.top) / fit.height).coerceIn(0f, 1f),
                        )
                        val hit = latestSpots.lastOrNull { spot ->
                            spotBox(spot, fit, latestStock[spot.id]).inflate(12f).contains(down.position)
                        }
                        if (hit != null) latestOnSelect(hit.id)
                        var moved = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (hit != null && (moved || (change.position - down.position).getDistance() > 8f)) {
                                moved = true
                                val (x, y) = toImage(change.position)
                                latestOnMove(hit.id, x, y)
                                change.consume()
                            }
                        }
                        if (hit == null && fit.contains(down.position)) {
                            val (x, y) = toImage(down.position)
                            latestOnAdd(x, y)
                        }
                    }
                },
        ) {
            val img = image ?: return@Canvas
            val fit = fitRect(img, size.width, size.height)
            drawImage(
                img,
                dstOffset = IntOffset(fit.left.roundToInt(), fit.top.roundToInt()),
                dstSize = IntSize(fit.width.roundToInt(), fit.height.roundToInt()),
                filterQuality = FilterQuality.High,
            )
            if (seat != null) {
                val seatY = fit.top + (seat.groundY - seat.seatHeight) * fit.height
                val person = seat.person
                if (person != null) {
                    val ph = seat.personScale * fit.height
                    val pw = ph * person.width / person.height.coerceAtLeast(1)
                    val cx = fit.left + seat.groundX * fit.width
                    val top = seatY + ph * 0.32f - ph
                    clipRect(bottom = seatY) {
                        drawImage(
                            person,
                            dstOffset = IntOffset((cx - pw / 2f).roundToInt(), top.roundToInt()),
                            dstSize = IntSize(pw.roundToInt().coerceAtLeast(1), ph.roundToInt().coerceAtLeast(1)),
                            alpha = 0.85f,
                            filterQuality = FilterQuality.High,
                        )
                    }
                }
                drawLine(
                    Paper.Accent, Offset(fit.left, seatY), Offset(fit.right, seatY), strokeWidth = 3f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
                )
            }
            spots.forEachIndexed { i, spot ->
                val item = stockImages[spot.id]
                val box = spotBox(spot, fit, item)
                val isSelected = spot.id == selectedId
                if (item != null) {
                    drawImage(
                        item,
                        dstOffset = IntOffset(box.left.roundToInt(), box.top.roundToInt()),
                        dstSize = IntSize(box.width.roundToInt().coerceAtLeast(1), box.height.roundToInt().coerceAtLeast(1)),
                        filterQuality = FilterQuality.High,
                    )
                } else {
                    drawRect(Color.White.copy(alpha = 0.55f), box.topLeft, box.size)
                }
                drawRect(
                    if (isSelected) Paper.Accent else Paper.Ink.copy(alpha = 0.7f), box.topLeft, box.size,
                    style = Stroke(width = if (isSelected) 4f else 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                )
                drawCircle(if (isSelected) Paper.Accent else Paper.Ink, 6f, Offset(box.center.x, box.bottom))
                val label = textMeasurer.measure("${i + 1}", TextStyle(fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Bold))
                val badge = Offset(box.left, box.top)
                drawCircle(if (isSelected) Paper.Accent else Paper.Ink, 14f, badge)
                drawText(label, topLeft = badge - Offset(label.size.width / 2f, label.size.height / 2f))
            }
        }
    }
}

/** Where the furniture drawing sits inside the canvas (centered, with a margin). */
private fun fitRect(img: ImageBitmap, w: Float, h: Float): Rect {
    val scale = min(w * 0.9f / img.width, h * 0.9f / img.height)
    val iw = img.width * scale
    val ih = img.height * scale
    return Rect(Offset((w - iw) / 2f, (h - ih) / 2f), Size(iw, ih))
}

/** Screen box of an item on a spot: bottom-centre on the spot, height from the spot's size. */
private fun spotBox(spot: DisplaySpot, fit: Rect, item: ImageBitmap?): Rect {
    val h = spot.size * fit.height
    val w = if (item != null) h * item.width / item.height.coerceAtLeast(1) else h * 0.8f
    val cx = fit.left + spot.x * fit.width
    val bottom = fit.top + spot.y * fit.height
    return Rect(cx - w / 2f, bottom - h, cx + w / 2f, bottom)
}

private fun Rect.inflate(by: Float) = Rect(left - by, top - by, right + by, bottom + by)
