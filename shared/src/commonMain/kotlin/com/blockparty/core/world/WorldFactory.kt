package com.blockparty.core.world

import com.blockparty.core.model.Business
import com.blockparty.core.model.GridRect
import com.blockparty.core.model.Lot
import com.blockparty.core.model.LotState
import com.blockparty.core.model.Street
import com.blockparty.core.model.WorldState
import kotlinx.serialization.json.Json

object WorldFactory {
    const val LOT_WIDTH = 6
    const val LOT_DEPTH = 6

    /**
     * A fresh single-player street: one owned lot in the middle, a vacant lot for sale to
     * the west, and a locked lot to the east that opens up as the street grows.
     */
    fun newWorld(playerName: String = "You"): WorldState {
        val lots = listOf(
            Lot("lot-west", GridRect(1, 0, LOT_WIDTH, LOT_DEPTH), LotState.VACANT),
            Lot(
                id = "lot-home",
                rect = GridRect(1 + LOT_WIDTH + 1, 0, LOT_WIDTH, LOT_DEPTH),
                state = LotState.OWNED,
                business = Business(name = "My Shop"),
            ),
            Lot("lot-east", GridRect(1 + (LOT_WIDTH + 1) * 2, 0, LOT_WIDTH, LOT_DEPTH), LotState.LOCKED),
        )
        val street = Street(
            id = "street-local",
            name = "Main Street",
            widthTiles = 1 + (LOT_WIDTH + 1) * 3,
            lotDepth = LOT_DEPTH,
            lots = lots,
        )
        return WorldState(playerName = playerName, street = street)
    }

    const val HOME_LOT_ID = "lot-home"
}

/** Upgrades older saves to the current format. */
object Migrations {
    fun upgrade(world: WorldState): WorldState {
        var w = world
        if (w.version < 2) {
            // v2: the west lot is for sale from the start (multiple businesses).
            w = w.updateLot("lot-west") { if (it.state == LotState.LOCKED) it.copy(state = LotState.VACANT) else it }
        }
        return w.copy(version = WorldState.CURRENT_VERSION)
    }
}

object WorldCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun encode(world: WorldState): String = json.encodeToString(WorldState.serializer(), world)

    fun decode(text: String): WorldState = Migrations.upgrade(json.decodeFromString(WorldState.serializer(), text))
}
