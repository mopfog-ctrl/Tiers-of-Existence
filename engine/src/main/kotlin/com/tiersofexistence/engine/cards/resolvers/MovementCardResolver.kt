package com.tiersofexistence.engine.cards.resolvers

import com.tiersofexistence.engine.cards.play.CardLifecycle
import com.tiersofexistence.engine.cards.play.CardPlayRequest
import com.tiersofexistence.engine.cards.play.CardPlayResult
import com.tiersofexistence.engine.cards.play.CardTarget
import com.tiersofexistence.engine.cards.play.TargetValidationError
import com.tiersofexistence.engine.cards.play.TargetValidator
import com.tiersofexistence.engine.cards.play.TokenLocation
import com.tiersofexistence.engine.cards.play.TokenLocator
import com.tiersofexistence.engine.model.TokenKind
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
 * [target] identifies a token by [com.tiersofexistence.engine.state.TokenId] — this resolver
 * looks up where it actually is via [TokenLocator] at resolution time, never trusting a position
 * recorded when the target was chosen (the fix for the stale-target bug documented in
 * `docs/card-mechanics-matrix.md` and exercised in `PrecedenceCardEffectIntegrationTest`). If the
 * token no longer exists by resolution time, this rejects gracefully rather than crashing.
 * Moving a token OUT of a Zone of Protection (the rule-12 "own token, own Zone" carve-out) is
 * still deliberately not implemented — the rulebook never states what distance/starting point
 * that move would use (§4 Q17) — so a target that resolves to a Zone gets an honest
 * "not implemented" rejection distinct from an actual Zone-of-Protection block.
 */
object MovementCardResolver {
    fun resolve(state: GameState, request: CardPlayRequest, target: CardTarget.Token, spaces: Int): CardPlayResult {
        val location = TokenLocator.locate(state, target.id)
        if (location is TokenLocation.NoLongerExists) {
            return CardPlayResult.Rejected(request, TargetValidationError.NoLegalTarget("${target.id} no longer exists"))
        }

        val zoneError = TargetValidator.validateZoneOfProtection(
            request.card,
            request.sourcePlayer,
            target.id.owner,
            location,
            ownTokenMovementAllowed = true,
        )
        if (zoneError != null) return CardPlayResult.Rejected(request, zoneError)

        if (location is TokenLocation.InZone) {
            // Legal per rule 12 (this is the player's own token in their own Zone, or the ZoP
            // check above would already have rejected it) but moving a token OUT of a Zone isn't
            // implemented yet — see the class doc. Honest about the gap rather than silently
            // no-op'ing or crashing.
            return CardPlayResult.Rejected(
                request,
                TargetValidationError.CardSpecificRestriction(
                    "Moving a token out of a Zone of Protection is not yet implemented (see docs/card-mechanics-matrix.md §4 Q17)",
                ),
            )
        }
        val fromPosition = (location as TokenLocation.InPlay).position

        val playResult = CardLifecycle.attemptPlay(state, request)
        if (playResult !is CardPlayResult.Resolved) return playResult

        when (target.id.kind) {
            TokenKind.TIER_TOKEN -> TurnEngine.moveTierToken(state, target.id.owner, target.id.tier, fromPosition, spaces)
            TokenKind.MARAUDER -> TurnEngine.moveMarauder(state, target.id.owner, target.id.tier, fromPosition, spaces)
        }
        return playResult
    }
}
