package com.blockparty.core.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Messages for Supabase Realtime (Phoenix channels over a websocket), enough to hear about
 * changes to one shared street. The app uses each change only as a cue to sync, so the payloads
 * aren't applied directly: the normal (incremental) pull fetches what changed, with the same
 * merge rules as always. Row-level security applies to Realtime too, so only members hear
 * about a street.
 */
object RealtimeProtocol {
    /** Tables whose changes matter to a street (all in the Realtime publication). */
    val TABLES = listOf("lots", "sprites", "members")
    /** Chat, notes and money (from the social migration). */
    val SOCIAL_TABLES = listOf("messages", "door_notes", "transfers")

    /** Phoenix expects a heartbeat at least every 30 seconds. */
    const val HEARTBEAT_MS = 25_000L

    fun socketUrl(config: SupabaseConfig): String {
        val base = config.baseUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        return "$base/realtime/v1/websocket?apikey=${config.apiKey}&vsn=1.0.0"
    }

    fun topic(streetId: String) = "realtime:block-party-street-$streetId"

    /** Joins the street's channel: changes to its lots, drawings and members. */
    fun join(streetId: String, accessToken: String, ref: String, tables: List<String> = TABLES + SOCIAL_TABLES): String = message(
        topic(streetId), "phx_join", ref, joinRef = ref,
        payload = buildJsonObject {
            putJsonObject("config") {
                putJsonObject("broadcast") { put("self", false) }
                putJsonObject("presence") { put("key", "") }
                putJsonArray("postgres_changes") {
                    for (table in tables) addJsonObject {
                        put("event", "*")
                        put("schema", StreetsApi.SCHEMA)
                        put("table", table)
                        put("filter", "street_id=eq.$streetId")
                    }
                }
            }
            put("access_token", accessToken)
        },
    )

    fun heartbeat(ref: String): String = message("phoenix", "heartbeat", ref, payload = JsonObject(emptyMap()))

    /** Sends a refreshed access token so the subscription keeps passing row-level security. */
    fun accessToken(streetId: String, accessToken: String, ref: String, joinRef: String): String =
        message(topic(streetId), "access_token", ref, joinRef, buildJsonObject { put("access_token", accessToken) })

    sealed interface Incoming {
        /** The channel is joined and listening. */
        data object Joined : Incoming
        /** The server refused the channel or the change subscription. */
        data class Failed(val reason: String) : Incoming
        /** Something changed on the street. */
        data class Change(val table: String) : Incoming
        /** The server closed the channel. */
        data object Closed : Incoming
        /** Heartbeat replies and anything else that needs no action. */
        data object Other : Incoming
    }

    fun parse(text: String, streetId: String, joinRef: String?): Incoming {
        val obj = runCatching { BackendJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return Incoming.Other
        val event = obj.string("event")
        val topic = obj.string("topic")
        val payload = obj["payload"] as? JsonObject
        if (topic != topic(streetId)) return Incoming.Other
        return when (event) {
            "phx_reply" -> {
                // Only the reply to the join matters; heartbeats and token updates are acknowledged too.
                if (joinRef == null || obj.string("ref") != joinRef) return Incoming.Other
                if (payload?.string("status") == "ok") Incoming.Joined
                else Incoming.Failed(
                    (payload?.get("response") as? JsonObject)?.string("reason") ?: "The live connection was refused.",
                )
            }
            "postgres_changes" -> {
                val table = (payload?.get("data") as? JsonObject)?.string("table").orEmpty()
                Incoming.Change(table)
            }
            "system" -> {
                // Reports whether the change subscription itself worked.
                if (payload?.string("status") == "error") Incoming.Failed(payload.string("message") ?: "Live updates aren't available.")
                else Incoming.Other
            }
            "phx_error", "phx_close" -> Incoming.Closed
            else -> Incoming.Other
        }
    }

    private fun message(topic: String, event: String, ref: String, joinRef: String? = null, payload: JsonObject): String =
        buildJsonObject {
            put("topic", topic)
            put("event", event)
            put("payload", payload)
            put("ref", ref)
            if (joinRef != null) put("join_ref", joinRef)
        }.toString()
}
