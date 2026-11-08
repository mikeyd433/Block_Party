package com.blockparty.app.data

import android.content.Context
import com.blockparty.app.BuildConfig
import com.blockparty.core.net.AuthClient
import com.blockparty.core.net.BackendException
import com.blockparty.core.net.HttpRequest
import com.blockparty.core.net.HttpResponse
import com.blockparty.core.net.HttpTransport
import com.blockparty.core.net.Session
import com.blockparty.core.net.SessionCodec
import com.blockparty.core.net.SessionStore
import com.blockparty.core.net.StreetsApi
import com.blockparty.core.net.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Plain HttpURLConnection, so the backend needs no extra libraries. */
class UrlConnectionTransport : HttpTransport {
    override suspend fun send(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        try {
            val conn = URL(request.url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = request.method
                conn.connectTimeout = 15_000
                conn.readTimeout = 20_000
                request.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                request.body?.let { body ->
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = conn.responseCode
                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                HttpResponse(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            throw BackendException("Can't reach the server. Check your internet connection.")
        }
    }
}

/** Keeps the session in the app's private preferences. */
class PrefsSessionStore(context: Context) : SessionStore {
    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)
    override fun load(): Session? = prefs.getString(KEY, null)?.let(SessionCodec::decode)

    override fun save(session: Session) {
        prefs.edit().putString(KEY, SessionCodec.encode(session)).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "session"
    }
}

/** One backend client per app process. */
class Backend private constructor(context: Context) {
    val config = SupabaseConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY)
    private val transport = UrlConnectionTransport()
    val auth = AuthClient(config, transport, PrefsSessionStore(context)) { System.currentTimeMillis() / 1000 }
    val streets = StreetsApi(config, transport, auth)

    companion object {
        @Volatile private var instance: Backend? = null
        fun get(context: Context): Backend =
            instance ?: synchronized(this) { instance ?: Backend(context.applicationContext).also { instance = it } }
    }
}
