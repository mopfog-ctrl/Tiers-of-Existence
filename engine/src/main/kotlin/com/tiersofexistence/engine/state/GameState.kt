package com.tiersofexistence.engine.state

import com.tiersofexistence.engine.board.BoardLayouts
import com.tiersofexistence.engine.board.TierBoard
import com.tiersofexistence.engine.cards.FateHarvestDeck
import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.TierLevel
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

    init {
        turnQueue = ArrayDeque(turnOrder.turnsFor(currentPhase, players))
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
        turnQueue = ArrayDeque(turnOrder.turnsFor(currentPhase, players))
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

    fun declareWinner(color: PlayerColor) {
        winner = color
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
