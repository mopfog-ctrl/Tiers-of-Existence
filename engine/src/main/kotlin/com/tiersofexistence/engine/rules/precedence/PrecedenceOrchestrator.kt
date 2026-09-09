package com.tiersofexistence.engine.rules.precedence

import com.tiersofexistence.engine.cards.play.CardPlayRequest
import com.tiersofexistence.engine.cards.play.CardPlayResult
import com.tiersofexistence.engine.cards.play.TriggeringEvent
import com.tiersofexistence.engine.cards.resolvers.CardEffectDispatcher
import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.rules.TurnDecisionProvider
import com.tiersofexistence.engine.state.GameState

/**
 * Owns HOW a Precedence window (rules 20-23) executes, once something else has decided one
 * should open. [com.tiersofexistence.engine.rules.TurnDriver] still decides WHEN: around a
 * pending card resolution ([SuspendedAction.PendingCardResolution] — its own
 * `playWithPrecedenceWindow` opens one there, then handles what happens next for that specific
 * card: discard it if Annulled, otherwise dispatch it and resolve any pending decision) or a
 * pending token move ([SuspendedAction.PendingMove], rule 23's own checkpoint, right after a
 * token is chosen and before it moves). This class only runs the window itself once asked:
 * gather response rounds, resolve the chain in reverse play order (rule 22) by dispatching
 * through [CardEffectDispatcher], and settle the resulting hand/discard bookkeeping — it never
 * decides to open one on its own, and never acts on the [suspendedAction][InteractionChain.suspendedAction]
 * itself (that's still the caller's job, same as before this class existed).
 *
 * [decisionsFor] mirrors [com.tiersofexistence.engine.rules.TurnDriver]'s own per-player seam —
 * a Precedence response can come from ANY seated player, not just whoever's turn it is, so this
 * needs the same per-player resolution `TurnDriver` already does; `TurnDriver` constructs one
 * instance of this class (passing its own `decisionsFor` straight through) and reuses it for
 * every window it opens, rather than one shared/global instance — matching how `TurnDriver`
 * itself is scoped to one driving session.
 *
 * Extracted (behavior-preserving) from `TurnDriver`, which previously held this logic directly
 * as `resolvePrecedenceWindow`/`offerResponseRounds` — moved here because it's a genuinely
 * self-contained algorithm (given a suspended action and a way to ask players questions, run
 * rules 20-23 to completion) that doesn't need to know anything about dice, held-card windows,
 * or movement, unlike the rest of `TurnDriver`.
 */
class PrecedenceOrchestrator(private val decisionsFor: (PlayerColor) -> TurnDecisionProvider) {

    /**
     * Opens an [InteractionChain] around [suspendedAction], offers every seated player
     * (`state.turnOrder.order`, matching the class's own "response order" convention) one or more
     * rounds of Precedence-response opportunity via [offerResponseRounds], then resolves the
     * chain and dispatches every surviving entry in reverse play order (rule 22).
     *
     * Every chain-response card was removed from its player's hand the moment it was played into
     * the chain (see [offerResponseRounds]) and is never returned there regardless of how it
     * resolves — **confirmed canon**: once played, a card is expended; Annulment (or a stale
     * target) nullifies what it *does*, not the historical fact that it was played. A
     * [CardPlayResult.Resolved] entry already discarded its own card via
     * [com.tiersofexistence.engine.cards.play.CardLifecycle.attemptPlay]; every other entry in
     * [InteractionChain.entriesSnapshot] needs an explicit discard here, not just the ones
     * [InteractionChain.resolutionOrder]/[CardEffectDispatcher.dispatchAll] actually saw —
     * that excludes cancelled entries AND Annulment entries themselves (an Annulment never has
     * an effect of its own to resolve), both of which still need their own card discarded per
     * the same confirmed canon, even though [CardEffectDispatcher] never touches them. (Every
     * Precedence card is [com.tiersofexistence.engine.cards.CardTiming.HELD] — see
     * `FateHarvestCatalog` — so none can produce [CardPlayResult.EnteredHand], and none of the 6
     * currently trigger [CardPlayResult.AwaitingDecision].)
     *
     * Returns the finished (RESOLVED-state) chain so callers that need
     * [InteractionChain.isSuspendedActionCancelled] can check it — meaningless for anything but a
     * [SuspendedAction.PendingCardResolution].
     */
    fun openWindow(state: GameState, suspendedAction: SuspendedAction): InteractionChain {
        val chain = InteractionChain.open(suspendedAction, eligiblePlayers = state.turnOrder.order)
        offerResponseRounds(state, chain)
        val order = chain.resolve()
        val results = CardEffectDispatcher.dispatchAll(state, order)
        chain.finishResolving()
        val resolvedEntryIds = order.zip(results).filter { (_, result) -> result is CardPlayResult.Resolved }.map { it.first.id }.toSet()
        // Every entry's own card entered GameState.resolvingCards the moment offerResponseRounds
        // removed it from its player's hand (see that method's doc) - a Resolved entry already
        // ended its own card via CardLifecycle.attemptPlay, so this sweep's explicit
        // endResolvingCard covers every other entry (cancelled, Annulment-own, or rejected at
        // resolution time - a stale target never reaches attemptPlay at all), matching the
        // discard it performs right alongside. See GameState.resolvingCards' own class doc.
        chain.entriesSnapshot().forEach { entry ->
            if (entry.id !in resolvedEntryIds) {
                state.deck.discard(entry.request.card)
                state.endResolvingCard(entry.request.card)
            }
        }
        return chain
    }

    /**
     * Repeatedly offers every currently-eligible player (per [InteractionChain.currentlyEligibleToAct])
     * a chance to respond via [TurnDecisionProvider.choosePrecedenceResponse] (resolved per-player
     * through [decisionsFor], since a response can come from any seated player, not just the one
     * whose turn it is) or pass, until the window closes on its own. A player who chooses to
     * respond has that card removed from their hand right here, before [InteractionChain.respond]
     * adds the entry — see [openWindow]'s doc for what happens to it from there.
     */
    private fun offerResponseRounds(state: GameState, chain: InteractionChain) {
        while (chain.isOpen) {
            val eligible = chain.currentlyEligibleToAct()
            if (eligible.isEmpty()) break // defensive: isOpen should already guarantee this is non-empty
            for (player in eligible) {
                if (!chain.isOpen) break
                val choice = decisionsFor(player).choosePrecedenceResponse(state, player, chain)
                if (choice == null) {
                    chain.pass(player)
                    continue
                }
                require(choice.card.hasPrecedence) {
                    "choosePrecedenceResponse must return a Precedence card, got ${choice.card.name}"
                }
                val hand = state.players.getValue(player).hand
                require(hand.remove(choice.card)) {
                    "$player chose to respond with ${choice.card.name}, but doesn't have it in hand"
                }
                // Live physical card from this instant until openWindow's own end-of-chain sweep
                // (or CardLifecycle.attemptPlay, for a Resolved entry) ends it - a chain can hold
                // several such in-flight responses at once, and dispatch happens only after every
                // round closes, so this can be a genuinely long window. See
                // GameState.resolvingCards' own class doc.
                state.beginResolvingCard(choice.card)
                chain.respond(player, CardPlayRequest(player, choice.card, choice.targets, TriggeringEvent.RespondingInChain(chain.id)))
            }
        }
    }
}
