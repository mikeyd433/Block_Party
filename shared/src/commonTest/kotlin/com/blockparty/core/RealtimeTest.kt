package com.blockparty.core

import com.blockparty.core.net.RealtimeProtocol
import com.blockparty.core.net.RealtimeProtocol.Incoming
import com.blockparty.core.net.SupabaseConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RealtimeTest {
    private val topic = "realtime:block-party-street-s1"

    @Test
    fun socketUrlUsesTheWebsocketSchemeAndKey() {
        assertEquals(
            "wss://proj.supabase.co/realtime/v1/websocket?apikey=sb_publishable_x&vsn=1.0.0",
            RealtimeProtocol.socketUrl(SupabaseConfig("https://proj.supabase.co/", "sb_publishable_x")),
        )
    }

    @Test
    fun joinListensToTheStreetsTablesWithTheAccessToken() {
        val msg = Json.parseToJsonElement(RealtimeProtocol.join("s1", "jwt", "1")).jsonObject
        assertEquals(topic, msg["topic"]!!.jsonPrimitive.content)
        assertEquals("phx_join", msg["event"]!!.jsonPrimitive.content)
        assertEquals("1", msg["join_ref"]!!.jsonPrimitive.content)
        val payload = msg["payload"]!!.jsonObject
        assertEquals("jwt", payload["access_token"]!!.jsonPrimitive.content)
        val changes = payload["config"]!!.jsonObject["postgres_changes"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("lots", "sprites", "members"), changes.map { it["table"]!!.jsonPrimitive.content })
        changes.forEach {
            assertEquals("block_party", it["schema"]!!.jsonPrimitive.content)
            assertEquals("street_id=eq.s1", it["filter"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun heartbeatGoesToThePhoenixTopic() {
        val msg = Json.parseToJsonElement(RealtimeProtocol.heartbeat("7")).jsonObject
        assertEquals("phoenix", msg["topic"]!!.jsonPrimitive.content)
        assertEquals("heartbeat", msg["event"]!!.jsonPrimitive.content)
        assertIs<JsonObject>(msg["payload"])
    }

    @Test
    fun incomingMessagesAreRecognised() {
        fun parse(s: String) = RealtimeProtocol.parse(s, "s1", joinRef = "1")
        assertEquals(Incoming.Joined, parse("""{"event":"phx_reply","payload":{"response":{"postgres_changes":[]},"status":"ok"},"ref":"1","topic":"$topic"}"""))
        assertEquals(Incoming.Other, parse("""{"event":"phx_reply","payload":{"response":{},"status":"ok"},"ref":"2","topic":"$topic"}"""), "a heartbeat or token reply")
        assertEquals(
            Incoming.Failed("Unauthorized"),
            parse("""{"event":"phx_reply","payload":{"response":{"reason":"Unauthorized"},"status":"error"},"ref":"1","topic":"$topic"}"""),
        )
        assertEquals(
            Incoming.Change("lots"),
            parse("""{"event":"postgres_changes","payload":{"data":{"schema":"block_party","table":"lots","type":"UPDATE","record":{}},"ids":[1]},"ref":null,"topic":"$topic"}"""),
        )
        assertIs<Incoming.Failed>(parse("""{"event":"system","payload":{"extension":"postgres_changes","message":"Unable to subscribe","status":"error"},"ref":null,"topic":"$topic"}"""))
        assertEquals(Incoming.Other, parse("""{"event":"system","payload":{"extension":"postgres_changes","message":"Subscribed","status":"ok"},"ref":null,"topic":"$topic"}"""))
        assertEquals(Incoming.Closed, parse("""{"event":"phx_error","payload":{},"ref":"1","topic":"$topic"}"""))
        assertEquals(Incoming.Other, parse("""{"event":"postgres_changes","payload":{},"ref":null,"topic":"realtime:other"}"""), "another channel")
        assertEquals(Incoming.Other, parse("not json"))
    }
}
