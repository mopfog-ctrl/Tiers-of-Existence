package com.tiersofexistence.engine.cards.resolvers

import com.tiersofexistence.engine.cards.play.CardLifecycle
import com.tiersofexistence.engine.cards.play.CardPlayRequest
import com.tiersofexistence.engine.cards.play.CardPlayResult
import com.tiersofexistence.engine.cards.play.CardTarget
import com.tiersofexistence.engine.cards.play.TargetValidationError
import com.tiersofexistence.engine.cards.play.TokenLocation
import com.tiersofexistence.engine.cards.play.TokenLocator
import com.tiersofexistence.engine.model.TokenKind
import com.tiersofexistence.engine.rules.TurnEngine
import com.tiersofexistence.engine.state.GameState

/**
 * Last Gasp: "Move any one of your tokens (of any type) 8 spaces. Any tokens you pass are
 * destroyed, as well as the moved token, except tokens in the Zone of Protection." The single
 * most destructive card in the deck, and genuinely unusual among pass-through effects — the
 * mover's own other tokens caught in the 8-space path are destroyed too (confirmed by the user:
 * this card's wording is the one place the usual "of other players" owner-exemption is
 * deliberately absent), *and* the moved token itself is destroyed once it arrives, on top of
 * whatever its landing square would normally do (see [TurnEngine.moveTierToken]/[TurnEngine
 * .moveMarauder]'s `exemptMoverOwnTokens = false`). Zone-of-Protection residents remain immune
 * either way, same as ordinary pass-through — they're simply invisible to the position-based
 * scan (see [com.tiersofexistence.engine.state.TierTokenPool]'s class doc).
 *
 * [target] must be the source player's own token — any type, Tier token or Marauder. If it's
 * currently inside a Zone of Protection, this rejects the same honest "not yet implemented" way
 * [MovementCardResolver] does for any other movement card (moving a token *out* of a Zone isn't
 * modeled yet — matrix §4 Q17); this card's rule-12 "your own token, your own Zone" carve-out
 * doesn't change that.
 */
object LastGaspResolver {
    private const val SPACES = 8

    fun resolve(state: GameState, request: CardPlayRequest, target: CardTarget.Token): CardPlayResult {
        if (target.id.owner != request.sourcePlayer) {
            return CardPlayResult.Rejected(
                request,
                TargetValidationError.WrongTokenType("Last Gasp may only move your own token, not ${target.id.owner}'s"),
            )
        }

        val location = TokenLocator.locate(state, target.id)
        val fromPosition = when (location) {
            is TokenLocation.InPlay -> location.position
            is TokenLocation.InZone -> return CardPlayResult.Rejected(
                request,
                TargetValidationError.CardSpecificRestriction(
                    "Moving a token out of a Zone of Protection is not yet implemented (see docs/card-mechanics-matrix.md §4 Q17)",
                ),
            )
            is TokenLocation.NoLongerExists -> return CardPlayResult.Rejected(request, TargetValidationError.NoLegalTarget("${target.id} no longer exists"))
        }

        val playResult = CardLifecycle.attemptPlay(state, request)
        if (playResult !is CardPlayResult.Resolved) return playResult

        when (target.id.kind) {
            TokenKind.TIER_TOKEN -> TurnEngine.moveTierToken(
                state, target.id.owner, target.id.tier, fromPosition, SPACES,
                destroysPassedTokens = true, exemptMoverOwnTokens = false,
            )
            TokenKind.MARAUDER -> TurnEngine.moveMarauder(
                state, target.id.owner, target.id.tier, fromPosition, SPACES,
                exemptMoverOwnTokens = false,
            )
        }

        // "As well as the moved token" — destroyed on arrival, unless its own landing square
        // already destroyed it first (Abyss/Infernal Abyss), in which case there's nothing left
        // to do; TokenId identity means this always finds the mover wherever it actually ended
        // up, never a stale position.
        if (TokenLocator.locate(state, target.id) !is TokenLocation.NoLongerExists) {
            val player = state.players.getValue(target.id.owner)
            when (target.id.kind) {
                TokenKind.TIER_TOKEN -> player.tierPool(target.id.tier).destroyById(target.id)
                TokenKind.MARAUDER -> player.marauders.destroyById(target.id)
            }
        }
        return playResult
    }
}
