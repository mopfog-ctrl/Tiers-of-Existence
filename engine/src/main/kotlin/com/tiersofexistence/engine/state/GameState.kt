package com.tiersofexistence.engine.state

import com.tiersofexistence.engine.board.BoardLayouts
import com.tiersofexistence.engine.board.TierBoard
import com.tiersofexistence.engine.cards.FateHarvestCard
import com.tiersofexistence.engine.cards.FateHarvestDeck
import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.rules.DeferredTurnModifier
import com.tiersofexistence.engine.rules.Phase
import com.tiersofexistence.engine.rules.PendingRoll
import com.tiersofexistence.engine.rules.TurnOrder

/**
 * Thrown by [GameState.skipEmptyPhases] when a full Phase cycle has been traversed with no
 * player eligible for a turn in any Phase and no winner declared — an engine invariant
 * violation (malformed/corrupted [GameState]), never a gameplay outcome. Deliberately not
 * modeled as a draw/loss/elimination result: ordinary canonical play can never legally reach
 * this state (see [GameState.skipEmptyPhases]'s own doc for why), so there is no rule to encode
 * for it, only a fail-safe against an unbounded loop if it's ever somehow reached anyway (e.g.
 * a hand-built test [GameState] with every pool emptied out).
 */
class GameStalledException(message: String) : IllegalStateException(message)

/**
 * Top-level mutable game state: one entry per player, the four boards, the Fate Harvest
 * deck, and where we are in the Round/Phase cycle.
 *
 * Each player starts with a single 1st Tier token on the Birth Canal/Start square, since
 * "Players will only have a 1st Tier token when starting the game, and therefore the first
 * Round of the game only has a 1st Tier Phase" (Rounds, Phases, and Turns, p.4).
 */
class GameState(
    val players: Map<PlayerColor, PlayerState>,
    val turnOrder: TurnOrder,
    val boards: Map<TierLevel, TierBoard> = BoardLayouts.current(),
    val deck: FateHarvestDeck = FateHarvestDeck.newShuffled(),
) {
    var roundNumber: Int = 1
        private set

    var phaseIndex: Int = 0
        private set

    private val _winners: MutableSet<PlayerColor> = mutableSetOf()

    /** Every declared winner — normally at most one ([declareWinner]'s "first sticks" rule),
     * but a genuine tie is possible: [declareSimultaneousWinners] (Galactic Roundabout is the
     * only card that can trigger it) can populate this with more than one color at once. */
    val winners: Set<PlayerColor> get() = _winners

    /** The first declared winner, or null if none yet — kept for the common (non-tied) case;
     * see [winners] for the full, tie-aware set. */
    val winner: PlayerColor? get() = _winners.firstOrNull()

    /**
     * True once any winner has been declared ([declareWinner]/[declareSimultaneousWinners]) —
     * the explicit, named domain check for "is this game over," so a caller doesn't need to spell
     * out `winners.isNotEmpty()` itself at every checkpoint.
     *
     * **This is a query, not an enforcement mechanism.** Nothing in [GameState], `TurnEngine`, a
     * token pool, or a card resolver refuses a mutation just because this is true, and none of
     * them ever will — those stay pure, turn-agnostic mechanical primitives on purpose (see
     * `TurnEngine`'s own class doc), and a hard refusal down there would trade a harmless-if-
     * pointless post-win mutation for a brand new crash risk on a state those layers have no way
     * to reason about (a card resolver, for instance, has no notion of "whose turn's own
     * remaining steps this mutation belongs to"). "The first player to land on You Win! wins the
     * game" (rulebook p.1) means the game ends the instant that happens, not "once whatever else
     * was already in progress finishes" — but enforcing that is inherently the job of whatever is
     * sequencing a turn's discrete steps (roll, offer a card, move, ...), since nothing below that
     * orchestration layer can see where one logical turn's steps end.
     *
     * **Every orchestrator that drives a turn as a sequence of discrete steps is responsible for
     * checking this between steps and stopping immediately once it becomes true** — not just
     * before the next full turn, but before continuing the CURRENT one. [com.tiersofexistence
     * .engine.rules.TurnDriver.driveOneTurn] is the reference implementation (see its private
     * `endTurnIfGameWon`, checked after every point in a turn that could newly produce a winner
     * before its own turn-ending move) and the reason this property exists: an earlier version had
     * no named way to ask this and simply never checked mid-turn, so a win declared partway
     * through a turn (e.g. by an early held-card play) didn't stop that same turn from going on to
     * roll and move another token anyway, sometimes moving the very token that just won off its
     * own `YOU_WIN` square — found by `GameSimulationTest`'s randomized-play harness across
     * ~4000 simulated games. `TurnDriver`'s own `TurnDecisionProvider`-driven turns are the only
     * orchestrator that exists today, but this class's own doc already anticipates a second one
     * (a human player's turn, driven by direct UI input rather than `driveOneTurn`) — whatever
     * that turns out to look like, it owns this exact same responsibility, and this property is
     * what lets it discover and follow the same convention `TurnDriver` already does, rather than
     * needing to independently reinvent (or forget) it.
     */
    val isOver: Boolean get() = _winners.isNotEmpty()

    val currentPhase: Phase get() = Phase.ROUND_ORDER[phaseIndex]

    /** Players still owed a turn in [currentPhase], in the order they'll take it. */
    private var turnQueue: ArrayDeque<PlayerColor> = ArrayDeque()

    /** Queued [DeferredTurnModifier]s not yet consumed — see [queueExtraTierTurn]. */
    private val deferredModifiers: MutableList<DeferredTurnModifier> = mutableListOf()

    /**
     * Pending skip debt per (player, Tier) — **confirmed canon**: independent
     * [queueSkipNextTierTurn] triggers against the same (player, Tier) stack; each one removes
     * exactly one future turn that player would otherwise actually receive on that Tier. An
     * explicit counter rather than one list entry per trigger specifically to avoid the
     * collapse bug an earlier list-based representation had (multiple entries matching the same
     * occurrence were consumed together in one `buildTurnQueue()` call instead of one entry
     * consuming one future occurrence each) — see [buildTurnQueue] for where debt is actually
     * consumed, only when the player is otherwise eligible for a turn there, never merely
     * because the Phase occurs.
     */
    private val pendingSkips: MutableMap<Pair<PlayerColor, TierLevel>, Int> = mutableMapOf()

    /** The in-progress turn's roll, once rolled but before it's been used to move a token — the
     * checkpoint Delayed Motion needs (see [PendingRoll]'s class doc). Null whenever no roll is
     * currently pending; see [beginPendingRoll]/[clearPendingRoll]. */
    var pendingRoll: PendingRoll? = null
        private set

    private val _resolvingCards: MutableList<FateHarvestCard> = mutableListOf()

    /**
     * Every [FateHarvestCard] currently drawn but not yet in any other zone — a physical card
     * has left the deck's own draw pile but hasn't reached the discard pile or a hand yet,
     * because whoever is driving the game is still in the middle of resolving it (choosing its
     * target(s), running it through a Precedence window, waiting on a triggered decision like
     * Cleansing's opponent discard, or similar). A drawn [com.tiersofexistence.engine.cards.CardTiming.HELD]
     * card never appears here at all — [TurnEngine][com.tiersofexistence.engine.rules.TurnEngine]
     * puts it straight into the drawing player's hand, so it's a live card in the hand zone from
     * the instant it's drawn, no separate "resolving" state needed.
     *
     * **This is a genuine fourth card zone, not a derived/best-effort count** — before this
     * existed, a card between [FateHarvestDeck.draw] and its own eventual [FateHarvestDeck
     * .discard] lived only as a local variable in whatever code was resolving it, invisible to
     * every other part of the engine; nothing needed to see it *until* an experimental reshuffle
     * mechanism needed to account for a card type's whole-game population and undercounted
     * exactly this window (found via `PlayerCountReincarnationCorrectedBenchmarkTest`'s
     * whole-game rarity-ceiling check). [beginResolvingCard]/[endResolvingCard] make the
     * transition explicit and symmetric — every drawn Immediate card enters this zone exactly
     * once (at [TurnEngine.resolvePrimaryTierLanding][com.tiersofexistence.engine.rules.TurnEngine]/
     * the Zone-internal equivalent, its two draw sites) and leaves it exactly once (wherever its
     * own resolution actually discards it — [com.tiersofexistence.engine.cards.play.CardLifecycle
     * .attemptPlay]'s own discard, [com.tiersofexistence.engine.rules.TurnDriver]'s
     * Rejected-play/Annulment-cancelled discards, or
     * [com.tiersofexistence.engine.cards.resolvers.discardStrandedImmediateCard] for a
     * card-driven-move landing) — so a card can never be double-counted (still "resolving" after
     * it's already been added to the discard pile) or lost (removed from the deck but present in
     * no zone at all). A plain `MutableList`, not a count, because more than one physical card
     * can genuinely be resolving at once — nesting is possible (one card's own resolution moving
     * a token onto another Fate Harvest square before the first card has finished resolving) and
     * this collection makes no assumption about how many are ever in flight simultaneously.
     *
     * Always empty by the time [com.tiersofexistence.engine.rules.TurnDriver.driveOneTurn]
     * returns — every path that adds a card here resolves it synchronously within the same
     * turn, so this is a *within-turn* accounting zone only, never something that needs to
     * survive across turns the way [pendingRoll] briefly can't either.
     */
    val resolvingCards: List<FateHarvestCard> get() = _resolvingCards.toList()

    /** Marks [card] as drawn-but-not-yet-resolved — see [resolvingCards]'s own doc for exactly
     * when this is called and why. Never called for a [com.tiersofexistence.engine.cards.CardTiming.HELD]
     * card (already live in a hand the instant it's drawn). */
    fun beginResolvingCard(card: FateHarvestCard) {
        _resolvingCards += card
    }

    /** The counterpart to [beginResolvingCard] — called at the exact point [card]'s own
     * resolution reaches ITS discard (regardless of which of the several discard paths
     * [resolvingCards]'s own doc lists actually fires for this particular play), so the
     * transition out of the "resolving" zone is symmetric with the transition into it. Throws if
     * [card] isn't currently resolving — the same "never silently no-op a lifecycle mismatch"
     * discipline [PlayerState.hand]'s own `require(hand.remove(...))` calls already use elsewhere
     * in this codebase, since a card that reaches here without ever having begun resolving (or
     * that's already been ended) means a caller's own bookkeeping is wrong, not a legitimate
     * state to paper over. */
    fun endResolvingCard(card: FateHarvestCard) {
        check(_resolvingCards.remove(card)) {
            "endResolvingCard(${card.name}) called but that card is not currently resolving " +
                "(resolvingCards=${_resolvingCards.map { it.name }}) - a caller's begin/end pairing is wrong"
        }
    }

    /**
     * Every [FateHarvestCard] currently live *outside* [deck]'s own discard pile: every player's
     * hand plus every card currently mid-resolution ([resolvingCards]) — never the draw pile
     * itself, since [FateHarvestDeck.draw] only ever needs this (via a
     * [FateHarvestDeck.ReshuffleStrategy] that wants to respect each card type's own whole-game
     * rarity ceiling) at the exact moment the draw pile is already empty. Grouped by
     * [FateHarvestCard.name] to a physical-copy count, matching how every other pile/hand
     * multiplicity in this codebase is already counted. This is the single, correct source for
     * "how many copies of this type exist outside the pile being regenerated right now" — see
     * `com.tiersofexistence.engine.benchmark.FateHarvestRegenerationRules`'s own class doc for
     * how a `ReshuffleStrategy` uses it.
     */
    fun liveCardCountsOutsideDiscardPile(): Map<String, Int> =
        (players.values.flatMap { it.hand } + resolvingCards).groupingBy { it.name }.eachCount()

    /** Total outstanding skip-turn debt across every (player, Tier) pair — the sum of
     * [pendingSkips]'s own per-pair counters. A read-only observability accessor, added
     * specifically so external instrumentation can correlate this debt against other
     * outcomes (see `com.tiersofexistence.engine.benchmark
     * .PlayerCountStructuralPredictorAnalysisTest`) — it mutates nothing and no gameplay logic
     * reads it; [pendingSkips] itself, and how debt is queued/consumed, are completely
     * unchanged by this property's existence. */
    val totalPendingSkipDebt: Int get() = pendingSkips.values.sum()

    /** How many [DeferredTurnModifier.ExtraTierTurn] entries are still queued and unconsumed —
     * same read-only observability rationale as [totalPendingSkipDebt]. [DeferredTurnModifier]
     * currently has only that one subtype (see its own class doc), so this is simply
     * [deferredModifiers]'s own size, not filtered by type. */
    val totalPendingExtraTierTurns: Int get() = deferredModifiers.size

    init {
        turnQueue = buildTurnQueue()
    }

    /** The color whose turn it currently is within [currentPhase], or null if the queue is empty
     * (e.g. right after construction, before anyone has driven the game with [endTurn]/[skipEmptyPhases]). */
    val currentTurn: PlayerColor? get() = turnQueue.firstOrNull()

    /** Advances to the next Phase, wrapping to a new Round after the 1st Tier Phase. */
    fun advancePhase() {
        players.values.forEach { it.hasPlayedCardThisPhase = false }
        phaseIndex += 1
        if (phaseIndex >= Phase.ROUND_ORDER.size) {
            phaseIndex = 0
            roundNumber += 1
        }
        turnQueue = buildTurnQueue()
    }

    /** The seating-order turn list for [currentPhase], with any pending skip debt
     * ([pendingSkips]) and queued [DeferredTurnModifier]s for the current Tier applied and
     * consumed. Marauder Phase is untouched — both are always Tier-turn-specific (see the class
     * docs on [pendingSkips] and [DeferredTurnModifier]). */
    private fun buildTurnQueue(): ArrayDeque<PlayerColor> {
        var base = turnOrder.turnsFor(currentPhase, players)
        val tier = (currentPhase as? Phase.Tier)?.tier
        if (tier != null) {
            // Consume exactly one pending skip per otherwise-eligible player — never merely
            // because this Tier's Phase occurred while they had no eligible turn here at all
            // (a player not in `base` has no debt touched, so it stays pending for whenever
            // they're next actually eligible on this Tier).
            base = base.filterNot { color ->
                val key = color to tier
                val debt = pendingSkips[key] ?: 0
                if (debt <= 0) return@filterNot false
                val remaining = debt - 1
                if (remaining > 0) pendingSkips[key] = remaining else pendingSkips.remove(key)
                true
            }

            val extras = deferredModifiers.filterIsInstance<DeferredTurnModifier.ExtraTierTurn>()
                .filter { it.tier == tier && it.player in base }
            if (extras.isNotEmpty()) {
                val withExtras = base.toMutableList()
                // Insert each extra turn immediately after that player's normal slot, in the
                // order the extras were queued — a player's own list index shifts as earlier
                // insertions land, so re-look-up each time rather than computing offsets once.
                extras.forEach { extra -> withExtras.add(withExtras.indexOf(extra.player) + 1, extra.player) }
                base = withExtras
            }
            deferredModifiers.removeAll(extras)
        }
        return ArrayDeque(base)
    }

    /**
     * Queues [player] to skip one future turn on [tier] only — Phase Loss, or the "Lose next
     * turn on this Tier" Time Wrinkle square. Never affects a turn already in progress (the
     * card/square is always resolved as part of the current turn's own move) — takes effect
     * starting the next time [player] would otherwise actually be eligible for a turn when
     * [tier]'s Phase turn queue is built (see [buildTurnQueue]).
     *
     * **Confirmed canon: independent skip triggers against the same (player, Tier) stack.**
     * Calling this twice before either skip is consumed queues 2 separate future turns to skip,
     * not 1 — tracked as debt in [pendingSkips], incremented here by exactly 1 each call. A skip
     * queued for one Tier never affects any other Tier, and never affects the Marauder Phase.
     */
    fun queueSkipNextTierTurn(player: PlayerColor, tier: TierLevel) {
        val key = player to tier
        pendingSkips[key] = (pendingSkips[key] ?: 0) + 1
    }

    /**
     * Grants [player] one extra turn on [tier], taken after their normal turn there — Phase
     * Control, or the "Take an extra turn, First Tier" Time Wrinkle square. If [tier]'s Phase is
     * the one currently active and [player] hasn't taken their turn in it yet this Round, the
     * extra turn is spliced into the live queue immediately after their upcoming turn (matching
     * "an extra turn taken after your normal turn there"). Otherwise it's queued for the next
     * time [tier]'s Phase turn queue is built.
     *
     * Phase Control's own text also describes a second case — "if your turn on that Tier already
     * ended this Round, play it immediately" — which would mean interrupting whatever's
     * currently resolving out of normal Phase order. That's deliberately NOT implemented here
     * (see `docs/card-mechanics-matrix.md` §4 Q15: genuinely ambiguous whether "immediately"
     * means a true interrupt or "next, once the current action finishes," and this engine has no
     * interrupt mechanism for non-Precedence Immediate cards regardless). Calling this method in
     * that situation falls back to queuing for [tier]'s next occurrence (next Round) rather than
     * granting the turn out-of-sequence — a conservative default, not a confirmed ruling.
     */
    fun queueExtraTierTurn(player: PlayerColor, tier: TierLevel) {
        val phase = currentPhase
        if (phase is Phase.Tier && phase.tier == tier) {
            val idx = turnQueue.indexOf(player)
            if (idx >= 0) {
                turnQueue.add(idx + 1, player)
                return
            }
        }
        deferredModifiers += DeferredTurnModifier.ExtraTierTurn(player, tier)
    }

    /**
     * Advances Phases (via [advancePhase]) until [currentPhase] has at least one eligible
     * player, or the game's been won — "Phases skip when there's nothing to be done in them."
     * Idempotent: a no-op if [currentTurn] is already non-null. Call this once to kick off
     * turn-driving on a fresh [GameState] (a new game always starts on the empty Marauder
     * Phase); [endTurn] calls it automatically afterward.
     *
     * Ordinary empty-Phase skipping is unbounded-loop-shaped on purpose — several consecutive
     * empty upper-Tier Phases skipping straight through to whichever Phase actually has a turn
     * is normal, canonical behavior, not something this method second-guesses. The one thing it
     * guards against is a genuinely impossible/corrupted state: no Phase has an eligible turn
     * *and* no winner has been declared, so the ordinary loop would spin forever. That can't
     * happen in legally-reached play (the 1st Tier's own auto-replenishment — see
     * [com.tiersofexistence.engine.state.TierTokenPool.refillInPlayIfRoom] — keeps that Phase
     * eligible for as long as any player has 1st Tier tokens left anywhere, and the game ends
     * once someone wins), but a deliberately/accidentally malformed [GameState] (e.g. hand-built
     * in a test with every pool emptied out) could reach it. This is a defensive engine-invariant
     * check, not a new gameplay rule — it never produces a draw/loss/elimination outcome, only
     * [GameStalledException] once [STALLED_STATE_PHASE_THRESHOLD] Phases have been traversed
     * without finding a single eligible turn.
     *
     * **[STALLED_STATE_PHASE_THRESHOLD] is deliberately many full Phase cycles, not one.** An
     * earlier version of this fail-safe used exactly one cycle ([Phase.ROUND_ORDER]'s length,
     * 5) — this was too tight, and a real test caught it: skip debt (see [pendingSkips]) can
     * legitimately defer a player's next eligible turn on a Tier past one full cycle, in a very
     * sparse game (few players/Tiers active) — the Round whose occurrence a skip consumes,
     * *plus* the following Round before that (player, Tier) is checked again, needs 2 full
     * cycles (10 Phases) in the sparsest case for just one queued skip, not 1.
     *
     * **Confirmed canon: independent skip triggers against the same (player, Tier) genuinely
     * stack.** [pendingSkips] tracks this as an explicit debt count — 2 independent triggers
     * mean 2 separate future occurrences are each skipped (consuming one unit of debt each)
     * before the player is eligible again, not one shared occurrence. (An earlier version of
     * both [pendingSkips]'s implementation and this doc had that backwards — a list-based
     * representation collapsed multiple matching entries together in one `buildTurnQueue()`
     * call instead of one at a time; fixed and confirmed as genuine canon, not just an
     * implementation detail, by the user.) In the sparsest single-player case, N stacked skips
     * against the same (player, Tier) need N+1 occurrences — 5×(N+1) Phases — before that
     * player is eligible again; see `GameStateTest`'s stacking test section for the concrete
     * N=1 and N=2 cases (and the 1st-Tier-auto-replenishment-independence and cross-Tier/
     * cross-player isolation cases alongside them).
     *
     * This is still not remotely reachable in ordinary 2-6 player play — some other player
     * almost always has *some* eligible turn within the same Round, so [skipEmptyPhases]'s
     * search pauses there long before any single player's skip debt would matter to it; a
     * legitimately sparse/edge-case [GameState] can hit the 5×(N+1)-Phase gap for a real
     * (if artificially large) N without being corrupted at all. The threshold is kept
     * generously above even a large N (500 Phases comfortably covers N up to 99) rather than
     * tuned tightly to whatever the most plausible N is, since legitimate stacking has no hard
     * ceiling this class can cheaply prove and a generous margin costs nothing — see
     * `GameStateTest`'s "50 stacked skips...does not falsely trigger the stalled-state
     * fail-safe" test for a concrete large-N confirmation this threshold still comfortably
     * holds after the collapse-to-stacking correction. A truly malformed state (e.g. every pool
     * emptied out) never finds anyone eligible no matter how far this searches, so it's never
     * confused with a legitimate stacked-skip gap regardless of how large.
     */
    fun skipEmptyPhases() {
        var phasesTraversedThisSearch = 0
        while (turnQueue.isEmpty() && winner == null) {
            advancePhase()
            phasesTraversedThisSearch += 1
            if (phasesTraversedThisSearch > STALLED_STATE_PHASE_THRESHOLD) {
                throw GameStalledException(
                    "GameState.skipEmptyPhases traversed $STALLED_STATE_PHASE_THRESHOLD Phases " +
                        "(${STALLED_STATE_PHASE_THRESHOLD / Phase.ROUND_ORDER.size} full cycles of " +
                        "${Phase.ROUND_ORDER}) starting from Round $roundNumber without finding any player " +
                        "with an eligible turn in any Phase, and no winner is declared. This is not a legal " +
                        "gameplay outcome (ordinary play always keeps at least the 1st Tier Phase eligible " +
                        "for any player with 1st Tier tokens remaining, per TierTokenPool's auto-" +
                        "replenishment, and this threshold already generously allows for legitimate " +
                        "DeferredTurnModifier-stacked skip gaps in a sparse game) — it indicates a malformed " +
                        "or corrupted GameState (e.g. every player's token pools emptied out with no winner " +
                        "declared), not a stalemate the game itself should ever reach.",
                )
            }
        }
    }

    /**
     * Ends [currentTurn]'s turn and moves to the next eligible player, per "a player's turn on
     * a Tier ends when they can no longer move nor play a card." Pass [grantAnotherTurn] = true
     * for a chained extra turn (a "Go again" Time Wrinkle square, or the Phase Control card) —
     * this keeps the same player active instead of advancing to the next color. Automatically
     * skips to the next Phase with an eligible player once the queue empties (see
     * [skipEmptyPhases]).
     */
    fun endTurn(grantAnotherTurn: Boolean = false) {
        if (!grantAnotherTurn) turnQueue.removeFirstOrNull()
        skipEmptyPhases()
    }

    /**
     * Records [color] as the winner — a no-op if a winner is already set. "The first player to
     * land on You Win! wins the game" (rulebook p.1) means exactly one true winner in the
     * ordinary sequential-turn case, so once declared it must never be overwritten by a later
     * exact landing in a later resolution. The one confirmed exception is a genuine tie within a
     * single Galactic Roundabout resolution — see [declareSimultaneousWinners].
     */
    fun declareWinner(color: PlayerColor) {
        if (_winners.isEmpty()) _winners += color
    }

    /**
     * Declares every color in [colors] a winner at once, as one atomic batch — confirmed by the
     * user (`docs/card-mechanics-matrix.md` §4 Q5): if Galactic Roundabout's simultaneous
     * whole-board move lands more than one player's token exactly on their own 4th Tier You Win
     * square in the very same resolution, that's a genuine tie/shared win, not "whichever token
     * happened to be processed first." A no-op if [colors] is empty.
     *
     * Unlike [declareWinner] (which only ever records the very first color it's ever called
     * with), this OVERWRITES whatever single winner an earlier call from within that same sweep
     * already locked in — see each landed token's own [declareWinner] call inside
     * `TurnEngine.resolveTierLanding`'s `YOU_WIN` case, which still fires per-token as that sweep
     * runs. This is only safe because [GalacticRoundaboutResolver][com.tiersofexistence.engine
     * .cards.resolvers.GalacticRoundaboutResolver] calls it exactly once, right after its own
     * move loop finishes, with the complete set of colors whose token landed exactly on You Win
     * during that one loop — and only when this game had no winner before the loop began (an
     * already-decided game is never resolving another card in the first place, so that
     * precondition is expected to already hold by the time this is reachable, not re-checked
     * here).
     */
    fun declareSimultaneousWinners(colors: Collection<PlayerColor>) {
        if (colors.isEmpty()) return
        _winners.clear()
        _winners += colors
    }

    /**
     * Begins tracking a pending roll for [player] with the raw [value] just rolled (e.g. via
     * [com.tiersofexistence.engine.rules.Dice.rollForPhase]) — the checkpoint Delayed Motion
     * needs to be able to add its "+2" to before the roll is used to move a token. Overwrites
     * any previous pending roll; callers are expected to have already consumed it (moved the
     * token it was for, or called [clearPendingRoll]) before starting a new one, same as every
     * other piece of turn-scoped transient state whoever's driving turns is responsible for.
     */
    fun beginPendingRoll(player: PlayerColor, value: Int): PendingRoll {
        val roll = PendingRoll(player, value)
        pendingRoll = roll
        return roll
    }

    /** Clears the pending roll once it's been read and used to move a token (or is otherwise no
     * longer needed) — the caller's responsibility; [TurnEngine.moveTierToken][com.tiersofexistence.engine.rules.TurnEngine.moveTierToken]
     * itself never reads or clears this. */
    fun clearPendingRoll() {
        pendingRoll = null
    }

    companion object {
        /** How many consecutive empty Phases [skipEmptyPhases] tolerates before concluding the
         * state is stalled, not just sparse — see that method's own doc for why this is many
         * cycles of [Phase.ROUND_ORDER], not one. */
        private const val STALLED_STATE_PHASE_THRESHOLD = 500

        /** Sets up a new game: each player gets one starting 1st Tier token, per the rulebook. */
        fun newGame(colors: List<PlayerColor>, turnOrder: TurnOrder = TurnOrder(colors)): GameState {
            val players = colors.associateWith { PlayerState(it) }
            players.values.forEach { it.tierPool(TierLevel.FIRST).startToken() }
            return GameState(players = players, turnOrder = turnOrder)
        }
    }
}
