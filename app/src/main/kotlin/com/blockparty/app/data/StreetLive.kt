package com.blockparty.app.data

import com.blockparty.core.net.AuthClient
import com.blockparty.core.net.BackendException
import com.blockparty.core.net.RealtimeProtocol
import com.blockparty.core.net.RealtimeProtocol.Incoming
import com.blockparty.core.net.SupabaseConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A live connection to one shared street (Supabase Realtime). Calls [onChange] whenever a lot,
 * drawing or member on the street changes, and [onLive] when the connection comes up or goes
 * down. Reconnects with backoff on its own until [stop]. Callbacks arrive on OkHttp's thread.
 */
class StreetLive(
    private val config: SupabaseConfig,
    private val auth: AuthClient,
    private val streetId: String,
    private val onChange: () -> Unit,
    private val onLive: (Boolean) -> Unit,
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch {
            var backoffMs = MIN_BACKOFF_MS
            while (isActive) {
                val joined = runCatching { connectOnce() }.getOrDefault(false)
                onLive(false)
                if (joined) backoffMs = MIN_BACKOFF_MS
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** One connection, until it closes. Returns whether the channel was joined. */
    private suspend fun connectOnce(): Boolean {
        val token = try {
            auth.validSession().accessToken
        } catch (e: BackendException) {
            return false
        }
        val refs = AtomicInteger(0)
        val joinRef = refs.incrementAndGet().toString()
        val closed = CompletableDeferred<Unit>()
        val joined = java.util.concurrent.atomic.AtomicBoolean(false)
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(RealtimeProtocol.join(streetId, token, joinRef))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                when (val msg = RealtimeProtocol.parse(text, streetId, joinRef)) {
                    Incoming.Joined -> {
                        joined.set(true)
                        onLive(true)
                        onChange() // catch anything that happened while not connected
                    }
                    is Incoming.Change -> onChange()
                    is Incoming.Failed, Incoming.Closed -> webSocket.close(1000, null)
                    Incoming.Other -> Unit
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                closed.complete(Unit)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                closed.complete(Unit)
            }
        }
        val socket = client.newWebSocket(Request.Builder().url(RealtimeProtocol.socketUrl(config)).build(), listener)
        try {
            var sentToken = token
            while (!closed.isCompleted) {
                kotlinx.coroutines.withTimeoutOrNull(RealtimeProtocol.HEARTBEAT_MS) { closed.await() }
                if (closed.isCompleted) break
                if (!socket.send(RealtimeProtocol.heartbeat(refs.incrementAndGet().toString()))) break
                // Access tokens last an hour; pass the refreshed one on so row-level security keeps passing.
                val current = runCatching { auth.validSession().accessToken }.getOrNull()
                if (current != null && current != sentToken) {
                    socket.send(RealtimeProtocol.accessToken(streetId, current, refs.incrementAndGet().toString(), joinRef))
                    sentToken = current
                }
            }
        } finally {
            socket.cancel()
        }
        return joined.get()
    }

    private companion object {
        const val MIN_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 60_000L
        val client: OkHttpClient by lazy {
            OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).connectTimeout(15, TimeUnit.SECONDS).build()
        }
    }
}
