package com.tiersofexistence.engine.cards.resolvers

import com.tiersofexistence.engine.cards.FateHarvestCatalog
import com.tiersofexistence.engine.cards.play.CardPlayRequest
import com.tiersofexistence.engine.cards.play.CardPlayResult
import com.tiersofexistence.engine.cards.play.TriggeringEvent
import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.PlayerColor.GREEN
import com.tiersofexistence.engine.model.PlayerColor.RED
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.rules.Phase
import com.tiersofexistence.engine.state.GameState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class TurnManipulationCardResolversTest {

    private fun cardNamed(name: String) = FateHarvestCatalog.all.single { it.name == name }

    private fun drawnRequest(player: PlayerColor, cardName: String, tier: TierLevel, squarePosition: Int = 6) = CardPlayRequest(
        sourcePlayer = player,
        card = cardNamed(cardName),
        triggeringEvent = TriggeringEvent.DrawnFromSquare(tier, squarePosition),
    )

    // --- Phase Loss ---

    @Test
    fun `Phase Loss makes the player skip their next turn on that Tier only`() {
        val state = GameState.newGame(listOf(RED, GREEN))
        state.skipEmptyPhases() // Round 1, 1st Tier Phase, RED up first
        assertEquals(RED, state.currentTurn)

        val result = PhaseLossResolver.resolve(state, drawnRequest(RED, "Phase Loss", TierLevel.FIRST))
        assertIs<CardPlayResult.Resolved>(result)

        state.endTurn() // RED's CURRENT turn still happens (per confirmed canon: never retroactive)
        assertEquals(GREEN, state.currentTurn)
        state.endTurn() // Round 2's 1st Tier Phase: RED is skipped
        assertEquals(2, state.roundNumber)
        assertEquals(GREEN, state.currentTurn)
    }

    @Test
    fun `Phase Loss cannot be resolved outside a DrawnFromSquare trigger`() {
        val state = GameState.newGame(listOf(RED))
        val heldStyleRequest = CardPlayRequest(RED, cardNamed("Phase Loss"), triggeringEvent = TriggeringEvent.PlayedFromHand)

        assertFailsWith<IllegalArgumentException> { PhaseLossResolver.resolve(state, heldStyleRequest) }
    }

    // --- Phase Control ---

    @Test
    fun `Phase Control splices an extra turn into the currently active Tier Phase`() {
        val state = GameState.newGame(listOf(RED, GREEN))
        state.skipEmptyPhases()
        assertEquals(RED, state.currentTurn)

        val result = PhaseControlResolver.resolve(state, drawnRequest(RED, "Phase Control", TierLevel.FIRST), TierLevel.FIRST)
        assertIs<CardPlayResult.Resolved>(result)

        assertEquals(RED, state.currentTurn) // RED's normal turn first
        state.endTurn()
        assertEquals(RED, state.currentTurn) // the extra turn from Phase Control
        state.endTurn()
        assertEquals(GREEN, state.currentTurn)
    }

    @Test
    fun `Phase Control can grant an extra turn on a Tier other than the one it was drawn from`() {
        val state = GameState.newGame(listOf(RED, GREEN))
        state.players.getValue(RED).tierPool(TierLevel.SECOND).startToken()
        assertEquals(Phase.Marauder, state.currentPhase) // drawn conceptually before any Tier Phase this Round

        val result = PhaseControlResolver.resolve(state, drawnRequest(RED, "Phase Control", TierLevel.FIRST), TierLevel.SECOND)
        assertIs<CardPlayResult.Resolved>(result)

        state.skipEmptyPhases()
        assertEquals(Phase.Tier(TierLevel.SECOND), state.currentPhase)
        assertEquals(RED, state.currentTurn)
        state.endTurn()
        assertEquals(RED, state.currentTurn) // the granted extra 2nd Tier turn
    }
}
