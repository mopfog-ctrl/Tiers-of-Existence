package com.tiersofexistence.engine.cards.resolvers

import com.tiersofexistence.engine.cards.FateHarvestCatalog
import com.tiersofexistence.engine.cards.play.CardPlayRequest
import com.tiersofexistence.engine.cards.play.CardPlayResult
import com.tiersofexistence.engine.cards.play.TriggeringEvent
import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.PlayerColor.GREEN
import com.tiersofexistence.engine.model.PlayerColor.RED
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.state.GameState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Radiation Burst: "All Staging Piles are emptied." Confirmed with the user: "all" means every
 * player's Staging Pile across every Tier, and emptying one this way never triggers that Tier's
 * normal promotion, even if it was at or above threshold. See [RadiationBurstResolver]'s class
 * doc.
 */
class RadiationBurstResolverTest {

    private fun cardNamed(name: String) = FateHarvestCatalog.all.single { it.name == name }

    // Registers the card as resolving before handing back the request, mirroring what the real
    // Held-card-play path (TurnDriver.offerHeldCardPlay) does at the point a card leaves hand -
    // see GameState.resolvingCards' own class doc for why CardLifecycle.attemptPlay's matching
    // endResolvingCard now expects this.
    private fun requestFor(state: GameState, player: PlayerColor): CardPlayRequest {
        val card = cardNamed("Radiation Burst")
        state.beginResolvingCard(card)
        return CardPlayRequest(
            sourcePlayer = player,
            card = card,
            triggeringEvent = TriggeringEvent.PlayedFromHand,
        )
    }

    @Test
    fun `empties every player's Staging Pile across every Tier`() {
        val state = GameState.newGame(listOf(RED, GREEN))
        state.players.getValue(RED).tierPool(TierLevel.FIRST).sendToStagingPile(0)
        state.players.getValue(GREEN).tierPool(TierLevel.FIRST).sendToStagingPile(0)
        state.players.getValue(RED).tierPool(TierLevel.SECOND).startToken()
        state.players.getValue(RED).tierPool(TierLevel.SECOND).sendToStagingPile(0)

        val result = RadiationBurstResolver.resolve(state, requestFor(state, RED))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(0, state.players.getValue(RED).tierPool(TierLevel.FIRST).stagingPile)
        assertEquals(0, state.players.getValue(GREEN).tierPool(TierLevel.FIRST).stagingPile)
        assertEquals(0, state.players.getValue(RED).tierPool(TierLevel.SECOND).stagingPile)
    }

    @Test
    fun `empties even the playing player's own Staging Piles, not just opponents'`() {
        val state = GameState.newGame(listOf(RED))
        state.players.getValue(RED).tierPool(TierLevel.FIRST).sendToStagingPile(0)

        val result = RadiationBurstResolver.resolve(state, requestFor(state, RED))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(0, state.players.getValue(RED).tierPool(TierLevel.FIRST).stagingPile)
    }

    @Test
    fun `emptied tokens return to the Ion Battery`() {
        val state = GameState.newGame(listOf(RED))
        val pool = state.players.getValue(RED).tierPool(TierLevel.FIRST)
        pool.sendToStagingPile(0)
        val stagedCount = pool.stagingPile
        val ionBatteryBeforeBurst = pool.ionBattery

        RadiationBurstResolver.resolve(state, requestFor(state, RED))

        assertEquals(ionBatteryBeforeBurst + stagedCount, pool.ionBattery)
    }

    @Test
    fun `does not trigger promotion even when a pile is at or above its threshold`() {
        val state = GameState.newGame(listOf(RED))
        val pool = state.players.getValue(RED).tierPool(TierLevel.THIRD) // threshold 2
        pool.startToken()
        pool.sendToStagingPile(0)
        pool.startToken()
        pool.sendToStagingPile(0) // pile at 2, exactly the threshold
        assertEquals(2, pool.stagingPile)
        val secondTierInPlayBefore = state.players.getValue(RED).tierPool(TierLevel.FOURTH).inPlayCount

        val result = RadiationBurstResolver.resolve(state, requestFor(state, RED))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(0, pool.stagingPile)
        // no promotion: nothing new started on the 4th Tier despite the pile hitting threshold
        assertEquals(secondTierInPlayBefore, state.players.getValue(RED).tierPool(TierLevel.FOURTH).inPlayCount)
    }

    @Test
    fun `leaves in-play tokens and empty piles alone`() {
        val state = GameState.newGame(listOf(RED))
        val pool = state.players.getValue(RED).tierPool(TierLevel.FIRST)
        val inPlayBefore = pool.inPlayPositions

        val result = RadiationBurstResolver.resolve(state, requestFor(state, RED))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(inPlayBefore, pool.inPlayPositions)
        assertEquals(0, pool.stagingPile)
    }

    @Test
    fun `playable by any player, no color restriction`() {
        val state = GameState.newGame(listOf(GREEN))
        state.players.getValue(GREEN).tierPool(TierLevel.FIRST).sendToStagingPile(0)

        val result = RadiationBurstResolver.resolve(state, requestFor(state, GREEN))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(0, state.players.getValue(GREEN).tierPool(TierLevel.FIRST).stagingPile)
    }

    @Test
    fun `dispatches through CardEffectDispatcher with no target needed`() {
        val state = GameState.newGame(listOf(RED))
        state.players.getValue(RED).tierPool(TierLevel.FIRST).sendToStagingPile(0)

        val result = CardEffectDispatcher.dispatch(state, requestFor(state, RED))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(0, state.players.getValue(RED).tierPool(TierLevel.FIRST).stagingPile)
    }
}
