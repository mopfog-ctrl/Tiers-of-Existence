package com.tiersofexistence.engine.cards.resolvers

import com.tiersofexistence.engine.cards.play.CardLifecycle
import com.tiersofexistence.engine.cards.play.CardPlayRequest
import com.tiersofexistence.engine.cards.play.CardPlayResult
import com.tiersofexistence.engine.cards.play.CardTarget
import com.tiersofexistence.engine.cards.play.TargetValidationError
import com.tiersofexistence.engine.cards.play.TokenLocation
import com.tiersofexistence.engine.cards.play.TokenLocator
import com.tiersofexistence.engine.model.TokenKind
import com.tiersofexistence.engine.state.GameState

/**
 * Circulate: "Move any Tier token to the next Zone of Protection on that Tier." Confirmed with
 * the user: the player chooses a Tier, then any Tier token currently in play on that Tier — any
 * owner, not just the player's own — and it teleports directly into the next Zone of Protection
 * walking clockwise from its current position ([com.tiersofexistence.engine.board.TierBoard
 * .nextZoneEntry]), wrapping the loop if needed. A Tier token already inside a Zone of
 * Protection cannot be targeted at all — Circulate is not one of the 5 named rule-12
 * exceptions, so ordinary Zone protection applies with no carve-out, even for the player's own
 * token (unlike an ordinary movement card's "your own token, your own Zone" allowance).
 * Marauders are excluded — they can never occupy a Zone of Protection.
 *
 * [target] carries its own Tier via [com.tiersofexistence.engine.state.TokenId.tier]; a
 * separate `CardTarget.TierChoice` isn't needed at the engine layer (a UI may still ask
 * "which Tier" first as part of its own two-step selection flow, matching how the card is
 * played, without that becoming a second [CardPlayRequest] target here).
 */
object CirculateResolver {
    fun resolve(state: GameState, request: CardPlayRequest, target: CardTarget.Token): CardPlayResult {
        if (target.id.kind != TokenKind.TIER_TOKEN) {
            return CardPlayResult.Rejected(request, TargetValidationError.WrongTokenType("Circulate can only move a Tier token, not a Marauder"))
        }

        val location = TokenLocator.locate(state, target.id)
        val fromPosition = when (location) {
            is TokenLocation.InPlay -> location.position
            is TokenLocation.InZone -> return CardPlayResult.Rejected(
                request,
                TargetValidationError.ZoneOfProtectionBlocksTarget("${target.id} is already inside a Zone of Protection and cannot be targeted by Circulate"),
            )
            is TokenLocation.NoLongerExists -> return CardPlayResult.Rejected(request, TargetValidationError.NoLegalTarget("${target.id} no longer exists"))
        }

        val board = state.boards.getValue(target.id.tier)
        val zoneEntry = board.nextZoneEntry(fromPosition)
            ?: return CardPlayResult.Rejected(request, TargetValidationError.CardSpecificRestriction("${target.id.tier} has no Zone of Protection to circulate into"))
        val zoneNumber = requireNotNull(zoneEntry.magnitude) { "Zone of Protection square on ${target.id.tier} has no zone number set" }

        val playResult = CardLifecycle.attemptPlay(state, request)
        if (playResult !is CardPlayResult.Resolved) return playResult

        state.players.getValue(target.id.owner).tierPool(target.id.tier).enterZone(fromPosition, zoneNumber)
        return playResult
    }
}
