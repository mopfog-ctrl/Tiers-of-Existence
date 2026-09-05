package com.tiersofexistence.engine.state

import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.model.TokenKind

/** One in-play Marauder's stable identity paired with its current main-loop position. */
private data class MarauderSlot(val id: TokenId, val position: Int)

/**
 * One player's Marauder tokens, shared across all four Tiers (Parts List: "4x Marauder
 * tokens per color"). Unlike Tier tokens, the rulebook never mentions a Hatchery for
 * Marauders — the per-Tier cap is enforced at the point a Marauder is placed instead.
 * [owner] is needed to mint each Marauder's stable [TokenId], the same identity model
 * [TierTokenPool] uses — a Marauder can never be a Zone resident, so unlike Tier tokens it
 * only ever needs one position list, never a second Zone-residence one.
 */
class MarauderPool(val owner: PlayerColor) {
    var ionBattery: Int = TierLevel.MARAUDER_TOKENS_PER_PLAYER
        private set

    private val inPlay: MutableMap<TierLevel, MutableList<MarauderSlot>> =
        TierLevel.entries.associateWith { mutableListOf<MarauderSlot>() }.toMutableMap()

    fun inPlayCount(tier: TierLevel): Int = inPlay.getValue(tier).size

    fun positions(tier: TierLevel): List<Int> = inPlay.getValue(tier).map { it.position }

    /** The [TokenId] of the Marauder at [position] on [tier], or null if none is there. */
    fun idAt(tier: TierLevel, position: Int): TokenId? = inPlay.getValue(tier).firstOrNull { it.position == position }?.id

    /** [id]'s current position on its own Tier, or null if it's no longer in play. */
    fun positionOf(id: TokenId): Int? = inPlay.getValue(id.tier).firstOrNull { it.id == id }?.position

    /**
     * Places a Marauder on [tier]'s Birth Canal. By default this enforces "only one Marauder
     * token is allowed per Tier per player" (Gameboard Rules); pass [bypassCap] = true when
     * a Fate Harvest card is explicitly adding an extra one (rule #9: "this limit does not
     * apply to Marauders added by Fate Harvest cards"). Returns the new Marauder's [TokenId].
     */
    fun placeOnBirthCanal(tier: TierLevel, bypassCap: Boolean = false): TokenId {
        require(bypassCap || inPlayCount(tier) < TierLevel.MARAUDER_MAX_IN_PLAY_PER_TIER_BASE) {
            "$tier already has a Marauder in play for this player"
        }
        require(ionBattery > 0) { "No Marauders left in Ion Battery" }
        ionBattery -= 1
        val id = TokenIdGenerator.next(owner, TokenKind.MARAUDER, tier)
        inPlay.getValue(tier) += MarauderSlot(id, 0)
        return id
    }

    /** Marauder destroyed (by an Abyss, Infernal Abyss, or a card) — returns it to the Ion Battery. */
    fun destroy(tier: TierLevel, position: Int) {
        val slot = inPlay.getValue(tier).firstOrNull { it.position == position }
        require(slot != null) { "No Marauder at position $position on $tier" }
        inPlay.getValue(tier).remove(slot)
        ionBattery += 1
    }

    /** Destroys the Marauder identified by [id]. Callers are expected to have already confirmed
     * [id] still exists (e.g. via [com.tiersofexistence.engine.cards.play.TokenLocator]) — this
     * throws if it doesn't, as an internal-consistency check, not a player-facing legality check. */
    fun destroyById(id: TokenId) {
        val slot = inPlay.getValue(id.tier).firstOrNull { it.id == id }
        require(slot != null) { "Marauder $id no longer exists" }
        inPlay.getValue(id.tier).remove(slot)
        ionBattery += 1
    }

    /** A Marauder Transport moves the Marauder to a neighboring Tier's Birth Canal. Its
     * [TokenId] is retired and a fresh one minted on the destination Tier, since [TokenId]
     * carries the Tier as part of identity (a Marauder's Tier is one of the things that makes it
     * distinguishable) — nothing in the rulebook or this engine treats a Marauder's identity as
     * surviving a Tier change, and no card currently needs it to. */
    fun moveToNeighboringTier(fromTier: TierLevel, toTier: TierLevel, position: Int): TokenId {
        require(toTier == fromTier.next() || toTier == fromTier.previous()) {
            "$toTier is not adjacent to $fromTier"
        }
        val slot = inPlay.getValue(fromTier).firstOrNull { it.position == position }
        require(slot != null) { "No Marauder at position $position on $fromTier" }
        inPlay.getValue(fromTier).remove(slot)
        val newId = TokenIdGenerator.next(owner, TokenKind.MARAUDER, toTier)
        inPlay.getValue(toTier) += MarauderSlot(newId, 0)
        return newId
    }

    fun move(tier: TierLevel, fromPosition: Int, toPosition: Int) {
        val slot = inPlay.getValue(tier).firstOrNull { it.position == fromPosition }
        require(slot != null) { "No Marauder at position $fromPosition on $tier" }
        inPlay.getValue(tier).remove(slot)
        inPlay.getValue(tier) += MarauderSlot(slot.id, toPosition)
    }
}
