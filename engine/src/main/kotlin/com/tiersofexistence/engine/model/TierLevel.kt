package com.tiersofexistence.engine.model

/**
 * The four game boards ("dimensions"). Values below are taken directly from the
 * rulebook's Gameboard Rules and Parts List sections.
 *
 * @param maxInPlay max Tier tokens a player may have on the board at once for this
 *   Tier; extras wait in the [state] Hatchery.
 * @param stagingPileThreshold tokens needed in this Tier's Nebula Staging Pile
 *   before they're returned to the Ion Battery and a token is started on the next
 *   Tier. Null for the 4th Tier, which has no promotion (it's the win condition).
 * @param tokensPerPlayer total physical Tier tokens each player owns for this Tier.
 */
enum class TierLevel(
    val number: Int,
    val maxInPlay: Int,
    val stagingPileThreshold: Int?,
    val tokensPerPlayer: Int,
) {
    FIRST(number = 1, maxInPlay = 2, stagingPileThreshold = 4, tokensPerPlayer = 8),
    SECOND(number = 2, maxInPlay = 2, stagingPileThreshold = 3, tokensPerPlayer = 6),
    THIRD(number = 3, maxInPlay = 2, stagingPileThreshold = 2, tokensPerPlayer = 4),
    FOURTH(number = 4, maxInPlay = 1, stagingPileThreshold = null, tokensPerPlayer = 2),
    ;

    /** The next higher Tier that a promoted token is started on, or null from the 4th Tier. */
    fun next(): TierLevel? = entries.firstOrNull { it.number == number + 1 }

    /** The next lower Tier, used by Marauder Transports. */
    fun previous(): TierLevel? = entries.firstOrNull { it.number == number - 1 }

    companion object {
        /**
         * Total Marauder tokens each player owns across all Tiers combined (Parts List:
         * "4x Marauder tokens per color"). This matches "only one Marauder token allowed
         * per Tier per player" (Gameboard Rules) times 4 Tiers.
         */
        const val MARAUDER_TOKENS_PER_PLAYER = 4

        /** Base max Marauders in play per player per Tier; Fate Harvest cards can exceed this (rule #9). */
        const val MARAUDER_MAX_IN_PLAY_PER_TIER_BASE = 1
    }
}
