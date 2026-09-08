package com.tiersofexistence.engine.cards.resolvers

import com.tiersofexistence.engine.cards.play.CardLifecycle
import com.tiersofexistence.engine.cards.play.CardPlayRequest
import com.tiersofexistence.engine.cards.play.CardPlayResult
import com.tiersofexistence.engine.cards.play.TargetValidationError
import com.tiersofexistence.engine.state.GameState

/**
 * Delayed Motion: "Add +2 to your die roll. This card must be played after your die roll, but
 * before you move the token." The only card in the deck that modifies a roll rather than a token
 * — needs a genuine engine checkpoint between "roll happened" and "token moved" that nothing else
 * in `TurnEngine` provides, since every other movement-affecting card acts on an already-placed
 * token. See [GameState.pendingRoll]/[com.tiersofexistence.engine.rules.PendingRoll] for that
 * checkpoint: whoever's driving the turn calls [GameState.beginPendingRoll] right after rolling,
 * gives the player a chance to play this card, then reads [com.tiersofexistence.engine.rules
 * .PendingRoll.total] back out to actually move the token.
 *
 * Confirmed by the user, resolving `docs/card-mechanics-matrix.md` §4 Q13: self-only — despite
 * having no Precedence flag (which is what would otherwise let a card respond to *any* player's
 * action) and no restated Your-Turn scope, this can only be played on the source player's own
 * pending roll, never someone else's. Enforced directly against [GameState.pendingRoll]'s own
 * [com.tiersofexistence.engine.rules.PendingRoll.player] rather than trusting the caller to only
 * ever invoke this during the right player's turn.
 */
object DelayedMotionResolver {
    private const val BONUS = 2

    fun resolve(state: GameState, request: CardPlayRequest): CardPlayResult {
        val pendingRoll = state.pendingRoll
        if (pendingRoll == null || pendingRoll.player != request.sourcePlayer) {
            return CardPlayResult.Rejected(
                request,
                TargetValidationError.CardSpecificRestriction(
                    "Delayed Motion can only be played on your own pending roll, after rolling and before moving the token",
                ),
            )
        }

        val playResult = CardLifecycle.attemptPlay(state, request)
        if (playResult !is CardPlayResult.Resolved) return playResult

        pendingRoll.addBonus(BONUS)
        return playResult
    }
}
