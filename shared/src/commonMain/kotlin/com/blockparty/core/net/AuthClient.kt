package com.blockparty.core.net

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.buildJsonObject

/** A signed-in player. Saved on the device so they stay signed in between launches. */
@Serializable
data class Session(
    val accessToken: String,
    val refreshToken: String,
    /** When [accessToken] expires, in seconds since 1970. */
    val expiresAt: Long,
    val userId: String,
    val email: String = "",
)

/** Text form of a [Session] for [SessionStore]s. Returns null for unreadable text. */
object SessionCodec {
    fun encode(session: Session): String = BackendJson.encodeToString(Session.serializer(), session)
    fun decode(text: String): Session? = runCatching { BackendJson.decodeFromString(Session.serializer(), text) }.getOrNull()
}

/** Keeps the session between launches (Android: private SharedPreferences). */
interface SessionStore {
    fun load(): Session?
    fun save(session: Session)
    fun clear()
}

sealed interface SignUpResult {
    data class SignedIn(val session: Session) : SignUpResult
    /** The project requires confirming the email before the first sign-in. */
    data object ConfirmEmail : SignUpResult
}

/**
 * Email + password accounts through Supabase Auth's REST API. Accounts are shared by every app
 * on the project: signing up here creates the player's `public.profiles` row (with
 * [signUp]'s display name), and an account made in another app signs in here too.
 *
 * @param now the current time in seconds since 1970.
 */
class AuthClient(
    private val config: SupabaseConfig,
    private val transport: HttpTransport,
    private val store: SessionStore,
    private val now: () -> Long,
) {
    private val lock = Mutex()
    private var cached: Session? = store.load()

    /** The saved session, without checking whether it has expired. */
    val session: Session? get() = cached

    suspend fun signUp(email: String, password: String, displayName: String): SignUpResult {
        val body = buildJsonObject {
            put("email", email.trim())
            put("password", password)
            putJsonObject("data") { put("display_name", displayName.trim()) }
        }
        val obj = post("/auth/v1/signup", body)
        // With email confirmation on, Supabase returns the new user without a session.
        if (obj.string("access_token") == null) return SignUpResult.ConfirmEmail
        return SignUpResult.SignedIn(keep(parseSession(obj)))
    }

    suspend fun signIn(email: String, password: String): Session {
        val body = buildJsonObject {
            put("email", email.trim())
            put("password", password)
        }
        return keep(parseSession(post("/auth/v1/token?grant_type=password", body)))
    }

    /** Signs out on this device. The server call is best-effort: the local session goes regardless. */
    suspend fun signOut() {
        val s = lock.withLock { cached.also { cached = null; store.clear() } } ?: return
        runCatching {
            transport.send(
                HttpRequest("POST", config.baseUrl + "/auth/v1/logout?scope=local", headers(s.accessToken)),
            )
        }
    }

    /**
     * A session whose access token is good for at least another minute, refreshing it if needed.
     * Throws [SignedOutException] if there's no session or it can't be refreshed.
     */
    suspend fun validSession(): Session = lock.withLock {
        val s = cached ?: throw SignedOutException()
        if (s.expiresAt - now() > REFRESH_MARGIN_SECONDS) s else refreshLocked(s)
    }

    /** Refreshes now, e.g. after the server rejected an access token that looked valid. */
    suspend fun forceRefresh(rejected: Session): Session = lock.withLock {
        val s = cached ?: throw SignedOutException()
        // Another call may already have refreshed it while this one waited.
        if (s.accessToken != rejected.accessToken) s else refreshLocked(s)
    }

    private suspend fun refreshLocked(s: Session): Session {
        val body = buildJsonObject { put("refresh_token", s.refreshToken) }
        val response = transport.send(
            HttpRequest("POST", config.baseUrl + "/auth/v1/token?grant_type=refresh_token", headers(null), body.toString()),
        )
        if (!response.isSuccess) {
            // 400/401: the refresh token was revoked or already used, so the player must sign in again.
            if (response.code in 400..499) {
                cached = null
                store.clear()
                throw SignedOutException()
            }
            throw errorFrom(response)
        }
        val fresh = parseSession(BackendJson.parseToJsonElement(response.body).jsonObject)
        cached = fresh
        store.save(fresh)
        return fresh
    }

    private suspend fun keep(s: Session): Session = lock.withLock {
        cached = s
        store.save(s)
        s
    }

    private suspend fun post(path: String, body: JsonObject): JsonObject {
        val response = transport.send(HttpRequest("POST", config.baseUrl + path, headers(null), body.toString()))
        if (!response.isSuccess) throw errorFrom(response)
        return BackendJson.parseToJsonElement(response.body).jsonObject
    }

    internal fun headers(accessToken: String?): Map<String, String> = buildMap {
        put("apikey", config.apiKey)
        put("Content-Type", "application/json")
        put("Accept", "application/json")
        // Publishable keys aren't JWTs, so only a signed-in player's token goes in Authorization.
        if (accessToken != null) put("Authorization", "Bearer $accessToken")
    }

    private fun parseSession(obj: JsonObject): Session {
        val access = obj.string("access_token") ?: throw BackendException("The server sent an unexpected reply.")
        val refresh = obj.string("refresh_token") ?: throw BackendException("The server sent an unexpected reply.")
        val user = obj["user"]?.jsonObject ?: throw BackendException("The server sent an unexpected reply.")
        val expiresAt = obj["expires_at"]?.jsonPrimitive?.long
            ?: (now() + (obj["expires_in"]?.jsonPrimitive?.long ?: 3600L))
        return Session(access, refresh, expiresAt, user.string("id").orEmpty(), user.string("email").orEmpty())
    }

    companion object {
        const val REFRESH_MARGIN_SECONDS = 60L
    }
}
