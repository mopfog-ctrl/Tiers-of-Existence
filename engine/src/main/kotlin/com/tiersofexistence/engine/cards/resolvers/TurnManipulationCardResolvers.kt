package com.tiersofexistence.engine.cards.resolvers

import com.tiersofexistence.engine.cards.play.CardLifecycle
import com.tiersofexistence.engine.cards.play.CardPlayRequest
import com.tiersofexistence.engine.cards.play.CardPlayResult
import com.tiersofexistence.engine.cards.play.TriggeringEvent
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.state.GameState

/**
 * Phase Loss: the drawing player loses their next turn on the Tier whose Fate Harvest square
 * they drew it from — never a Tier of their choosing, and never affecting the turn currently in
 * progress (see [GameState.queueSkipNextTierTurn]'s doc). Since this card is Immediate, it can
 * only ever be resolved via [CardLifecycle.onDrawnFromSquare], so [request]'s
 * [TriggeringEvent.DrawnFromSquare] is where the affected Tier comes from — there is no
 * "chosen" target for this card.
 */
object PhaseLossResolver {
    fun resolve(state: GameState, request: CardPlayRequest): CardPlayResult {
        val triggeringEvent = request.triggeringEvent
        require(triggeringEvent is TriggeringEvent.DrawnFromSquare) {
            "Phase Loss must be resolved as part of drawing it from a square, was $triggeringEvent"
        }

        val playResult = CardLifecycle.attemptPlay(state, request)
        if (playResult !is CardPlayResult.Resolved) return playResult

        state.queueSkipNextTierTurn(request.sourcePlayer, triggeringEvent.tier)
        return playResult
    }
}

/**
 * Phase Control: the player chooses any one of the four Tiers and gets an extra turn there,
 * taken after their normal turn on that Tier. Delegates entirely to
 * [GameState.queueExtraTierTurn], which already implements the "hasn't happened yet this Round"
 * splice case — see that method's doc for why the "already ended this Round, play it
 * immediately" case is deliberately NOT a true interrupt here (`docs/card-mechanics-matrix.md`
 * §4 Q15).
 */
object PhaseControlResolver {
    fun resolve(state: GameState, request: CardPlayRequest, tier: TierLevel): CardPlayResult {
        val playResult = CardLifecycle.attemptPlay(state, request)
        if (playResult !is CardPlayResult.Resolved) return playResult

        state.queueExtraTierTurn(request.sourcePlayer, tier)
        return playResult
    }
}
