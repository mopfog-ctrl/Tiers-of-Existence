package com.tiersofexistence.engine.cards.play

import com.tiersofexistence.engine.cards.FateHarvestCatalog
import com.tiersofexistence.engine.model.PlayerColor.GREEN
import com.tiersofexistence.engine.model.PlayerColor.RED
import com.tiersofexistence.engine.model.PlayerColor.YELLOW
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.state.GameState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CardLifecycleTest {

    private fun cardNamed(name: String) = FateHarvestCatalog.all.single { it.name == name }

    private fun game() = GameState.newGame(listOf(RED, GREEN, YELLOW))

    // --- Held lifecycle: draw -> enter hand -> remain persistent -> validate on play -> resolve -> leave hand ---

    @Test
    fun `a Held card drawn from a square enters hand without being validated or discarded`() {
        val state = game()
        val tacticalMotion = cardNamed("Tactical Motion") // Held

        val result = CardLifecycle.onDrawnFromSquare(state, RED, tacticalMotion, TierLevel.FIRST, squarePosition = 6)

        assertIs<CardPlayResult.EnteredHand>(result)
        assertEquals(listOf(tacticalMotion), state.players.getValue(RED).hand)
        assertEquals(0, state.deck.discardPileSize)
        assertFalse(state.players.getValue(RED).hasPlayedCardThisPhase)
    }

    @Test
    fun `playing a Held card from hand resolves it, discards it, and marks the per-Phase flag`() {
        val state = game()
        val tacticalMotion = cardNamed("Tactical Motion")
        state.players.getValue(RED).hand += tacticalMotion

        val result = CardLifecycle.playFromHand(state, RED, tacticalMotion)

        assertIs<CardPlayResult.Resolved>(result)
        assertTrue(state.players.getValue(RED).hand.isEmpty())
        assertEquals(1, state.deck.discardPileSize)
        assertTrue(state.players.getValue(RED).hasPlayedCardThisPhase)
    }

    @Test
    fun `a rejected play from hand puts the card back, unconsumed`() {
        val state = game()
        val corpuscleRot = cardNamed("Corpuscle Rot") // Yellow-restricted
        state.players.getValue(RED).hand += corpuscleRot // RED shouldn't have this, but simulate anyway

        val result = CardLifecycle.playFromHand(state, RED, corpuscleRot)

        assertIs<CardPlayResult.Rejected>(result)
        assertIs<TargetValidationError.WrongColor>((result as CardPlayResult.Rejected).reason)
        assertEquals(listOf(corpuscleRot), state.players.getValue(RED).hand) // given back
        assertEquals(0, state.deck.discardPileSize) // never discarded
        assertFalse(state.players.getValue(RED).hasPlayedCardThisPhase)
    }

    @Test
    fun `playFromHand fails fast if the player doesn't actually have that card`() {
        val state = game()
        val tacticalMotion = cardNamed("Tactical Motion")

        assertFailsWith<IllegalArgumentException> { CardLifecycle.playFromHand(state, RED, tacticalMotion) }
    }

    @Test
    fun `a Held card is blocked once the per-Phase limit is already spent`() {
        val state = game()
        state.players.getValue(RED).hasPlayedCardThisPhase = true
        val tacticalMotion = cardNamed("Tactical Motion")
        state.players.getValue(RED).hand += tacticalMotion

        val result = CardLifecycle.playFromHand(state, RED, tacticalMotion)

        assertIs<CardPlayResult.Rejected>(result)
        assertIs<TargetValidationError.PhaseCardLimitReached>((result as CardPlayResult.Rejected).reason)
        assertEquals(listOf(tacticalMotion), state.players.getValue(RED).hand)
    }

    // --- Immediate lifecycle: draw -> validate -> resolve atomically -> discard ---

    @Test
    fun `an Immediate card drawn from a square resolves atomically and is discarded, never entering hand`() {
        val state = game()
        val evasiveAction = cardNamed("Evasive Action") // Immediate

        val result = CardLifecycle.onDrawnFromSquare(state, RED, evasiveAction, TierLevel.FIRST, squarePosition = 6)

        assertIs<CardPlayResult.Resolved>(result)
        assertTrue(state.players.getValue(RED).hand.isEmpty())
        assertEquals(1, state.deck.discardPileSize)
        assertTrue(state.players.getValue(RED).hasPlayedCardThisPhase)
    }

    @Test
    fun `a mandatory Immediate card still plays even if the per-Phase limit is already spent`() {
        val state = game()
        state.players.getValue(RED).hasPlayedCardThisPhase = true
        val evasiveAction = cardNamed("Evasive Action")

        val result = CardLifecycle.onDrawnFromSquare(state, RED, evasiveAction, TierLevel.FIRST, squarePosition = 6)

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(1, state.deck.discardPileSize)
    }

    @Test
    fun `a Color-restricted Immediate card drawn by the wrong color is rejected, not silently played`() {
        // No Immediate Color card exists in the catalog today, but attemptPlay's color check
        // must still hold for any future one — exercised directly via attemptPlay rather than
        // relying on onDrawnFromSquare's mandatory path silently succeeding.
        val state = game()
        val corpuscleRot = cardNamed("Corpuscle Rot")
        val request = CardPlayRequest(RED, corpuscleRot, triggeringEvent = TriggeringEvent.DrawnFromSquare(TierLevel.FIRST, 6))

        val result = CardLifecycle.attemptPlay(state, request)

        assertIs<CardPlayResult.Rejected>(result)
        assertEquals(0, state.deck.discardPileSize)
    }
}
