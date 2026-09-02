package com.tiersofexistence.engine.state

import com.tiersofexistence.engine.cards.FateHarvestCard
import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.TierLevel

/** Everything owned by one player: their Tier token pools, Marauder pool, and held cards. */
class PlayerState(val color: PlayerColor) {
    val tierPools: Map<TierLevel, TierTokenPool> =
        TierLevel.entries.associateWith { TierTokenPool(it) }

    val marauders: MarauderPool = MarauderPool()

    /** Cards drawn this player is holding (timing = HELD) rather than having played immediately. */
    val hand: MutableList<FateHarvestCard> = mutableListOf()

    /** Whether this player has already played a Fate Harvest card this Phase (rule #4). */
    var hasPlayedCardThisPhase: Boolean = false

    fun tierPool(tier: TierLevel): TierTokenPool = tierPools.getValue(tier)

    /** Whether this player has any token in play on [tier] — determines whether they get a turn (rulebook p.4). */
    fun hasTierTurn(tier: TierLevel): Boolean = tierPool(tier).inPlayCount > 0

    /** Whether this player has any Marauder in play on any Tier — determines a Marauder Phase turn. */
    fun hasMarauderTurn(): Boolean = TierLevel.entries.any { marauders.inPlayCount(it) > 0 }
}
