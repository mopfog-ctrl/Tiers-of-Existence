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

    @Test
    fun `a fresh game has no current turn until skipEmptyPhases is called`() {
        val game = GameState.newGame(listOf(PlayerColor.RED, PlayerColor.GREEN))
        assertEquals(Phase.Marauder, game.currentPhase)
        assertEquals(null, game.currentTurn)
    }

    @Test
    fun `skipEmptyPhases lands Round 1 directly on the 1st Tier Phase`() {
        val game = GameState.newGame(listOf(PlayerColor.RED, PlayerColor.GREEN))

        game.skipEmptyPhases()

        assertEquals(1, game.roundNumber)
        assertEquals(Phase.Tier(TierLevel.FIRST), game.currentPhase)
        assertEquals(PlayerColor.RED, game.currentTurn)
    }

    @Test
    fun `endTurn advances color order, then Phase, and wraps to a new Round`() {
        val game = GameState.newGame(listOf(PlayerColor.RED, PlayerColor.GREEN))
        game.skipEmptyPhases()
        assertEquals(PlayerColor.RED, game.currentTurn)

        game.endTurn()
        assertEquals(PlayerColor.GREEN, game.currentTurn)

        game.endTurn()
        // Both players are done with the only eligible Phase (1st Tier) in Round 1 — wraps
        // straight to Round 2's 1st Tier Phase too, since Marauder/4th/3rd/2nd are still empty.
        assertEquals(2, game.roundNumber)
        assertEquals(Phase.Tier(TierLevel.FIRST), game.currentPhase)
        assertEquals(PlayerColor.RED, game.currentTurn)
    }

    @Test
    fun `endTurn with grantAnotherTurn keeps the same player active`() {
        val game = GameState.newGame(listOf(PlayerColor.RED, PlayerColor.GREEN))
        game.skipEmptyPhases()

        game.endTurn(grantAnotherTurn = true)

        assertEquals(PlayerColor.RED, game.currentTurn)
    }

    // --- Deferred turn modifiers (Phase Loss / Phase Control / matching Time Wrinkle squares) ---

    @Test
    fun `queueSkipNextTierTurn never affects the turn in progress, only the Tier's next occurrence`() {
        val game = GameState.newGame(listOf(PlayerColor.RED, PlayerColor.GREEN))
        game.skipEmptyPhases()
        assertEquals(PlayerColor.RED, game.currentTurn)

        game.queueSkipNextTierTurn(PlayerColor.RED, TierLevel.FIRST) // drawn as part of RED's current turn
        game.endTurn() // RED's current turn still happens normally
        assertEquals(PlayerColor.GREEN, game.currentTurn)

        game.endTurn() // Round 2's 1st Tier Phase: RED is skipped, only GREEN plays
        assertEquals(2, game.roundNumber)
        assertEquals(Phase.Tier(TierLevel.FIRST), game.currentPhase)
        assertEquals(PlayerColor.GREEN, game.currentTurn)

        game.endTurn() // Round 3: the skip was consumed, RED is back to normal
        assertEquals(3, game.roundNumber)
        assertEquals(PlayerColor.RED, game.currentTurn)
    }

    @Test
    fun `queueExtraTierTurn splices into the live queue when that Tier's Phase is already active`() {
        val game = GameState.newGame(listOf(PlayerColor.RED, PlayerColor.GREEN))
        game.skipEmptyPhases()
        assertEquals(PlayerColor.RED, game.currentTurn) // RED hasn't taken this Phase's turn yet

        game.queueExtraTierTurn(PlayerColor.RED, TierLevel.FIRST)

        assertEquals(PlayerColor.RED, game.currentTurn) // still RED's normal turn first
        game.endTurn()
        assertEquals(PlayerColor.RED, game.currentTurn) // the extra turn, taken right after
        game.endTurn()
        assertEquals(PlayerColor.GREEN, game.currentTurn) // then GREEN, as normal
    }

    @Test
    fun `queueExtraTierTurn for a Phase not currently active is applied and consumed at its next occurrence`() {
        val game = GameState.newGame(listOf(PlayerColor.RED, PlayerColor.GREEN))
        assertEquals(Phase.Marauder, game.currentPhase) // not the 1st Tier Phase yet

        game.queueExtraTierTurn(PlayerColor.RED, TierLevel.FIRST)
        game.skipEmptyPhases()

        assertEquals(Phase.Tier(TierLevel.FIRST), game.currentPhase)
        assertEquals(PlayerColor.RED, game.currentTurn)
        game.endTurn()
        assertEquals(PlayerColor.RED, game.currentTurn) // the granted extra turn
        game.endTurn()
        assertEquals(PlayerColor.GREEN, game.currentTurn)

        game.endTurn() // Round 2: the extra turn was consumed, doesn't repeat
        assertEquals(2, game.roundNumber)
        assertEquals(PlayerColor.RED, game.currentTurn)
        game.endTurn()
        assertEquals(PlayerColor.GREEN, game.currentTurn) // no second RED turn this time
    }

    @Test
    fun `two independent ExtraTierTurn triggers each grant their own turn, never more than one repeat each`() {
        val game = GameState.newGame(listOf(PlayerColor.RED, PlayerColor.GREEN))
        game.skipEmptyPhases()

        game.queueExtraTierTurn(PlayerColor.RED, TierLevel.FIRST)
        game.queueExtraTierTurn(PlayerColor.RED, TierLevel.FIRST)

        assertEquals(PlayerColor.RED, game.currentTurn)
        game.endTurn()
        assertEquals(PlayerColor.RED, game.currentTurn) // extra #1
        game.endTurn()
        assertEquals(PlayerColor.RED, game.currentTurn) // extra #2
        game.endTurn()
        assertEquals(PlayerColor.GREEN, game.currentTurn) // no third repeat
    }
}
