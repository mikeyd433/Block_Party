package com.blockparty.app.ui.draw

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Slider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.blockparty.app.DrawPurpose
import com.blockparty.app.GameViewModel
import com.blockparty.app.ui.common.Checkerboard
import com.blockparty.app.ui.theme.Paper
import com.blockparty.core.geometry.Facing
import com.blockparty.core.geometry.IsoProjection
import com.blockparty.core.geometry.Storefront
import com.blockparty.core.model.SpriteAsset
import com.blockparty.core.model.SpriteKind
import com.blockparty.core.model.WorldState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val palette = listOf(
    0xFF2E2B26, 0xFF6B6459, 0xFFB3AC9F, 0xFFFFFFFF, 0xFFD9443A, 0xFFF08A4B, 0xFFF5C542, 0xFFF7E68B,
    0xFF7BC67B, 0xFF2F8F5B, 0xFF5BC0D9, 0xFF3D6FC4, 0xFF27387A, 0xFF9B6FD1, 0xFFE58BB8, 0xFF8C5A3C,
    0xFFE2B48A, 0xFF4A3426,
).map { Color(it) }

private val brushSizes = listOf("S" to 8f, "M" to 18f, "L" to 40f, "XL" to 80f)

/** Furniture footprints offered when drawing furniture (flipping in-game transposes them). */
private val footprints = listOf(1 to 1, 2 to 1, 2 to 2, 3 to 1, 3 to 2)

private const val CANVAS_LONG_SIDE = 1024

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DrawingScreen(vm: GameViewModel, world: WorldState, kind: SpriteKind, purpose: DrawPurpose) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lotWidth = vm.homeLot?.rect?.w ?: 6
    val aspect = if (kind == SpriteKind.FACADE) Storefront(IsoProjection()).facadeAspect(lotWidth) else 1f
    val state = remember {
        DrawingState(CANVAS_LONG_SIDE, (CANVAS_LONG_SIDE / aspect).roundToInt())
    }
    var name by remember { mutableStateOf("") }
    var facesRight by remember { mutableStateOf(true) }
    var footprint by remember { mutableStateOf(1 to 1) }
    var alignToGrid by remember { mutableStateOf(true) }
    var signed by remember { mutableStateOf(world.playerName.isNotBlank()) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var depth by remember { mutableFloatStateOf(0.45f) }
    var facing by remember { mutableStateOf(Facing.LEFT) }
    var turning by remember { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val bitmap = withContext(Dispatchers.IO) { DrawingExport.decodeUpload(context.contentResolver, uri) }
            if (bitmap == null) {
                message = "Couldn't open that image."
            } else {
                state.uploaded = bitmap
                state.uploadedCutout = null
                state.uploadedCutout = withContext(Dispatchers.Default) { DrawingExport.removeBackground(bitmap) }
            }
        }
    }

    fun leave() {
        if (state.strokes.isNotEmpty() || state.uploaded != null || state.isTurned) confirmDiscard = true else vm.back()
    }

    fun turn() {
        if (turning) return
        turning = true
        scope.launch {
            val flat = withContext(Dispatchers.Default) { state.rasterize() }
            val result = withContext(Dispatchers.Default) { DrawingExport.addDepth(flat, depth, facing) }
            turning = false
            if (result == null) message = "Draw the front first!" else state.applyTurned(result, flat)
        }
    }

    // Live thickness changes after turning: re-extrude from the flat drawing. A newer slider
    // value restarts this effect, so stale renders are dropped.
    LaunchedEffect(depth, state.isTurned) {
        val flat = state.flatRender ?: return@LaunchedEffect
        delay(40)
        val result = withContext(Dispatchers.Default) { DrawingExport.addDepth(flat, depth, facing) }
        if (result != null) state.replaceTurned(result)
    }
    BackHandler(onBack = ::leave)

    fun save() {
        if (saving) return
        saving = true
        scope.launch {
            val ground = state.turnedGround
            var groundX = 0.5f
            var groundY = 1f
            val result: Bitmap? = withContext(Dispatchers.Default) {
                val full = state.rasterize()
                // Storefronts keep the whole canvas so they map exactly onto the wall.
                if (kind == SpriteKind.FACADE) return@withContext DrawingExport.scaleWithin(full, CANVAS_LONG_SIDE)
                val (bitmap, crop) = DrawingExport.cropAndScaleWithRect(full) ?: return@withContext null
                if (ground != null) {
                    // A turned box rests on the middle of its base, not the bottom of the picture.
                    groundX = ((ground.x - crop.left) / crop.width).coerceIn(0f, 1f)
                    groundY = ((ground.y - crop.top) / crop.height).coerceIn(0f, 1f)
                }
                bitmap
            }
            saving = false
            if (result == null) {
                message = "Draw something first!"
                return@launch
            }
            val template = SpriteAsset(
                id = "",
                kind = kind,
                name = name.trim().ifBlank { defaultName(kind, purpose) },
                widthPx = result.width,
                heightPx = result.height,
                facesRight = facesRight,
                footprintW = footprint.first,
                footprintH = footprint.second,
                // A turned drawing is already isometric, so it stands as an upright cutout.
                alignToGrid = alignToGrid && !state.isTurned,
                groundX = groundX,
                groundY = groundY,
                showCredit = signed,
            )
            vm.saveDrawing(result, template, purpose)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title(kind, purpose)) },
                navigationIcon = { IconButton(onClick = ::leave) { Icon(Icons.Default.Close, "Close") } },
                actions = {
                    IconButton(onClick = state::undo, enabled = state.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                    IconButton(onClick = state::redo, enabled = state.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
                    TextButton(onClick = ::save, enabled = !saving && !state.isEmpty) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            // Drawing surface. Kept out of the scrolling area so strokes don't scroll the page.
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .aspectRatio(aspect)
                    .clip(MaterialTheme.shapes.medium)
                    .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium),
            ) {
                if (kind == SpriteKind.FACADE) {
                    // Transparent parts of a storefront show the plain wall behind it.
                    Box(Modifier.fillMaxSize().background(Paper.Wall))
                } else {
                    Checkerboard(Modifier.fillMaxSize())
                }
                DrawingCanvas(state, Modifier.fillMaxSize())
            }

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = !state.eraser, onClick = { state.eraser = false },
                        label = { Text("Brush") }, leadingIcon = { Icon(Icons.Default.Brush, null, Modifier.size(18.dp)) },
                    )
                    FilterChip(
                        selected = state.eraser, onClick = { state.eraser = true },
                        label = { Text("Eraser") }, leadingIcon = { Icon(Icons.Outlined.CleaningServices, null, Modifier.size(18.dp)) },
                    )
                    for ((label, w) in brushSizes) {
                        FilterChip(selected = state.brushWidth == w, onClick = { state.brushWidth = w }, label = { Text(label) })
                    }
                }

                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (c in palette) {
                        val selected = c == state.color && !state.eraser
                        Box(
                            Modifier
                                .size(if (selected) 40.dp else 34.dp)
                                .clip(CircleShape)
                                .background(c)
                                .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.2f), CircleShape)
                                .clickable { state.color = c; state.eraser = false },
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) {
                        Icon(Icons.Default.Image, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Upload image")
                    }
                    OutlinedButton(onClick = state::clear) {
                        Icon(Icons.Default.DeleteSweep, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Clear")
                    }
                }
                if (state.uploaded != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoFixHigh, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Remove photo background", Modifier.weight(1f))
                        Switch(checked = state.removeBackground, onCheckedChange = { state.removeBackground = it })
                    }
                }

                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(40) },
                    label = { Text("Name") }, placeholder = { Text(defaultName(kind, purpose)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )

                when (kind) {
                    SpriteKind.FURNITURE -> {
                        MakeItThreeD(
                            state = state,
                            depth = depth,
                            onDepth = { depth = it },
                            facing = facing,
                            onFacing = { facing = it },
                            busy = turning,
                            onTurn = ::turn,
                        )
                        Text("Floor space", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (fp in footprints) {
                                FilterChip(
                                    selected = footprint == fp, onClick = { footprint = fp },
                                    label = { Text("${fp.first} × ${fp.second}") },
                                )
                            }
                        }
                        // A turned drawing is already at the isometric angle.
                        if (!state.isTurned) Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Angle to the floor grid")
                                Text(
                                    if (alignToGrid) "Draw it straight on. It'll be angled to line up with the floor."
                                    else "Stands upright facing you, like a paper cutout.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = alignToGrid, onCheckedChange = { alignToGrid = it })
                        }
                    }
                    SpriteKind.CHARACTER -> {
                        Text("My drawing faces", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = !facesRight, onClick = { facesRight = false }, label = { Text("← Left") })
                            FilterChip(selected = facesRight, onClick = { facesRight = true }, label = { Text("Right →") })
                        }
                        Text(
                            "Characters flip automatically to face where they walk.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SpriteKind.FACADE -> Text(
                        "This panel covers the front wall of your shop. Leave parts empty to show the plain wall.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SpriteKind.PRODUCT -> Unit
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Sign with my name")
                        Text(
                            if (world.playerName.isBlank()) "Set an artist name in the shop editor first."
                            else if (signed) "Others see \"${world.playerName}\" when they tap it." else "You'll stay anonymous.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = signed, onCheckedChange = { signed = it }, enabled = world.playerName.isNotBlank())
                }
                message?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    LaunchedEffect(it) { kotlinx.coroutines.delay(2500); message = null }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard drawing?") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; vm.back() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep drawing") } },
        )
    }
}

/** The live drawing surface. Strokes are drawn in an offscreen layer so the eraser can clear pixels. */
@Composable
private fun DrawingCanvas(state: DrawingState, modifier: Modifier = Modifier) {
    val base = state.baseImage
    val baseImage = remember(base) { base?.asImageBitmap() }
    Canvas(
        modifier
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .pointerInput(state) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val k = state.width / size.width.toFloat()
                    state.begin(down.position * k)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        state.extend(change.position * k)
                        change.consume()
                    }
                    state.end()
                }
            },
    ) {
        val k = size.width / state.width
        scale(k, pivot = Offset.Zero) {
            if (base != null && baseImage != null) {
                val r = state.baseImageRect(base)
                drawImage(
                    baseImage,
                    dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
                    dstSize = IntSize(r.width().roundToInt(), r.height().roundToInt()),
                    filterQuality = FilterQuality.Medium,
                )
            }
            fun stroke(width: Float) = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)
            for (s in state.strokes) {
                drawPath(
                    s.path.asComposePath(), s.color, style = stroke(s.width),
                    blendMode = if (s.eraser) BlendMode.Clear else BlendMode.SrcOver,
                )
            }
            if (state.current.isNotEmpty()) {
                drawPath(
                    smoothPath(state.current.toList()).asComposePath(),
                    state.color, style = stroke(state.brushWidth),
                    blendMode = if (state.eraser) BlendMode.Clear else BlendMode.SrcOver,
                )
            }
        }
    }
}

/** "Add depth & turn": front view in, isometric extruded drawing out, then keep drawing. */
@Composable
private fun MakeItThreeD(
    state: DrawingState,
    depth: Float,
    onDepth: (Float) -> Unit,
    facing: Facing,
    onFacing: (Facing) -> Unit,
    busy: Boolean,
    onTurn: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ViewInAr, null)
                Spacer(Modifier.width(8.dp))
                Text("Make it 3D", style = MaterialTheme.typography.titleSmall)
            }
            if (state.isTurned) {
                Text(
                    "Turned and given depth. Keep drawing to add details to the top and side.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ThicknessSlider(depth, onDepth)
                OutlinedButton(onClick = state::backToFlat) { Text("Back to flat drawing") }
                Text(
                    "Going back drops anything drawn since the turn.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Draw the front straight on, then turn it to the street's angle with depth added.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ThicknessSlider(depth, onDepth)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Faces", Modifier.width(80.dp))
                    FilterChip(selected = facing == Facing.LEFT, onClick = { onFacing(Facing.LEFT) }, label = { Text("↙ Left") })
                    FilterChip(selected = facing == Facing.RIGHT, onClick = { onFacing(Facing.RIGHT) }, label = { Text("Right ↘") })
                }
                Button(onClick = onTurn, enabled = !busy && !state.isEmpty) {
                    Text(if (busy) "Turning…" else "Add depth & turn")
                }
            }
        }
    }
}

@Composable
private fun ThicknessSlider(depth: Float, onDepth: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Thickness", Modifier.width(80.dp))
        Slider(
            value = depth, onValueChange = onDepth,
            valueRange = DrawingExport.MIN_DEPTH..DrawingExport.MAX_DEPTH,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun title(kind: SpriteKind, purpose: DrawPurpose) = when (purpose) {
    DrawPurpose.FURNITURE -> "Draw furniture"
    DrawPurpose.FACADE -> "Draw your storefront"
    DrawPurpose.SHOPKEEPER -> "Draw your shopkeeper"
    DrawPurpose.OFFERING -> "Draw it"
}.let { if (kind == SpriteKind.CHARACTER && purpose != DrawPurpose.SHOPKEEPER) "Draw a character" else it }

private fun defaultName(kind: SpriteKind, purpose: DrawPurpose) = when (purpose) {
    DrawPurpose.FURNITURE -> "Furniture"
    DrawPurpose.FACADE -> "Storefront"
    DrawPurpose.SHOPKEEPER -> "Shopkeeper"
    DrawPurpose.OFFERING -> "Item"
}.let { if (kind == SpriteKind.PRODUCT && purpose != DrawPurpose.OFFERING) "Item" else it }
