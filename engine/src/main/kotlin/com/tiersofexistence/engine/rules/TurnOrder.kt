package com.tiersofexistence.engine.rules

import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.state.PlayerState

/**
 * The fixed seating order of players, set once at game start (rulebook: "The Nebula Staging
 * Piles on the 1st Tier game board also indicate the order of turns for each Phase (from
 * left to right)"). The same order applies to every Phase of every Round.
 */
class TurnOrder(val order: List<PlayerColor>) {
    init {
        require(order.isNotEmpty()) { "Turn order must include at least one player" }
        require(order.toSet().size == order.size) { "Turn order must not repeat a player" }
    }

    /**
     * Players who actually get a turn in [phase], in seating order — "a player only gets a
     * turn on a Tier if they have a token on that Tier" (p.4), and likewise for Marauders.
     */
    fun turnsFor(phase: Phase, players: Map<PlayerColor, PlayerState>): List<PlayerColor> =
        order.filter { color ->
            val player = players.getValue(color)
            when (phase) {
                Phase.Marauder -> player.hasMarauderTurn()
                is Phase.Tier -> player.hasTierTurn(phase.tier)
            }
        }
}
