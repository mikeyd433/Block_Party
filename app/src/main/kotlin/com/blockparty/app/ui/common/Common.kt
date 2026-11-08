package com.blockparty.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blockparty.app.GameViewModel
import com.blockparty.core.model.SpriteAsset
import com.blockparty.core.model.TagCategory
import com.blockparty.core.model.TagLibrary

/** Checkerboard that shows where a drawing is transparent. */
@Composable
fun Checkerboard(modifier: Modifier = Modifier, cell: Float = 18f) {
    Canvas(modifier) {
        drawRect(Color.White)
        val shade = Color(0xFFECE8DF)
        var y = 0
        while (y * cell < size.height) {
            var x = y % 2
            while (x * cell < size.width) {
                drawRect(shade, Offset(x * cell, y * cell), Size(cell, cell))
                x += 2
            }
            y++
        }
    }
}

@Composable
fun SpriteThumbnail(image: ImageBitmap?, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Checkerboard(Modifier.fillMaxSize(), cell = 12f)
        if (image != null) Image(image, contentDescription = null, modifier = Modifier.fillMaxSize().padding(4.dp))
    }
}

/** Bottom sheet listing drawings of one kind, plus a button to draw a new one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpritePickerSheet(
    title: String,
    sprites: List<SpriteAsset>,
    vm: GameViewModel,
    drawLabel: String,
    onDismiss: () -> Unit,
    onPick: (SpriteAsset) -> Unit,
    onDraw: () -> Unit,
    onDelete: ((SpriteAsset) -> Unit)? = null,
) {
    var confirmDelete by remember { mutableStateOf<SpriteAsset?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(96.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().height(360.dp),
            ) {
                item {
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .clickable(onClick = onDraw)
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Brush, null, Modifier.size(36.dp))
                        }
                        Text(drawLabel, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2)
                    }
                }
                items(sprites.sortedByDescending { it.createdAtMillis }, key = { it.id }) { sprite ->
                    Column(
                        Modifier.clip(RoundedCornerShape(10.dp)).clickable { onPick(sprite) }.padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box {
                            SpriteThumbnail(vm.sprites.images[sprite.id], Modifier.fillMaxWidth().aspectRatio(1f))
                            if (onDelete != null) {
                                Icon(
                                    Icons.Default.Close, "Delete",
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(2.dp)
                                        .size(22.dp)
                                        .clip(RoundedCornerShape(11.dp))
                                        .background(Color.White.copy(alpha = 0.85f))
                                        .clickable { confirmDelete = sprite }
                                        .padding(3.dp),
                                )
                            }
                        }
                        Text(
                            sprite.name.ifBlank { "Untitled" },
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    confirmDelete?.let { sprite ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete drawing?") },
            text = { Text("\"${sprite.name.ifBlank { "Untitled" }}\" will be removed everywhere it's used.") },
            confirmButton = {
                TextButton(onClick = { onDelete?.invoke(sprite); confirmDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

/** Tag chips grouped by category. Tags above [playerLevel] are shown locked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagPicker(selected: List<String>, playerLevel: Int, onToggle: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val grouped = TagLibrary.byCategory()
        for (category in TagCategory.entries) {
            val tags = grouped[category].orEmpty()
            Text(category.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (tag in tags) {
                    val locked = tag.unlockLevel > playerLevel
                    FilterChip(
                        selected = tag.id in selected,
                        onClick = { onToggle(tag.id) },
                        enabled = !locked,
                        label = { Text(if (locked) "${tag.label} · Lv ${tag.unlockLevel}" else tag.label) },
                        leadingIcon = if (locked) ({ Icon(Icons.Default.Lock, null, Modifier.size(14.dp)) }) else null,
                    )
                }
            }
        }
    }
}

/** Shows a drawing slot: thumbnail plus draw/pick/clear buttons. */
@Composable
fun DrawingSlot(
    label: String,
    image: ImageBitmap?,
    hasDrawing: Boolean,
    onDraw: () -> Unit,
    onPick: (() -> Unit)? = null,
    onClear: (() -> Unit)? = null,
    thumbnailAspect: Float = 1f,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SpriteThumbnail(image, Modifier.height(72.dp).aspectRatio(thumbnailAspect))
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Row {
                TextButton(onClick = onDraw) {
                    Icon(Icons.Default.Brush, null, Modifier.size(18.dp)); Spacer(Modifier.size(4.dp))
                    Text(if (hasDrawing) "Redraw" else "Draw")
                }
                if (onPick != null) TextButton(onClick = onPick) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.size(4.dp)); Text("Pick")
                }
                if (hasDrawing && onClear != null) TextButton(onClick = onClear) { Text("Clear") }
            }
        }
    }
}
