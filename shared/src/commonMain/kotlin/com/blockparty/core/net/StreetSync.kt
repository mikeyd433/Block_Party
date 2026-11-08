package com.blockparty.core.net

import com.blockparty.core.model.Business
import com.blockparty.core.model.CharacterRole
import com.blockparty.core.model.EconomyState
import com.blockparty.core.model.FurniturePlacement
import com.blockparty.core.model.Lot
import com.blockparty.core.model.LotState
import com.blockparty.core.model.SpriteAsset
import com.blockparty.core.model.WorldState
import com.blockparty.core.npc.NpcGenerator
import com.blockparty.core.sim.Lots
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * How a shared street stays in step with the server.
 *
 * Each device is the authority for its player's own shops and drawings: it sends them up and
 * keeps its local copy when pulling. Everyone else's shops and drawings come from the server.
 * Buying, closing and merging happen locally first (as on a single-player street) and the
 * planner turns the difference from the server into the matching server calls, so the game
 * code doesn't need to know whether it's online.
 */
internal val SyncJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

/** The player's private per-street state, kept on the server so it survives a reinstall. */
@Serializable
data class PlayerState(
    val coins: Long,
    val playerLevel: Int = 0,
    val economy: EconomyState = EconomyState(),
    val storage: List<String> = emptyList(),
)

/** What the server has for a street. */
data class ServerStreet(
    val lots: List<RemoteLot>,
    val sprites: List<RemoteSprite>,
)

/** A call that brings the server in line with the player's own shops and drawings. */
sealed interface SyncOp {
    data class Claim(val lotId: String) : SyncOp
    data class Close(val lotId: String) : SyncOp
    data class Merge(val keepId: String, val otherId: String) : SyncOp
    data class Update(val lotId: String) : SyncOp
    /** [withPng]: the drawing hasn't been uploaded before, so its PNG goes too. */
    data class PutSprite(val spriteId: String, val withPng: Boolean) : SyncOp
    data class DeleteSprite(val spriteId: String) : SyncOp
    data object PutPlayerState : SyncOp
}

object SyncCodec {
    fun business(b: Business?): JsonElement = b?.let { SyncJson.encodeToJsonElement(Business.serializer(), it) } ?: JsonNull
    fun furniture(f: List<FurniturePlacement>): JsonElement =
        SyncJson.encodeToJsonElement(ListSerializer(FurniturePlacement.serializer()), f)
    fun sprite(s: SpriteAsset): JsonElement = SyncJson.encodeToJsonElement(SpriteAsset.serializer(), s)
    fun playerState(w: WorldState): JsonElement =
        SyncJson.encodeToJsonElement(PlayerState.serializer(), PlayerState(w.coins, w.playerLevel, w.economy, w.storage))

    fun decodeBusiness(e: JsonElement?): Business? =
        if (e == null || e is JsonNull) null else runCatching { SyncJson.decodeFromJsonElement(Business.serializer(), e) }.getOrNull()
    fun decodeFurniture(e: JsonElement?): List<FurniturePlacement> =
        if (e == null || e is JsonNull) emptyList()
        else runCatching { SyncJson.decodeFromJsonElement(ListSerializer(FurniturePlacement.serializer()), e) }.getOrDefault(emptyList())
    fun decodeSprite(e: JsonElement): SpriteAsset? = runCatching { SyncJson.decodeFromJsonElement(SpriteAsset.serializer(), e) }.getOrNull()
    fun decodePlayerState(e: JsonElement): PlayerState? =
        runCatching { SyncJson.decodeFromJsonElement(PlayerState.serializer(), e) }.getOrNull()

    /** Changes when anything the server stores about one of the player's shops changes. */
    fun lotHash(lot: Lot): Int =
        (business(lot.business).toString() + furniture(lot.furniture).toString() + lot.ownerName).hashCode()
    fun spriteHash(s: SpriteAsset): Int = sprite(s).toString().hashCode()
    fun playerStateHash(w: WorldState): Int = playerState(w).toString().hashCode()
}

private fun Lot.isMineOn(world: WorldState) = state == LotState.OWNED && ownerId != null && ownerId == world.myUserId

/** Drawings this player made on the street (not starter art, not downloaded from others). */
fun WorldState.ownSprites(): List<SpriteAsset> = sprites.filter { !it.isStarter && it.id !in sync.foreignSprites }

object SyncPlanner {
    /** The calls that bring [server] in line with the player's own shops and drawings in [local]. */
    fun plan(local: WorldState, server: List<RemoteLot>): List<SyncOp> {
        val me = local.myUserId ?: return emptyList()
        val serverById = server.associateBy { it.lotId }
        val localById = local.street.lots.associateBy { it.id }
        val ops = ArrayList<SyncOp>()

        // Shops the player closed (or lost to debt) here, still theirs on the server.
        for (s in server) {
            if (s.ownerId != me) continue
            val l = localById[s.lotId] ?: continue // gone locally: merged into another shop (below)
            if (!l.isMineOn(local)) ops += SyncOp.Close(s.lotId)
        }

        for (l in local.street.lots) {
            if (!l.isMineOn(local)) continue
            val s = serverById[l.id] ?: continue
            when {
                s.state == LotState.VACANT.name -> {
                    ops += SyncOp.Claim(l.id)
                    ops += SyncOp.Update(l.id) // claiming starts with no furniture
                }
                s.ownerId == me -> {
                    // Lots this shop expanded into here: on the server, but gone locally and inside it.
                    val absorbed = server.filter { o ->
                        o.lotId !in localById && o.lotId != l.id && o.rect.intersects(l.rect) &&
                            (o.state == LotState.VACANT.name || o.ownerId == me)
                    }
                    when {
                        absorbed.isNotEmpty() -> ops += SyncOp.Merge(l.id, absorbed.first().lotId)
                        local.sync.pushedLots[l.id] != SyncCodec.lotHash(l) -> ops += SyncOp.Update(l.id)
                    }
                }
                // Owned by someone else: they got it first. The merge hands it back to them.
            }
        }

        val mine = local.ownSprites()
        for (sprite in mine) {
            val pushed = local.sync.pushedSprites[sprite.id]
            if (pushed != SyncCodec.spriteHash(sprite)) ops += SyncOp.PutSprite(sprite.id, withPng = pushed == null)
        }
        val mineIds = mine.map { it.id }.toSet()
        for (id in local.sync.pushedSprites.keys) if (id !in mineIds) ops += SyncOp.DeleteSprite(id)

        if (local.sync.pushedPlayerState != SyncCodec.playerStateHash(local)) ops += SyncOp.PutPlayerState
        return ops
    }
}

/** The local world after taking in the server's copy, and what the device still has to do. */
data class MergeResult(
    val world: WorldState,
    /** Drawings whose PNGs need downloading. */
    val newSprites: List<String>,
    /** Things to tell the player (lost a lot to someone quicker). */
    val notices: List<String>,
)

object SyncMerge {
    fun merge(local: WorldState, server: ServerStreet): MergeResult {
        val me = local.myUserId ?: return MergeResult(local, emptyList(), emptyList())
        val localById = local.street.lots.associateBy { it.id }
        val myLocalLots = local.street.lots.filter { it.isMineOn(local) }
        val notices = ArrayList<String>()
        var coins = local.coins
        val pushedLots = local.sync.pushedLots.toMutableMap()

        val lots = server.lots.mapNotNull { s ->
            val l = localById[s.lotId]
            val mineHere = l != null && l.isMineOn(local)
            // Folded into one of the player's shops here, waiting to be merged on the server.
            val absorbedHere = l == null && (s.state == LotState.VACANT.name || s.ownerId == me) &&
                myLocalLots.any { it.rect.intersects(s.rect) }
            when {
                absorbedHere -> null
                mineHere && (s.ownerId == me || s.state == LotState.VACANT.name) -> l // theirs to send, or a claim on its way
                mineHere -> {
                    // Someone else claimed it first: they keep it, and the player gets their money back.
                    val refund = Lots.price(l!!.copy(state = LotState.VACANT), local)
                    coins += refund
                    notices += "Someone else bought the lot you picked a moment before you did, so you got your $refund coins back."
                    pushedLots.remove(s.lotId)
                    fromServer(s)
                }
                s.ownerId == me && l != null -> l // closed here, the close is on its way
                s.ownerId == me -> {
                    // The player's shop from another device or an earlier install.
                    fromServer(s).also { pushedLots[it.id] = SyncCodec.lotHash(it) }
                }
                else -> fromServer(s)
            }
        }.sortedBy { it.rect.x }

        // Drawings: everyone else's from the server; the player's own come back after a reinstall.
        val localSprites = local.sprites.associateBy { it.id }
        val serverIds = server.sprites.map { it.id }.toSet()
        val foreign = local.sync.foreignSprites.toMutableSet()
        val pushedSprites = local.sync.pushedSprites.toMutableMap()
        val newSprites = ArrayList<String>()
        val sprites = local.sprites.filterNot { it.id in foreign && it.id !in serverIds }.toMutableList()
        for (rs in server.sprites) {
            val asset = SyncCodec.decodeSprite(rs.meta)?.copy(id = rs.id) ?: continue
            val existing = localSprites[rs.id]
            if (rs.ownerId == me) {
                if (existing == null) {
                    sprites += asset
                    newSprites += rs.id
                    pushedSprites[rs.id] = SyncCodec.spriteHash(asset)
                }
                continue
            }
            foreign += rs.id
            if (existing == null) {
                sprites += asset
                newSprites += rs.id
            } else if (existing != asset) {
                val i = sprites.indexOfFirst { it.id == rs.id }
                if (i >= 0) sprites[i] = asset
            }
        }
        foreign.retainAll(serverIds)

        // Customers drawn by anyone join the street's crowd; their profile comes from the drawing id,
        // so every device sees the same names, tastes and budgets.
        val spriteIds = sprites.map { it.id }.toSet()
        val npcs = local.npcs.filter { it.spriteId in spriteIds }.toMutableList()
        val npcSprites = npcs.map { it.spriteId }.toSet()
        for (s in sprites) {
            if (s.role == CharacterRole.CUSTOMER && s.id !in npcSprites) npcs += NpcGenerator.profile("npc-${s.id}", s.id, local.playerLevel)
        }

        val width = (lots.maxOfOrNull { it.rect.right } ?: 0) + 1
        val world = local.copy(
            coins = coins,
            street = local.street.copy(lots = lots, widthTiles = maxOf(width, MIN_WIDTH)),
            sprites = sprites,
            npcs = npcs,
            sync = local.sync.copy(pushedLots = pushedLots, pushedSprites = pushedSprites, foreignSprites = foreign),
        )
        return MergeResult(world, newSprites, notices)
    }

    /** Restores the player's coins, books and storage from the server (a new install). */
    fun restorePlayer(world: WorldState, state: PlayerState): WorldState =
        world.copy(coins = state.coins, playerLevel = state.playerLevel, economy = state.economy, storage = state.storage)
            .let { it.copy(sync = it.sync.copy(pushedPlayerState = SyncCodec.playerStateHash(it))) }

    private fun fromServer(s: RemoteLot): Lot {
        val state = runCatching { LotState.valueOf(s.state) }.getOrDefault(LotState.VACANT)
        return Lot(
            id = s.lotId,
            rect = s.rect,
            state = state,
            business = if (state == LotState.OWNED) SyncCodec.decodeBusiness(s.business) else null,
            furniture = if (state == LotState.OWNED) SyncCodec.decodeFurniture(s.furniture) else emptyList(),
            ownerId = s.ownerId,
            ownerName = s.ownerName,
        )
    }

    private const val MIN_WIDTH = 22
}

/** Reads and writes drawing PNGs on the device. */
interface DrawingFiles {
    suspend fun read(spriteId: String): ByteArray?
    suspend fun write(spriteId: String, png: ByteArray)
}

/**
 * Runs the sync for one shared street: [push] sends what the planner asks for, [pull] fetches the
 * street. The caller applies the results to its world (see GameViewModel).
 */
class StreetSyncer(private val api: StreetsApi, val streetId: String, private val files: DrawingFiles) {
    /** What a push managed to send, to record in the world's [com.blockparty.core.model.SyncState]. */
    data class Pushed(
        val lots: Map<String, Int> = emptyMap(),
        val sprites: Map<String, Int> = emptyMap(),
        val deletedSprites: Set<String> = emptySet(),
        val playerState: Int? = null,
    )

    /**
     * Sends the player's changes. Stops at the first failure and returns what got through, so the
     * rest is tried again next time; the failure is rethrown after recording progress via [onProgress].
     */
    suspend fun push(local: WorldState, server: List<RemoteLot>, onProgress: (Pushed) -> Unit = {}): Pushed {
        var done = Pushed()
        val ops = SyncPlanner.plan(local, server)
        try {
            for (op in ops) {
                when (op) {
                    is SyncOp.Claim -> {
                        val lot = local.lot(op.lotId) ?: continue
                        // False: someone else was quicker; the next pull sorts it out.
                        api.claimLot(streetId, op.lotId, lot.ownerName, SyncCodec.business(lot.business))
                    }
                    is SyncOp.Close -> api.closeLot(streetId, op.lotId)
                    is SyncOp.Merge -> {
                        val lot = local.lot(op.keepId) ?: continue
                        if (api.mergeLots(streetId, op.keepId, op.otherId, lot.rect, SyncCodec.business(lot.business), SyncCodec.furniture(lot.furniture))) {
                            done = done.copy(lots = done.lots + (lot.id to SyncCodec.lotHash(lot)))
                        }
                    }
                    is SyncOp.Update -> {
                        val lot = local.lot(op.lotId) ?: continue
                        api.updateLot(streetId, lot.id, lot.ownerName, SyncCodec.business(lot.business), SyncCodec.furniture(lot.furniture))
                        done = done.copy(lots = done.lots + (lot.id to SyncCodec.lotHash(lot)))
                    }
                    is SyncOp.PutSprite -> {
                        val sprite = local.sprite(op.spriteId) ?: continue
                        if (op.withPng) {
                            val png = files.read(sprite.id) ?: continue // not written to disk yet
                            api.uploadDrawing(streetId, sprite.id, png)
                        }
                        api.putSprite(streetId, sprite.id, SyncCodec.sprite(sprite))
                        done = done.copy(sprites = done.sprites + (sprite.id to SyncCodec.spriteHash(sprite)))
                    }
                    is SyncOp.DeleteSprite -> {
                        api.deleteSprite(streetId, op.spriteId)
                        done = done.copy(deletedSprites = done.deletedSprites + op.spriteId)
                    }
                    SyncOp.PutPlayerState -> {
                        api.putPlayerState(streetId, SyncCodec.playerState(local))
                        done = done.copy(playerState = SyncCodec.playerStateHash(local))
                    }
                }
            }
        } catch (e: BackendException) {
            onProgress(done)
            throw e
        }
        return done
    }

    suspend fun pull(): ServerStreet = ServerStreet(api.lots(streetId), api.sprites(streetId))

    suspend fun playerState(): PlayerState? = api.playerState(streetId)?.let(SyncCodec::decodePlayerState)

    /** Downloads drawings into [files]; returns the ids that arrived. */
    suspend fun download(ids: List<String>): List<String> = ids.filter { id ->
        val png = runCatching { api.downloadDrawing(streetId, id) }.getOrNull() ?: return@filter false
        files.write(id, png)
        true
    }

    companion object {
        /** Records a push in the world's sync state. */
        fun record(world: WorldState, pushed: Pushed): WorldState = world.copy(
            sync = world.sync.copy(
                pushedLots = world.sync.pushedLots + pushed.lots,
                pushedSprites = world.sync.pushedSprites + pushed.sprites - pushed.deletedSprites,
                pushedPlayerState = pushed.playerState ?: world.sync.pushedPlayerState,
            ),
        )
    }
}
