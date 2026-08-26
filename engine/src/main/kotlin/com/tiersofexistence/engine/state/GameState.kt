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
    val boards: Map<TierLevel, TierBoard> = BoardLayouts.allPlaceholders(),
    val deck: FateHarvestDeck = FateHarvestDeck.newShuffled(),
) {
    var roundNumber: Int = 1
        private set

    var phaseIndex: Int = 0
        private set

    var winner: PlayerColor? = null
        private set

    val currentPhase: Phase get() = Phase.ROUND_ORDER[phaseIndex]

    /** Advances to the next Phase, wrapping to a new Round after the 1st Tier Phase. */
    fun advancePhase() {
        players.values.forEach { it.hasPlayedCardThisPhase = false }
        phaseIndex += 1
        if (phaseIndex >= Phase.ROUND_ORDER.size) {
            phaseIndex = 0
            roundNumber += 1
        }
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
