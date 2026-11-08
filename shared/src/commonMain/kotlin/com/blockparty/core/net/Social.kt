package com.blockparty.core.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A street chat message. */
@Serializable
data class ChatMessage(
    val id: String,
    @SerialName("author_id") val authorId: String? = null,
    @SerialName("author_name") val authorName: String = "",
    val body: String,
    @SerialName("created_at") val createdAt: String,
)

/** A note left at a shop's door. */
@Serializable
data class DoorNote(
    val id: String,
    @SerialName("lot_id") val lotId: String,
    @SerialName("author_id") val authorId: String? = null,
    @SerialName("author_name") val authorName: String = "",
    val body: String,
    @SerialName("created_at") val createdAt: String,
)

/** Money between two players on a street: a gift, a loan, or a repayment of a loan. */
@Serializable
data class Transfer(
    val id: String,
    @SerialName("from_id") val fromId: String? = null,
    @SerialName("to_id") val toId: String? = null,
    @SerialName("from_name") val fromName: String = "",
    val kind: String,
    val amount: Long,
    val note: String = "",
    @SerialName("loan_id") val loanId: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("claimed_at") val claimedAt: String? = null,
) {
    companion object {
        const val GIFT = "gift"
        const val LOAN = "loan"
        const val REPAYMENT = "repayment"
    }
}

/** A loan as seen by one player: who it's with, how much is still owed, and which way. */
data class LoanBalance(
    val loan: Transfer,
    val repaid: Long,
    /** True if the player borrowed it (and owes), false if they lent it. */
    val borrowed: Boolean,
) {
    val outstanding: Long get() = (loan.amount - repaid).coerceAtLeast(0)
}

object Loans {
    /** The player's loans (lent and borrowed) with what's been paid back, unpaid ones first. */
    fun balances(transfers: List<Transfer>, me: String): List<LoanBalance> {
        val repaid = transfers.filter { it.kind == Transfer.REPAYMENT && it.loanId != null }
            .groupBy { it.loanId!! }.mapValues { (_, rs) -> rs.sumOf { it.amount } }
        return transfers.filter { it.kind == Transfer.LOAN && (it.fromId == me || it.toId == me) }
            .map { LoanBalance(it, repaid[it.id] ?: 0, borrowed = it.toId == me) }
            .sortedWith(compareBy<LoanBalance> { it.outstanding == 0L }.thenByDescending { it.loan.createdAt })
    }

    /** What collecting [t] should say to the recipient. */
    fun describeIncoming(t: Transfer): String {
        val who = t.fromName.ifBlank { "Someone" }
        val note = t.note.takeIf { it.isNotBlank() }?.let { " \"$it\"" }.orEmpty()
        return when (t.kind) {
            Transfer.LOAN -> "$who lent you ${t.amount} coins.$note Pay it back from the money screen when you can."
            Transfer.REPAYMENT -> "$who paid back ${t.amount} coins of your loan.$note"
            else -> "$who gave you ${t.amount} coins!$note"
        }
    }
}

// --- Server calls ---------------------------------------------------------------------------------

/** The latest [limit] chat messages, oldest first, or only those after [after] (a created_at). */
suspend fun StreetsApi.chat(streetId: String, after: String? = null, limit: Int = 100): List<ChatMessage> {
    val since = after?.let { "&created_at=gt.${encodeTimestamp(it)}" }.orEmpty()
    val body = getPath("messages?select=id,author_id,author_name,body,created_at&street_id=eq.$streetId$since&order=created_at.desc&limit=$limit")
    return BackendJson.decodeFromString(ListSerializer(ChatMessage.serializer()), body).reversed()
}

suspend fun StreetsApi.sendChat(streetId: String, authorName: String, body: String) {
    val me = myUserId()
    val json = buildJsonObject {
        put("street_id", streetId)
        put("author_id", me)
        put("author_name", authorName.take(40))
        put("body", body.trim().take(500))
    }
    postPath("messages", json.toString())
}

suspend fun StreetsApi.deleteChat(streetId: String, id: String) = deletePath("messages?street_id=eq.$streetId&id=eq.$id")

suspend fun StreetsApi.doorNotes(streetId: String): List<DoorNote> {
    val body = getPath("door_notes?select=id,lot_id,author_id,author_name,body,created_at&street_id=eq.$streetId&order=created_at.desc&limit=500")
    return BackendJson.decodeFromString(ListSerializer(DoorNote.serializer()), body)
}

suspend fun StreetsApi.leaveDoorNote(streetId: String, lotId: String, authorName: String, body: String) {
    val me = myUserId()
    val json = buildJsonObject {
        put("street_id", streetId)
        put("lot_id", lotId)
        put("author_id", me)
        put("author_name", authorName.take(40))
        put("body", body.trim().take(280))
    }
    postPath("door_notes", json.toString())
}

suspend fun StreetsApi.deleteDoorNote(streetId: String, id: String) = deletePath("door_notes?street_id=eq.$streetId&id=eq.$id")

/** Sends money to another player on the street. [loanId] is set for repayments. */
suspend fun StreetsApi.sendMoney(
    streetId: String, toId: String, fromName: String, kind: String, amount: Long, note: String = "", loanId: String? = null,
) {
    val me = myUserId()
    val json = buildJsonObject {
        put("street_id", streetId)
        put("from_id", me)
        put("to_id", toId)
        put("from_name", fromName.take(40))
        put("kind", kind)
        put("amount", amount)
        put("note", note.trim().take(140))
        if (loanId != null) put("loan_id", loanId)
    }
    postPath("transfers", json.toString())
}

/** Money sent to the player that they haven't collected yet. */
suspend fun StreetsApi.uncollectedMoney(streetId: String): List<Transfer> {
    val me = myUserId()
    val body = getPath("transfers?select=*&street_id=eq.$streetId&to_id=eq.$me&claimed_at=is.null&order=created_at.asc")
    return BackendJson.decodeFromString(ListSerializer(Transfer.serializer()), body)
}

/** Collects one transfer. Returns its amount, or null if it was already collected. */
suspend fun StreetsApi.collect(transferId: String): Long? {
    val body = rpcRaw("claim_transfer", buildJsonObject { put("p_id", transferId) }.toString())
    return (runCatching { BackendJson.parseToJsonElement(body) }.getOrNull() as? JsonPrimitive)?.longOrNull
}

/** Loans and repayments the player is part of (for the money screen). */
suspend fun StreetsApi.loanHistory(streetId: String): List<Transfer> {
    val body = getPath("transfers?select=*&street_id=eq.$streetId&kind=in.(loan,repayment)&order=created_at.desc&limit=500")
    return BackendJson.decodeFromString(ListSerializer(Transfer.serializer()), body)
}

/** Timestamps contain '+' (time zone), which must be escaped in a query string. */
internal fun encodeTimestamp(ts: String): String = ts.replace("+", "%2B").replace(":", "%3A")
