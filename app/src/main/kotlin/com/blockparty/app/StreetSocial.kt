package com.blockparty.app

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.blockparty.core.net.BackendException
import com.blockparty.core.net.ChatMessage
import com.blockparty.core.net.DoorNote
import com.blockparty.core.net.LoanBalance
import com.blockparty.core.net.Loans
import com.blockparty.core.net.RemoteMember
import com.blockparty.core.net.StreetsApi
import com.blockparty.core.net.Transfer
import com.blockparty.core.net.chat
import com.blockparty.core.net.collect
import com.blockparty.core.net.deleteChat
import com.blockparty.core.net.deleteDoorNote
import com.blockparty.core.net.doorNotes
import com.blockparty.core.net.leaveDoorNote
import com.blockparty.core.net.loanHistory
import com.blockparty.core.net.sendChat
import com.blockparty.core.net.sendMoney
import com.blockparty.core.net.uncollectedMoney

/**
 * The social side of one open shared street: chat, door notes, and money between players.
 * Refreshed by the street's sync loop (and so by live updates). Failures here never mark the
 * street offline: the game keeps syncing even if, say, chat isn't set up on the server yet.
 */
class StreetSocial(
    private val api: StreetsApi,
    val streetId: String,
    val myUserId: String,
    private val prefs: SharedPreferences,
) {
    var messages by mutableStateOf<List<ChatMessage>>(emptyList())
        private set
    var notes by mutableStateOf<List<DoorNote>>(emptyList())
        private set
    var members by mutableStateOf<List<RemoteMember>>(emptyList())
        private set
    var loans by mutableStateOf<List<LoanBalance>?>(null)
        private set
    /** Why the social features aren't working, if they aren't. */
    var problem by mutableStateOf<String?>(null)
        private set

    /** Set by [debugShow]: screenshots use canned data and never call the server. */
    private var offline = false

    private val readKey = "chat_read_$streetId"
    /** created_at of the newest message the player has seen. */
    var lastRead by mutableStateOf(prefs.getString(readKey, "").orEmpty())
        private set

    val unread: Int get() = messages.count { it.authorId != myUserId && it.createdAt > lastRead }

    fun markRead() {
        val newest = messages.lastOrNull()?.createdAt ?: return
        if (newest > lastRead) {
            lastRead = newest
            prefs.edit().putString(readKey, newest).apply()
        }
    }

    fun notesFor(lotId: String): List<DoorNote> = notes.filter { it.lotId == lotId }

    /**
     * Fetches new chat messages and notes, and collects money sent to the player. [onMoney] gets
     * each collected transfer (to add to their coins).
     */
    suspend fun refresh(onMoney: (Transfer) -> Unit) {
        if (offline) return
        try {
            val newer = api.chat(streetId, after = messages.lastOrNull()?.createdAt)
            if (newer.isNotEmpty()) {
                val known = messages.map { it.id }.toSet()
                messages = (messages + newer.filter { it.id !in known }).takeLast(MAX_MESSAGES)
            }
            notes = api.doorNotes(streetId)
            for (t in api.uncollectedMoney(streetId)) {
                val amount = api.collect(t.id) ?: continue // collected already (another device)
                onMoney(t.copy(amount = amount))
            }
            problem = null
        } catch (e: BackendException) {
            problem = e.message
        }
    }

    suspend fun send(authorName: String, text: String) {
        api.sendChat(streetId, authorName, text)
        refreshChat()
    }

    suspend fun deleteMessage(id: String) {
        api.deleteChat(streetId, id)
        messages = messages.filterNot { it.id == id }
    }

    suspend fun leaveNote(lotId: String, authorName: String, text: String) {
        api.leaveDoorNote(streetId, lotId, authorName, text)
        notes = api.doorNotes(streetId)
    }

    suspend fun deleteNote(id: String) {
        api.deleteDoorNote(streetId, id)
        notes = notes.filterNot { it.id == id }
    }

    /** Loads who's on the street and the player's loans, for the money screen. */
    suspend fun loadMoney() {
        if (offline) return
        members = api.members(streetId)
        loans = Loans.balances(api.loanHistory(streetId), myUserId)
    }

    /** Sends money; the caller takes the coins off only after this succeeds. */
    suspend fun sendMoney(toId: String, fromName: String, kind: String, amount: Long, note: String, loanId: String?) {
        api.sendMoney(streetId, toId, fromName, kind, amount, note, loanId)
        runCatching { loans = Loans.balances(api.loanHistory(streetId), myUserId) }
    }

    private suspend fun refreshChat() {
        val newer = api.chat(streetId, after = messages.lastOrNull()?.createdAt)
        val known = messages.map { it.id }.toSet()
        messages = (messages + newer.filter { it.id !in known }).takeLast(MAX_MESSAGES)
    }

    /** Test hook: canned data for screenshots. */
    @androidx.annotation.VisibleForTesting
    fun debugShow(messages: List<ChatMessage>, notes: List<DoorNote>, members: List<RemoteMember>, loans: List<LoanBalance>) {
        offline = true
        this.messages = messages
        this.notes = notes
        this.members = members
        this.loans = loans
    }

    private companion object {
        const val MAX_MESSAGES = 300
    }
}
