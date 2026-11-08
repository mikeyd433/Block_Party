package com.blockparty.app.ui.shop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.blockparty.app.DrawPurpose
import com.blockparty.app.GameViewModel
import com.blockparty.app.Screen
import com.blockparty.app.ui.common.DrawingSlot
import com.blockparty.app.ui.common.SpritePickerSheet
import com.blockparty.app.ui.common.SpriteThumbnail
import com.blockparty.app.ui.common.TagPicker
import com.blockparty.core.geometry.IsoProjection
import com.blockparty.core.geometry.Storefront
import com.blockparty.core.model.Business
import com.blockparty.core.model.BusinessMode
import com.blockparty.core.model.Offering
import com.blockparty.core.model.OfferingKind
import com.blockparty.core.model.SpriteKind
import com.blockparty.core.model.TagLibrary
import com.blockparty.core.model.WorldState

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShopEditorScreen(vm: GameViewModel, world: WorldState) {
    val lot = vm.homeLot ?: return
    val business = lot.business ?: Business(name = "My Shop")
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Your shop") },
                navigationIcon = { IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Section("Identity") {
                OutlinedTextField(
                    value = business.name, onValueChange = vm::setBusinessName,
                    label = { Text("Business name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = business.description, onValueChange = vm::setBusinessDescription,
                    label = { Text("Description") }, minLines = 2, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("A haunted bakery that only opens after dark…") },
                )
                Text("This business offers", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    BusinessMode.entries.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = business.mode == mode,
                            onClick = { vm.setBusinessMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(i, BusinessMode.entries.size),
                        ) { Text(mode.label) }
                    }
                }
            }
            HorizontalDivider()

            Section("What you sell") {
                if (business.offerings.isEmpty()) {
                    Text(
                        "Nothing yet. Draw your first ${if (business.mode == BusinessMode.SERVICES) "service" else "product"}!",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for (offering in business.offerings) OfferingRow(vm, offering) { vm.editOffering(offering) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (business.mode != BusinessMode.SERVICES) {
                        Button(onClick = { vm.startOffering(OfferingKind.PRODUCT) }) {
                            Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Product")
                        }
                    }
                    if (business.mode != BusinessMode.PRODUCTS) {
                        Button(onClick = { vm.startOffering(OfferingKind.SERVICE) }) {
                            Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Service")
                        }
                    }
                }
            }
            HorizontalDivider()

            Section("Shop tags") {
                Text(
                    "Tags decide which customers will love your shop.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TagPicker(business.tagIds, world.playerLevel, vm::toggleBusinessTag)
            }
            HorizontalDivider()

            Section("Look") {
                val facadeAspect = Storefront(IsoProjection()).facadeAspect(lot.rect.w)
                DrawingSlot(
                    label = "Storefront",
                    image = business.facadeSpriteId?.let { vm.sprites.images[it] },
                    hasDrawing = business.facadeSpriteId != null,
                    onDraw = { vm.navigate(Screen.Draw(SpriteKind.FACADE, DrawPurpose.FACADE)) },
                    onClear = vm::clearFacade,
                    thumbnailAspect = facadeAspect,
                )
                DrawingSlot(
                    label = "Shopkeeper (you!)",
                    image = business.shopkeeperSpriteId?.let { vm.sprites.images[it] },
                    hasDrawing = business.shopkeeperSpriteId != null,
                    onDraw = { vm.navigate(Screen.Draw(SpriteKind.CHARACTER, DrawPurpose.SHOPKEEPER)) },
                    onClear = vm::clearShopkeeper,
                )
            }
            HorizontalDivider()

            Section("Artist name") {
                OutlinedTextField(
                    value = world.playerName, onValueChange = vm::setPlayerName,
                    label = { Text("Shown on drawings you choose to sign") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun OfferingRow(vm: GameViewModel, offering: Offering, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SpriteThumbnail(offering.spriteId?.let { vm.sprites.images[it] }, Modifier.size(52.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(offering.name.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleSmall)
                val tags = offering.tagIds.mapNotNull { TagLibrary.get(it)?.label }
                Text(
                    (if (offering.kind == OfferingKind.SERVICE) "Service" else "Product") +
                        if (tags.isNotEmpty()) " · " + tags.joinToString(", ") else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text("${offering.priceCoins} ¢", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfferingEditorScreen(vm: GameViewModel, world: WorldState) {
    val draft = vm.offeringDraft ?: return
    val isNew = vm.homeLot?.business?.offerings?.none { it.id == draft.id } ?: true
    val mode = vm.homeLot?.business?.mode ?: BusinessMode.PRODUCTS
    var priceText by remember(draft.id) { mutableStateOf(draft.priceCoins.toString()) }
    var showPicker by remember { mutableStateOf(false) }
    val noun = if (draft.kind == OfferingKind.SERVICE) "service" else "product"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New $noun" else "Edit $noun") },
                navigationIcon = { IconButton(onClick = vm::discardDraft) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (!isNew) IconButton(onClick = vm::deleteDraft) { Icon(Icons.Default.Delete, "Delete") }
                    TextButton(onClick = vm::saveDraft, enabled = draft.name.isNotBlank()) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Section("Details") {
                if (mode == BusinessMode.BOTH) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        OfferingKind.entries.forEachIndexed { i, kind ->
                            SegmentedButton(
                                selected = draft.kind == kind,
                                onClick = { vm.updateDraft { it.copy(kind = kind) } },
                                shape = SegmentedButtonDefaults.itemShape(i, OfferingKind.entries.size),
                            ) { Text(if (kind == OfferingKind.PRODUCT) "Product" else "Service") }
                        }
                    }
                }
                OutlinedTextField(
                    value = draft.name, onValueChange = { v -> vm.updateDraft { it.copy(name = v) } },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { v ->
                        val digits = v.filter(Char::isDigit).take(9)
                        priceText = digits
                        vm.updateDraft { it.copy(priceCoins = digits.toLongOrNull() ?: 0L) }
                    },
                    label = { Text("Price (coins)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text("Any price you like. Customers will react to it.") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            HorizontalDivider()
            Section("Picture") {
                DrawingSlot(
                    label = "How it looks",
                    image = draft.spriteId?.let { vm.sprites.images[it] },
                    hasDrawing = draft.spriteId != null,
                    onDraw = { vm.navigate(Screen.Draw(SpriteKind.PRODUCT, DrawPurpose.OFFERING)) },
                    onPick = { showPicker = true },
                    onClear = { vm.updateDraft { it.copy(spriteId = null) } },
                )
            }
            HorizontalDivider()
            Section("Tags") {
                TagPicker(draft.tagIds, world.playerLevel, onToggle = { id ->
                    vm.updateDraft { it.copy(tagIds = if (id in it.tagIds) it.tagIds - id else it.tagIds + id) }
                })
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = vm::saveDraft, enabled = draft.name.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(if (draft.name.isBlank()) "Give it a name to save" else "Save $noun")
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showPicker) {
        SpritePickerSheet(
            title = "Product & service drawings",
            sprites = world.sprites.filter { it.kind == SpriteKind.PRODUCT },
            vm = vm,
            drawLabel = "Draw new",
            onDismiss = { showPicker = false },
            onPick = { s -> vm.updateDraft { it.copy(spriteId = s.id) }; showPicker = false },
            onDraw = {
                showPicker = false
                vm.navigate(Screen.Draw(SpriteKind.PRODUCT, DrawPurpose.OFFERING))
            },
        )
    }
}
