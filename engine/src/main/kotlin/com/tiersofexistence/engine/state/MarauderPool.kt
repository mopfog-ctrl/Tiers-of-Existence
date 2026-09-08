package com.tiersofexistence.engine.state

import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.model.TokenKind

/** One in-play Marauder's stable identity paired with its current main-loop position. */
private data class MarauderSlot(val id: TokenId, val position: Int)

/**
 * One player's Marauder tokens, shared across all four Tiers. Unlike Tier tokens, Marauders
 * have no Ion Battery/draw-pile resource — the rulebook only ever describes an Ion Battery for
 * Tier tokens (rulebook.txt:150-163: "The Ion Battery is your draw pile... Matter is neither
 * destroyed nor created... The same applies to Marauder tokens" is itself about *Tier* tokens'
 * wait-for-a-slot behavior, never a Marauder-specific reserve). The Parts List's "4x Marauder
 * tokens per color" is a physical-component count for the physical game, not an engine resource
 * pool (confirmed by the user) — a Marauder simply comes into existence when a legal
 * rule/board-event/card effect spawns one, and simply ceases to exist when destroyed; nothing
 * is drawn from or returned to anywhere. The only limits on how many a player can have are the
 * per-Tier cap ([placeOnBirthCanal]'s [bypassCap]) and, transitively, however many cards in the
 * deck can spawn one — never a global reserve this pool tracks.
 *
 * The rulebook never mentions a Hatchery for Marauders either — the per-Tier cap is enforced at
 * the point a Marauder is placed instead. [owner] is needed to mint each Marauder's stable
 * [TokenId], the same identity model [TierTokenPool] uses — a Marauder can never be a Zone
 * resident, so unlike Tier tokens it only ever needs one position list, never a second
 * Zone-residence one.
 */
class MarauderPool(val owner: PlayerColor) {
    private val inPlay: MutableMap<TierLevel, MutableList<MarauderSlot>> =
        TierLevel.entries.associateWith { mutableListOf<MarauderSlot>() }.toMutableMap()

    fun inPlayCount(tier: TierLevel): Int = inPlay.getValue(tier).size

    fun positions(tier: TierLevel): List<Int> = inPlay.getValue(tier).map { it.position }

    /** [TokenId]s of every Marauder in play on [tier], in no particular guaranteed order — one
     * entry per Marauder, including stacked duplicates at the same position. See
     * [TierTokenPool.inPlayIds] for why this exists (Galactic Roundabout's whole-board sweep). */
    fun inPlayIds(tier: TierLevel): List<TokenId> = inPlay.getValue(tier).map { it.id }

    /** The [TokenId] of the Marauder at [position] on [tier], or null if none is there. */
    fun idAt(tier: TierLevel, position: Int): TokenId? = inPlay.getValue(tier).firstOrNull { it.position == position }?.id

    /** [id]'s current position on its own Tier, or null if it's no longer in play. */
    fun positionOf(id: TokenId): Int? = inPlay.getValue(id.tier).firstOrNull { it.id == id }?.position

    /**
     * Spawns a new Marauder on [tier]'s Birth Canal. By default this enforces "only one Marauder
     * token is allowed per Tier per player" (Gameboard Rules); pass [bypassCap] = true when
     * a Fate Harvest card is explicitly adding an extra one (rule #9: "this limit does not
     * apply to Marauders added by Fate Harvest cards"). Returns the new Marauder's [TokenId].
     * No resource is consumed — see the class doc; a legal spawn can never fail merely because
     * some number of Marauders already exist, only because the per-Tier cap blocks it.
     */
    fun placeOnBirthCanal(tier: TierLevel, bypassCap: Boolean = false): TokenId {
        require(bypassCap || inPlayCount(tier) < TierLevel.MARAUDER_MAX_IN_PLAY_PER_TIER_BASE) {
            "$tier already has a Marauder in play for this player"
        }
        val id = TokenIdGenerator.next(owner, TokenKind.MARAUDER, tier)
        inPlay.getValue(tier) += MarauderSlot(id, 0)
        return id
    }

    /** Marauder destroyed (by an Abyss, Infernal Abyss, or a card) — simply ceases to exist; see
     * the class doc for why nothing is returned anywhere. */
    fun destroy(tier: TierLevel, position: Int) {
        val slot = inPlay.getValue(tier).firstOrNull { it.position == position }
        require(slot != null) { "No Marauder at position $position on $tier" }
        inPlay.getValue(tier).remove(slot)
    }

    /** Destroys the Marauder identified by [id]. Callers are expected to have already confirmed
     * [id] still exists (e.g. via [com.tiersofexistence.engine.cards.play.TokenLocator]) — this
     * throws if it doesn't, as an internal-consistency check, not a player-facing legality check. */
    fun destroyById(id: TokenId) {
        val slot = inPlay.getValue(id.tier).firstOrNull { it.id == id }
        require(slot != null) { "Marauder $id no longer exists" }
        inPlay.getValue(id.tier).remove(slot)
    }

    /** Destroys every Marauder currently in play on [tier] — used by whole-Tier-wipe effects
     * like Fluidic Wave. A Marauder can never be a Zone of Protection resident (Tier tokens
     * only), so unlike [TierTokenPool.destroyAllInPlayAndStagingPile] there's no exclusion to
     * apply here. */
    fun destroyAllInPlay(tier: TierLevel) {
        inPlay.getValue(tier).clear()
    }

    /** Destroys every Marauder currently at [position] on [tier] in one sweep — stacking is
     * legal, same as [TierTokenPool.destroyAllAt]. Used by whole-square-sweep effects like
     * Plasma Burst. A no-op if nothing is there. */
    fun destroyAllAt(tier: TierLevel, position: Int) {
        inPlay.getValue(tier).removeAll { it.position == position }
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

    /** Moves the Marauder [id] to [toPosition] — identity-based, so it's unambiguous even when
     * another Marauder already shares [id]'s starting position. See
     * [TierTokenPool.moveById] for why this exists. */
    fun moveById(id: TokenId, toPosition: Int) {
        val slot = inPlay.getValue(id.tier).firstOrNull { it.id == id }
        require(slot != null) { "Marauder $id no longer exists" }
        inPlay.getValue(id.tier).remove(slot)
        inPlay.getValue(id.tier) += MarauderSlot(id, toPosition)
    }
}
