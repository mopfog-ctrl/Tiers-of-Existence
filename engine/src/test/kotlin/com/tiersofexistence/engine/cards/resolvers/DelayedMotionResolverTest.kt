package com.tiersofexistence.engine.cards.resolvers

import com.tiersofexistence.engine.cards.FateHarvestCatalog
import com.tiersofexistence.engine.cards.play.CardPlayRequest
import com.tiersofexistence.engine.cards.play.CardPlayResult
import com.tiersofexistence.engine.cards.play.TargetValidationError
import com.tiersofexistence.engine.cards.play.TriggeringEvent
import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.PlayerColor.GREEN
import com.tiersofexistence.engine.model.PlayerColor.RED
import com.tiersofexistence.engine.state.GameState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Delayed Motion: "Add +2 to your die roll. This card must be played after your die roll, but
 * before you move the token." Confirmed by the user, resolving `docs/card-mechanics-matrix.md`
 * §4 Q13: self-only, never playable on another player's pending roll. See
 * [DelayedMotionResolver]'s class doc.
 */
class DelayedMotionResolverTest {

    private fun cardNamed(name: String) = FateHarvestCatalog.all.single { it.name == name }

    private fun requestFor(player: PlayerColor) = CardPlayRequest(
        sourcePlayer = player,
        card = cardNamed("Delayed Motion"),
        triggeringEvent = TriggeringEvent.PlayedFromHand,
    )

    @Test
    fun `adds 2 to the source player's own pending roll`() {
        val state = GameState.newGame(listOf(RED))
        val roll = state.beginPendingRoll(RED, 4)

        val result = DelayedMotionResolver.resolve(state, requestFor(RED))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(6, roll.total)
        assertEquals(6, state.pendingRoll!!.total)
    }

    @Test
    fun `is discarded and counts against the Phase's card-play limit once played`() {
        val state = GameState.newGame(listOf(RED))
        state.beginPendingRoll(RED, 4)

        val result = DelayedMotionResolver.resolve(state, requestFor(RED))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(1, state.deck.discardPileSize)
        assertTrue(state.players.getValue(RED).hasPlayedCardThisPhase)
    }

    @Test
    fun `rejected when there is no pending roll at all`() {
        val state = GameState.newGame(listOf(RED))

        val result = DelayedMotionResolver.resolve(state, requestFor(RED))

        assertIs<CardPlayResult.Rejected>(result)
        assertIs<TargetValidationError.CardSpecificRestriction>(result.reason)
        assertEquals(0, state.deck.discardPileSize)
    }

    @Test
    fun `rejected when the pending roll belongs to another player`() {
        val state = GameState.newGame(listOf(RED, GREEN))
        val roll = state.beginPendingRoll(GREEN, 4)

        val result = DelayedMotionResolver.resolve(state, requestFor(RED))

        assertIs<CardPlayResult.Rejected>(result)
        assertIs<TargetValidationError.CardSpecificRestriction>(result.reason)
        // Neither the roll nor the Phase-play bookkeeping is touched by a rejected play.
        assertEquals(4, roll.total)
        assertFalse(state.players.getValue(RED).hasPlayedCardThisPhase)
        assertEquals(0, state.deck.discardPileSize)
    }

    @Test
    fun `dispatches through CardEffectDispatcher`() {
        val state = GameState.newGame(listOf(RED))
        val roll = state.beginPendingRoll(RED, 4)

        val result = CardEffectDispatcher.dispatch(state, requestFor(RED))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(6, roll.total)
    }
}
