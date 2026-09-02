package com.tiersofexistence.engine.state

import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.rules.Phase
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameStateTest {

    @Test
    fun `new game gives every player one 1st Tier token in play and nothing else`() {
        val game = GameState.newGame(listOf(PlayerColor.RED, PlayerColor.GREEN))

        for (player in game.players.values) {
            assertEquals(1, player.tierPool(TierLevel.FIRST).inPlayCount)
            assertEquals(0, player.tierPool(TierLevel.SECOND).inPlayCount)
            assertEquals(0, player.tierPool(TierLevel.THIRD).inPlayCount)
            assertEquals(0, player.tierPool(TierLevel.FOURTH).inPlayCount)
            assertTrue(TierLevel.entries.all { player.marauders.inPlayCount(it) == 0 })
        }
    }

    @Test
    fun `round 1 only has turns in the 1st Tier Phase`() {
        val game = GameState.newGame(listOf(PlayerColor.RED, PlayerColor.GREEN))

        val turnsPerPhase = Phase.ROUND_ORDER.associateWith { game.turnOrder.turnsFor(it, game.players) }

        assertTrue(turnsPerPhase.getValue(Phase.Marauder).isEmpty())
        assertTrue(turnsPerPhase.getValue(Phase.Tier(TierLevel.FOURTH)).isEmpty())
        assertTrue(turnsPerPhase.getValue(Phase.Tier(TierLevel.THIRD)).isEmpty())
        assertTrue(turnsPerPhase.getValue(Phase.Tier(TierLevel.SECOND)).isEmpty())
        assertEquals(listOf(PlayerColor.RED, PlayerColor.GREEN), turnsPerPhase.getValue(Phase.Tier(TierLevel.FIRST)))
    }

    @Test
    fun `advancing through all 5 phases starts a new round`() {
        val game = GameState.newGame(listOf(PlayerColor.RED))
        assertEquals(1, game.roundNumber)

        repeat(Phase.ROUND_ORDER.size) { game.advancePhase() }

        assertEquals(2, game.roundNumber)
        assertEquals(Phase.Marauder, game.currentPhase)
    }

    @Test
    fun `a player with a token on a higher Tier goes before one who only has a 1st Tier token`() {
        val game = GameState.newGame(listOf(PlayerColor.RED, PlayerColor.GREEN))
        game.players.getValue(PlayerColor.GREEN).tierPool(TierLevel.SECOND).startToken()

        val secondTierTurns = game.turnOrder.turnsFor(Phase.Tier(TierLevel.SECOND), game.players)

        assertEquals(listOf(PlayerColor.GREEN), secondTierTurns)
    }
}
