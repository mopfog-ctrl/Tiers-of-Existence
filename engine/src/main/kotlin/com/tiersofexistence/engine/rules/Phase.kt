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
         * Full Phase order for one Round. [TurnOrder.turnsFor] returns an empty list for a
         * Phase where nobody has a turn, but nothing yet acts on that: `GameState.advancePhase`
         * steps through [ROUND_ORDER] one entry at a time regardless, so turn-resolution logic
         * must itself skip empty Phases and, per the rulebook ("the first Round of the game
         * only has a 1st Tier Phase. When the 1st Tier Phase ends, the Round is over," Rounds/
         * Phases/Turns p.4), end a Round early when the 1st Tier Phase closes with the higher
         * Phases having had zero eligible players. Not yet implemented — a gap to close before
         * relying on this for real gameplay, not something the current code already handles.
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
