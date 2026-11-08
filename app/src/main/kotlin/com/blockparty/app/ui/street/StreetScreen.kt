package com.blockparty.app.ui.street

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chair
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.blockparty.app.DrawPurpose
import com.blockparty.app.GameViewModel
import com.blockparty.app.Screen
import com.blockparty.app.ui.common.SpritePickerSheet
import com.blockparty.app.ui.common.SpriteThumbnail
import com.blockparty.app.ui.theme.Paper
import com.blockparty.core.geometry.IsoProjection
import com.blockparty.core.geometry.Vec2
import com.blockparty.core.model.LotState
import com.blockparty.core.model.NpcProfile
import com.blockparty.core.npc.NpcPool
import com.blockparty.core.npc.CustomerBrain
import com.blockparty.core.npc.ShopCatalog
import com.blockparty.core.npc.StaffSpec
import com.blockparty.core.npc.Staffing
import com.blockparty.core.npc.StreetCrowd
import com.blockparty.core.sim.Economy
import com.blockparty.core.sim.Lots
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import com.blockparty.core.npc.CrowdEvent
import com.blockparty.core.npc.ShopDoor
import com.blockparty.core.npc.Fixture
import com.blockparty.core.npc.Fixtures
import com.blockparty.core.model.FurnitureType
import com.blockparty.core.model.Lot
import com.blockparty.core.model.OfferingKind
import kotlin.random.Random
import com.blockparty.core.model.SpriteAsset
import com.blockparty.core.model.SpriteKind
import com.blockparty.core.model.WorldState
import com.blockparty.core.sim.Placement
import com.blockparty.core.world.WorldFactory
import kotlinx.coroutines.launch
import kotlin.math.min

private val iso = IsoProjection()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreetScreen(vm: GameViewModel, world: WorldState) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val textMeasurer = rememberTextMeasurer()
    val currentLot = vm.currentLot ?: return
    var buyLotId by remember { mutableStateOf<String?>(null) }

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var cameraReady by remember { mutableStateOf(false) }
    var interior by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var movingId by remember { mutableStateOf<String?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    var showPeople by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    val popups = remember { mutableStateListOf<Popup>() }
    var selectedNpc by remember { mutableStateOf<NpcProfile?>(null) }
    var flash by remember { mutableStateOf<Flash?>(null) }
    val effects = remember { mutableStateMapOf<String, Effect>() }
    var time by remember { mutableFloatStateOf(0f) }

    val freeTiles = remember(currentLot.furniture, world.sprites) { Placement.freeTiles(currentLot, world::sprite) }

    // Customers: who can appear, and which shops they might walk into.
    val crowd = vm.crowd
    val candidates = remember(world.npcs, world.sprites) { NpcPool.weighted(world) }
    // Every open shop on the street can have customers.
    val hour = remember(time.toInt() / 60) { java.time.LocalTime.now().hour }
    val doors = remember(world.street.lots, world.sprites, hour) {
        world.street.lots.filter { it.state == LotState.OWNED && it.business != null }
            .map { lot -> shopDoor(world, lot, Placement.freeTiles(lot, world::sprite), hour) }
    }
    val pick: (Random) -> NpcProfile? = { r -> NpcPool.choose(candidates, r) }
    val latestDoors by rememberUpdatedState(doors)
    val latestPick by rememberUpdatedState(pick)
    if (!vm.crowdWarmedUp) {
        // Start with a lively street rather than an empty one.
        crowd.warmUp(25f, world.street, doors, pick)
        vm.crowdWarmedUp = true
    }

    // Placing new furniture always happens inside the shop.
    val placing = vm.placingSpriteId
    LaunchedEffect(placing) { if (placing != null) { interior = true; selectedId = null; movingId = null } }

    LaunchedEffect(Unit) {
        val start = withFrameNanosCompat()
        var last = start
        while (true) {
            val now = withFrameNanosCompat()
            val dt = ((now - last) / 1e9f).coerceIn(0f, 0.1f)
            last = now
            time = (now - start) / 1e9f
            if ((now / 1_000_000_000L) % 60L == 0L) crowd.traffic = vm.trafficNow()
            crowd.update(dt, world.street, latestDoors, latestPick)
            val events = crowd.drainEvents()
            if (events.isNotEmpty()) {
                vm.recordEvents(events)
                for (e in events) when (e) {
                    is CrowdEvent.Sale -> popups += Popup("+${e.total}", Paper.Coin, e.at, time)
                    is CrowdEvent.Review -> popups += Popup("★".repeat(e.stars) + "☆".repeat(5 - e.stars), Paper.Star, e.at, time, extraRise = 0.9f)
                    is CrowdEvent.Visit -> Unit
                }
                popups.removeAll { time - it.startSec > Popup.DURATION_SEC }
            }
        }
    }

    // Built per frame inside the draw phase (reading `time` there only invalidates drawing,
    // not the whole screen's composition).
    fun sceneAt(t: Float) = StreetScene(
        world = world,
        iso = iso,
        images = vm.sprites.images,
        interiorLotId = if (interior) currentLot.id else null,
        timeSec = t,
        staff = crowd.staff,
        services = crowd.services,
        effects = effects,
        selectedId = selectedId,
        freeTileHints = if (placing != null || movingId != null) freeTiles else null,
        flash = flash,
        crowd = crowd.people,
        lotPrices = world.street.lots.filter { it.state == LotState.VACANT }.associate { it.id to Lots.price(it, world) },
    )
    val scene = sceneAt(0f) // time-independent queries: bounds for camera and taps

    fun animateCamera(target: Rect, fill: Float) {
        if (canvasSize == IntSize.Zero) return
        val targetZoom = min(canvasSize.width * fill / target.width, canvasSize.height * 0.7f / target.height)
            .coerceIn(MIN_ZOOM, MAX_ZOOM)
        val targetPan = Offset(canvasSize.width / 2f, canvasSize.height * 0.48f) - target.center * targetZoom
        val fromPan = pan
        val fromZoom = zoom
        scope.launch {
            animate(0f, 1f, animationSpec = tween(450)) { v, _ ->
                zoom = fromZoom + (targetZoom - fromZoom) * v
                pan = fromPan + (targetPan - fromPan) * v
            }
        }
    }

    fun enterShop(lot: Lot = currentLot) {
        if (lot.business == null) return
        interior = true
        animateCamera(scene.lotBounds(lot), 0.9f)
    }

    fun exitToStreet() {
        interior = false
        selectedId = null
        movingId = null
        vm.startPlacing(null)
        animateCamera(scene.worldBounds(), 0.95f)
    }

    fun trigger(id: String, type: EffectType) {
        effects.filterValues { time - it.startSec > it.type.durationSec }.keys.forEach(effects::remove)
        effects[id] = Effect(type, time)
    }

    // Origin tile for a footprint so it's roughly centered on the tapped tile.
    fun originFor(sprite: SpriteAsset?, flipped: Boolean, tx: Int, ty: Int): Pair<Int, Int> {
        val r = Placement.footprint(sprite, 0, 0, flipped)
        return (tx - (r.w - 1) / 2) to (ty - (r.h - 1) / 2)
    }

    fun selectPerson(effectId: String): Boolean {
        val person = crowd.people.firstOrNull { npcEffectId(it) == effectId } ?: return false
        trigger(effectId, EffectType.HOP)
        selectedId = effectId
        selectedNpc = person.profile
        return true
    }

    val onTap by rememberUpdatedState { pos: Offset ->
        val p = (pos - pan) / zoom
        val tappedPerson = (sceneAt(time).hitTest(p) as? Hit.Cutout)?.id?.takeIf { it.startsWith("npc:") }
        if (placing == null && movingId == null && tappedPerson != null && selectPerson(tappedPerson)) {
            // Tapped a customer.
        } else if (!interior) {
            val tile = iso.screenToTile(p.x, p.y)
            val lot = world.street.lots.firstOrNull { it.rect.contains(tile.x, tile.y) }
                ?: world.street.lots.firstOrNull { it.state == LotState.OWNED && it.business != null && scene.lotBounds(it).contains(p) }
            when {
                lot == null -> Unit
                lot.state == LotState.OWNED && lot.business != null -> {
                    vm.selectLot(lot.id)
                    enterShop(lot)
                }
                lot.state == LotState.VACANT -> buyLotId = lot.id
                lot.state == LotState.LOCKED -> scope.launch { snackbar.showSnackbar("This lot unlocks as the street grows.") }
            }
        } else {
            val tile = iso.screenToTile(p.x, p.y)
            val moving = movingId
            when {
                placing != null -> {
                    val sprite = world.sprite(placing)
                    val (ox, oy) = originFor(sprite, false, tile.x, tile.y)
                    val placed = vm.placeFurniture(ox, oy)
                    val rect = Placement.footprint(sprite, ox, oy, false)
                    flash = Flash(rect, placed != null, time)
                    if (placed != null) trigger(placed.id, EffectType.WOBBLE)
                }
                moving != null -> {
                    val piece = currentLot.furniture.firstOrNull { it.id == moving }
                    if (piece != null) {
                        val sprite = world.sprite(piece.spriteId)
                        val (ox, oy) = originFor(sprite, piece.flipped, tile.x, tile.y)
                        val ok = vm.moveFurniture(moving, ox, oy)
                        flash = Flash(Placement.footprint(sprite, ox, oy, piece.flipped), ok, time)
                        if (ok) {
                            trigger(moving, EffectType.WOBBLE)
                            movingId = null
                        }
                    } else movingId = null
                }
                else -> when (val hit = sceneAt(time).hitTest(p)) {
                    is Hit.Cutout -> {
                        trigger(hit.id, EffectType.HOP)
                        selectedId = hit.id
                    }
                    is Hit.Tile -> {
                        selectedNpc = null
                        if (selectedId == null && !currentLot.rect.contains(hit.x, hit.y) &&
                            !scene.lotBounds(currentLot).contains(p)
                        ) exitToStreet() else selectedId = null
                    }
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Paper.Background)
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    canvasSize = size
                    if (!cameraReady && size.width > 0) {
                        val b = scene.worldBounds()
                        zoom = (size.width * 0.95f / b.width).coerceIn(MIN_ZOOM, MAX_ZOOM)
                        pan = Offset(size.width / 2f, size.height * 0.48f) - b.center * zoom
                        cameraReady = true
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, panChange, zoomChange, _ ->
                        val newZoom = (zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
                        pan = centroid - (centroid - pan) * (newZoom / zoom) + panChange
                        zoom = newZoom
                    }
                }
                .pointerInput(Unit) { detectTapGestures { onTap(it) } }
        ) {
            translate(pan.x, pan.y) {
                scale(zoom, pivot = Offset.Zero) {
                    drawStreet(sceneAt(time), textMeasurer)
                    drawPopups(popups, time, iso, textMeasurer)
                }
            }
        }

        TopBar(
            title = currentLot.business?.name?.ifBlank { null } ?: if (currentLot.business == null) "Vacant lot" else "My Shop",
            subtitle = world.street.name + if (interior) " · inside" else "",
            coins = world.coins,
            rating = currentLot.business?.rating,
            debtDaysLeft = Economy.graceDaysLeft(world, vm.today()),
            onClick = { showStats = true },
            modifier = Modifier.align(Alignment.TopCenter),
        )

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SnackbarHost(snackbar)
            val selectedPiece = currentLot.furniture.firstOrNull { it.id == selectedId }
            val keeperSprite = world.sprite(currentLot.business?.shopkeeperSpriteId)
            when {
                placing != null -> HintCard(
                    sprite = world.sprite(placing),
                    vm = vm,
                    text = "Tap the floor to place it. Tap again to place another.",
                    action = "Done",
                    onAction = { vm.startPlacing(null) },
                )
                movingId != null -> HintCard(
                    sprite = world.sprite(currentLot.furniture.firstOrNull { it.id == movingId }?.spriteId),
                    vm = vm,
                    text = "Tap where it should go.",
                    action = "Cancel",
                    onAction = { movingId = null },
                )
                selectedPiece != null -> {
                    val sprite = world.sprite(selectedPiece.spriteId)
                    SelectionCard(
                        sprite = sprite,
                        vm = vm,
                        onClose = { selectedId = null },
                    ) {
                        TextButton(onClick = {
                            if (vm.flipFurniture(selectedPiece.id)) trigger(selectedPiece.id, EffectType.WOBBLE)
                            else scope.launch { snackbar.showSnackbar("No room to turn it around here.") }
                        }) { Icon(Icons.Default.Flip, null); Spacer(Modifier.width(4.dp)); Text("Flip") }
                        TextButton(onClick = { movingId = selectedPiece.id; selectedId = null }) {
                            Icon(Icons.Default.OpenWith, null); Spacer(Modifier.width(4.dp)); Text("Move")
                        }
                        TextButton(onClick = { vm.navigate(Screen.DisplayEditor(selectedPiece.id)) }) {
                            Icon(Icons.Default.Tune, null); Spacer(Modifier.width(4.dp)); Text("Setup")
                        }
                        if (sprite != null) {
                            TextButton(onClick = {
                                vm.setAlignToGrid(sprite.id, !sprite.alignToGrid)
                                trigger(selectedPiece.id, EffectType.WOBBLE)
                            }) {
                                Icon(Icons.Default.ViewInAr, null); Spacer(Modifier.width(4.dp))
                                Text(if (sprite.alignToGrid) "Stand up" else "Angle")
                            }
                        }
                        TextButton(onClick = { vm.removeFurniture(selectedPiece.id); selectedId = null }) {
                            Icon(Icons.Default.Delete, null); Spacer(Modifier.width(4.dp)); Text("Store")
                        }
                    }
                }
                selectedId?.startsWith("npc:") == true && selectedNpc != null -> {
                    val npc = selectedNpc!!
                    val sprite = world.sprite(npc.spriteId)
                    SelectionCard(sprite = sprite, vm = vm, title = npc.name, onClose = { selectedId = null; selectedNpc = null }) {
                        CustomerDetails(npc)
                    }
                }
                selectedId == SHOPKEEPER_ID && keeperSprite != null ->
                    SelectionCard(sprite = keeperSprite, vm = vm, onClose = { selectedId = null }) {}
                interior -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val soldOut = currentLot.business?.offerings.orEmpty().count { it.kind == OfferingKind.PRODUCT && it.stock == 0 }
                    if (soldOut > 0) {
                        AssistChip(
                            onClick = vm::restockAll,
                            label = { Text("$soldOut sold out · Restock all (${vm.restockAllNeed().second}¢)") },
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(onClick = ::exitToStreet, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Map, "Back to street")
                    }
                    Button(onClick = { showPicker = true }) {
                        Icon(Icons.Default.Chair, null); Spacer(Modifier.width(6.dp)); Text("Furniture", maxLines = 1)
                    }
                    FilledTonalButton(onClick = { vm.navigate(Screen.ShopEditor) }) {
                        Icon(Icons.Default.Edit, null); Spacer(Modifier.width(6.dp)); Text("Shop", maxLines = 1)
                    }
                }
                }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(onClick = { showPeople = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Groups, "Street people")
                    }
                    Button(onClick = { enterShop() }, enabled = currentLot.business != null) {
                        Icon(Icons.Default.Storefront, null); Spacer(Modifier.width(6.dp)); Text("Go inside")
                    }
                    FilledTonalButton(onClick = { vm.navigate(Screen.ShopEditor) }) {
                        Icon(Icons.Default.Edit, null); Spacer(Modifier.width(6.dp)); Text("Edit shop", maxLines = 1)
                    }
                }
            }
        }
    }

    buyLotId?.let { id ->
        val lot = world.lot(id)
        if (lot != null) {
            val price = Lots.price(lot, world)
            AlertDialog(
                onDismissRequest = { buyLotId = null },
                title = { Text("Buy this lot?") },
                text = {
                    Text(
                        "Open a new shop here for $price coins. Rent is ${Economy.rentPerDay(lot, world.street)} coins a day." +
                            if (world.coins < price) "\n\nYou have ${world.coins} coins." else ""
                    )
                },
                confirmButton = {
                    TextButton(enabled = world.coins >= price, onClick = {
                        buyLotId = null
                        val error = vm.buyLot(id)
                        if (error != null) scope.launch { snackbar.showSnackbar(error) } else {
                            world.lot(id)?.let { enterShop(it.copy(business = com.blockparty.core.model.Business("New Shop"))) }
                            vm.navigate(Screen.ShopEditor)
                        }
                    }) { Text("Buy for $price") }
                },
                dismissButton = { TextButton(onClick = { buyLotId = null }) { Text("Cancel") } },
            )
        }
    }

    if (showStats) {
        currentLot.business?.let { business ->
            ShopStatsSheet(
                business = business,
                coins = world.coins,
                rentPerDay = Economy.rentPerDay(currentLot, world.street),
                wagesPerDay = Economy.wagesPerDay(business),
                books = world.economy.books,
                restockNeed = vm.restockAllNeed(),
                onRestockAll = vm::restockAll,
                debtDaysLeft = Economy.graceDaysLeft(world, vm.today()),
                today = vm.today(),
                onDismiss = { showStats = false },
            )
        }
    }

    if (showPeople) {
        PeopleSheet(
            world = world,
            vm = vm,
            onDismiss = { showPeople = false },
            onDraw = {
                showPeople = false
                vm.navigate(Screen.Draw(SpriteKind.CHARACTER, DrawPurpose.CUSTOMER))
            },
        )
    }

    if (showPicker) {
        SpritePickerSheet(
            title = "Furniture & decor",
            sprites = world.sprites.filter { it.kind == SpriteKind.FURNITURE }
                .sortedByDescending { s -> world.storage.count { it == s.id } },
            badges = world.storage.groupingBy { it }.eachCount().mapValues { "×${it.value} stored" },
            vm = vm,
            drawLabel = "Draw new furniture",
            onDismiss = { showPicker = false },
            onPick = { vm.startPlacing(it.id); showPicker = false },
            onDraw = {
                showPicker = false
                vm.navigate(Screen.Draw(SpriteKind.FURNITURE, DrawPurpose.FURNITURE))
            },
            onDelete = { vm.deleteSprite(it.id) },
        )
    }
}

/** The shop as customers see it: where they can walk, what furniture they use, what it sells. */
private fun shopDoor(world: WorldState, lot: Lot, free: Set<Pair<Int, Int>>, hour: Int): ShopDoor {
    val entry = (lot.rect.x + lot.rect.w / 2) to (lot.rect.bottom - 1)
    val fixtures = ArrayList<Fixture>()
    val browseSpots = ArrayList<Pair<Int, Int>>()
    for (piece in lot.furniture) {
        val sprite = world.sprite(piece.spriteId) ?: continue
        val rect = Placement.footprint(sprite, piece.x, piece.y, piece.flipped)
        val access by lazy { Fixtures.accessTile(rect, free, entry) }
        if (sprite.furnitureType != FurnitureType.DECOR) fixtures += Fixture(piece.id, sprite.furnitureType, rect, access)
        if (sprite.displaySpots.isNotEmpty()) access?.let { browseSpots += it }
    }
    val business = lot.business
    val services = business?.offerings.orEmpty().any { it.kind == OfferingKind.SERVICE }
    val catalog = ShopCatalog.of(business)
    val employees = business?.employees.orEmpty()
    val greeterBoost = Staffing.greeterBoost(employees)
    // Products on display, by the tile customers stand on to look at them.
    val productIds = business?.offerings.orEmpty().filter { it.kind == OfferingKind.PRODUCT }.map { it.id }.toSet()
    val displayItems = HashMap<Pair<Int, Int>, MutableList<String>>()
    for (piece in lot.furniture) {
        val sprite = world.sprite(piece.spriteId) ?: continue
        if (sprite.displaySpots.isEmpty()) continue
        val stocked = sprite.displaySpots.mapNotNull { piece.stock[it.id] }.filter { it in productIds }
        if (stocked.isEmpty()) continue
        val rect = Placement.footprint(sprite, piece.x, piece.y, piece.flipped)
        val access = Fixtures.accessTile(rect, free, entry) ?: continue
        displayItems.getOrPut(access) { ArrayList() } += stocked
    }
    return ShopDoor(
        lotId = lot.id,
        lot = lot.rect,
        freeTiles = free,
        visitChance = { (CustomerBrain.visitChance(it, catalog) * greeterBoost).coerceAtMost(0.9f) },
        staff = listOf(StaffSpec(StreetCrowd.SHOPKEEPER_STAFF_ID, null)) + employees.map { Staffing.spec(it, hour) },
        fixtures = fixtures,
        offersServices = services,
        servicesOnly = services && business?.offerings.orEmpty().none { it.kind == OfferingKind.PRODUCT },
        browseSpots = browseSpots,
        serviceIds = business?.offerings.orEmpty().filter { it.kind == OfferingKind.SERVICE }.map { it.id },
        catalog = catalog,
        displayItems = displayItems,
    )
}

private const val MIN_ZOOM = 0.15f
private const val MAX_ZOOM = 4f

/** Suspends until the next frame and returns its timestamp in nanoseconds. */
private suspend fun withFrameNanosCompat(): Long = androidx.compose.runtime.withFrameNanos { it }

@Composable
private fun TopBar(
    title: String,
    subtitle: String,
    coins: Long,
    rating: Float?,
    debtDaysLeft: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .statusBarsPadding()
            .padding(12.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MonetizationOn, "Coins", Modifier.size(16.dp), tint = Paper.Coin)
                    Spacer(Modifier.width(3.dp))
                    Text(
                        "$coins", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                        color = if (coins < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Star, "Rating", Modifier.size(16.dp), tint = Paper.Star)
                    Spacer(Modifier.width(3.dp))
                    Text(rating?.let { "%.1f".format(it) } ?: "–", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            }
            if (debtDaysLeft != null) {
                Text(
                    "In debt: $debtDaysLeft day${if (debtDaysLeft == 1) "" else "s"} to recover",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

}

@Composable
private fun HintCard(sprite: SpriteAsset?, vm: GameViewModel, text: String, action: String, onAction: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (sprite != null) SpriteThumbnail(vm.sprites.images[sprite.id], Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onAction) { Text(action) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectionCard(
    sprite: SpriteAsset?,
    vm: GameViewModel,
    onClose: () -> Unit,
    title: String? = null,
    actions: @Composable () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (sprite != null) SpriteThumbnail(vm.sprites.images[sprite.id], Modifier.size(48.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title ?: sprite?.name?.ifBlank { null } ?: "Untitled", style = MaterialTheme.typography.titleSmall)
                    if (sprite != null && sprite.kind == SpriteKind.FURNITURE) {
                        val parts = listOfNotNull(
                            sprite.furnitureType.label,
                            sprite.displaySpots.size.takeIf { it > 0 }?.let { "displays $it" },
                        )
                        Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        creditLine(sprite),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
            }
            FlowRow { actions() }
        }
    }
}

/** Artist credit, honouring the artist's choice to stay anonymous. */
fun creditLine(sprite: SpriteAsset?): String =
    if (sprite != null && sprite.showCredit && sprite.artistName.isNotBlank()) "Drawn by ${sprite.artistName}"
    else "Anonymous artist"
