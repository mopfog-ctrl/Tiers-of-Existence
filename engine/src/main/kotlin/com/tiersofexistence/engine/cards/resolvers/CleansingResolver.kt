package com.tiersofexistence.engine.cards.resolvers

import com.tiersofexistence.engine.cards.FateHarvestCard
import com.tiersofexistence.engine.cards.play.CardLifecycle
import com.tiersofexistence.engine.cards.play.CardPlayRequest
import com.tiersofexistence.engine.cards.play.CardPlayResult
import com.tiersofexistence.engine.cards.play.CardTarget
import com.tiersofexistence.engine.cards.play.PendingDecision
import com.tiersofexistence.engine.cards.play.TargetValidationError
import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.state.GameState

/**
 * Cleansing (Atmospheric): "Choose an opponent. That opponent must choose and discard one of
 * their cards. The person discarding chooses which card to discard." A genuine two-step decision
 * spanning two different players — [target] is the source player's own choice (which opponent),
 * but *which card* to discard from that opponent's hand is the opponent's own choice, not
 * something this resolver (or the source player) can pick for them.
 *
 * That's why [resolve] can't finish synchronously the way every other resolver does: once the
 * card itself is legally played (color/Phase/per-Phase-limit checks, discarding Cleansing itself
 * — [CardLifecycle.attemptPlay]), it returns [CardPlayResult.AwaitingDecision] with
 * [PendingDecision.OpponentDiscardChoice] naming the targeted opponent, rather than
 * [CardPlayResult.Resolved] — mirroring the existing "offer, don't auto-apply" pattern
 * ([com.tiersofexistence.engine.rules.SquareEffect.MayEnterZone]/`MayBuildMarauder`/
 * `MayTransport`) rather than inventing a second, parallel pending-decision engine alongside
 * [com.tiersofexistence.engine.rules.precedence.InteractionChain]: whatever is driving the game
 * is expected to prompt the named opponent and then call [completeDiscard] once they've answered,
 * same as it's already expected to call `TurnEngine.enterZoneOfProtection` once a player answers
 * that offer.
 *
 * **Confirmed by the user, resolving `docs/card-mechanics-matrix.md` §4 Q12**: a player with an
 * empty hand is not a legal target at all — "Cleansing cannot be played against a player who has
 * no cards in their hand." This is a target-legality check, evaluated before
 * [CardLifecycle.attemptPlay] runs (same as the self-target/not-in-game checks below), so a play
 * against an empty-handed opponent is [CardPlayResult.Rejected] rather than a played-but-fizzled
 * [CardPlayResult.Resolved] — Cleansing itself is never discarded and never counts against the
 * Phase's card-play limit for an illegal target, matching [CardLifecycle]'s own class doc ("a
 * card whose target turns out illegal should never consume the per-Phase play limit or get
 * discarded").
 */
object CleansingResolver {
    fun resolve(state: GameState, request: CardPlayRequest, target: CardTarget.PlayerChoice): CardPlayResult {
        if (target.color == request.sourcePlayer) {
            return CardPlayResult.Rejected(
                request,
                TargetValidationError.WrongTokenType("Cleansing must target an opponent, not yourself"),
            )
        }
        if (target.color !in state.players) {
            return CardPlayResult.Rejected(
                request,
                TargetValidationError.NoLegalTarget("${target.color} is not a player in this game"),
            )
        }
        if (state.players.getValue(target.color).hand.isEmpty()) {
            return CardPlayResult.Rejected(
                request,
                TargetValidationError.NoLegalTarget("${target.color} has no cards in hand to discard"),
            )
        }

        val playResult = CardLifecycle.attemptPlay(state, request)
        if (playResult !is CardPlayResult.Resolved) return playResult

        return CardPlayResult.AwaitingDecision(request, PendingDecision.OpponentDiscardChoice(target.color))
    }

    /**
     * Completes a Cleansing play that returned [CardPlayResult.AwaitingDecision] — the targeted
     * opponent's own choice of which of their held cards to discard, per the card's own "the
     * person discarding chooses which card to discard." [decidingPlayer] must be the color the
     * pending decision named, and [cardToDiscard] must actually be sitting in their hand — both
     * are the caller's responsibility to get right (this throws rather than silently no-op'ing,
     * an internal-consistency check, not a player-facing legality check, same contract as
     * [com.tiersofexistence.engine.state.TierTokenPool.destroyById]).
     */
    fun completeDiscard(state: GameState, decidingPlayer: PlayerColor, cardToDiscard: FateHarvestCard) {
        val hand = state.players.getValue(decidingPlayer).hand
        require(hand.remove(cardToDiscard)) { "$decidingPlayer does not have ${cardToDiscard.name} in hand" }
        state.deck.discard(cardToDiscard)
    }
}
