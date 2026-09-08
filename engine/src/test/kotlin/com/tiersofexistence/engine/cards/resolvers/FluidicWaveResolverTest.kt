package com.tiersofexistence.engine.cards.resolvers

import com.tiersofexistence.engine.cards.FateHarvestCatalog
import com.tiersofexistence.engine.cards.play.CardPlayRequest
import com.tiersofexistence.engine.cards.play.CardPlayResult
import com.tiersofexistence.engine.cards.play.TargetValidationError
import com.tiersofexistence.engine.cards.play.TriggeringEvent
import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.PlayerColor.BLUE
import com.tiersofexistence.engine.model.PlayerColor.GREEN
import com.tiersofexistence.engine.model.PlayerColor.RED
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.state.GameState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Fluidic Wave: "Blue player removes all tokens from the 1st Tier. This includes tokens in play
 * as well as tokens in Staging Piles, but does not include Tier tokens in the Zone of
 * Protection." Confirmed with the user: goes no further than the printed text — Ion Battery
 * reserves are untouched, only in-play/Staging Pile tokens are swept. See
 * [FluidicWaveResolver]'s class doc and `docs/card-mechanics-matrix.md` §7.
 */
class FluidicWaveResolverTest {

    private fun cardNamed(name: String) = FateHarvestCatalog.all.single { it.name == name }

    private fun requestFor(player: PlayerColor) = CardPlayRequest(
        sourcePlayer = player,
        card = cardNamed("Fluidic Wave"),
        triggeringEvent = TriggeringEvent.PlayedFromHand,
    )

    @Test
    fun `wipes every player's 1st Tier in-play and Staging Pile tokens, including the Blue player's own`() {
        val state = GameState.newGame(listOf(BLUE, RED, GREEN)) // each starts with 1 in-play 1st Tier token
        val bluePool = state.players.getValue(BLUE).tierPool(TierLevel.FIRST)
        val greenPool = state.players.getValue(GREEN).tierPool(TierLevel.FIRST)
        state.players.getValue(RED).tierPool(TierLevel.FIRST).sendToStagingPile(0)
        // Move Blue's and Green's tokens off Birth Canal so a post-wipe refill (which also lands
        // on Birth Canal) can't be mistaken for the original, un-destroyed token.
        bluePool.moveInPlay(0, 5)
        greenPool.moveInPlay(0, 5)

        val result = FluidicWaveResolver.resolve(state, requestFor(BLUE))

        assertIs<CardPlayResult.Resolved>(result)
        assertTrue(5 !in bluePool.inPlayPositions) // Blue's own token was wiped too, not spared
        assertTrue(5 !in greenPool.inPlayPositions)
        assertEquals(0, state.players.getValue(RED).tierPool(TierLevel.FIRST).stagingPile)
        assertEquals(1, state.deck.discardPileSize)
    }

    @Test
    fun `Zone of Protection residents survive the wipe, unlike everything else on the Tier`() {
        val state = GameState.newGame(listOf(BLUE, RED))
        val redPool = state.players.getValue(RED).tierPool(TierLevel.FIRST)
        redPool.moveInPlay(0, 10)
        redPool.enterZone(fromPosition = 10, zoneNumber = 2)

        val result = FluidicWaveResolver.resolve(state, requestFor(BLUE))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(listOf(2), redPool.zoneResidents) // untouched
    }

    @Test
    fun `1st Tier auto-replenishes from the Ion Battery after the wipe, same as any other slot-freeing mutation`() {
        val state = GameState.newGame(listOf(BLUE, RED))

        val result = FluidicWaveResolver.resolve(state, requestFor(BLUE))

        assertIs<CardPlayResult.Resolved>(result)
        // Ion Battery reserves are untouched by the wipe itself, so there's plenty left to
        // immediately repopulate both players back up to the 1st Tier's 2-in-play cap.
        assertEquals(2, state.players.getValue(BLUE).tierPool(TierLevel.FIRST).inPlayCount)
        assertEquals(2, state.players.getValue(RED).tierPool(TierLevel.FIRST).inPlayCount)
    }

    @Test
    fun `destroys 1st Tier Marauders too, since the card names no token type`() {
        val state = GameState.newGame(listOf(BLUE, RED))
        state.players.getValue(RED).marauders.placeOnBirthCanal(TierLevel.FIRST)
        state.players.getValue(RED).marauders.placeOnBirthCanal(TierLevel.SECOND)

        val result = FluidicWaveResolver.resolve(state, requestFor(BLUE))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(0, state.players.getValue(RED).marauders.inPlayCount(TierLevel.FIRST))
        assertEquals(1, state.players.getValue(RED).marauders.inPlayCount(TierLevel.SECOND)) // untouched — 2nd Tier
    }

    @Test
    fun `tokens on other Tiers are untouched`() {
        val state = GameState.newGame(listOf(BLUE, RED))
        state.players.getValue(RED).tierPool(TierLevel.SECOND).startToken()

        val result = FluidicWaveResolver.resolve(state, requestFor(BLUE))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(1, state.players.getValue(RED).tierPool(TierLevel.SECOND).inPlayCount)
    }

    @Test
    fun `played by a non-Blue player is rejected and never wipes anything`() {
        val state = GameState.newGame(listOf(BLUE, RED))

        val result = FluidicWaveResolver.resolve(state, requestFor(RED))

        assertIs<CardPlayResult.Rejected>(result)
        assertIs<TargetValidationError.WrongColor>(result.reason)
        assertEquals(1, state.players.getValue(BLUE).tierPool(TierLevel.FIRST).inPlayCount) // untouched
        assertEquals(1, state.players.getValue(RED).tierPool(TierLevel.FIRST).inPlayCount) // untouched
        assertEquals(0, state.deck.discardPileSize) // never even attempted
    }

    @Test
    fun `dispatches through CardEffectDispatcher with no target needed`() {
        val state = GameState.newGame(listOf(BLUE, RED))
        state.players.getValue(RED).tierPool(TierLevel.FIRST).sendToStagingPile(0)

        val result = CardEffectDispatcher.dispatch(state, requestFor(BLUE))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(0, state.players.getValue(RED).tierPool(TierLevel.FIRST).stagingPile)
    }
}
