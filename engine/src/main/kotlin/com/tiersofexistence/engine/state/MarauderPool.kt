package com.tiersofexistence.engine.state

import com.tiersofexistence.engine.model.TierLevel

/**
 * One player's Marauder tokens, shared across all four Tiers (Parts List: "4x Marauder
 * tokens per color"). Unlike Tier tokens, the rulebook never mentions a Hatchery for
 * Marauders — the per-Tier cap is enforced at the point a Marauder is placed instead.
 */
class MarauderPool {
    var ionBattery: Int = TierLevel.MARAUDER_TOKENS_PER_PLAYER
        private set

    private val inPlay: MutableMap<TierLevel, MutableList<Int>> =
        TierLevel.entries.associateWith { mutableListOf<Int>() }.toMutableMap()

    fun inPlayCount(tier: TierLevel): Int = inPlay.getValue(tier).size

    fun positions(tier: TierLevel): List<Int> = inPlay.getValue(tier).toList()

    /**
     * Places a Marauder on [tier]'s Birth Canal. By default this enforces "only one Marauder
     * token is allowed per Tier per player" (Gameboard Rules); pass [bypassCap] = true when
     * a Fate Harvest card is explicitly adding an extra one (rule #9: "this limit does not
     * apply to Marauders added by Fate Harvest cards").
     */
    fun placeOnBirthCanal(tier: TierLevel, bypassCap: Boolean = false) {
        require(bypassCap || inPlayCount(tier) < TierLevel.MARAUDER_MAX_IN_PLAY_PER_TIER_BASE) {
            "$tier already has a Marauder in play for this player"
        }
        require(ionBattery > 0) { "No Marauders left in Ion Battery" }
        ionBattery -= 1
        inPlay.getValue(tier) += 0
    }

    /** Marauder destroyed (by an Abyss, Infernal Abyss, or a card) — returns it to the Ion Battery. */
    fun destroy(tier: TierLevel, position: Int) {
        require(inPlay.getValue(tier).remove(position)) { "No Marauder at position $position on $tier" }
        ionBattery += 1
    }

    /** A Marauder Transport moves the Marauder to a neighboring Tier's Birth Canal. */
    fun moveToNeighboringTier(fromTier: TierLevel, toTier: TierLevel, position: Int) {
        require(toTier == fromTier.next() || toTier == fromTier.previous()) {
            "$toTier is not adjacent to $fromTier"
        }
        require(inPlay.getValue(fromTier).remove(position)) { "No Marauder at position $position on $fromTier" }
        inPlay.getValue(toTier) += 0
    }

    fun move(tier: TierLevel, fromPosition: Int, toPosition: Int) {
        require(inPlay.getValue(tier).remove(fromPosition)) { "No Marauder at position $fromPosition on $tier" }
        inPlay.getValue(tier) += toPosition
    }
}
