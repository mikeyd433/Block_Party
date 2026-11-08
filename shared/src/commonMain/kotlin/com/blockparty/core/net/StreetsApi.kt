package com.blockparty.core.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
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

/**
 * Shared streets: list the player's streets, create one, join by invite code, see who's on it,
 * make a new invite code (admin), and leave. Everything lives in the `block_party` schema and
 * goes through PostgREST; row-level security limits each player to streets they're a member of.
 */
class StreetsApi(
    private val config: SupabaseConfig,
    private val transport: HttpTransport,
    private val auth: AuthClient,
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

    /** The player's name from their shared profile, used as their name on streets they create or join. */
    suspend fun myDisplayName(): String? {
        val me = auth.validSession().userId
        val body = call("GET", "profiles?select=display_name&id=eq.$me", null, mapOf("Accept-Profile" to "public"))
        val rows = BackendJson.decodeFromString(ListSerializer(JsonObject.serializer()), body)
        return rows.firstOrNull()?.string("display_name")?.takeIf { it.isNotBlank() }
    }

    private suspend fun get(path: String): String = call("GET", path, null, emptyMap())

    private suspend fun rpcString(function: String, args: JsonObject): String {
        val body = call("POST", "rpc/$function", args.toString(), emptyMap())
        val value = runCatching { (BackendJson.parseToJsonElement(body) as? JsonPrimitive)?.contentOrNull }.getOrNull()
        return value ?: throw BackendException("The server sent an unexpected reply.")
    }

    /**
     * Sends a PostgREST request as the signed-in player. If the server rejects the access token
     * (it expired early, or the clock is off), refreshes once and retries.
     */
    private suspend fun call(method: String, path: String, body: String?, extra: Map<String, String>): String {
        var session = auth.validSession()
        repeat(2) { attempt ->
            val headers = auth.headers(session.accessToken) + schemaHeaders(method) + extra
            val response = transport.send(HttpRequest(method, "${config.baseUrl}/rest/v1/$path", headers, body))
            if (response.isSuccess) return response.body
            if (response.code == 401 && attempt == 0) {
                session = auth.forceRefresh(session)
            } else {
                throw errorFrom(response)
            }
        }
        throw BackendException("Something went wrong.")
    }

    /** PostgREST picks the schema from these headers; reads use Accept-Profile, writes Content-Profile. */
    private fun schemaHeaders(method: String): Map<String, String> =
        if (method == "GET" || method == "HEAD") mapOf("Accept-Profile" to SCHEMA) else mapOf("Content-Profile" to SCHEMA)

    companion object {
        const val SCHEMA = "block_party"

        /** Invite codes are 6 characters, shown in capitals; people may type them in any case or with spaces. */
        fun normalizeInviteCode(code: String): String = code.filter { it.isLetterOrDigit() }.uppercase()
    }
}
