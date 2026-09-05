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
 * Parallel Phasing: "Move any one of your tokens (of any type) forward four spaces and move any
 * other player's token forward four spaces. You may not move an opponent's token if it's in the
 * Zone of Protection." Two independent targets in one resolution, unlike every other movement
 * card ([MovementCardResolver], one target) — see `docs/card-mechanics-matrix.md` §8.
 *
 * [ownTarget] must belong to [CardPlayRequest.sourcePlayer]; [opponentTarget] must belong to
 * someone else — "any OTHER player's token" is not optional, unlike the single-target movement
 * cards, which place no ownership constraint on their one target. Both targets are re-located via
 * [TokenLocator] and fully validated (existence, ownership, Zone of Protection) before either is
 * moved, matching [GravitonRiftResolver]'s all-or-nothing pattern for compound effects — the
 * rulebook's own "cancels both moves as one unit" Annulment framing implies this is one atomic
 * play, not two independent half-plays that could partially succeed.
 *
 * The card's own text draws the Zone-of-Protection line differently per side: the owner's own
 * token gets rule 12's "your own movement card on your own token" carve-out (though, same as
 * [MovementCardResolver], actually moving a Zone-resident token back out isn't implemented, so
 * that target still gets an honest "not yet implemented" rejection rather than a ZoP block); the
 * opponent's token gets no carve-out at all — Parallel Phasing is not one of the 5 named rule-12
 * exceptions, so a Zone-resident opponent token is simply illegal to target, full stop.
 */
object ParallelPhasingResolver {
    fun resolve(state: GameState, request: CardPlayRequest, ownTarget: CardTarget.Token, opponentTarget: CardTarget.Token): CardPlayResult {
        if (ownTarget.id.owner != request.sourcePlayer) {
            return CardPlayResult.Rejected(
                request,
                TargetValidationError.WrongTokenType("Parallel Phasing's first target must be your own token, not ${ownTarget.id.owner}'s"),
            )
        }
        if (opponentTarget.id.owner == request.sourcePlayer) {
            return CardPlayResult.Rejected(
                request,
                TargetValidationError.WrongTokenType("Parallel Phasing's second target must belong to another player, not your own"),
            )
        }

        val ownCheck = validateMovable(state, request, ownTarget, ownTokenMovementAllowed = true)
        if (ownCheck is MovableCheck.Blocked) return ownCheck.result
        val opponentCheck = validateMovable(state, request, opponentTarget, ownTokenMovementAllowed = false)
        if (opponentCheck is MovableCheck.Blocked) return opponentCheck.result

        val playResult = CardLifecycle.attemptPlay(state, request)
        if (playResult !is CardPlayResult.Resolved) return playResult

        move(state, ownTarget, (ownCheck as MovableCheck.Movable).fromPosition)
        move(state, opponentTarget, (opponentCheck as MovableCheck.Movable).fromPosition)
        return playResult
    }

    /** The outcome of checking whether one target can legally be moved — either its current
     * in-play position, or the [CardPlayResult.Rejected] to return instead. */
    private sealed class MovableCheck {
        data class Movable(val fromPosition: Int) : MovableCheck()
        data class Blocked(val result: CardPlayResult.Rejected) : MovableCheck()
    }

    private fun validateMovable(state: GameState, request: CardPlayRequest, target: CardTarget.Token, ownTokenMovementAllowed: Boolean): MovableCheck {
        val location = TokenLocator.locate(state, target.id)
        if (location is TokenLocation.NoLongerExists) {
            return MovableCheck.Blocked(CardPlayResult.Rejected(request, TargetValidationError.NoLegalTarget("${target.id} no longer exists")))
        }
        val zoneError = TargetValidator.validateZoneOfProtection(
            request.card,
            request.sourcePlayer,
            target.id.owner,
            location,
            ownTokenMovementAllowed = ownTokenMovementAllowed,
        )
        if (zoneError != null) return MovableCheck.Blocked(CardPlayResult.Rejected(request, zoneError))
        if (location is TokenLocation.InZone) {
            // Legal per rule 12 (the ZoP check above already rejects any other case) but moving a
            // token OUT of a Zone isn't implemented yet — see MovementCardResolver's class doc.
            return MovableCheck.Blocked(
                CardPlayResult.Rejected(
                    request,
                    TargetValidationError.CardSpecificRestriction(
                        "Moving a token out of a Zone of Protection is not yet implemented (see docs/card-mechanics-matrix.md §4 Q17)",
                    ),
                ),
            )
        }
        return MovableCheck.Movable((location as TokenLocation.InPlay).position)
    }

    private fun move(state: GameState, target: CardTarget.Token, fromPosition: Int) {
        when (target.id.kind) {
            TokenKind.TIER_TOKEN -> TurnEngine.moveTierToken(state, target.id.owner, target.id.tier, fromPosition, spaces = 4)
            TokenKind.MARAUDER -> TurnEngine.moveMarauder(state, target.id.owner, target.id.tier, fromPosition, spaces = 4)
        }
    }
}
