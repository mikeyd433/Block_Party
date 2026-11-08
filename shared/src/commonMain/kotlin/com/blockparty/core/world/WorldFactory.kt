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
     * A fresh single-player street: one owned lot in the middle and locked
     * neighbours that later progression will open up.
     */
    fun newWorld(playerName: String = "You"): WorldState {
        val lots = listOf(
            Lot("lot-west", GridRect(1, 0, LOT_WIDTH, LOT_DEPTH), LotState.LOCKED),
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

object WorldCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun encode(world: WorldState): String = json.encodeToString(WorldState.serializer(), world)

    fun decode(text: String): WorldState = json.decodeFromString(WorldState.serializer(), text)
}
