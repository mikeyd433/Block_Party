package com.blockparty.core

import com.blockparty.core.model.DayBook
import com.blockparty.core.net.AuthClient
import com.blockparty.core.net.HttpRequest
import com.blockparty.core.net.HttpResponse
import com.blockparty.core.net.HttpTransport
import com.blockparty.core.net.Loans
import com.blockparty.core.net.Session
import com.blockparty.core.net.SessionStore
import com.blockparty.core.net.StreetsApi
import com.blockparty.core.net.SupabaseConfig
import com.blockparty.core.net.Transfer
import com.blockparty.core.net.chat
import com.blockparty.core.net.collect
import com.blockparty.core.net.leaveDoorNote
import com.blockparty.core.net.sendChat
import com.blockparty.core.net.sendMoney
import com.blockparty.core.net.uncollectedMoney
import com.blockparty.core.sim.Economy
import com.blockparty.core.world.WorldFactory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SocialTest {
    private class Fake : HttpTransport {
        val sent = mutableListOf<HttpRequest>()
        val replies = ArrayDeque<HttpResponse>()
        override suspend fun send(request: HttpRequest): HttpResponse { sent += request; return replies.removeFirst() }
    }

    private fun api(): Pair<Fake, StreetsApi> {
        val t = Fake()
        val config = SupabaseConfig("https://p.supabase.co", "key")
        val store = object : SessionStore {
            override fun load() = Session("acc", "ref", Long.MAX_VALUE / 2, "me")
            override fun save(session: Session) {}
            override fun clear() {}
        }
        return t to StreetsApi(config, t, AuthClient(config, t, store) { 0L })
    }

    private fun loan(id: String, from: String, to: String, amount: Long) = Transfer(id, from, to, "", Transfer.LOAN, amount, createdAt = id)
    private fun repay(loanId: String, from: String, to: String, amount: Long) =
        Transfer("r-$loanId-$amount", from, to, "", Transfer.REPAYMENT, amount, loanId = loanId)

    @Test
    fun loanBalancesCountRepayments() {
        val history = listOf(
            loan("l1", "bo", "me", 200), repay("l1", "me", "bo", 80), repay("l1", "me", "bo", 20),
            loan("l2", "me", "cy", 50), repay("l2", "cy", "me", 50),
            loan("l3", "bo", "cy", 10), // not mine
        )
        val balances = Loans.balances(history, "me")
        assertEquals(listOf("l1", "l2"), balances.map { it.loan.id }, "unpaid first, others' loans left out")
        assertEquals(100, balances[0].outstanding)
        assertTrue(balances[0].borrowed)
        assertEquals(0, balances[1].outstanding)
        assertTrue(!balances[1].borrowed)
    }

    @Test
    fun incomingMoneyIsDescribed() {
        val gift = Transfer("t", "bo", "me", "Bo", Transfer.GIFT, 50, note = "for rent")
        assertEquals("Bo gave you 50 coins! \"for rent\"", Loans.describeIncoming(gift))
        assertTrue(Loans.describeIncoming(gift.copy(kind = Transfer.LOAN, note = "")).startsWith("Bo lent you 50 coins."))
    }

    @Test
    fun friendsMoneyIsBooked() {
        val w = WorldFactory.newWorld()
        val after = Economy.sendToFriend(Economy.receiveFromFriend(w, 10, 50), 10, 20)
        assertEquals(w.coins + 30, after.coins)
        val day = after.economy.books.single()
        assertEquals(DayBook(10, fromFriends = 50, toFriends = 20), day)
        assertEquals(30, day.net)
    }

    @Test
    fun socialRequestsHaveTheRightShape() = runTest {
        val (t, api) = api()
        t.replies += HttpResponse(201, "")
        api.sendChat("s1", "Ann", "  hello  ")
        assertEquals("https://p.supabase.co/rest/v1/messages", t.sent.last().url)
        assertEquals("""{"street_id":"s1","author_id":"me","author_name":"Ann","body":"hello"}""", t.sent.last().body)
        assertEquals("block_party", t.sent.last().headers["Content-Profile"])

        t.replies += HttpResponse(201, "")
        api.leaveDoorNote("s1", "lot-2", "Ann", "nice shop")
        assertTrue(t.sent.last().body!!.contains("\"lot_id\":\"lot-2\""))

        t.replies += HttpResponse(201, "")
        api.sendMoney("s1", "bo", "Ann", Transfer.REPAYMENT, 25, loanId = "l1")
        assertEquals(
            """{"street_id":"s1","from_id":"me","to_id":"bo","from_name":"Ann","kind":"repayment","amount":25,"note":"","loan_id":"l1"}""",
            t.sent.last().body,
        )

        // New messages since the last one: the timestamp's '+' must be escaped.
        t.replies += HttpResponse(200, """[{"id":"m2","author_name":"Bo","body":"b","created_at":"2"},{"id":"m1","author_name":"Bo","body":"a","created_at":"1"}]""")
        val msgs = api.chat("s1", after = "2026-11-09T10:00:00.123+00:00")
        assertEquals(listOf("m1", "m2"), msgs.map { it.id }, "oldest first")
        assertTrue(t.sent.last().url.contains("created_at=gt.2026-11-09T10%3A00%3A00.123%2B00%3A00"))

        t.replies += HttpResponse(200, """[{"id":"t1","from_id":"bo","to_id":"me","from_name":"Bo","kind":"gift","amount":50}]""")
        assertEquals(50, api.uncollectedMoney("s1").single().amount)
        assertTrue(t.sent.last().url.contains("to_id=eq.me&claimed_at=is.null"))

        t.replies += HttpResponse(200, "50")
        assertEquals(50L, api.collect("t1"))
        t.replies += HttpResponse(200, "null")
        assertNull(api.collect("t1"), "already collected")
    }
}
