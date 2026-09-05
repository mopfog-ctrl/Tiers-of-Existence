package com.tiersofexistence.engine.cards.play

import com.tiersofexistence.engine.cards.CardTiming
import com.tiersofexistence.engine.cards.FateHarvestCard
import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.state.GameState

/**
 * Explicit Immediate-vs-Held card lifecycle (Phase D), engine-enforced rather than left to the
 * UI to get right:
 *
 * - Immediate: draw → validate → resolve atomically → discard. [onDrawnFromSquare] does this in
 *   one call — there is no "decide whether to play it," the play is mandatory.
 * - Held: draw → enter hand → remain persistent → validate when a play is attempted → resolve →
 *   leave hand. [onDrawnFromSquare] only does the first half (enters hand); [playFromHand] does
 *   the second half whenever the player later chooses to play it.
 *
 * [attemptPlay] is the shared validation+bookkeeping core both paths funnel through, so a card's
 * specific effect (Phase J) is the only thing that varies per card. [CardPlayResult.Resolved]
 * means "this play is legal and its generic bookkeeping (discard, per-Phase flag) is done," NOT
 * "the card's specific effect has been applied" — this class deliberately stops short of
 * touching board/token state itself; a per-card resolver applies the actual effect using the
 * [CardPlayRequest]'s targets. Ordering matters for a card with targets: run card-specific
 * target legality first (token type, ownership, Zone of Protection via
 * [TargetValidator.validateZoneOfProtection] with that card's own `ownTokenMovementAllowed`,
 * etc.) and only call [attemptPlay] once those pass — a card whose target turns out illegal
 * should never consume the per-Phase play limit or get discarded.
 */
object CardLifecycle {

    /**
     * A Tier token has landed on a Fate Harvest square and drawn [card]. For a [CardTiming.HELD]
     * card this only files it into [player]'s hand — no validation, since nothing is being
     * played yet. For a [CardTiming.IMMEDIATE] card this immediately attempts to play it via
     * [attemptPlay] — mandatory, per rule 14.
     */
    fun onDrawnFromSquare(
        state: GameState,
        player: PlayerColor,
        card: FateHarvestCard,
        tier: TierLevel,
        squarePosition: Int,
        targets: List<CardTarget> = emptyList(),
    ): CardPlayResult {
        val request = CardPlayRequest(player, card, targets, TriggeringEvent.DrawnFromSquare(tier, squarePosition))
        if (card.timing == CardTiming.HELD) {
            state.players.getValue(player).hand += card
            return CardPlayResult.EnteredHand(request)
        }
        return attemptPlay(state, request)
    }

    /**
     * [player] chooses to play a [CardTiming.HELD] card already sitting in their hand.
     * [CardPlayResult.Rejected] leaves the card in hand — a rejected play never consumes it,
     * matching Phase D's "meaningful engine result rather than silently failing."
     */
    fun playFromHand(
        state: GameState,
        player: PlayerColor,
        card: FateHarvestCard,
        targets: List<CardTarget> = emptyList(),
    ): CardPlayResult {
        val hand = state.players.getValue(player).hand
        require(hand.remove(card)) { "$player does not have ${card.name} in hand" }

        val request = CardPlayRequest(player, card, targets, TriggeringEvent.PlayedFromHand)
        val result = attemptPlay(state, request)
        if (result is CardPlayResult.Rejected) hand += card
        return result
    }

    /**
     * The shared validation+bookkeeping core: color restriction (rule 18), then the per-Phase
     * play limit (rule 4; Immediate cards exempt — see [TargetValidator.validatePhaseCardLimit]).
     * If legal, marks [com.tiersofexistence.engine.state.PlayerState.hasPlayedCardThisPhase] and
     * discards the card (rule 5). Does not validate card-specific targets — see the class doc.
     */
    fun attemptPlay(state: GameState, request: CardPlayRequest): CardPlayResult {
        val player = state.players.getValue(request.sourcePlayer)
        val card = request.card

        val colorError = TargetValidator.validateColorRestriction(card, request.sourcePlayer)
        if (colorError != null) return CardPlayResult.Rejected(request, colorError)

        val phaseLimitError = TargetValidator.validatePhaseCardLimit(card, player.hasPlayedCardThisPhase)
        if (phaseLimitError != null) return CardPlayResult.Rejected(request, phaseLimitError)

        player.hasPlayedCardThisPhase = true
        state.deck.discard(card)
        return CardPlayResult.Resolved(request)
    }
}
