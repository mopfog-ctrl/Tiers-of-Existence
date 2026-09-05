package com.tiersofexistence.engine.cards.resolvers

import com.tiersofexistence.engine.cards.play.CardLifecycle
import com.tiersofexistence.engine.cards.play.CardPlayRequest
import com.tiersofexistence.engine.cards.play.CardPlayResult
import com.tiersofexistence.engine.cards.play.CardTarget
import com.tiersofexistence.engine.cards.play.TargetValidator
import com.tiersofexistence.engine.rules.TokenKind
import com.tiersofexistence.engine.rules.TurnEngine
import com.tiersofexistence.engine.state.GameState

/**
 * Shared resolver for the family of near-identical "move any token (of any type) forward N
 * spaces; you may not move an opponent's token if it's in the Zone of Protection" cards —
 * Tactical Motion, Tactical Step, Evasive Action, Skip/Hop/and Jump, and Sidestep all share this
 * exact shape, differing only in distance/timing/scope/Precedence (already captured by their own
 * [com.tiersofexistence.engine.cards.FateHarvestCard] catalog entry, not re-derived here). One
 * implementation, not five near-duplicate classes — see `docs/card-mechanics-matrix.md`'s
 * governing principle and §4 Q10.
 *
 * [target] must be a token currently on the main loop ([CardTarget.Token]) — moving a token OUT
 * of a Zone of Protection via a movement card (the rule-12 "own token, own Zone" carve-out) is
 * deliberately not handled here: the rulebook never states what distance/starting point that
 * move would use, flagged in the matrix (§3.3/§4 Q17) as needing user confirmation before
 * building it. [CardTarget]'s own type system already prevents passing a
 * [CardTarget.ZoneResidentToken] here by mistake — moving one out is future work, not a silent
 * no-op of this resolver.
 */
object MovementCardResolver {
    fun resolve(state: GameState, request: CardPlayRequest, target: CardTarget.Token, spaces: Int): CardPlayResult {
        val zoneError = TargetValidator.validateZoneOfProtection(
            request.card,
            request.sourcePlayer,
            target,
            ownTokenMovementAllowed = true,
        )
        if (zoneError != null) return CardPlayResult.Rejected(request, zoneError)

        val playResult = CardLifecycle.attemptPlay(state, request)
        if (playResult !is CardPlayResult.Resolved) return playResult

        when (target.kind) {
            TokenKind.TIER_TOKEN -> TurnEngine.moveTierToken(state, target.owner, target.tier, target.position, spaces)
            TokenKind.MARAUDER -> TurnEngine.moveMarauder(state, target.owner, target.tier, target.position, spaces)
        }
        return playResult
    }
}
