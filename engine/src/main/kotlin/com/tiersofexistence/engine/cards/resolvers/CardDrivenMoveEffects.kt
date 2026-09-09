package com.tiersofexistence.engine.cards.resolvers

import com.tiersofexistence.engine.cards.CardTiming
import com.tiersofexistence.engine.rules.SquareEffect
import com.tiersofexistence.engine.state.GameState

/**
 * A card resolver that moves a token via [com.tiersofexistence.engine.rules.TurnEngine] gets back
 * a [com.tiersofexistence.engine.rules.MoveResult]/[com.tiersofexistence.engine.rules.ZoneMoveResult]
 * exactly like an ordinary dice-driven move does — but unlike [com.tiersofexistence.engine.rules
 * .TurnDriver] (which has a [com.tiersofexistence.engine.rules.TurnDecisionProvider] to ask), a
 * resolver here has no way to offer [SquareEffect.MayEnterZone]/[SquareEffect.MayBuildMarauder]/
 * [SquareEffect.MayTransport], or to choose an Immediate card's targets. Every one of the
 * *optional* offers is deliberately not surfaced for a card-driven move — already documented on
 * [MovementCardResolver]'s own class doc ("a movement-card resolver doesn't surface the offer
 * itself") — a token landing on a Zone entry/Marauder Construction/Transport square via a card's
 * own move just stays an ordinary token there, same as declining the offer would.
 *
 * [SquareEffect.DrewCard] is different: unlike those offers, the draw itself already happened
 * unconditionally inside [com.tiersofexistence.engine.rules.TurnEngine] the instant the token
 * landed there (same as any other landing) — the card physically left the deck. A [CardTiming.HELD]
 * card is already safe (added straight to the drawing player's hand by `TurnEngine` itself,
 * regardless of caller). A [CardTiming.IMMEDIATE] card is NOT: `TurnEngine` never resolves/discards
 * it itself (mandatory Immediate plays always go through [com.tiersofexistence.engine.rules
 * .TurnDriver.resolveImmediateCard] instead, which no resolver here has access to), so a caller
 * that never inspects the landing effect at all — every movement/Parallel Phasing/Last Gasp/
 * Galactic Roundabout resolver before this fix — simply drops it: not in a hand (Immediate cards
 * never enter one), not resolved, not discarded, just gone. Found by `GameSimulationTest`'s
 * randomized-play harness as a genuine deck/discard/hand-conservation violation, reachable any
 * time a card-driven move happens to land a token on a Fate Harvest square.
 *
 * There is no legal way for this resolver to actually PLAY a mandatory card with an unknown
 * target here (no [com.tiersofexistence.engine.rules.TurnDecisionProvider] to ask), so — matching
 * the exact precedent already established for "an Immediate card genuinely has nowhere to
 * land" ([com.tiersofexistence.engine.rules.TurnDriver.resolveImmediateCard]'s own doc: "discarding
 * it is the only place left for it to go that doesn't fabricate new behavior") — this discards it
 * rather than inventing a substitute resolution or losing it.
 */
internal fun discardStrandedImmediateCard(state: GameState, effect: SquareEffect) {
    if (effect is SquareEffect.DrewCard && effect.card.timing == CardTiming.IMMEDIATE) {
        state.deck.discard(effect.card)
        // This card entered GameState.resolvingCards the instant TurnEngine drew it (see that
        // property's own class doc) - discarding it here IS its entire "resolution" for a
        // card-driven move, so the matching transition out of the resolving zone happens right
        // alongside the discard, same as every other end-of-resolution discard path does.
        state.endResolvingCard(effect.card)
    }
}
