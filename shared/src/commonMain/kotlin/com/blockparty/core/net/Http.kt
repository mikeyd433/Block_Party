package com.blockparty.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * A minimal HTTP layer so the backend client stays platform-independent: each platform
 * supplies a [HttpTransport] (Android uses HttpURLConnection), and tests use a fake.
 */
class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    /** Binary body (drawing uploads); sent instead of [body] when set. */
    val bytes: ByteArray? = null,
)

/** [bytes] is the raw body (drawing downloads); [body] is the same as text. */
class HttpResponse(val code: Int, val body: String, val bytes: ByteArray = body.encodeToByteArray()) {
    val isSuccess: Boolean get() = code in 200..299
}

fun interface HttpTransport {
    /** Sends the request. Throws on network failure (no connection, timeout). */
    suspend fun send(request: HttpRequest): HttpResponse
}

/** Where the backend lives. [apiKey] is the project's publishable (public) key. */
data class SupabaseConfig(val url: String, val apiKey: String) {
    val baseUrl: String get() = url.trimEnd('/')
}

/** A backend failure with a message that can be shown to the player as-is. */
open class BackendException(message: String, val code: Int? = null) : Exception(message)

/** The saved session is no longer valid (expired refresh token, account removed). */
class SignedOutException : BackendException("You've been signed out. Sign in again.")

internal val BackendJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

/**
 * Turns a Supabase error response into a friendly message. Auth errors carry `error_code` and
 * `msg` (older servers: `error` and `error_description`); database errors carry `message`,
 * which for the game's functions is already written for players ("No street with that invite code").
 */
internal fun errorFrom(response: HttpResponse): BackendException {
    val obj = runCatching { BackendJson.parseToJsonElement(response.body).jsonObject }.getOrNull()
    fun field(name: String) = (obj?.get(name) as? JsonPrimitive)?.contentOrNull
    val code = field("error_code") ?: field("error")
    val raw = field("msg") ?: field("error_description") ?: field("message")
    val friendly = when (code) {
        "invalid_credentials", "invalid_grant" -> "Wrong email or password."
        "email_not_confirmed" -> "Confirm your email first (check your inbox), then sign in."
        "user_already_exists", "email_exists" -> "That email already has an account. Sign in instead."
        "weak_password" -> raw ?: "Choose a stronger password."
        "validation_failed", "email_address_invalid" -> raw ?: "Check the email address."
        "over_email_send_rate_limit", "over_request_rate_limit" -> "Too many tries. Wait a minute and try again."
        "signup_disabled" -> "New accounts are turned off right now."
        else -> null
    }
    val message = friendly ?: raw?.takeIf { it.isNotBlank() } ?: when (response.code) {
        401, 403 -> "You don't have access to that."
        404 -> "Couldn't find that."
        in 500..599 -> "The server had a problem. Try again in a moment."
        else -> "Something went wrong (${response.code})."
    }
    return BackendException(message, response.code)
}

internal fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
