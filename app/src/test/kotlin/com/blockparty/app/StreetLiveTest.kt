package com.blockparty.app

import com.blockparty.app.data.StreetLive
import com.blockparty.core.net.AuthClient
import com.blockparty.core.net.HttpTransport
import com.blockparty.core.net.Session
import com.blockparty.core.net.SessionStore
import com.blockparty.core.net.SupabaseConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Plays Supabase Realtime's side of the conversation to check the live connection. */
class StreetLiveTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    @Test
    fun joinsTheStreetAndReportsChanges() {
        val received = LinkedBlockingQueue<String>()
        var serverSocket: WebSocket? = null
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverSocket = webSocket
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                received.put(text)
            }
        }))
        server.start()
        val config = SupabaseConfig(server.url("/").toString().trimEnd('/'), "sb_publishable_test")
        val store = object : SessionStore {
            override fun load() = Session("jwt-1", "refresh", expiresAt = Long.MAX_VALUE / 2, userId = "u1")
            override fun save(session: Session) {}
            override fun clear() {}
        }
        val auth = AuthClient(config, HttpTransport { error("no HTTP expected") }, store) { 0L }
        val changes = LinkedBlockingQueue<Unit>()
        val live = LinkedBlockingQueue<Boolean>()
        StreetLive(config, auth, "s1", onChange = { changes.put(Unit) }, onLive = { live.put(it) }).start(scope)

        // The app joins the street's channel with the player's token and the publishable key.
        val join = Json.parseToJsonElement(received.poll(10, TimeUnit.SECONDS)!!).jsonObject
        assertEquals("phx_join", join["event"]!!.jsonPrimitive.content)
        assertEquals("realtime:block-party-street-s1", join["topic"]!!.jsonPrimitive.content)
        assertEquals("jwt-1", join["payload"]!!.jsonObject["access_token"]!!.jsonPrimitive.content)
        val request = server.takeRequest()
        assertTrue(request.path!!.startsWith("/realtime/v1/websocket?apikey=sb_publishable_test&vsn=1.0.0"))

        // Joined: the connection is live, and it syncs once to catch anything missed.
        val ref = join["ref"]!!.jsonPrimitive.content
        serverSocket!!.send("""{"event":"phx_reply","payload":{"response":{},"status":"ok"},"ref":"$ref","topic":"realtime:block-party-street-s1"}""")
        assertEquals(true, live.poll(10, TimeUnit.SECONDS))
        changes.poll(10, TimeUnit.SECONDS)!!

        // A friend's change wakes the sync.
        serverSocket!!.send("""{"event":"postgres_changes","payload":{"data":{"table":"lots","type":"UPDATE"}},"ref":null,"topic":"realtime:block-party-street-s1"}""")
        changes.poll(10, TimeUnit.SECONDS)!!

        // The server going away is noticed (and the app will reconnect).
        serverSocket!!.close(1000, "bye")
        assertEquals(false, live.poll(10, TimeUnit.SECONDS))
    }
}
