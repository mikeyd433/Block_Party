package com.blockparty.app.ui.online

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
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
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blockparty.app.GameViewModel
import com.blockparty.app.OnlineViewModel
import com.blockparty.app.Screen
import com.blockparty.core.model.WorldState
import com.blockparty.core.net.RemoteStreet
import com.blockparty.core.net.StreetsApi

/** The player's streets: the single-player one on this phone, and shared streets with friends. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreetsScreen(vm: GameViewModel, online: OnlineViewModel, world: WorldState) {
    LaunchedEffect(Unit) { online.start() }
    var dialog by remember { mutableStateOf<String?>(null) } // "create" or "join"
    val context = LocalContext.current
    val account = online.account

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Streets") },
                navigationIcon = { IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (account != null) {
                        IconButton(onClick = online::refreshStreets, enabled = !online.loadingStreets) {
                            Icon(Icons.Default.Refresh, "Refresh")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle("On this phone")
            val onLocal = vm.sharedStreetId == null
            Card(onClick = { if (onLocal) vm.back() else vm.openLocalStreet() }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Home, null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (onLocal) world.street.name else "My street", style = MaterialTheme.typography.titleMedium)
                        Text("Your single-player street", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(if (onLocal) "Playing" else "Open", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }

            Spacer(Modifier.height(4.dp))
            SectionTitle("Shared streets")
            if (account == null) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Run shops on one street with your friends", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Sign in to create a street and invite friends with a code, or join theirs. " +
                                "Your single-player street stays on this phone either way.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = { vm.navigate(Screen.SignIn) }) {
                            Icon(Icons.AutoMirrored.Filled.Login, null); Spacer(Modifier.width(8.dp)); Text("Sign in or create account")
                        }
                    }
                }
            } else {
                when {
                    online.streets.isEmpty() && online.loadingStreets ->
                        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                    online.streets.isEmpty() && online.streetsError == null ->
                        Text(
                            "You're not on any shared streets yet. Create one and invite friends, or join a friend's street with their code.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
                online.streetsError?.let { ErrorText(it) }
                for (street in online.streets) {
                    StreetCard(street, isAdmin = street.adminId == account.userId, playing = vm.sharedStreetId == street.id) {
                        vm.navigate(Screen.SharedStreet(street.id))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { online.clearFormError(); dialog = "create" }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Create street", maxLines = 1)
                    }
                    FilledTonalButton(onClick = { online.clearFormError(); dialog = "join" }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Groups, null); Spacer(Modifier.width(6.dp)); Text("Join with code", maxLines = 1)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Signed in as ${account.displayName.ifBlank { account.email }}", style = MaterialTheme.typography.bodyMedium)
                        if (account.displayName.isNotBlank()) {
                            Text(account.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    TextButton(onClick = online::signOut) { Text("Sign out") }
                }
            }
        }
    }

    when (dialog) {
        "create" -> TextEntryDialog(
            title = "Create a street",
            label = "Street name",
            hint = "You'll be its admin and get an invite code to share. It uses this phone's time zone for the day and night.",
            confirm = "Create",
            busy = online.busy,
            error = online.formError,
            capitalization = KeyboardCapitalization.Words,
            maxLength = 60,
            onDismiss = { dialog = null },
            onConfirm = { name -> online.createStreet(name) { id -> dialog = null; vm.navigate(Screen.SharedStreet(id)) } },
        )
        "join" -> TextEntryDialog(
            title = "Join a street",
            label = "Invite code",
            hint = "Ask a friend on the street for its 6-character code.",
            confirm = "Join",
            busy = online.busy,
            error = online.formError,
            capitalization = KeyboardCapitalization.Characters,
            maxLength = 12,
            valid = { StreetsApi.normalizeInviteCode(it).length == 6 },
            // A copied invite message (or code) fills itself in.
            initial = { clipboardText(context)?.let(StreetsApi::findInviteCode).orEmpty() },
            onDismiss = { dialog = null },
            onConfirm = { code -> online.joinStreet(code) { id -> dialog = null; vm.navigate(Screen.SharedStreet(id)) } },
        )
    }
}

@Composable
private fun StreetCard(street: RemoteStreet, isAdmin: Boolean, playing: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Groups, null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(street.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "Code ${street.inviteCode}" + if (isAdmin) " · You're the admin" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (playing) Text("Playing", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Sign in, or create an account (shared with the other apps on the same project). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInScreen(vm: GameViewModel, online: OnlineViewModel) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var confirmSentTo by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(creating) { online.clearFormError() }

    val emailOk = email.trim().let { it.contains('@') && it.substringAfter('@').contains('.') }
    val canSubmit = !online.busy && emailOk && password.length >= MIN_PASSWORD && (!creating || name.isNotBlank())
    fun submit() {
        if (!canSubmit) return
        if (creating) {
            online.signUp(name, email, password, onDone = vm::back, onConfirmEmail = { confirmSentTo = email.trim(); creating = false })
        } else {
            online.signIn(email, password, onDone = vm::back)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (creating) "Create account" else "Sign in") },
                navigationIcon = { IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = !creating, onClick = { creating = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Sign in") }
                SegmentedButton(selected = creating, onClick = { creating = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("New account") }
            }
            confirmSentTo?.let {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Text(
                        "Almost there: we sent a confirmation link to $it. Open it, then come back and sign in.",
                        Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (creating) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(40) },
                    label = { Text("Your name") },
                    supportingText = { Text("Shown to friends on your streets.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = email, onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("Password") },
                supportingText = if (creating) ({ Text("At least $MIN_PASSWORD characters.") }) else null,
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (showPassword) "Hide password" else "Show password")
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
            online.formError?.let { ErrorText(it) }
            Button(onClick = ::submit, enabled = canSubmit, modifier = Modifier.fillMaxWidth()) {
                if (online.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(if (creating) "Create account" else "Sign in")
            }
            Text(
                "One account works across the apps that share this server, so if you already have one there, sign in with it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A shared street: its invite code, who's on it, and leaving. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedStreetScreen(vm: GameViewModel, online: OnlineViewModel, streetId: String) {
    LaunchedEffect(streetId) {
        online.start()
        online.loadMembers(streetId)
        if (online.streets.none { it.id == streetId }) online.refreshStreets()
    }
    val context = LocalContext.current
    val street = online.streets.firstOrNull { it.id == streetId }
    val me = online.account?.userId
    val isAdmin = street != null && street.adminId == me
    var confirm by remember { mutableStateOf<String?>(null) } // "leave" or "newCode"
    var openError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(street?.name ?: "Street", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        if (street == null) {
            Column(Modifier.padding(padding).fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (online.loadingStreets || online.account == null && online.busy) CircularProgressIndicator()
                else Text("This street isn't in your list. You may have left it.", style = MaterialTheme.typography.bodyMedium)
            }
            return@Scaffold
        }
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Invite code", style = MaterialTheme.typography.labelLarge)
                    Text(
                        street.inviteCode, fontSize = 36.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                        letterSpacing = 4.sp,
                    )
                    Text("Friends enter this under Streets → Join with code.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { shareInvite(context, street) }) {
                            Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text("Share")
                        }
                        OutlinedButton(onClick = { copyCode(context, street.inviteCode) }) {
                            Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Copy")
                        }
                    }
                    if (isAdmin) {
                        TextButton(onClick = { online.clearFormError(); confirm = "newCode" }, enabled = !online.busy) { Text("Make a new code") }
                    }
                }
            }

            val playing = vm.sharedStreetId == streetId
            val account = online.account
            Button(
                onClick = {
                    if (playing) vm.back()
                    else if (account != null) {
                        openError = null
                        val name = account.displayName.ifBlank { account.email.substringBefore('@') }
                        vm.openSharedStreet(street, account.userId, name) { error -> openError = error }
                    }
                },
                enabled = !vm.openingStreet && account != null,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (vm.openingStreet) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text("Opening street…")
                } else {
                    Icon(Icons.Default.Storefront, null); Spacer(Modifier.width(8.dp))
                    Text(if (playing) "Back to the street" else "Open street")
                }
            }
            openError?.let { ErrorText(it) }
            if (playing) {
                Text(
                    when {
                        vm.syncProblem != null -> "Not syncing right now (${vm.syncProblem}). Changes are saved and go up when it's back."
                        vm.liveConnected -> "Live: friends' changes show up within a second or two."
                        else -> "Connecting for live updates… (changes still sync every 20 seconds)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (vm.syncProblem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                "Buy a lot and run your shop next to your friends'. Your shops and drawings are saved to the street, " +
                    "and you'll see theirs. Your coins here are separate from your single-player street.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val people = online.members[streetId]
            SectionTitle(if (people == null) "People" else "People (${people.size})")
            if (people == null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
            } else {
                for (m in people) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(m.displayName.ifBlank { "Someone" }, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        if (m.userId == street.adminId) AssistChip(onClick = {}, label = { Text("Admin") })
                        if (m.userId == me) {
                            Spacer(Modifier.width(6.dp))
                            AssistChip(onClick = {}, label = { Text("You") })
                        }
                    }
                }
            }

            online.formError?.takeIf { confirm == null }?.let { ErrorText(it) }
            Spacer(Modifier.height(8.dp))
            if (isAdmin) {
                Text(
                    "As the street's admin you can't leave it yet.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                OutlinedButton(onClick = { online.clearFormError(); confirm = "leave" }, enabled = !online.busy) { Text("Leave street") }
            }
        }
    }

    when (confirm) {
        "leave" -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Leave ${street?.name ?: "this street"}?") },
            text = {
                Column {
                    Text("Any shops you run there become vacant lots. You can rejoin later with an invite code.")
                    online.formError?.let { Spacer(Modifier.height(8.dp)); ErrorText(it) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val userId = me
                        online.leaveStreet(streetId) {
                            confirm = null
                            if (userId != null) vm.leftSharedStreet(userId, streetId)
                            vm.back()
                        }
                    },
                    enabled = !online.busy,
                ) { Text("Leave") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Stay") } },
        )
        "newCode" -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Make a new invite code?") },
            text = { Text("The current code stops working. People already on the street stay.") },
            confirmButton = {
                TextButton(onClick = { online.newInviteCode(streetId); confirm = null }, enabled = !online.busy) { Text("New code") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TextEntryDialog(
    title: String,
    label: String,
    hint: String,
    confirm: String,
    busy: Boolean,
    error: String?,
    capitalization: KeyboardCapitalization,
    maxLength: Int,
    valid: (String) -> Boolean = { it.isNotBlank() },
    initial: () -> String = { "" },
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial()) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(hint, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(maxLength) },
                    label = { Text(label) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = capitalization, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { ErrorText(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = !busy && valid(text)) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text(confirm)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ErrorText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

private fun shareInvite(context: Context, street: RemoteStreet) {
    val text = "Come run a shop on my street \"${street.name}\" in Block Party! " +
        "Open Streets, tap Join with code and enter ${street.inviteCode}."
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Invite friends"))
}

private fun clipboardText(context: Context): String? = runCatching {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}.getOrNull()

private fun copyCode(context: Context, code: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Invite code", code))
    // Android 13+ shows its own confirmation when something is copied.
    if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
}

private const val MIN_PASSWORD = 6
