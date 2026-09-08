package com.tiersofexistence.engine.cards.resolvers

import com.tiersofexistence.engine.cards.FateHarvestCatalog
import com.tiersofexistence.engine.cards.play.CardPlayRequest
import com.tiersofexistence.engine.cards.play.CardPlayResult
import com.tiersofexistence.engine.cards.play.CardTarget
import com.tiersofexistence.engine.cards.play.PendingDecision
import com.tiersofexistence.engine.cards.play.TargetValidationError
import com.tiersofexistence.engine.cards.play.TriggeringEvent
import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.PlayerColor.BLACK
import com.tiersofexistence.engine.model.PlayerColor.GREEN
import com.tiersofexistence.engine.model.PlayerColor.RED
import com.tiersofexistence.engine.state.GameState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Cleansing (Atmospheric): "Choose an opponent. That opponent must choose and discard one of
 * their cards. The person discarding chooses which card to discard." A two-step decision spanning
 * two players — see [CleansingResolver]'s class doc and `docs/card-mechanics-matrix.md` §26/§4 Q12.
 */
class CleansingResolverTest {

    private fun cardNamed(name: String) = FateHarvestCatalog.all.single { it.name == name }

    private fun requestFor(player: PlayerColor, targets: List<CardTarget> = emptyList()) = CardPlayRequest(
        sourcePlayer = player,
        card = cardNamed("Cleansing (Atmospheric)"),
        targets = targets,
        triggeringEvent = TriggeringEvent.PlayedFromHand,
    )

    @Test
    fun `targeting an opponent with cards in hand returns AwaitingDecision naming them`() {
        val state = GameState.newGame(listOf(RED, GREEN))
        state.players.getValue(GREEN).hand += cardNamed("Tactical Step")

        val result = CleansingResolver.resolve(state, requestFor(RED, listOf(CardTarget.PlayerChoice(GREEN))), CardTarget.PlayerChoice(GREEN))

        val awaiting = assertIs<CardPlayResult.AwaitingDecision>(result)
        assertEquals(PendingDecision.OpponentDiscardChoice(GREEN), awaiting.pending)
        // Cleansing itself is already legally played and discarded, regardless of the pending decision.
        assertEquals(1, state.deck.discardPileSize)
        assertTrue(state.players.getValue(RED).hasPlayedCardThisPhase)
    }

    @Test
    fun `targeting an opponent with an empty hand is a no-op, resolved directly`() {
        val state = GameState.newGame(listOf(RED, GREEN)) // GREEN's hand starts empty

        val result = CleansingResolver.resolve(state, requestFor(RED, listOf(CardTarget.PlayerChoice(GREEN))), CardTarget.PlayerChoice(GREEN))

        assertIs<CardPlayResult.Resolved>(result)
        // Still legally played — Cleansing itself is discarded even though its effect fizzled.
        assertEquals(1, state.deck.discardPileSize)
    }

    @Test
    fun `targeting yourself is rejected, not played`() {
        val state = GameState.newGame(listOf(RED, GREEN))

        val result = CleansingResolver.resolve(state, requestFor(RED, listOf(CardTarget.PlayerChoice(RED))), CardTarget.PlayerChoice(RED))

        assertIs<CardPlayResult.Rejected>(result)
        assertIs<TargetValidationError.WrongTokenType>(result.reason)
        assertEquals(0, state.deck.discardPileSize)
    }

    @Test
    fun `targeting a color not in this game is rejected`() {
        val state = GameState.newGame(listOf(RED, GREEN))

        val result = CleansingResolver.resolve(state, requestFor(RED, listOf(CardTarget.PlayerChoice(BLACK))), CardTarget.PlayerChoice(BLACK))

        assertIs<CardPlayResult.Rejected>(result)
        assertIs<TargetValidationError.NoLegalTarget>(result.reason)
        assertEquals(0, state.deck.discardPileSize)
    }

    @Test
    fun `completeDiscard removes the chosen card from the deciding player's hand and discards it`() {
        val state = GameState.newGame(listOf(RED, GREEN))
        val kept = cardNamed("Tactical Step")
        val discarded = cardNamed("Divine Assistance")
        state.players.getValue(GREEN).hand += kept
        state.players.getValue(GREEN).hand += discarded

        CleansingResolver.completeDiscard(state, GREEN, discarded)

        assertEquals(listOf(kept), state.players.getValue(GREEN).hand)
        assertEquals(1, state.deck.discardPileSize)
    }

    @Test
    fun `completeDiscard throws if the card is not actually in the deciding player's hand`() {
        val state = GameState.newGame(listOf(RED, GREEN))

        assertFailsWith<IllegalArgumentException> {
            CleansingResolver.completeDiscard(state, GREEN, cardNamed("Tactical Step"))
        }
    }

    @Test
    fun `dispatches through CardEffectDispatcher`() {
        val state = GameState.newGame(listOf(RED, GREEN))
        state.players.getValue(GREEN).hand += cardNamed("Tactical Step")

        val result = CardEffectDispatcher.dispatch(state, requestFor(RED, listOf(CardTarget.PlayerChoice(GREEN))))

        val awaiting = assertIs<CardPlayResult.AwaitingDecision>(result)
        assertEquals(PendingDecision.OpponentDiscardChoice(GREEN), awaiting.pending)
    }

    @Test
    fun `dispatched without a target is rejected, not a crash`() {
        val state = GameState.newGame(listOf(RED, GREEN))

        val result = CardEffectDispatcher.dispatch(state, requestFor(RED))

        assertIs<CardPlayResult.Rejected>(result)
        assertIs<TargetValidationError.NoLegalTarget>(result.reason)
    }
}
