package com.tiersofexistence.engine.cards.resolvers

import com.tiersofexistence.engine.cards.FateHarvestCatalog
import com.tiersofexistence.engine.cards.play.CardPlayRequest
import com.tiersofexistence.engine.cards.play.CardPlayResult
import com.tiersofexistence.engine.cards.play.CardTarget
import com.tiersofexistence.engine.cards.play.TargetValidationError
import com.tiersofexistence.engine.cards.play.TriggeringEvent
import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.PlayerColor.GREEN
import com.tiersofexistence.engine.model.PlayerColor.RED
import com.tiersofexistence.engine.model.PlayerColor.YELLOW
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.state.GameState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DestructionCardResolversTest {

    private fun cardNamed(name: String) = FateHarvestCatalog.all.single { it.name == name }

    private fun requestFor(player: PlayerColor, cardName: String) = CardPlayRequest(
        sourcePlayer = player,
        card = cardNamed(cardName),
        triggeringEvent = TriggeringEvent.PlayedFromHand,
    )

    // --- DestructionCardResolver (Divine Assistance, Insidious Flux) ---

    @Test
    fun `Divine Assistance destroys an in-play token belonging to any player`() {
        val state = GameState.newGame(listOf(RED, GREEN))
        val id = state.players.getValue(GREEN).tierPool(TierLevel.FIRST).idAt(0)!!

        val result = DestructionCardResolver.resolve(state, requestFor(RED, "Divine Assistance"), CardTarget.Token(id))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(0, state.players.getValue(GREEN).tierPool(TierLevel.FIRST).inPlayCount)
    }

    @Test
    fun `Divine Assistance can destroy a token sitting in a Staging Pile`() {
        val state = GameState.newGame(listOf(RED, GREEN))
        val pool = state.players.getValue(GREEN).tierPool(TierLevel.FIRST)
        pool.sendToStagingPile(0)
        val target = CardTarget.StagingPileToken(GREEN, TierLevel.FIRST)

        val result = DestructionCardResolver.resolve(state, requestFor(RED, "Divine Assistance"), target)

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(0, pool.stagingPile)
    }

    @Test
    fun `Divine Assistance is a named exception that can destroy a Zone-resident token`() {
        val state = GameState.newGame(listOf(RED, GREEN))
        val pool = state.players.getValue(GREEN).tierPool(TierLevel.FIRST)
        val id = pool.idAt(0)!!
        pool.moveInPlay(0, 10)
        pool.enterZone(fromPosition = 10, zoneNumber = 2)

        val result = DestructionCardResolver.resolve(state, requestFor(RED, "Divine Assistance"), CardTarget.Token(id))

        assertIs<CardPlayResult.Resolved>(result)
        assertTrue(pool.zoneResidents.isEmpty())
    }

    @Test
    fun `Insidious Flux (not a named exception) cannot destroy a Zone-resident token`() {
        val state = GameState.newGame(listOf(RED, GREEN))
        val pool = state.players.getValue(GREEN).tierPool(TierLevel.FIRST)
        val id = pool.idAt(0)!!
        pool.moveInPlay(0, 10)
        pool.enterZone(fromPosition = 10, zoneNumber = 2)

        val result = DestructionCardResolver.resolve(state, requestFor(RED, "Insidious Flux"), CardTarget.Token(id))

        assertIs<CardPlayResult.Rejected>(result)
        assertIs<TargetValidationError.ZoneOfProtectionBlocksTarget>((result as CardPlayResult.Rejected).reason)
        assertEquals(listOf(2), pool.zoneResidents) // untouched
    }

    @Test
    fun `Insidious Flux destroys one token from a chosen Staging Pile`() {
        val state = GameState.newGame(listOf(RED, GREEN))
        val pool = state.players.getValue(RED).tierPool(TierLevel.SECOND)
        pool.startToken()
        pool.sendToStagingPile(0)

        val result = DestructionCardResolver.resolve(state, requestFor(GREEN, "Insidious Flux"), CardTarget.StagingPileToken(RED, TierLevel.SECOND))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(0, pool.stagingPile)
    }

    @Test
    fun `a destroy card targeting a token that no longer exists is rejected gracefully, not a crash`() {
        val state = GameState.newGame(listOf(RED, GREEN))
        val pool = state.players.getValue(GREEN).tierPool(TierLevel.FIRST)
        val id = pool.idAt(0)!!
        pool.destroyInPlay(0) // the token is gone before this resolver ever runs

        val result = DestructionCardResolver.resolve(state, requestFor(RED, "Divine Assistance"), CardTarget.Token(id))

        assertIs<CardPlayResult.Rejected>(result)
        assertIs<TargetValidationError.NoLegalTarget>((result as CardPlayResult.Rejected).reason)
    }

    // --- InfernalAbyssResolver ---

    @Test
    fun `Infernal Abyss sacrifices one of the player's own tokens`() {
        val state = GameState.newGame(listOf(RED))
        val id = state.players.getValue(RED).tierPool(TierLevel.FIRST).idAt(0)!!

        val result = InfernalAbyssResolver.resolve(state, requestFor(RED, "Infernal Abyss"), CardTarget.Token(id))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(0, state.players.getValue(RED).tierPool(TierLevel.FIRST).inPlayCount)
    }

    @Test
    fun `Infernal Abyss cannot target another player's token`() {
        val state = GameState.newGame(listOf(RED, GREEN))
        val id = state.players.getValue(GREEN).tierPool(TierLevel.FIRST).idAt(0)!!

        val result = InfernalAbyssResolver.resolve(state, requestFor(RED, "Infernal Abyss"), CardTarget.Token(id))

        assertIs<CardPlayResult.Rejected>(result)
        assertEquals(1, state.players.getValue(GREEN).tierPool(TierLevel.FIRST).inPlayCount) // untouched
        assertEquals(0, state.deck.discardPileSize) // never even attempted
    }

    @Test
    fun `Infernal Abyss cannot target the player's own Zone-resident token either`() {
        val state = GameState.newGame(listOf(RED))
        val pool = state.players.getValue(RED).tierPool(TierLevel.FIRST)
        val id = pool.idAt(0)!!
        pool.moveInPlay(0, 10)
        pool.enterZone(fromPosition = 10, zoneNumber = 2)

        val result = InfernalAbyssResolver.resolve(state, requestFor(RED, "Infernal Abyss"), CardTarget.Token(id))

        assertIs<CardPlayResult.Rejected>(result)
        assertTrue(pool.zoneResidents.isNotEmpty()) // Infernal Abyss gets no own-Zone carve-out
    }

    // --- CorpuscleRotResolver ---

    @Test
    fun `Corpuscle Rot destroys a 4th Tier token and starts new tokens on the 1st and 2nd Tiers`() {
        val state = GameState.newGame(listOf(YELLOW, GREEN))
        val id = state.players.getValue(GREEN).tierPool(TierLevel.FOURTH).startToken()
        val before1st = state.players.getValue(YELLOW).tierPool(TierLevel.FIRST).inPlayCount

        val result = CorpuscleRotResolver.resolve(state, requestFor(YELLOW, "Corpuscle Rot"), CardTarget.Token(id))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(0, state.players.getValue(GREEN).tierPool(TierLevel.FOURTH).inPlayCount)
        assertEquals(before1st + 1, state.players.getValue(YELLOW).tierPool(TierLevel.FIRST).inPlayCount)
        assertEquals(1, state.players.getValue(YELLOW).tierPool(TierLevel.SECOND).inPlayCount)
    }

    @Test
    fun `Corpuscle Rot rejects a target that isn't on the 4th Tier`() {
        val state = GameState.newGame(listOf(YELLOW, GREEN))
        val id = state.players.getValue(GREEN).tierPool(TierLevel.FIRST).idAt(0)!!

        assertFailsWith<IllegalArgumentException> { CorpuscleRotResolver.resolve(state, requestFor(YELLOW, "Corpuscle Rot"), CardTarget.Token(id)) }
    }

    @Test
    fun `Corpuscle Rot played by a non-Yellow player is rejected before any board mutation`() {
        val state = GameState.newGame(listOf(RED, GREEN))
        val id = state.players.getValue(GREEN).tierPool(TierLevel.FOURTH).startToken()

        val result = CorpuscleRotResolver.resolve(state, requestFor(RED, "Corpuscle Rot"), CardTarget.Token(id))

        assertIs<CardPlayResult.Rejected>(result)
        assertEquals(1, state.players.getValue(GREEN).tierPool(TierLevel.FOURTH).inPlayCount) // untouched
        assertEquals(0, state.deck.discardPileSize)
    }
}
