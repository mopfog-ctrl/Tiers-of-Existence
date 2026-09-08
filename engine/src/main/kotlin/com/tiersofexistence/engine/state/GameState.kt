package com.tiersofexistence.engine.state

import com.tiersofexistence.engine.board.BoardLayouts
import com.tiersofexistence.engine.board.TierBoard
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

    val currentPhase: Phase get() = Phase.ROUND_ORDER[phaseIndex]

    /** Players still owed a turn in [currentPhase], in the order they'll take it. */
    private var turnQueue: ArrayDeque<PlayerColor> = ArrayDeque()

    /** Queued [DeferredTurnModifier]s not yet consumed — see [queueSkipNextTierTurn]/[queueExtraTierTurn]. */
    private val deferredModifiers: MutableList<DeferredTurnModifier> = mutableListOf()

    /** The in-progress turn's roll, once rolled but before it's been used to move a token — the
     * checkpoint Delayed Motion needs (see [PendingRoll]'s class doc). Null whenever no roll is
     * currently pending; see [beginPendingRoll]/[clearPendingRoll]. */
    var pendingRoll: PendingRoll? = null
        private set

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

    /** The seating-order turn list for [currentPhase], with any queued [DeferredTurnModifier]s
     * for the current Tier applied and consumed. Marauder Phase is untouched — deferred
     * modifiers are always Tier-turn-specific (see the class doc on [DeferredTurnModifier]). */
    private fun buildTurnQueue(): ArrayDeque<PlayerColor> {
        var base = turnOrder.turnsFor(currentPhase, players)
        val tier = (currentPhase as? Phase.Tier)?.tier
        if (tier != null) {
            val skips = deferredModifiers.filterIsInstance<DeferredTurnModifier.SkipNextTierTurn>()
                .filter { it.tier == tier && it.player in base }
            base = base.filterNot { color -> skips.any { it.player == color } }
            deferredModifiers.removeAll(skips)

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

    /** Queues [player] to skip their next turn on [tier] only — Phase Loss, or the "Lose next
     * turn on this Tier" Time Wrinkle square. Never affects a turn already in progress (the
     * card/square is always resolved as part of the current turn's own move) — takes effect
     * starting the next time [tier]'s Phase turn queue is built, per confirmed canon. */
    fun queueSkipNextTierTurn(player: PlayerColor, tier: TierLevel) {
        deferredModifiers += DeferredTurnModifier.SkipNextTierTurn(player, tier)
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
     * 5) — this was too tight, and a real test caught it: [DeferredTurnModifier.SkipNextTierTurn]
     * (Phase Loss, or the 1st Tier's own "Lose next turn on this Tier" Time Wrinkle square) can
     * legitimately defer a player's next eligible turn on a Tier past one full cycle, in a very
     * sparse game (few players/Tiers active) — and per that class's own doc, independent
     * triggers stack, so more than one queued skip against the same (player, Tier) can defer
     * eligibility by more than one cycle, consumed one at a time. None of this is remotely
     * reachable in ordinary 2-6 player play (some other player almost always has *some* eligible
     * turn within the same Round), but a legitimately sparse/edge-case [GameState] can hit it
     * without being corrupted at all. A generous multi-cycle threshold tells the two apart: a
     * truly malformed state (e.g. every pool emptied out) never finds anyone eligible no matter
     * how far this searches, while a legitimate stacked-skip gap always resolves within a small,
     * bounded number of cycles — see `GameStateTest`'s "a queued Phase Loss on a single sparse
     * player's only Tier resumes normally, not as a stalled state" regression test for the
     * concrete case that caught this.
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
