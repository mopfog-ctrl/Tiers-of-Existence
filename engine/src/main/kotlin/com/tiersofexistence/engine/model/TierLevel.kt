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
        /** Base max Marauders in play per player per Tier; Fate Harvest cards can exceed this (rule #9).
         * Marauders have no separate per-player supply/reserve to cap total count — the Parts List's
         * "4x Marauder tokens per color" is a physical-component count, not an engine resource pool
         * (confirmed by the user; the rulebook never describes a Marauder Ion Battery/draw pile the
         * way it explicitly does for Tier tokens — see rulebook.txt:150-163). A player's Marauder
         * count in play is bounded only by this per-Tier cap (times 4 Tiers) plus however many
         * Fate Harvest cards let them exceed it. */
        const val MARAUDER_MAX_IN_PLAY_PER_TIER_BASE = 1
    }
}
