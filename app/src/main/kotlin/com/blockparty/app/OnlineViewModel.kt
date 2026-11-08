package com.blockparty.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blockparty.app.data.Backend
import com.blockparty.core.net.BackendException
import com.blockparty.core.net.RemoteMember
import com.blockparty.core.net.RemoteStreet
import com.blockparty.core.net.SignUpResult
import com.blockparty.core.net.SignedOutException
import kotlinx.coroutines.launch

/** The signed-in player. [displayName] is their shared profile name (blank until it loads). */
data class Account(val userId: String, val email: String, val displayName: String = "")

/**
 * Accounts and shared streets: sign in / up / out, the player's streets, creating and joining
 * streets, members and invite codes. Playing on a shared street (syncing lots and drawings)
 * comes later; the single-player street stays in [GameViewModel].
 */
class OnlineViewModel(app: Application) : AndroidViewModel(app) {
    private val backend by lazy { Backend.get(app) }

    var account by mutableStateOf<Account?>(null)
        private set

    var streets by mutableStateOf<List<RemoteStreet>>(emptyList())
        private set
    var loadingStreets by mutableStateOf(false)
        private set
    var streetsError by mutableStateOf<String?>(null)
        private set

    /** Members per street id, loaded when a street is opened. */
    var members by mutableStateOf<Map<String, List<RemoteMember>>>(emptyMap())
        private set

    /** A form action (sign-in, create, join...) is running. */
    var busy by mutableStateOf(false)
        private set
    /** Error from the last form action, shown on the form. */
    var formError by mutableStateOf<String?>(null)
        private set

    private var started = false
    /** Set by [debugShow]: screenshots use canned data and never call the server. */
    private var offline = false

    /** Restores a saved session. Called when the streets screen first opens, not at app launch. */
    fun start() {
        if (started) return
        started = true
        val s = backend.auth.session ?: return
        account = Account(s.userId, s.email)
        afterSignIn()
    }

    fun clearFormError() {
        formError = null
    }

    fun signIn(email: String, password: String, onDone: () -> Unit) = action {
        val s = backend.auth.signIn(email, password)
        account = Account(s.userId, s.email)
        afterSignIn()
        onDone()
    }

    /** [onConfirmEmail] runs instead of [onDone] when the project asks new accounts to confirm their email. */
    fun signUp(name: String, email: String, password: String, onDone: () -> Unit, onConfirmEmail: () -> Unit) = action {
        when (val r = backend.auth.signUp(email, password, name)) {
            is SignUpResult.SignedIn -> {
                account = Account(r.session.userId, r.session.email, name.trim())
                afterSignIn()
                onDone()
            }
            SignUpResult.ConfirmEmail -> onConfirmEmail()
        }
    }

    fun signOut() {
        viewModelScope.launch { backend.auth.signOut() }
        signedOut()
    }

    fun refreshStreets() {
        if (account == null || offline) return
        viewModelScope.launch {
            loadingStreets = true
            try {
                streets = backend.streets.myStreets()
                streetsError = null
            } catch (e: SignedOutException) {
                signedOut()
            } catch (e: BackendException) {
                streetsError = e.message
            } finally {
                loadingStreets = false
            }
        }
    }

    fun loadMembers(streetId: String) {
        if (offline) return
        viewModelScope.launch {
            try {
                members = members + (streetId to backend.streets.members(streetId))
            } catch (e: SignedOutException) {
                signedOut()
            } catch (_: BackendException) {
                // The screen keeps showing what it had; the next visit tries again.
            }
        }
    }

    fun createStreet(name: String, onCreated: (String) -> Unit) = action {
        val zone = java.time.ZoneId.systemDefault().id
        val id = backend.streets.createStreet(name, zone, nameOnStreets())
        reloadStreetsQuietly()
        onCreated(id)
    }

    fun joinStreet(code: String, onJoined: (String) -> Unit) = action {
        val id = backend.streets.joinStreet(code, nameOnStreets())
        reloadStreetsQuietly()
        onJoined(id)
    }

    fun newInviteCode(streetId: String) = action {
        val code = backend.streets.newInvite(streetId)
        streets = streets.map { if (it.id == streetId) it.copy(inviteCode = code) else it }
    }

    fun leaveStreet(streetId: String, onLeft: () -> Unit) = action {
        backend.streets.leave(streetId)
        streets = streets.filterNot { it.id == streetId }
        members = members - streetId
        onLeft()
    }

    /** Test hook: show a signed-in state without a server. */
    @androidx.annotation.VisibleForTesting
    fun debugShow(account: Account?, streets: List<RemoteStreet>, members: Map<String, List<RemoteMember>> = emptyMap()) {
        started = true
        offline = true
        this.account = account
        this.streets = streets
        this.members = members
    }

    /** After creating or joining, the action already succeeded, so a failed list reload isn't an error. */
    private suspend fun reloadStreetsQuietly() {
        try {
            streets = backend.streets.myStreets()
        } catch (_: BackendException) {
        }
    }

    private fun afterSignIn() {
        refreshStreets()
        viewModelScope.launch {
            val name = runCatching { backend.streets.myDisplayName() }.getOrNull() ?: return@launch
            account = account?.copy(displayName = name)
        }
    }

    /** The name other players see on streets: the profile name, or the start of the email. */
    private fun nameOnStreets(): String =
        account?.displayName?.ifBlank { null } ?: account?.email?.substringBefore('@').orEmpty()

    private fun signedOut() {
        account = null
        streets = emptyList()
        members = emptyMap()
        streetsError = null
    }

    /** Runs a form action: one at a time, with its error shown on the form. */
    private fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        formError = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: SignedOutException) {
                signedOut()
                formError = e.message
            } catch (e: BackendException) {
                formError = e.message
            } finally {
                busy = false
            }
        }
    }
}
