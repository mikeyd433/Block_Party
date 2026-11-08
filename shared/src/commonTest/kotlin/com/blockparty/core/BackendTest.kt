package com.blockparty.core

import com.blockparty.core.net.AuthClient
import com.blockparty.core.net.BackendException
import com.blockparty.core.net.HttpRequest
import com.blockparty.core.net.HttpResponse
import com.blockparty.core.net.HttpTransport
import com.blockparty.core.net.Session
import com.blockparty.core.net.SessionStore
import com.blockparty.core.net.SignUpResult
import com.blockparty.core.net.SignedOutException
import com.blockparty.core.net.StreetsApi
import com.blockparty.core.net.SupabaseConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeTransport : HttpTransport {
    val sent = mutableListOf<HttpRequest>()
    private val replies = ArrayDeque<HttpResponse>()
    fun reply(code: Int, body: String) = replies.addLast(HttpResponse(code, body))
    override suspend fun send(request: HttpRequest): HttpResponse {
        sent += request
        return replies.removeFirstOrNull() ?: error("No reply queued for ${request.method} ${request.url}")
    }
}

private class MemoryStore(var saved: Session? = null) : SessionStore {
    override fun load() = saved
    override fun save(session: Session) { saved = session }
    override fun clear() { saved = null }
}

private const val SESSION_JSON =
    """{"access_token":"acc1","refresh_token":"ref1","expires_in":3600,"expires_at":5000,"token_type":"bearer","user":{"id":"u1","email":"a@b.co"}}"""

class BackendTest {
    private val config = SupabaseConfig("https://proj.supabase.co/", "sb_publishable_key")
    private var clock = 1000L

    private fun setup(saved: Session? = null): Triple<FakeTransport, MemoryStore, Pair<AuthClient, StreetsApi>> {
        val transport = FakeTransport()
        val store = MemoryStore(saved)
        val auth = AuthClient(config, transport, store) { clock }
        return Triple(transport, store, auth to StreetsApi(config, transport, auth))
    }

    private val signedIn = Session("acc1", "ref1", expiresAt = 5000, userId = "u1", email = "a@b.co")

    @Test
    fun signInSavesTheSessionAndSendsThePublishableKey() = runTest {
        val (t, store, apis) = setup()
        t.reply(200, SESSION_JSON)
        val s = apis.first.signIn(" a@b.co ", "pw")
        assertEquals(signedIn, s)
        assertEquals(signedIn, store.saved)
        val req = t.sent.single()
        assertEquals("https://proj.supabase.co/auth/v1/token?grant_type=password", req.url)
        assertEquals("sb_publishable_key", req.headers["apikey"])
        assertNull(req.headers["Authorization"], "a publishable key isn't a JWT, so no bearer before sign-in")
        assertTrue(req.body!!.contains("\"email\":\"a@b.co\""))
    }

    @Test
    fun signUpSendsTheDisplayNameForTheSharedProfile() = runTest {
        val (t, _, apis) = setup()
        t.reply(200, SESSION_JSON)
        val r = apis.first.signUp("a@b.co", "pw123456", " Ann ")
        assertIs<SignUpResult.SignedIn>(r)
        assertTrue(t.sent.single().body!!.contains("\"data\":{\"display_name\":\"Ann\"}"))
    }

    @Test
    fun signUpWithEmailConfirmationReturnsConfirmEmail() = runTest {
        val (t, store, apis) = setup()
        t.reply(200, """{"id":"u1","email":"a@b.co","confirmation_sent_at":"2026-11-08T00:00:00Z"}""")
        assertEquals(SignUpResult.ConfirmEmail, apis.first.signUp("a@b.co", "pw123456", "Ann"))
        assertNull(store.saved)
    }

    @Test
    fun authErrorsBecomeFriendlyMessages() = runTest {
        val (t, _, apis) = setup()
        t.reply(400, """{"code":400,"error_code":"invalid_credentials","msg":"Invalid login credentials"}""")
        val e = assertFailsWith<BackendException> { apis.first.signIn("a@b.co", "bad") }
        assertEquals("Wrong email or password.", e.message)
        t.reply(400, """{"error":"invalid_grant","error_description":"Email not confirmed","error_code":"email_not_confirmed"}""")
        val e2 = assertFailsWith<BackendException> { apis.first.signIn("a@b.co", "pw") }
        assertTrue(e2.message!!.startsWith("Confirm your email"))
    }

    @Test
    fun expiringTokenIsRefreshedBeforeUse() = runTest {
        val (t, store, apis) = setup(signedIn.copy(expiresAt = 1030))
        t.reply(200, SESSION_JSON.replace("acc1", "acc2").replace("ref1", "ref2"))
        t.reply(200, "[]")
        assertEquals(emptyList(), apis.second.myStreets())
        assertTrue(t.sent[0].url.endsWith("grant_type=refresh_token"))
        assertTrue(t.sent[0].body!!.contains("ref1"))
        assertEquals("Bearer acc2", t.sent[1].headers["Authorization"])
        assertEquals("ref2", store.saved!!.refreshToken)
    }

    @Test
    fun rejectedTokenIsRefreshedOnceAndRetried() = runTest {
        val (t, _, apis) = setup(signedIn)
        t.reply(401, """{"code":"PGRST303","message":"JWT expired"}""")
        t.reply(200, SESSION_JSON.replace("acc1", "acc2"))
        t.reply(200, "[]")
        apis.second.myStreets()
        assertEquals(3, t.sent.size)
        assertEquals("Bearer acc2", t.sent[2].headers["Authorization"])
    }

    @Test
    fun revokedRefreshTokenSignsOut() = runTest {
        val (t, store, apis) = setup(signedIn.copy(expiresAt = 0))
        t.reply(400, """{"error_code":"refresh_token_not_found","msg":"Invalid Refresh Token"}""")
        assertFailsWith<SignedOutException> { apis.second.myStreets() }
        assertNull(store.saved)
        assertNull(apis.first.session)
    }

    @Test
    fun streetsAreReadFromTheBlockPartySchema() = runTest {
        val (t, _, apis) = setup(signedIn)
        t.reply(200, """[{"id":"s1","name":"Elm St","invite_code":"AB12CD","admin_id":"u1","time_zone":"Europe/London","created_at":"x","extra":1}]""")
        val streets = apis.second.myStreets()
        assertEquals("AB12CD", streets.single().inviteCode)
        assertEquals("Europe/London", streets.single().timeZone)
        val req = t.sent.single()
        assertEquals("GET", req.method)
        assertTrue(req.url.startsWith("https://proj.supabase.co/rest/v1/streets?"))
        assertEquals("block_party", req.headers["Accept-Profile"])
        assertEquals("Bearer acc1", req.headers["Authorization"])
    }

    @Test
    fun createAndJoinCallTheGameFunctions() = runTest {
        val (t, _, apis) = setup(signedIn)
        t.reply(200, "\"street-uuid\"")
        assertEquals("street-uuid", apis.second.createStreet(" Elm St ", "Europe/London", "Ann"))
        val create = t.sent.last()
        assertEquals("https://proj.supabase.co/rest/v1/rpc/create_street", create.url)
        assertEquals("block_party", create.headers["Content-Profile"])
        assertEquals("""{"p_name":"Elm St","p_time_zone":"Europe/London","p_display_name":"Ann"}""", create.body)

        t.reply(200, "\"street-uuid\"")
        apis.second.joinStreet(" ab 12-cd ", "Bo")
        assertEquals("""{"p_code":"AB12CD","p_display_name":"Bo"}""", t.sent.last().body)
    }

    @Test
    fun functionErrorsShowTheirMessage() = runTest {
        val (t, _, apis) = setup(signedIn)
        t.reply(400, """{"code":"P0001","details":null,"hint":null,"message":"No street with that invite code"}""")
        val e = assertFailsWith<BackendException> { apis.second.joinStreet("ZZZZZZ", "Bo") }
        assertEquals("No street with that invite code", e.message)
    }

    @Test
    fun leaveDeletesOnlyMyMembership() = runTest {
        val (t, _, apis) = setup(signedIn)
        t.reply(204, "")
        apis.second.leave("s1")
        val req = t.sent.single()
        assertEquals("DELETE", req.method)
        assertTrue(req.url.endsWith("members?street_id=eq.s1&user_id=eq.u1"))
        assertEquals("block_party", req.headers["Content-Profile"])
    }

    @Test
    fun displayNameComesFromTheSharedProfile() = runTest {
        val (t, _, apis) = setup(signedIn)
        t.reply(200, """[{"display_name":"Ann"}]""")
        assertEquals("Ann", apis.second.myDisplayName())
        assertEquals("public", t.sent.single().headers["Accept-Profile"])
    }

    @Test
    fun signOutClearsLocallyEvenIfTheServerFails() = runTest {
        val (t, store, apis) = setup(signedIn)
        // No reply queued: the logout request throws, and sign-out still succeeds.
        apis.first.signOut()
        assertNull(store.saved)
        assertEquals("https://proj.supabase.co/auth/v1/logout?scope=local", t.sent.single().url)
        assertFailsWith<SignedOutException> { apis.first.validSession() }
    }
}
