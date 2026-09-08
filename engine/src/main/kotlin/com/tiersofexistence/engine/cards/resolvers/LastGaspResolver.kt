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
 * currently inside a Zone of Protection (only possible for a Tier token — a Marauder can never be
 * a Zone resident), this card's rule-12 "your own token, your own Zone" carve-out applies, moving
 * it via [TurnEngine.moveZoneToken] (§4 Q17, resolved: a Zone is a real dice-driven sub-path) —
 * the pass-through destroy never reaches other tokens resident in that same Zone, matching this
 * card's own "except tokens in the Zone of Protection" clause (see that function's doc).
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
        if (location is TokenLocation.NoLongerExists) {
            return CardPlayResult.Rejected(request, TargetValidationError.NoLegalTarget("${target.id} no longer exists"))
        }

        val playResult = CardLifecycle.attemptPlay(state, request)
        if (playResult !is CardPlayResult.Resolved) return playResult

        when (location) {
            is TokenLocation.InZone -> TurnEngine.moveZoneToken(
                state, target.id, SPACES,
                destroysPassedTokens = true, exemptMoverOwnTokens = false,
            )
            is TokenLocation.InPlay -> when (target.id.kind) {
                TokenKind.TIER_TOKEN -> TurnEngine.moveTierToken(
                    state, target.id.owner, target.id.tier, location.position, SPACES,
                    destroysPassedTokens = true, exemptMoverOwnTokens = false,
                )
                TokenKind.MARAUDER -> TurnEngine.moveMarauder(
                    state, target.id.owner, target.id.tier, location.position, SPACES,
                    exemptMoverOwnTokens = false,
                )
            }
            is TokenLocation.NoLongerExists -> error("unreachable — handled above")
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
