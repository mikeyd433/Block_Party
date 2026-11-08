package com.blockparty.core

import com.blockparty.core.model.Business
import com.blockparty.core.model.CharacterRole
import com.blockparty.core.model.FurniturePlacement
import com.blockparty.core.model.GridRect
import com.blockparty.core.model.LotState
import com.blockparty.core.model.SpriteAsset
import com.blockparty.core.model.SpriteKind
import com.blockparty.core.model.WorldState
import com.blockparty.core.net.PlayerState
import com.blockparty.core.net.RemoteLot
import com.blockparty.core.net.RemoteSprite
import com.blockparty.core.net.ServerStreet
import com.blockparty.core.net.StreetSyncer
import com.blockparty.core.net.SyncCodec
import com.blockparty.core.net.SyncMerge
import com.blockparty.core.net.SyncOp
import com.blockparty.core.net.SyncPlanner
import com.blockparty.core.sim.Economy
import com.blockparty.core.sim.Lots
import com.blockparty.core.world.WorldFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncTest {
    private val me = "me"
    private val bo = "bo"

    private fun rect(i: Int) = GridRect(1 + i * 7, 0, 6, 6)
    private fun vacant(i: Int) = RemoteLot("lot-$i", rect(i), "VACANT")
    private fun owned(i: Int, owner: String, name: String = "Shop $i") =
        RemoteLot("lot-$i", rect(i), "OWNED", owner, owner, SyncCodec.business(Business(name)), SyncCodec.furniture(emptyList()))

    private fun fresh(): WorldState = WorldFactory.newSharedWorld("street-1", "Elm", "UTC", me, "Me").copy(coins = 1000)

    /** The world after its first pull, with everything the player owns marked as already sent. */
    private fun opened(vararg lots: RemoteLot): WorldState {
        val w = SyncMerge.merge(fresh(), ServerStreet(lots.toList(), emptyList())).world
        return w.copy(sync = w.sync.copy(pushedPlayerState = SyncCodec.playerStateHash(w)))
    }

    @Test
    fun firstPullBuildsTheStreetFromTheServer() {
        val w = opened(vacant(0), owned(1, bo, "Bo's Bakes"), vacant(2))
        assertEquals(listOf("lot-0", "lot-1", "lot-2"), w.street.lots.map { it.id })
        assertEquals("Bo's Bakes", w.lot("lot-1")!!.business!!.name)
        assertTrue(!w.lot("lot-1")!!.isMine(w))
        assertTrue(w.street.widthTiles >= rect(2).right + 1)
        assertEquals(emptyList(), SyncPlanner.plan(w, listOf(vacant(0), owned(1, bo), vacant(2))))
    }

    @Test
    fun buyingALotHereClaimsItAndSendsTheShop() {
        val server = listOf(vacant(0), owned(1, bo))
        val bought = (Lots.buy(opened(*server.toTypedArray()), "lot-0", 1) as Lots.Result.Ok).world
        val ops = SyncPlanner.plan(bought, server)
        assertTrue(SyncOp.Claim("lot-0") in ops)
        assertTrue(SyncOp.Update("lot-0") in ops)
        assertTrue(SyncOp.PutPlayerState in ops, "coins changed")

        // Until the claim lands, pulling keeps the player's new shop.
        val pulled = SyncMerge.merge(bought, ServerStreet(server, emptyList())).world
        assertEquals(LotState.OWNED, pulled.lot("lot-0")!!.state)
        assertEquals(me, pulled.lot("lot-0")!!.ownerId)
    }

    @Test
    fun losingARaceForALotRefundsIt() {
        val start = opened(vacant(0))
        val bought = (Lots.buy(start, "lot-0", 1) as Lots.Result.Ok).world
        val r = SyncMerge.merge(bought, ServerStreet(listOf(owned(0, bo, "Bo's")), emptyList()))
        assertEquals(bo, r.world.lot("lot-0")!!.ownerId)
        assertEquals(start.coins, r.world.coins)
        assertEquals(1, r.notices.size)
    }

    @Test
    fun ownShopsKeepLocalChangesAndOnlyChangesAreSent() {
        val server = listOf(owned(0, me, "Old name"))
        val w = opened(*server.toTypedArray())
        assertEquals(emptyList(), SyncPlanner.plan(w, server), "nothing new to send after the first pull")
        val renamed = w.updateLot("lot-0") { it.copy(business = it.business!!.copy(name = "New name")) }
        assertEquals(listOf<SyncOp>(SyncOp.Update("lot-0")), SyncPlanner.plan(renamed, server))
        // A pull before the update lands doesn't undo the rename.
        assertEquals("New name", SyncMerge.merge(renamed, ServerStreet(server, emptyList())).world.lot("lot-0")!!.business!!.name)
        val sent = StreetSyncer.record(renamed, StreetSyncer.Pushed(lots = mapOf("lot-0" to SyncCodec.lotHash(renamed.lot("lot-0")!!))))
        assertEquals(emptyList(), SyncPlanner.plan(sent, server))
    }

    @Test
    fun closingAShopHereClosesItOnTheServer() {
        val server = listOf(owned(0, me))
        val closed = Economy.closeBusiness(opened(*server.toTypedArray()), "lot-0")
        assertEquals(listOf<SyncOp>(SyncOp.Close("lot-0")), SyncPlanner.plan(closed, server))
        assertEquals(LotState.VACANT, SyncMerge.merge(closed, ServerStreet(server, emptyList())).world.lot("lot-0")!!.state)
    }

    @Test
    fun expandingIntoTheNextLotMergesOnTheServer() {
        val server = listOf(owned(0, me), vacant(1))
        val merged = (Lots.merge(opened(*server.toTypedArray()), "lot-0", "lot-1", 1) as Lots.Result.Ok).world
        assertEquals(listOf<SyncOp>(SyncOp.Merge("lot-0", "lot-1"), SyncOp.PutPlayerState), SyncPlanner.plan(merged, server))
        // Pulling before the merge lands doesn't bring the absorbed lot back.
        val pulled = SyncMerge.merge(merged, ServerStreet(server, emptyList())).world
        assertEquals(listOf("lot-0"), pulled.street.lots.map { it.id })
        assertEquals(13, pulled.lot("lot-0")!!.rect.w)
    }

    @Test
    fun otherPlayersShopsFollowTheServer() {
        val w = opened(owned(0, bo, "Bo's"))
        val later = SyncMerge.merge(w, ServerStreet(listOf(owned(0, bo, "Bo's Bigger Bakes")), emptyList())).world
        assertEquals("Bo's Bigger Bakes", later.lot("lot-0")!!.business!!.name)
        // Bo closed it.
        val closed = SyncMerge.merge(later, ServerStreet(listOf(vacant(0)), emptyList())).world
        assertEquals(LotState.VACANT, closed.lot("lot-0")!!.state)
        assertNull(closed.lot("lot-0")!!.business)
    }

    @Test
    fun ownShopComesBackOnANewInstall() {
        val furniture = listOf(FurniturePlacement("f1", "starter-table", 2, 2))
        val mine = owned(0, me, "My Cakes").copy(furniture = SyncCodec.furniture(furniture))
        val w = opened(mine)
        assertEquals("My Cakes", w.lot("lot-0")!!.business!!.name)
        assertEquals(furniture, w.lot("lot-0")!!.furniture)
        assertEquals(emptyList(), SyncPlanner.plan(w, listOf(mine)), "a restored shop isn't sent straight back")
        val restored = SyncMerge.restorePlayer(w, PlayerState(coins = 345))
        assertEquals(345, restored.coins)
        assertTrue(SyncOp.PutPlayerState !in SyncPlanner.plan(restored, listOf(mine)))
    }

    @Test
    fun drawingsGoUpAndOtherPlayersDrawingsComeDown() {
        val customer = SpriteAsset("bo-cust", SpriteKind.CHARACTER, "Cat", 100, 200, role = CharacterRole.CUSTOMER)
        val mine = SpriteAsset("my-table", SpriteKind.FURNITURE, "Table", 300, 200)
        val starter = SpriteAsset("starter-x", SpriteKind.FURNITURE, "Starter", 10, 10, isStarter = true)
        val w = opened(vacant(0)).copy(sprites = listOf(mine, starter))

        assertEquals(listOf<SyncOp>(SyncOp.PutSprite("my-table", withPng = true)), SyncPlanner.plan(w, listOf(vacant(0))))
        val sent = StreetSyncer.record(w, StreetSyncer.Pushed(sprites = mapOf("my-table" to SyncCodec.spriteHash(mine))))
        assertEquals(emptyList(), SyncPlanner.plan(sent, listOf(vacant(0))))
        // Editing its details sends them again, without the PNG.
        val edited = sent.copy(sprites = listOf(mine.copy(name = "Big table"), starter))
        assertEquals(listOf<SyncOp>(SyncOp.PutSprite("my-table", withPng = false)), SyncPlanner.plan(edited, listOf(vacant(0))))
        // Deleting it removes it from the street.
        val deleted = sent.copy(sprites = listOf(starter))
        assertEquals(listOf<SyncOp>(SyncOp.DeleteSprite("my-table")), SyncPlanner.plan(deleted, listOf(vacant(0))))

        val r = SyncMerge.merge(sent, ServerStreet(listOf(vacant(0)), listOf(RemoteSprite("bo-cust", bo, SyncCodec.sprite(customer)))))
        assertEquals(listOf("bo-cust"), r.newSprites)
        assertTrue("bo-cust" in r.world.sync.foreignSprites)
        assertTrue(r.world.npcs.any { it.spriteId == "bo-cust" }, "their customers walk this street too")
        assertEquals(emptyList(), SyncPlanner.plan(r.world, listOf(vacant(0))), "other players' drawings aren't sent back")

        // Bo deletes it.
        val gone = SyncMerge.merge(r.world, ServerStreet(listOf(vacant(0)), emptyList())).world
        assertTrue(gone.sprites.none { it.id == "bo-cust" })
        assertTrue(gone.npcs.none { it.spriteId == "bo-cust" })
    }
}
