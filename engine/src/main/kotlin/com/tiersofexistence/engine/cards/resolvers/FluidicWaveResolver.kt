package com.tiersofexistence.engine.cards.resolvers

import com.tiersofexistence.engine.cards.play.CardLifecycle
import com.tiersofexistence.engine.cards.play.CardPlayRequest
import com.tiersofexistence.engine.cards.play.CardPlayResult
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.state.GameState

/**
 * Fluidic Wave: "Blue player removes all tokens from the 1st Tier. This includes tokens in play
 * as well as tokens in Staging Piles, but does not include Tier tokens in the Zone of
 * Protection." An unconditional wipe of the entire 1st Tier — every player's in-play tokens and
 * Staging Pile contents, Tier tokens and Marauders alike (the card names no token type, so rule
 * 11's "all tokens" default applies) — not a Blue-only or opponent-only effect despite being
 * restricted to the Blue player to *play*. There is no target to choose (`docs/
 * card-mechanics-matrix.md` §7: "Legal targets: None to choose").
 *
 * A player's Ion Battery reserves are deliberately untouched — confirmed with the user as going
 * no further than the card's own printed wording ("in play" and "Staging Piles" only); a token
 * already sitting in reserve was never "on the 1st Tier" to begin with. Zone-of-Protection
 * residents are the one explicit carve-out the card's own text states, distinct from (though
 * consistent with) rule 12's default protection.
 */
object FluidicWaveResolver {
    fun resolve(state: GameState, request: CardPlayRequest): CardPlayResult {
        val playResult = CardLifecycle.attemptPlay(state, request)
        if (playResult !is CardPlayResult.Resolved) return playResult

        state.players.values.forEach { player ->
            player.tierPool(TierLevel.FIRST).destroyAllInPlayAndStagingPile()
            player.marauders.destroyAllInPlay(TierLevel.FIRST)
        }
        return playResult
    }
}
