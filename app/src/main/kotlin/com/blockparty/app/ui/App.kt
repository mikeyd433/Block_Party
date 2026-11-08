package com.blockparty.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blockparty.app.GameViewModel
import com.blockparty.app.Screen
import com.blockparty.app.ui.draw.DrawingScreen
import com.blockparty.app.ui.shop.DisplayEditorScreen
import com.blockparty.app.ui.shop.OfferingEditorScreen
import com.blockparty.app.ui.shop.ShopEditorScreen
import com.blockparty.app.ui.street.StreetScreen

@Composable
fun BlockPartyApp(vm: GameViewModel = viewModel()) {
    val world = vm.world
    if (world == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    BackHandler(enabled = vm.backStack.size > 1) {
        if (vm.screen == Screen.OfferingEditor) vm.discardDraft() else vm.back()
    }
    if (vm.notices.isNotEmpty()) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = vm::dismissNotices,
            title = { androidx.compose.material3.Text("News from the street") },
            text = { androidx.compose.material3.Text(vm.notices.joinToString("\n\n")) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = vm::dismissNotices) { androidx.compose.material3.Text("OK") } },
        )
    }
    when (val screen = vm.screen) {
        Screen.Street -> StreetScreen(vm, world)
        Screen.ShopEditor -> ShopEditorScreen(vm, world)
        Screen.OfferingEditor -> OfferingEditorScreen(vm, world)
        is Screen.Draw -> DrawingScreen(vm, world, screen.kind, screen.purpose)
        is Screen.DisplayEditor -> DisplayEditorScreen(vm, world, screen.placementId)
        Screen.JobBoard -> com.blockparty.app.ui.shop.JobBoardScreen(vm, world)
    }
}
