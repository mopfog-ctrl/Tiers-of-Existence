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
        /**
         * Full Phase order for one Round. 1st Tier is always last, so "the first Round of the
         * game only has a 1st Tier Phase. When the 1st Tier Phase ends, the Round is over"
         * (Rounds/Phases/Turns p.4) needs no special-casing: [TurnOrder.turnsFor] already
         * returns an empty list for a Phase with no eligible players (Marauder/4th/3rd/2nd in
         * Round 1), and `GameState.advancePhase` walking every entry in [ROUND_ORDER] once per
         * Round produces the right outcome on its own — a turn-resolution loop just needs to
         * call it once per Phase regardless of whether that Phase had any turns.
         */
        val ROUND_ORDER: List<Phase> = listOf(
            Marauder,
            Tier(TierLevel.FOURTH),
            Tier(TierLevel.THIRD),
            Tier(TierLevel.SECOND),
            Tier(TierLevel.FIRST),
        )
    }
}
