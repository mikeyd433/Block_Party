package com.blockparty.core.net

import com.blockparty.core.model.GridRect
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** A shared street the player belongs to (`block_party.streets`). */
@Serializable
data class RemoteStreet(
    val id: String,
    val name: String,
    @SerialName("invite_code") val inviteCode: String,
    @SerialName("admin_id") val adminId: String,
    @SerialName("time_zone") val timeZone: String = "UTC",
    @SerialName("created_at") val createdAt: String = "",
)

/** Someone on a shared street (`block_party.members`). */
@Serializable
data class RemoteMember(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("joined_at") val joinedAt: String = "",
)

/** A lot on a shared street as the server has it. [business] and [furniture] are the game's JSON. */
@Serializable
data class RemoteLot(
    @SerialName("lot_id") val lotId: String,
    val rect: GridRect,
    val state: String,
    @SerialName("owner_id") val ownerId: String? = null,
    @SerialName("owner_name") val ownerName: String = "",
    val business: JsonElement? = null,
    val furniture: JsonElement? = null,
    /** Changes whenever the lot does; compared for equality only, so device clocks don't matter. */
    @SerialName("updated_at") val updatedAt: String = "",
)

/** Just enough of a lot to tell whether it changed since the last pull. */
@Serializable
data class LotVersion(
    @SerialName("lot_id") val lotId: String,
    @SerialName("updated_at") val updatedAt: String = "",
)

/** A drawing shared with a street: [meta] is the game's SpriteAsset JSON; the PNG is in storage. */
@Serializable
data class RemoteSprite(
    val id: String,
    @SerialName("owner_id") val ownerId: String,
    val meta: JsonElement,
)

/**
 * Shared streets: list the player's streets, create one, join by invite code, see who's on it,
 * make a new invite code (admin), and leave. Everything lives in the `block_party` schema and
 * goes through PostgREST; row-level security limits each player to streets they're a member of.
 */
class StreetsApi(
    private val config: SupabaseConfig,
    private val transport: HttpTransport,
    private val auth: AuthClient,
    /** The current time as an ISO-8601 timestamp (only needs to differ between updates). */
    private val nowIso: () -> String = { defaultNowIso() },
) {
    suspend fun myStreets(): List<RemoteStreet> {
        val body = get("streets?select=id,name,invite_code,admin_id,time_zone,created_at&order=created_at.asc")
        return BackendJson.decodeFromString(ListSerializer(RemoteStreet.serializer()), body)
    }

    suspend fun members(streetId: String): List<RemoteMember> {
        val body = get("members?select=user_id,display_name,joined_at&street_id=eq.$streetId&order=joined_at.asc")
        return BackendJson.decodeFromString(ListSerializer(RemoteMember.serializer()), body)
    }

    /** Creates a street with the player as admin, and returns its id. */
    suspend fun createStreet(name: String, timeZone: String, displayName: String): String = rpcString(
        "create_street",
        buildJsonObject {
            put("p_name", name.trim())
            put("p_time_zone", timeZone)
            put("p_display_name", displayName.trim())
        },
    )

    /** Joins the street with this invite code (any case, spaces ignored) and returns its id. */
    suspend fun joinStreet(code: String, displayName: String): String = rpcString(
        "join_street",
        buildJsonObject {
            put("p_code", normalizeInviteCode(code))
            put("p_display_name", displayName.trim())
        },
    )

    /** Admin only: replaces the street's invite code (the old one stops working). */
    suspend fun newInvite(streetId: String): String =
        rpcString("new_invite", buildJsonObject { put("p_street", streetId) })

    /** Leaves the street. Shops the player owned there become vacant lots. */
    suspend fun leave(streetId: String) {
        val me = auth.validSession().userId
        call("DELETE", "members?street_id=eq.$streetId&user_id=eq.$me", null, mapOf("Prefer" to "return=minimal"))
    }

    // --- Playing on a street -----------------------------------------------------------------------

    /** The street's lots, or only [ids] when given. */
    suspend fun lots(streetId: String, ids: Collection<String>? = null): List<RemoteLot> {
        // Lot ids are letters, digits and dashes, so they need no quoting in the filter.
        val only = ids?.joinToString(",", prefix = "&lot_id=in.(", postfix = ")").orEmpty()
        val body = get("lots?select=lot_id,rect,state,owner_id,owner_name,business,furniture,updated_at&street_id=eq.$streetId$only&order=lot_id.asc")
        return BackendJson.decodeFromString(ListSerializer(RemoteLot.serializer()), body)
    }

    /** Every lot's id and change stamp: a cheap check for what changed since the last pull. */
    suspend fun lotVersions(streetId: String): List<LotVersion> {
        val body = get("lots?select=lot_id,updated_at&street_id=eq.$streetId&order=lot_id.asc")
        return BackendJson.decodeFromString(ListSerializer(LotVersion.serializer()), body)
    }

    suspend fun sprites(streetId: String): List<RemoteSprite> {
        val body = get("sprites?select=id,owner_id,meta&street_id=eq.$streetId")
        return BackendJson.decodeFromString(ListSerializer(RemoteSprite.serializer()), body)
    }

    /** Claims a vacant lot for the player. False if someone else got it first. */
    suspend fun claimLot(streetId: String, lotId: String, ownerName: String, business: JsonElement?): Boolean = rpcBoolean(
        "claim_lot",
        buildJsonObject {
            put("p_street", streetId)
            put("p_lot", lotId)
            put("p_owner_name", ownerName)
            put("p_business", business ?: JsonNull)
        },
    )

    /** Closes one of the player's shops: the lot becomes vacant. */
    suspend fun closeLot(streetId: String, lotId: String): Boolean =
        rpcBoolean("close_lot", buildJsonObject { put("p_street", streetId); put("p_lot", lotId) })

    /** Folds [otherId] (vacant, or the player's) into the player's shop [keepId], which becomes [rect]. */
    suspend fun mergeLots(
        streetId: String, keepId: String, otherId: String, rect: GridRect, business: JsonElement?, furniture: JsonElement,
    ): Boolean = rpcBoolean(
        "merge_lots",
        buildJsonObject {
            put("p_street", streetId)
            put("p_keep", keepId)
            put("p_other", otherId)
            put("p_rect", BackendJson.encodeToJsonElement(GridRect.serializer(), rect))
            put("p_business", business ?: JsonNull)
            put("p_furniture", furniture)
        },
    )

    /** Saves the contents of one of the player's shops. */
    suspend fun updateLot(streetId: String, lotId: String, ownerName: String, business: JsonElement?, furniture: JsonElement) {
        val body = buildJsonObject {
            // Marks the lot as changed so other players' cheap version check picks it up.
            put("updated_at", nowIso())
            put("owner_name", ownerName)
            put("business", business ?: JsonNull)
            put("furniture", furniture)
        }
        call("PATCH", "lots?street_id=eq.$streetId&lot_id=eq.$lotId", body.toString(), mapOf("Prefer" to "return=minimal"))
    }

    /** Adds or updates one of the player's drawings on the street. */
    suspend fun putSprite(streetId: String, spriteId: String, meta: JsonElement) {
        val me = auth.validSession().userId
        val body = buildJsonObject {
            put("street_id", streetId)
            put("id", spriteId)
            put("owner_id", me)
            put("meta", meta)
        }
        call(
            "POST", "sprites?on_conflict=street_id,id", body.toString(),
            mapOf("Prefer" to "resolution=merge-duplicates,return=minimal"),
        )
    }

    suspend fun deleteSprite(streetId: String, spriteId: String) {
        call("DELETE", "sprites?street_id=eq.$streetId&id=eq.$spriteId", null, mapOf("Prefer" to "return=minimal"))
    }

    /** The player's private state on the street (coins, books, storage), or null if never saved. */
    suspend fun playerState(streetId: String): JsonElement? {
        val me = auth.validSession().userId
        val body = get("player_state?select=state&street_id=eq.$streetId&user_id=eq.$me")
        val rows = BackendJson.decodeFromString(ListSerializer(JsonObject.serializer()), body)
        return rows.firstOrNull()?.get("state")?.takeIf { it is JsonObject && it.isNotEmpty() }
    }

    suspend fun putPlayerState(streetId: String, state: JsonElement) {
        val me = auth.validSession().userId
        val body = buildJsonObject { put("state", state) }
        call("PATCH", "player_state?street_id=eq.$streetId&user_id=eq.$me", body.toString(), mapOf("Prefer" to "return=minimal"))
    }

    /** Uploads a drawing's PNG. Drawings never change once saved, so an existing file counts as done. */
    suspend fun uploadDrawing(streetId: String, spriteId: String, png: ByteArray) {
        val response = send("POST", storageUrl("object", streetId, spriteId), null, png, mapOf("Content-Type" to "image/png"), schema = false)
        if (response.isSuccess) return
        val duplicate = response.code == 409 || (response.code == 400 && response.body.contains("exists", ignoreCase = true)) ||
            response.body.contains("Duplicate", ignoreCase = true)
        if (!duplicate) throw errorFrom(response)
    }

    /** Downloads a drawing's PNG, or null if it isn't there (yet). */
    suspend fun downloadDrawing(streetId: String, spriteId: String): ByteArray? {
        val response = send("GET", storageUrl("object/authenticated", streetId, spriteId), null, null, emptyMap(), schema = false)
        if (response.isSuccess) return response.bytes
        if (response.code == 404 || response.code == 400) return null
        throw errorFrom(response)
    }

    private fun storageUrl(kind: String, streetId: String, spriteId: String) =
        "${config.baseUrl}/storage/v1/$kind/$BUCKET/$streetId/$spriteId.png"

    /** The player's name from their shared profile, used as their name on streets they create or join. */
    suspend fun myDisplayName(): String? {
        val me = auth.validSession().userId
        val body = call("GET", "profiles?select=display_name&id=eq.$me", null, mapOf("Accept-Profile" to "public"))
        val rows = BackendJson.decodeFromString(ListSerializer(JsonObject.serializer()), body)
        return rows.firstOrNull()?.string("display_name")?.takeIf { it.isNotBlank() }
    }

    private suspend fun get(path: String): String = call("GET", path, null, emptyMap())

    // For the social calls (Social.kt).
    internal suspend fun getPath(path: String): String = get(path)
    internal suspend fun postPath(path: String, body: String) = call("POST", path, body, mapOf("Prefer" to "return=minimal"))
    internal suspend fun deletePath(path: String) {
        call("DELETE", path, null, mapOf("Prefer" to "return=minimal"))
    }
    internal suspend fun rpcRaw(function: String, body: String): String = call("POST", "rpc/$function", body, emptyMap())
    internal suspend fun myUserId(): String = auth.validSession().userId

    private suspend fun rpcString(function: String, args: JsonObject): String {
        val body = call("POST", "rpc/$function", args.toString(), emptyMap())
        val value = runCatching { (BackendJson.parseToJsonElement(body) as? JsonPrimitive)?.contentOrNull }.getOrNull()
        return value ?: throw BackendException("The server sent an unexpected reply.")
    }

    private suspend fun rpcBoolean(function: String, args: JsonObject): Boolean = rpcString(function, args) == "true"

    /** A PostgREST request; returns the body or throws a friendly [BackendException]. */
    private suspend fun call(method: String, path: String, body: String?, extra: Map<String, String>): String {
        val response = send(method, "${config.baseUrl}/rest/v1/$path", body, null, extra, schema = true)
        if (!response.isSuccess) throw errorFrom(response)
        return response.body
    }

    /**
     * Sends a request as the signed-in player. If the server rejects the access token (it expired
     * early, or the clock is off), refreshes once and retries.
     */
    private suspend fun send(
        method: String, url: String, body: String?, bytes: ByteArray?, extra: Map<String, String>, schema: Boolean,
    ): HttpResponse {
        var session = auth.validSession()
        var response: HttpResponse? = null
        for (attempt in 0..1) {
            val headers = auth.headers(session.accessToken) + (if (schema) schemaHeaders(method) else emptyMap()) + extra
            response = transport.send(HttpRequest(method, url, headers, body, bytes))
            if (response.code != 401 || attempt == 1) break
            session = auth.forceRefresh(session)
        }
        return response!!
    }

    /** PostgREST picks the schema from these headers; reads use Accept-Profile, writes Content-Profile. */
    private fun schemaHeaders(method: String): Map<String, String> =
        if (method == "GET" || method == "HEAD") mapOf("Accept-Profile" to SCHEMA) else mapOf("Content-Profile" to SCHEMA)

    companion object {
        const val SCHEMA = "block_party"
        const val BUCKET = "block-party-drawings"

        /** Invite codes are 6 characters, shown in capitals; people may type them in any case or with spaces. */
        fun normalizeInviteCode(code: String): String = code.filter { it.isLetterOrDigit() }.uppercase()

        /**
         * Finds an invite code in copied text: the share message ("...enter AB12CD.") or a bare
         * code. Null if there isn't one, so ordinary clipboard text isn't mistaken for a code.
         */
        fun findInviteCode(text: String): String? {
            val trimmed = text.trim()
            if (Regex("[A-Za-z0-9]{6}").matches(trimmed)) return trimmed.uppercase()
            return Regex("(?:enter|code)\\s+([A-Za-z0-9]{6})\\b", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.uppercase()
        }
    }
}

@OptIn(kotlin.time.ExperimentalTime::class)
private fun defaultNowIso(): String = kotlin.time.Clock.System.now().toString()
