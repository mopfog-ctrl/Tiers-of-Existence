package com.tiersofexistence.engine.rules

import com.tiersofexistence.engine.model.TierLevel

/**
 * The five Phases of a Round, in Phase Clock order (rulebook "Typical Game Round", p.4-5):
 * Marauder, then 4th Tier down to 1st Tier.
 */
sealed class Phase {
    data object Marauder : Phase()
    data class Tier(val tier: TierLevel) : Phase()

    companion object {
        /** Full Phase order for one Round. Round 1 effectively only reaches [Tier] FIRST, since
         * no player has tokens on higher Tiers or Marauders yet — [TurnOrder] naturally skips
         * phases where nobody has a turn. */
        val ROUND_ORDER: List<Phase> = listOf(
            Marauder,
            Tier(TierLevel.FOURTH),
            Tier(TierLevel.THIRD),
            Tier(TierLevel.SECOND),
            Tier(TierLevel.FIRST),
        )
    }
}
