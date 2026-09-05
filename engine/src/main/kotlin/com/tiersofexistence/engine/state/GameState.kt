package com.tiersofexistence.engine.state

import com.tiersofexistence.engine.board.BoardLayouts
import com.tiersofexistence.engine.board.TierBoard
import com.tiersofexistence.engine.cards.FateHarvestDeck
import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.rules.DeferredTurnModifier
import com.tiersofexistence.engine.rules.Phase
import com.tiersofexistence.engine.rules.TurnOrder

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

    var winner: PlayerColor? = null
        private set

    val currentPhase: Phase get() = Phase.ROUND_ORDER[phaseIndex]

    /** Players still owed a turn in [currentPhase], in the order they'll take it. */
    private var turnQueue: ArrayDeque<PlayerColor> = ArrayDeque()

    /** Queued [DeferredTurnModifier]s not yet consumed — see [queueSkipNextTierTurn]/[queueExtraTierTurn]. */
    private val deferredModifiers: MutableList<DeferredTurnModifier> = mutableListOf()

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
     */
    fun skipEmptyPhases() {
        while (turnQueue.isEmpty() && winner == null) advancePhase()
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
     * land on You Win! wins the game" (rulebook p.1) means exactly one true winner, so once
     * declared it must never be overwritten by a later exact landing in the same or a later
     * resolution (e.g. two tokens both landing exactly on their own 4th Tier You Win square
     * within one Galactic Roundabout resolution — see `docs/card-mechanics-matrix.md` §4 Q5:
     * which one is "first" when a card moves multiple tokens at once is the caller's processing
     * order, still an open question, but whichever gets declared first here is final regardless).
     */
    fun declareWinner(color: PlayerColor) {
        if (winner == null) winner = color
    }

    companion object {
        /** Sets up a new game: each player gets one starting 1st Tier token, per the rulebook. */
        fun newGame(colors: List<PlayerColor>, turnOrder: TurnOrder = TurnOrder(colors)): GameState {
            val players = colors.associateWith { PlayerState(it) }
            players.values.forEach { it.tierPool(TierLevel.FIRST).startToken() }
            return GameState(players = players, turnOrder = turnOrder)
        }
    }
}
