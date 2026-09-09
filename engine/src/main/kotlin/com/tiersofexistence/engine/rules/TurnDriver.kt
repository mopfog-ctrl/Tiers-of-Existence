package com.tiersofexistence.engine.rules

import com.tiersofexistence.engine.cards.CardTiming
import com.tiersofexistence.engine.cards.FateHarvestCard
import com.tiersofexistence.engine.cards.play.CardPlayRequest
import com.tiersofexistence.engine.cards.play.CardPlayResult
import com.tiersofexistence.engine.cards.play.PendingDecision
import com.tiersofexistence.engine.cards.play.TokenLocation
import com.tiersofexistence.engine.cards.play.TokenLocator
import com.tiersofexistence.engine.cards.play.TriggeringEvent
import com.tiersofexistence.engine.cards.resolvers.CardEffectDispatcher
import com.tiersofexistence.engine.cards.resolvers.CleansingResolver
import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.model.TokenKind
import com.tiersofexistence.engine.rules.precedence.InteractionChain
import com.tiersofexistence.engine.rules.precedence.SuspendedAction
import com.tiersofexistence.engine.state.GameState
import com.tiersofexistence.engine.state.TokenId

/**
 * Drives one full turn at a time — roll, choose-a-token, move, resolve-the-landing, AND (since
 * this integration) Fate Harvest card play — delegating every real player choice to a
 * [TurnDecisionProvider].
 *
 * **Fate Harvest integration.** Four legal card-play opportunities are wired into the turn
 * lifecycle, each derived from the card rules already implemented elsewhere in this engine
 * rather than invented here:
 * - **Immediate cards** (rule 14, mandatory the instant drawn): when [SquareEffect.DrewCard]
 *   carries a [CardTiming.IMMEDIATE] card, [resolveImmediateCard] asks
 *   [TurnDecisionProvider.chooseImmediateCardTargets] for its target(s) and resolves it
 *   immediately via [CardEffectDispatcher], through the same Precedence window (below) every
 *   other card play gets — replacing the old `GameState.queuePendingImmediateCard`/
 *   `pendingImmediateCards` placeholder, which has been removed now that nothing uses it. This is
 *   handled both for an ordinary main-loop landing and for a Zone-internal Fate Harvest square
 *   (previously silently dropped — [ZoneMoveResult.StillInZone]'s effect was never even
 *   inspected by this class before).
 * - **Held cards**: [TurnDecisionProvider.chooseHeldCardBeforeRoll] offers one voluntary
 *   [CardScope.YOUR_TURN] play before rolling; [TurnDecisionProvider.chooseCardAfterRollBeforeMove]
 *   offers one more after rolling but before the roll moves a token — Delayed Motion's own
 *   window (see below). Both go through [offerHeldCardPlay], which removes the chosen card from
 *   hand, resolves it (Precedence window included), and returns it to hand if the play turns out
 *   illegal (matching [com.tiersofexistence.engine.cards.play.CardLifecycle.playFromHand]'s own
 *   established contract) — no new arbitrary windows beyond these two.
 * - **Delayed Motion**: [GameState.beginPendingRoll] is called right after rolling, exactly
 *   where it was already designed to be called from; [chooseCardAfterRollBeforeMove] is the
 *   legal opportunity to play it (or, per that method's own doc, any other card whose printed
 *   timing genuinely fits this exact window — this driver doesn't hardcode "only Delayed
 *   Motion"); [GameState.pendingRoll]'s final total is read back out via [PendingRoll.total]
 *   exactly as before, then [GameState.clearPendingRoll] runs at the end of the turn as before.
 * - **Precedence** (rules 20-23): [resolvePrecedenceWindow] opens a real
 *   [InteractionChain] around every point this driver suspends something a Precedence card could
 *   respond to — a pending card resolution (any Immediate/Held play, wrapped by
 *   [playWithPrecedenceWindow]) or a pending token move (right after a token is chosen, before
 *   [moveAndResolve] actually moves it) — offers every seated player a response round via
 *   [offerResponseRounds] (repeating the round whenever a new entry reopens it, per
 *   [InteractionChain]'s own rules), then dispatches whatever survives in reverse play order
 *   (rule 22) before letting the suspended action itself proceed. See the two private helpers'
 *   own docs for exactly how a chain-response card's hand/discard bookkeeping works — this is new
 *   bookkeeping this integration needed, since [InteractionChain]/[CardEffectDispatcher]
 *   themselves only sequence/apply plays, they never touch a hand or the deck's discard pile.
 *
 * Time Wrinkle squares remain fully handled as before: "Lose next turn on this Tier" and "Take an
 * extra turn, First Tier" auto-apply inside [TurnEngine] itself; "Go again"
 * ([SquareEffect.GoAgain]) is acted on here by passing `grantAnotherTurn = true` to
 * [GameState.endTurn].
 *
 * [rollForPhase] defaults to [Dice.rollForPhase] (genuinely random) but can be overridden for a
 * deterministic test/simulation — a plain function reference rather than a `Random` seed, since
 * `kotlin.random.Random`'s internals make forcing an exact `nextInt` sequence awkward, and a
 * fixed lambda is simpler to reason about in a test than a seeded generator would be anyway.
 *
 * [decisionsFor] resolves a fresh [TurnDecisionProvider] per player rather than sharing one
 * across the whole driver — needed for a game seating several AI players with genuinely
 * different behavior (up to 5 AI opponents, each a different behavior type, is the stated first-
 * release shape), not just one uniform policy for everyone. The two secondary constructors cover
 * the common cases: a single [TurnDecisionProvider] for every player (unchanged from this
 * class's original shape), or a `Map<PlayerColor, TurnDecisionProvider>` keyed by seat. A human
 * seat is deliberately not modeled here at all — this class only ever drives a turn it's asked
 * to, so whatever's orchestrating a mixed human/AI game simply never calls [driveOneTurn] for a
 * human player's turn in the first place, and drives that seat some other way (direct UI input
 * into `TurnEngine`) instead.
 */
class TurnDriver(
    private val decisionsFor: (PlayerColor) -> TurnDecisionProvider,
    private val rollForPhase: (Phase) -> Int = { Dice.rollForPhase(it) },
) {
    /** Every player driven by the same [decisions], regardless of seat — the common
     * single-behavior case (also every existing test/simulation from before per-player behavior
     * was needed). */
    constructor(decisions: TurnDecisionProvider, rollForPhase: (Phase) -> Int = { Dice.rollForPhase(it) }) :
        this({ decisions }, rollForPhase)

    /** Each player driven by their own entry in [decisionsByPlayer] — the natural shape for "N
     * AI players, each a different behavior type." Throws if [driveOneTurn] is ever asked to
     * drive a player with no entry (a seat this map doesn't cover, e.g. a human player whose
     * turn should never have reached this driver in the first place — see the class doc). */
    constructor(decisionsByPlayer: Map<PlayerColor, TurnDecisionProvider>, rollForPhase: (Phase) -> Int = { Dice.rollForPhase(it) }) :
        this({ color -> decisionsByPlayer.getValue(color) }, rollForPhase)

    /**
     * Drives exactly one player's turn to completion: offers a pre-roll Held-card opportunity,
     * rolls, offers a post-roll/pre-move opportunity (Delayed Motion's window), asks that
     * player's own [TurnDecisionProvider] (via [decisionsFor]) which eligible token to move,
     * opens a Precedence window around that pending move, moves the chosen token, resolves
     * whatever that landing produces (including any Immediate card it draws, and any optional
     * offer), then ends the turn — chaining another turn for the same player on a "Go again"
     * square, otherwise advancing to the next eligible player/Phase (see [GameState.endTurn]).
     *
     * Returns false without doing anything if there's no current turn to drive — either the game
     * has already been won, or [GameState.skipEmptyPhases] hasn't been called yet on a
     * freshly-constructed [GameState] (call that first to prime the turn queue).
     */
    fun driveOneTurn(state: GameState): Boolean {
        val player = state.currentTurn ?: return false
        val decisions = decisionsFor(player)
        val phase = state.currentPhase
        val tier = (phase as? Phase.Tier)?.tier

        offerHeldCardPlay(state, player, decisions.chooseHeldCardBeforeRoll(state, player))
        if (endTurnIfGameWon(state)) return true

        val roll = state.beginPendingRoll(player, rollForPhase(phase))
        offerHeldCardPlay(state, player, decisions.chooseCardAfterRollBeforeMove(state, player, roll.total))
        if (endTurnIfGameWon(state)) return true

        val candidates = movableCandidates(state, player, tier)

        // Every player in the turn queue was already filtered on having something movable at the
        // moment the queue was built (PlayerState.hasTierTurn/hasMarauderTurn) — but that's a
        // point-in-time snapshot, not a standing guarantee: a Fate Harvest card played earlier
        // this same Phase, by any player (Divine Assistance, Graviton Rift, Plasma Burst,
        // Galactic Roundabout, ...), can legitimately destroy the last token this queued player
        // had left on this Tier before their own slot in the queue is reached, and this turn's
        // own pre-roll/post-roll held-card windows above can do the same. An earlier version
        // crashed here on exactly that (found by GameSimulationTest's randomized-play harness,
        // reachable within a few hundred turns of ordinary multi-card play) — there's nothing
        // malformed about this state, so end the turn gracefully instead: no token to choose,
        // nothing to move, same as a Phase with no eligible player at all, just discovered one
        // player-slot later than usual.
        if (candidates.isEmpty()) {
            state.clearPendingRoll()
            state.endTurn()
            return true
        }

        val chosen = decisions.chooseTokenToMove(state, player, candidates)
        require(chosen in candidates) { "TurnDecisionProvider.chooseTokenToMove must return one of the offered candidates, got $chosen" }

        // Rule 23's own worked example: a Precedence card may respond before a pending roll is
        // used to move a token. isSuspendedActionCancelled is meaningless for a PendingMove (an
        // Annulment with no preceding chain entry has nothing to cancel here — see
        // InteractionChain's own class doc), so the move always proceeds afterward.
        resolvePrecedenceWindow(state, SuspendedAction.PendingMove(player))
        if (endTurnIfGameWon(state)) return true

        val grantAnotherTurn = moveAndResolve(state, decisions, player, chosen, roll.total)
        state.clearPendingRoll()
        state.endTurn(grantAnotherTurn)
        return true
    }

    /**
     * "The first player to land on You Win! wins the game" (rulebook p.1) means the game ends
     * the instant that happens — not "once whatever else this turn was already doing finishes."
     * `GameState.declareWinner`/`declareSimultaneousWinners` themselves don't remove the winning
     * token from play or otherwise freeze anything (a winning token stays a perfectly ordinary
     * in-play token afterward, per `TurnEngine`'s own YOU_WIN landing) — nothing previously
     * stopped THIS turn's own remaining steps once a win happened mid-turn (a pre-roll held-card
     * play that itself wins, mid-turn, still let the SAME turn go on to also roll and move
     * another (or even the very same, now-winning) token afterward, and a Precedence response to
     * someone else's win could still destroy the winning token before this check ever ran). That
     * let the winner's own recorded color stay correct (`declareWinner`'s "first sticks" no-op
     * already made that safe) but let the "finished" game keep mutating regardless — found by
     * `GameSimulationTest`'s randomized-play harness across ~4000 simulated games as a winner
     * declared with the winning token later moved away from (or destroyed off of) its own
     * YOU_WIN square, within the very same turn that won. Checked after every point in
     * [driveOneTurn] that could newly produce a winner before its own turn-ending move — a
     * held-card play (either window) or a Precedence response to the pending move; nothing needs
     * checking after the move itself, since that's already the last thing [driveOneTurn] does.
     * Returns true (turn handled) if the game is now won, ending the turn immediately without
     * doing anything further.
     */
    private fun endTurnIfGameWon(state: GameState): Boolean {
        if (state.winners.isEmpty()) return false
        state.clearPendingRoll()
        state.endTurn()
        return true
    }

    /** Every token [player] could choose to move this turn: for a Tier Phase, [tier]'s own Tier
     * tokens (main-loop or Zone-resident alike — a Zone resident is just as legal a choice, per
     * the confirmed "a Zone is a dice-driven sub-path" ruling); for the Marauder Phase
     * ([tier] null), every Marauder in play across all four Tiers (a single Marauder-Phase turn
     * lets the player pick which one of their Marauders moves, per [com.tiersofexistence.engine
     * .state.PlayerState.hasMarauderTurn]'s "any Tier" eligibility). */
    private fun movableCandidates(state: GameState, player: PlayerColor, tier: TierLevel?): List<TokenId> {
        val playerState = state.players.getValue(player)
        return if (tier != null) {
            val pool = playerState.tierPool(tier)
            pool.inPlayIds + pool.zoneResidentIds
        } else {
            TierLevel.entries.flatMap { playerState.marauders.inPlayIds(it) }
        }
    }

    /** Moves [id] by [spaces] and resolves whatever that landing produces, returning true if the
     * turn should chain (a "Go again" square). Uses the identity-based movers
     * ([TurnEngine.moveTierTokenById]/[TurnEngine.moveMarauderById]/[TurnEngine.moveZoneToken])
     * throughout rather than resolving a position and moving by position — [id] is already known
     * exactly, and more than one of the player's own tokens can legally share a position
     * (stacking), so a position-based move risks silently moving the wrong one). */
    private fun moveAndResolve(state: GameState, decisions: TurnDecisionProvider, player: PlayerColor, id: TokenId, spaces: Int): Boolean =
        when (id.kind) {
            TokenKind.TIER_TOKEN -> {
                val moveResult = when (TokenLocator.locate(state, id)) {
                    is TokenLocation.InPlay -> TurnEngine.moveTierTokenById(state, id, spaces)
                    is TokenLocation.InZone -> when (val zoneResult = TurnEngine.moveZoneToken(state, id, spaces)) {
                        is ZoneMoveResult.ExitedZone -> zoneResult.moveResult
                        is ZoneMoveResult.StillInZone -> {
                            // A Zone-internal square carries no turn-chaining/offer effect this
                            // driver needs to act on beyond a possible Immediate card draw (see
                            // TurnEngine.resolveZoneLanding) — only the "exited back onto the
                            // main loop" case can produce anything else.
                            resolveZoneInternalEffect(state, decisions, player, id.tier, zoneResult.zoneNumber, zoneResult.effect)
                            null
                        }
                    }
                    // Legitimately reachable, not a TurnDecisionProvider bug: chooseTokenToMove
                    // returned a token that DID exist at that moment, but the Precedence window
                    // opened right afterward (SuspendedAction.PendingMove, rule 23's own
                    // checkpoint) can destroy exactly this token before it ever moves — e.g. an
                    // opponent responds with a destruction card targeting the chosen token
                    // instead of rescuing something else, the mirror image of the rule 23 worked
                    // example this driver already handles (found by GameSimulationTest's
                    // randomized-play harness, which previously crashed the whole turn loop
                    // here). Nothing to move and nothing landed on, so there's no landing effect
                    // to resolve — the turn simply ends without a move, same as any other turn
                    // that doesn't chain into a "Go again."
                    TokenLocation.NoLongerExists -> null
                }
                moveResult?.let { resolveTierEffect(state, decisions, player, id.tier, it) } ?: false
            }
            TokenKind.MARAUDER -> {
                // Same legitimately-reachable staleness as the TIER_TOKEN branch above (rule
                // 23's PendingMove Precedence window can destroy the chosen Marauder before it
                // moves — e.g. an opponent's Divine Assistance targeting it instead of rescuing
                // something else) — TurnEngine.moveMarauderById `require`s the Marauder still
                // exists and previously crashed the whole turn loop on this exact input (found
                // by GameSimulationTest's randomized-play harness). Nothing to move, so no
                // Marauder-landing effect to resolve — the turn simply ends without a move.
                if (TokenLocator.locate(state, id) is TokenLocation.NoLongerExists) {
                    false
                } else {
                    resolveMarauderEffect(state, decisions, player, id.tier, TurnEngine.moveMarauderById(state, id, spaces))
                }
            }
        }

    /** Acts on a Tier token's landing effect, asking [decisions] about any optional offer and
     * resolving a drawn Immediate card (see class doc). Returns true only for [SquareEffect.GoAgain]. */
    private fun resolveTierEffect(state: GameState, decisions: TurnDecisionProvider, player: PlayerColor, tier: TierLevel, result: MoveResult): Boolean {
        when (val effect = result.effect) {
            is SquareEffect.MayEnterZone -> {
                if (decisions.chooseEnterZone(state, player, tier, effect.zoneNumber)) {
                    TurnEngine.enterZoneOfProtection(state, player, tier, result.finalPosition, effect.zoneNumber)
                }
            }
            SquareEffect.MayBuildMarauder -> {
                if (decisions.chooseBuildMarauder(state, player, tier)) {
                    TurnEngine.buildMarauder(state, player, tier)
                }
            }
            is SquareEffect.DrewCard -> {
                if (effect.card.timing == CardTiming.IMMEDIATE) {
                    resolveImmediateCard(state, decisions, player, effect.card, tier, result.finalPosition)
                }
                // A HELD card was already added to the drawing player's hand directly by
                // TurnEngine itself (unchanged, pre-existing behavior) — nothing more to do here.
            }
            SquareEffect.GoAgain -> return true
            else -> Unit // None, SentToStagingPile, SentToStart, Promoted, Won, Destroyed,
            // LoseNextTierTurn/GrantedExtraTierTurn (already auto-applied inside TurnEngine),
            // MayTransport (Tier tokens never land on a Marauder Transport's effect).
        }
        return false
    }

    /** Acts on a Zone-internal square's effect ([ZoneMoveResult.StillInZone]) — the only thing
     * that can happen here that this driver needs to react to is drawing a mandatory Immediate
     * card from a Zone-internal Fate Harvest square (a Zone can never contain a "Go again"/
     * Marauder-offer/Zone-entry square, so every other [SquareEffect] case is structurally
     * impossible here). [squarePosition] for the resulting [TriggeringEvent.DrawnFromSquare] is
     * the Zone's own main-loop entry square — there's no numbered square inside a Zone itself to
     * report, and no resolver currently reads this field for anything beyond record-keeping. */
    private fun resolveZoneInternalEffect(state: GameState, decisions: TurnDecisionProvider, player: PlayerColor, tier: TierLevel, zoneNumber: Int, effect: SquareEffect) {
        if (effect is SquareEffect.DrewCard && effect.card.timing == CardTiming.IMMEDIATE) {
            val squarePosition = state.boards.getValue(tier).zoneEntryIndex(zoneNumber)
            resolveImmediateCard(state, decisions, player, effect.card, tier, squarePosition)
        }
    }

    /**
     * Resolves a mandatory Immediate card (rule 14) the instant it's drawn: asks [decisions] for
     * its target(s) via [TurnDecisionProvider.chooseImmediateCardTargets], then plays it through
     * the normal Precedence-aware path ([playWithPrecedenceWindow]) exactly like any other card.
     *
     * If the play comes back [CardPlayResult.Rejected] (no legal target exists right now — the
     * rulebook doesn't say what should happen to a mandatory card that genuinely has nowhere to
     * land, and `docs/card-mechanics-matrix.md` has no ruling on it either), this does NOT invent
     * a substitute effect: the drawn card is simply discarded. It was already removed from the
     * deck by drawing it, is never held (Immediate cards never enter a hand), and can't be
     * silently dropped either — discarding it is the only place left for it to go that doesn't
     * fabricate new behavior.
     */
    private fun resolveImmediateCard(state: GameState, decisions: TurnDecisionProvider, player: PlayerColor, card: FateHarvestCard, tier: TierLevel, squarePosition: Int) {
        val targets = decisions.chooseImmediateCardTargets(state, player, card)
        val request = CardPlayRequest(player, card, targets, TriggeringEvent.DrawnFromSquare(tier, squarePosition))
        val result = playWithPrecedenceWindow(state, request)
        if (result is CardPlayResult.Rejected) {
            state.deck.discard(card)
        }
    }

    /**
     * [choice] is [player]'s decision at a legal Held-card-play opportunity (before rolling, or
     * after rolling/before moving) — null means they declined, so this does nothing. Otherwise
     * removes [CardChoice.card] from [player]'s hand, plays it through
     * [playWithPrecedenceWindow], and returns it to hand if the play turns out illegal
     * ([CardPlayResult.Rejected]) — matching [com.tiersofexistence.engine.cards.play.CardLifecycle
     * .playFromHand]'s own established "a rejected play never consumes the card" contract. A play
     * that resolves normally, or comes back [CardPlayResult.AwaitingDecision] (already
     * legitimately committed — see [com.tiersofexistence.engine.cards.resolvers.CleansingResolver]),
     * does not return it.
     */
    private fun offerHeldCardPlay(state: GameState, player: PlayerColor, choice: CardChoice?) {
        if (choice == null) return
        val hand = state.players.getValue(player).hand
        require(hand.remove(choice.card)) {
            "TurnDecisionProvider chose ${choice.card.name} to play from hand, but $player doesn't have it"
        }
        val request = CardPlayRequest(player, choice.card, choice.targets, TriggeringEvent.PlayedFromHand)
        val result = playWithPrecedenceWindow(state, request)
        if (result is CardPlayResult.Rejected) hand += choice.card
    }

    /** Acts on a Marauder's landing effect — the only offer a Marauder can get is
     * [SquareEffect.MayTransport]; a Marauder never chains a turn. */
    private fun resolveMarauderEffect(state: GameState, decisions: TurnDecisionProvider, player: PlayerColor, tier: TierLevel, result: MoveResult): Boolean {
        if (result.effect is SquareEffect.MayTransport) {
            val options = listOfNotNull(tier.next(), tier.previous())
            val choice = decisions.chooseTransport(state, player, tier, options)
            if (choice != null) TurnEngine.transportMarauder(state, player, tier, choice, result.finalPosition)
        }
        return false
    }

    /**
     * Opens a Precedence-response window (rules 20-23) around [request] before it actually
     * resolves, offers every seated player one or more response rounds via
     * [resolvePrecedenceWindow], then either resolves [request] itself or, if an Annulment
     * cancelled it (see [InteractionChain.isSuspendedActionCancelled] — only meaningful for a
     * [SuspendedAction.PendingCardResolution]), discards [request]'s own card without resolving
     * it and returns null. A cancelled play is NOT a [CardPlayResult.Rejected] — it was
     * legitimately played and would have resolved, its effect was specifically annulled — so it's
     * never returned to a hand, matching how a cancelled [com.tiersofexistence.engine.rules
     * .precedence.ChainEntry] already never gets its own card back either (see
     * [resolvePrecedenceWindow]'s doc).
     */
    private fun playWithPrecedenceWindow(state: GameState, request: CardPlayRequest): CardPlayResult? {
        val chain = resolvePrecedenceWindow(state, SuspendedAction.PendingCardResolution(request))
        if (chain.isSuspendedActionCancelled) {
            state.deck.discard(request.card)
            return null
        }
        val result = CardEffectDispatcher.dispatch(state, request)
        resolveAwaitingDecision(state, result)
        return result
    }

    /**
     * If [result] is [CardPlayResult.AwaitingDecision], resolves the pending decision right
     * away — currently only ever [PendingDecision.OpponentDiscardChoice] (Cleansing; no resolver
     * produces [PendingDecision.PrecedenceWindowOpen] today, since this class's own
     * [resolvePrecedenceWindow] handles every Precedence window directly rather than routing
     * through this pending-decision vocabulary). For anything else, this is a no-op.
     *
     * Cleansing's own text is explicit that the *targeted opponent* — never the player who
     * played Cleansing — chooses which of their own held cards to discard, so this always asks
     * [decidingPlayer]'s own [TurnDecisionProvider] (via [decisionsFor]), never [result]
     * .request's source player's, and never reads [decidingPlayer]'s hand into anything the
     * source player's own provider could see — the eligible-cards list is built here and handed
     * only to [decidingPlayer]'s provider.
     */
    private fun resolveAwaitingDecision(state: GameState, result: CardPlayResult) {
        if (result !is CardPlayResult.AwaitingDecision) return
        when (val pending = result.pending) {
            is PendingDecision.OpponentDiscardChoice -> {
                val decidingPlayer = pending.decidingPlayer
                val eligibleCards = state.players.getValue(decidingPlayer).hand.toList()
                val chosen = decisionsFor(decidingPlayer).chooseCleansingDiscard(state, decidingPlayer, result.request.sourcePlayer, eligibleCards)
                require(chosen in eligibleCards) {
                    "chooseCleansingDiscard must return one of the offered eligibleCards, got $chosen"
                }
                CleansingResolver.completeDiscard(state, decidingPlayer, chosen)
            }
            is PendingDecision.PrecedenceWindowOpen -> Unit
        }
    }

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
     * Precedence card is [CardTiming.HELD] — see `FateHarvestCatalog` — so none can produce
     * [CardPlayResult.EnteredHand], and none of the 6 currently trigger
     * [CardPlayResult.AwaitingDecision].)
     *
     * Returns the finished (RESOLVED-state) chain so callers that need
     * [InteractionChain.isSuspendedActionCancelled] can check it — meaningless for anything but a
     * [SuspendedAction.PendingCardResolution].
     */
    private fun resolvePrecedenceWindow(state: GameState, suspendedAction: SuspendedAction): InteractionChain {
        val chain = InteractionChain.open(suspendedAction, eligiblePlayers = state.turnOrder.order)
        offerResponseRounds(state, chain)
        val order = chain.resolve()
        val results = CardEffectDispatcher.dispatchAll(state, order)
        chain.finishResolving()
        val resolvedEntryIds = order.zip(results).filter { (_, result) -> result is CardPlayResult.Resolved }.map { it.first.id }.toSet()
        chain.entriesSnapshot().forEach { entry ->
            if (entry.id !in resolvedEntryIds) state.deck.discard(entry.request.card)
        }
        return chain
    }

    /**
     * Repeatedly offers every currently-eligible player (per [InteractionChain.currentlyEligibleToAct])
     * a chance to respond via [TurnDecisionProvider.choosePrecedenceResponse] (resolved per-player
     * through [decisionsFor], since a response can come from any seated player, not just the one
     * whose turn it is) or pass, until the window closes on its own. A player who chooses to
     * respond has that card removed from their hand right here, before [chain.respond] adds the
     * entry — see [resolvePrecedenceWindow]'s doc for what happens to it from there.
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
                chain.respond(player, CardPlayRequest(player, choice.card, choice.targets, TriggeringEvent.RespondingInChain(chain.id)))
            }
        }
    }
}
