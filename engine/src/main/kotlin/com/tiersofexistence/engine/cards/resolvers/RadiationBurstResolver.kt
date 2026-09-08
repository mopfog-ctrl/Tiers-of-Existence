package com.tiersofexistence.engine.cards.resolvers

import com.tiersofexistence.engine.cards.play.CardLifecycle
import com.tiersofexistence.engine.cards.play.CardPlayRequest
import com.tiersofexistence.engine.cards.play.CardPlayResult
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.state.GameState

/**
 * Radiation Burst: "All Staging Piles are emptied." No color restriction, no target to choose.
 * Confirmed with the user: "all" means every player's Staging Pile across all four Tiers, not
 * just the playing player's own — and emptying one this way never triggers that Tier's normal
 * promotion, even if a pile happened to be at or above its threshold when emptied (see
 * [com.tiersofexistence.engine.state.TierTokenPool.emptyStagingPile]).
 */
object RadiationBurstResolver {
    fun resolve(state: GameState, request: CardPlayRequest): CardPlayResult {
        val playResult = CardLifecycle.attemptPlay(state, request)
        if (playResult !is CardPlayResult.Resolved) return playResult

        state.players.values.forEach { player ->
            TierLevel.entries.forEach { tier -> player.tierPool(tier).emptyStagingPile() }
        }
        return playResult
    }
}
