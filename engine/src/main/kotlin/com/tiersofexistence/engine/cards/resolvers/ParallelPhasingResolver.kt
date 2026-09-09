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
import com.tiersofexistence.engine.rules.ZoneMoveResult
import com.tiersofexistence.engine.state.GameState
import com.tiersofexistence.engine.state.TokenId

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
 * token gets rule 12's "your own movement card on your own token" carve-out — implemented via
 * [TurnEngine.moveZoneToken], same as [MovementCardResolver] (§4 Q17, resolved: a Zone is a real
 * dice-driven sub-path); the opponent's token gets no carve-out at all — Parallel Phasing is not
 * one of the 5 named rule-12 exceptions, so a Zone-resident opponent token is simply illegal to
 * target, full stop.
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

        val ownBlocked = validateMovable(state, request, ownTarget, ownTokenMovementAllowed = true)
        if (ownBlocked != null) return ownBlocked
        val opponentBlocked = validateMovable(state, request, opponentTarget, ownTokenMovementAllowed = false)
        if (opponentBlocked != null) return opponentBlocked

        val playResult = CardLifecycle.attemptPlay(state, request)
        if (playResult !is CardPlayResult.Resolved) return playResult

        // Move identity-based, re-locating each target fresh right at the moment it actually
        // moves rather than trusting either target's pre-validated position — moving the OWN
        // token first can have a side effect that invalidates the ALREADY-validated opponent
        // target before its own move happens (e.g. the own token's landing chains into a
        // Hyperthrust pass-through that destroys the opponent's token, or the own token simply
        // lands on the same square the opponent's token already occupied and a stale recorded
        // position would then silently move the wrong occupant). Found by GameSimulationTest's
        // randomized-play harness as a genuine "No in-play token at position N" crash. If a
        // target has vanished by the time its own move is attempted, that half of the compound
        // effect simply doesn't apply — the play itself is already committed (the card is
        // legitimately expended either way), matching this codebase's established "a
        // resolution-time-vanished target is a graceful no-op, never a crash" pattern.
        move(state, ownTarget.id)
        move(state, opponentTarget.id)
        return playResult
    }

    /** Checks [target] can actually be moved by this card right now — still exists, and legal
     * per [TargetValidator.validateZoneOfProtection] — without moving anything. Returns the
     * [CardPlayResult.Rejected] to return instead, or null if clear to proceed. */
    private fun validateMovable(state: GameState, request: CardPlayRequest, target: CardTarget.Token, ownTokenMovementAllowed: Boolean): CardPlayResult.Rejected? {
        val location = TokenLocator.locate(state, target.id)
        if (location is TokenLocation.NoLongerExists) {
            return CardPlayResult.Rejected(request, TargetValidationError.NoLegalTarget("${target.id} no longer exists"))
        }
        val zoneError = TargetValidator.validateZoneOfProtection(
            request.card,
            request.sourcePlayer,
            target.id.owner,
            location,
            ownTokenMovementAllowed = ownTokenMovementAllowed,
        )
        return zoneError?.let { CardPlayResult.Rejected(request, it) }
    }

    /** Re-locates [id] fresh (never a stale snapshot) and moves it 4 spaces via the
     * identity-based movers — a no-op if [id] no longer exists at all by this moment. */
    private fun move(state: GameState, id: TokenId) {
        when (val location = TokenLocator.locate(state, id)) {
            is TokenLocation.NoLongerExists -> Unit
            is TokenLocation.InZone -> when (val zoneResult = TurnEngine.moveZoneToken(state, id, spaces = 4)) {
                is ZoneMoveResult.StillInZone -> discardStrandedImmediateCard(state, zoneResult.effect)
                is ZoneMoveResult.ExitedZone -> discardStrandedImmediateCard(state, zoneResult.moveResult.effect)
            }
            is TokenLocation.InPlay -> when (id.kind) {
                TokenKind.TIER_TOKEN -> discardStrandedImmediateCard(state, TurnEngine.moveTierTokenById(state, id, spaces = 4).effect)
                TokenKind.MARAUDER -> TurnEngine.moveMarauderById(state, id, spaces = 4)
            }
        }
    }
}
