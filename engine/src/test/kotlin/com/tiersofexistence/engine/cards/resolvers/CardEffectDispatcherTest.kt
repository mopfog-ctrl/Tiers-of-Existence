package com.tiersofexistence.engine.cards.resolvers

import com.tiersofexistence.engine.cards.FateHarvestCatalog
import com.tiersofexistence.engine.cards.play.CardPlayRequest
import com.tiersofexistence.engine.cards.play.CardPlayResult
import com.tiersofexistence.engine.cards.play.CardTarget
import com.tiersofexistence.engine.cards.play.TargetValidationError
import com.tiersofexistence.engine.cards.play.TriggeringEvent
import com.tiersofexistence.engine.model.PlayerColor.BLACK
import com.tiersofexistence.engine.model.PlayerColor.GREEN
import com.tiersofexistence.engine.model.PlayerColor.RED
import com.tiersofexistence.engine.model.PlayerColor.WHITE
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.state.GameState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CardEffectDispatcherTest {

    private fun cardNamed(name: String) = FateHarvestCatalog.all.single { it.name == name }

    private fun requestFor(player: com.tiersofexistence.engine.model.PlayerColor, cardName: String, targets: List<CardTarget> = emptyList()) =
        CardPlayRequest(player, cardNamed(cardName), targets, TriggeringEvent.PlayedFromHand)

    @Test
    fun `dispatches a fixed-Tier construction card`() {
        val state = GameState.newGame(listOf(WHITE))

        val result = CardEffectDispatcher.dispatch(state, requestFor(WHITE, "Dwarf Star"))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(1, state.players.getValue(WHITE).marauders.inPlayCount(TierLevel.FOURTH))
    }

    @Test
    fun `dispatches a player-chosen Tier construction card`() {
        val state = GameState.newGame(listOf(RED))

        val result = CardEffectDispatcher.dispatch(state, requestFor(RED, "Materialize Army", listOf(CardTarget.TierChoice(TierLevel.THIRD))))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(1, state.players.getValue(RED).marauders.inPlayCount(TierLevel.THIRD))
    }

    @Test
    fun `a Tier-choice card dispatched without a target is rejected, not a crash`() {
        val state = GameState.newGame(listOf(RED))

        val result = CardEffectDispatcher.dispatch(state, requestFor(RED, "Materialize Army"))

        assertIs<CardPlayResult.Rejected>(result)
        assertIs<TargetValidationError.NoLegalTarget>((result as CardPlayResult.Rejected).reason)
    }

    @Test
    fun `dispatches a movement card with the card's own fixed distance`() {
        val state = GameState.newGame(listOf(RED))
        val id = state.players.getValue(RED).tierPool(TierLevel.FIRST).idAt(0)!!

        val result = CardEffectDispatcher.dispatch(state, requestFor(RED, "Tactical Step", listOf(CardTarget.Token(id))))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(listOf(1), state.players.getValue(RED).tierPool(TierLevel.FIRST).inPlayPositions)
    }

    @Test
    fun `dispatches Graviton Rift across multiple Tiers in one call`() {
        val state = GameState.newGame(listOf(BLACK, GREEN))
        state.players.getValue(GREEN).tierPool(TierLevel.SECOND).startToken()
        val firstTierId = state.players.getValue(GREEN).tierPool(TierLevel.FIRST).idAt(0)!!
        val secondTierId = state.players.getValue(GREEN).tierPool(TierLevel.SECOND).idAt(0)!!
        val targets = listOf(CardTarget.Token(firstTierId), CardTarget.Token(secondTierId))

        val result = CardEffectDispatcher.dispatch(state, requestFor(BLACK, "Graviton Rift", targets))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(0, state.players.getValue(GREEN).tierPool(TierLevel.FIRST).inPlayCount)
        assertEquals(0, state.players.getValue(GREEN).tierPool(TierLevel.SECOND).inPlayCount)
    }

    @Test
    fun `an unregistered card throws rather than silently doing nothing`() {
        val state = GameState.newGame(listOf(RED))

        assertFailsWith<IllegalStateException> { CardEffectDispatcher.dispatch(state, requestFor(RED, "Plasma Burst")) }
    }
}
