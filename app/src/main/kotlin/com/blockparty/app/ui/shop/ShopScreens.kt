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
import com.blockparty.core.model.SupplyTier
import com.blockparty.core.sim.Economy
import com.blockparty.core.sim.Lots
import com.blockparty.core.model.Lot
import com.blockparty.core.model.LotState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
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
    val lot = vm.currentLot ?: return
    val business = lot.business ?: run { vm.back(); return }
    var showFacadePicker by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    var expandTarget by remember { mutableStateOf<Lot?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
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
                    onPick = { showFacadePicker = true },
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
            HorizontalDivider()

            Section("Staff") {
                StaffSection(vm, business.employees)
            }
            HorizontalDivider()

            Section("Shop space") {
                Text(
                    "${lot.rect.w}×${lot.rect.h} tiles · rent ${Economy.rentPerDay(lot, world.street)}¢ a day",
                    style = MaterialTheme.typography.bodyMedium,
                )
                for (side in listOf(-1, 1)) {
                    val other = Lots.neighbour(world, lot, side) ?: continue
                    val cost = Lots.mergeCost(world, lot, other) ?: continue
                    val where = if (side < 0) "west" else "east"
                    OutlinedButton(onClick = { expandTarget = other }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (other.state == LotState.OWNED) "Merge with your shop to the $where"
                            else "Expand into the lot to the $where ($cost¢)"
                        )
                    }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedButton(
                    onClick = { confirmClose = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Close this business") }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("Close ${business.name.ifBlank { "this shop" }}?") },
            text = { Text("Its furniture goes to your storage and the lot becomes vacant. Products, stock and reviews are lost.") },
            confirmButton = { TextButton(onClick = { confirmClose = false; vm.closeCurrentBusiness() }) { Text("Close business") } },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Keep it open") } },
        )
    }

    expandTarget?.let { other ->
        val otherBusiness = other.business
        AlertDialog(
            onDismissRequest = { expandTarget = null },
            title = { Text(if (otherBusiness != null) "Merge shops?" else "Expand the shop?") },
            text = {
                Text(
                    if (otherBusiness != null) "${otherBusiness.name.ifBlank { "Your other shop" }} joins this one. Its furniture stays where it is and its products and services move here."
                    else "Buy the lot next door for ${Lots.mergeCost(world, lot, other)}¢ and make this shop bigger. Rent grows with the size."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    expandTarget = null
                    message = vm.expandInto(other.id)
                }) { Text(if (otherBusiness != null) "Merge" else "Expand") }
            },
            dismissButton = { TextButton(onClick = { expandTarget = null }) { Text("Cancel") } },
        )
    }

    if (showFacadePicker) {
        SpritePickerSheet(
            title = "Storefronts",
            sprites = world.sprites.filter { it.kind == SpriteKind.FACADE },
            vm = vm,
            drawLabel = "Draw new storefront",
            onDismiss = { showFacadePicker = false },
            onPick = { vm.setFacade(it.id); showFacadePicker = false },
            onDraw = {
                showFacadePicker = false
                vm.navigate(Screen.Draw(SpriteKind.FACADE, DrawPurpose.FACADE))
            },
        )
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
            Column(horizontalAlignment = Alignment.End) {
                Text("${offering.priceCoins} ¢", style = MaterialTheme.typography.titleMedium)
                if (offering.kind == OfferingKind.PRODUCT) {
                    val lot = vm.currentLot
                    val world = vm.world
                    if (lot != null && world != null) {
                        val cap = Economy.capacity(offering, lot, world::sprite)
                        val (units, cost) = Economy.restockNeed(offering, lot, world::sprite)
                        Text(
                            if (offering.stock == 0) "Sold out" else "${offering.stock}/$cap in stock",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (offering.stock == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (units > 0) TextButton(onClick = { vm.restock(offering.id) }) { Text("Restock ($cost¢)") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfferingEditorScreen(vm: GameViewModel, world: WorldState) {
    val draft = vm.offeringDraft ?: return
    val isNew = vm.currentLot?.business?.offerings?.none { it.id == draft.id } ?: true
    val mode = vm.currentLot?.business?.mode ?: BusinessMode.PRODUCTS
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
                    supportingText = {
                        Text("Any price you like. Thrifty customers spend about 5–20, regulars 15–60, big spenders 50–200.")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            HorizontalDivider()
            Section("Supplies") {
                Text(
                    if (draft.kind == OfferingKind.SERVICE) "Supplies used each time you perform it. Better supplies cost more and earn better reviews."
                    else "What you pay the supplier per unit. Better supplies cost more and earn better reviews.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (tier in SupplyTier.entries) {
                        val cost = if (draft.kind == OfferingKind.SERVICE) tier.serviceCost else tier.unitCost
                        FilterChip(
                            selected = draft.supplyTier == tier,
                            onClick = { vm.updateDraft { it.copy(supplyTier = tier) } },
                            label = { Text("${tier.label} · $cost¢") },
                        )
                    }
                }
                val unitCost = if (draft.kind == OfferingKind.SERVICE) draft.supplyTier.serviceCost else draft.supplyTier.unitCost
                Text(
                    "Profit per sale: ${draft.priceCoins - unitCost}¢",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (draft.priceCoins - unitCost < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
                if (draft.kind == OfferingKind.PRODUCT && isNew) {
                    Text(
                        "New products start with no stock. Restock them from the shop editor after saving.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
