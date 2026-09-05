package com.tiersofexistence.engine.cards.resolvers

import com.tiersofexistence.engine.cards.play.CardLifecycle
import com.tiersofexistence.engine.cards.play.CardPlayRequest
import com.tiersofexistence.engine.cards.play.CardPlayResult
import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.state.GameState

/**
 * Shared resolver for cards that place a Marauder on a Tier's Birth Canal, bypassing the
 * 1-per-Tier cap (rule 9: "this limit does not apply to Marauders added by Fate Harvest
 * cards") — Dwarf Star, Materialize Army, Essence Assimilator, Materialize Help. [tier] is
 * fixed per-card (4th/any/1st/3rd respectively) or player-chosen (Materialize Army) — either
 * way, the caller resolves which Tier before calling this; it isn't parsed from [request] here.
 */
object MarauderConstructionCardResolver {
    fun resolve(state: GameState, request: CardPlayRequest, player: PlayerColor, tier: TierLevel): CardPlayResult {
        val playResult = CardLifecycle.attemptPlay(state, request)
        if (playResult !is CardPlayResult.Resolved) return playResult
        state.players.getValue(player).marauders.placeOnBirthCanal(tier, bypassCap = true)
        return playResult
    }
}

/**
 * Shared resolver for cards that start a fresh Tier token directly on one or more Birth Canals
 * — Verdant Growth (1st/2nd/3rd Tier at once), Elemental Rebirth (1st Tier only). Each Tier's
 * own Ion Battery/Hatchery capacity is checked independently via
 * [com.tiersofexistence.engine.state.TierTokenPool.startToken] — one Tier being out of tokens
 * doesn't block a construct on another Tier in the same card (see
 * `docs/card-mechanics-matrix.md`'s Verdant Growth entry for this edge case, low-priority and
 * not expected to trigger under normal play).
 */
object BirthCanalConstructionCardResolver {
    fun resolve(state: GameState, request: CardPlayRequest, player: PlayerColor, tiers: List<TierLevel>): CardPlayResult {
        val playResult = CardLifecycle.attemptPlay(state, request)
        if (playResult !is CardPlayResult.Resolved) return playResult
        val pools = state.players.getValue(player)
        tiers.forEach { pools.tierPool(it).startToken() }
        return playResult
    }
}

/**
 * Shared resolver for cards that add a token directly to a Staging Pile (not via a Nebula
 * landing) — Lucky Nebula (1st Tier), Luckier Nebula (2nd Tier), Emitting Nebula (1st Tier).
 * Runs the same promotion-threshold check
 * ([com.tiersofexistence.engine.state.TierTokenPool.addToStagingPileDirectly]) a Nebula landing
 * would, including starting the next Tier's token if the pile was already one short of
 * threshold. Emitting Nebula's own additional "only during the 1st Tier Phase" restriction is
 * NOT enforced here — that needs the `PhaseRestriction` concept flagged in
 * `docs/card-mechanics-matrix.md` §4 Q7, which doesn't exist yet; this resolver alone is not a
 * complete implementation of that card.
 */
object StagingPileConstructionCardResolver {
    fun resolve(state: GameState, request: CardPlayRequest, player: PlayerColor, tier: TierLevel): CardPlayResult {
        val playResult = CardLifecycle.attemptPlay(state, request)
        if (playResult !is CardPlayResult.Resolved) return playResult
        val pool = state.players.getValue(player).tierPool(tier)
        val promoted = pool.addToStagingPileDirectly()
        if (promoted) tier.next()?.let { state.players.getValue(player).tierPool(it).startToken() }
        return playResult
    }
}
