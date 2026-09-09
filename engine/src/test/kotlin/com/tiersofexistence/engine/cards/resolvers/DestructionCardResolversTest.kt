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
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DestructionCardResolversTest {

    private fun cardNamed(name: String) = FateHarvestCatalog.all.single { it.name == name }

    // Registers the card as resolving before handing back the request, mirroring what the real
    // Held-card-play path (TurnDriver.offerHeldCardPlay) does at the point a card leaves hand -
    // see GameState.resolvingCards' own class doc for why CardLifecycle.attemptPlay's matching
    // endResolvingCard now expects this.
    private fun requestFor(state: GameState, player: PlayerColor, cardName: String): CardPlayRequest {
        val card = cardNamed(cardName)
        state.beginResolvingCard(card)
        return CardPlayRequest(
            sourcePlayer = player,
            card = card,
            triggeringEvent = TriggeringEvent.PlayedFromHand,
        )
    }

    // --- DestructionCardResolver (Divine Assistance, Insidious Flux) ---

    @Test
    fun `Divine Assistance destroys an in-play token belonging to any player`() {
        val state = GameState.newGame(listOf(RED, GREEN))
        val id = state.players.getValue(GREEN).tierPool(TierLevel.FIRST).idAt(0)!!

        val result = DestructionCardResolver.resolve(state, requestFor(state, RED, "Divine Assistance"), CardTarget.Token(id))

        assertIs<CardPlayResult.Resolved>(result)
        // 1st Tier auto-replenishes from the Ion Battery back up to the 2-in-play cap, so GREEN
        // isn't left stranded at 0.
        assertEquals(2, state.players.getValue(GREEN).tierPool(TierLevel.FIRST).inPlayCount)
    }

    @Test
    fun `Divine Assistance can destroy a token sitting in a Staging Pile`() {
        val state = GameState.newGame(listOf(RED, GREEN))
        val pool = state.players.getValue(GREEN).tierPool(TierLevel.FIRST)
        pool.sendToStagingPile(0)
        val target = CardTarget.StagingPileToken(GREEN, TierLevel.FIRST)

        val result = DestructionCardResolver.resolve(state, requestFor(state, RED, "Divine Assistance"), target)

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

        val result = DestructionCardResolver.resolve(state, requestFor(state, RED, "Divine Assistance"), CardTarget.Token(id))

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

        val result = DestructionCardResolver.resolve(state, requestFor(state, RED, "Insidious Flux"), CardTarget.Token(id))

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

        val result = DestructionCardResolver.resolve(state, requestFor(state, GREEN, "Insidious Flux"), CardTarget.StagingPileToken(RED, TierLevel.SECOND))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(0, pool.stagingPile)
    }

    @Test
    fun `a destroy card targeting a token that no longer exists is rejected gracefully, not a crash`() {
        val state = GameState.newGame(listOf(RED, GREEN))
        val pool = state.players.getValue(GREEN).tierPool(TierLevel.FIRST)
        val id = pool.idAt(0)!!
        pool.destroyInPlay(0) // the token is gone before this resolver ever runs

        val result = DestructionCardResolver.resolve(state, requestFor(state, RED, "Divine Assistance"), CardTarget.Token(id))

        assertIs<CardPlayResult.Rejected>(result)
        assertIs<TargetValidationError.NoLegalTarget>((result as CardPlayResult.Rejected).reason)
    }

    @Test
    fun `a destroy card targeting an emptied Staging Pile is rejected gracefully, not a crash`() {
        // Previously TierTokenPool.destroyFromStagingPile() `require`d a non-empty pile with no
        // caller-side check, so a StagingPileToken target that emptied out between being chosen
        // and resolving (e.g. another effect draining it first in the same Precedence chain)
        // would crash the resolver instead of rejecting like every other stale target does.
        val state = GameState.newGame(listOf(RED, GREEN))
        val pool = state.players.getValue(GREEN).tierPool(TierLevel.FIRST)
        assertEquals(0, pool.stagingPile) // never populated — nothing to destroy

        val result = DestructionCardResolver.resolve(
            state,
            requestFor(state, RED, "Divine Assistance"),
            CardTarget.StagingPileToken(GREEN, TierLevel.FIRST),
        )

        assertIs<CardPlayResult.Rejected>(result)
        assertIs<TargetValidationError.NoLegalTarget>((result as CardPlayResult.Rejected).reason)
    }

    // --- InfernalAbyssResolver ---

    @Test
    fun `Infernal Abyss sacrifices one of the player's own tokens`() {
        val state = GameState.newGame(listOf(RED))
        val id = state.players.getValue(RED).tierPool(TierLevel.FIRST).idAt(0)!!

        val result = InfernalAbyssResolver.resolve(state, requestFor(state, RED, "Infernal Abyss"), CardTarget.Token(id))

        assertIs<CardPlayResult.Resolved>(result)
        // 1st Tier auto-replenishes from the Ion Battery back up to the 2-in-play cap, so RED
        // isn't left stranded at 0.
        assertEquals(2, state.players.getValue(RED).tierPool(TierLevel.FIRST).inPlayCount)
    }

    @Test
    fun `Infernal Abyss cannot target another player's token`() {
        val state = GameState.newGame(listOf(RED, GREEN))
        val id = state.players.getValue(GREEN).tierPool(TierLevel.FIRST).idAt(0)!!

        val result = InfernalAbyssResolver.resolve(state, requestFor(state, RED, "Infernal Abyss"), CardTarget.Token(id))

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

        val result = InfernalAbyssResolver.resolve(state, requestFor(state, RED, "Infernal Abyss"), CardTarget.Token(id))

        assertIs<CardPlayResult.Rejected>(result)
        assertTrue(pool.zoneResidents.isNotEmpty()) // Infernal Abyss gets no own-Zone carve-out
    }

    // --- CorpuscleRotResolver ---

    @Test
    fun `Corpuscle Rot destroys a 4th Tier token and starts new tokens on the 1st and 2nd Tiers`() {
        val state = GameState.newGame(listOf(YELLOW, GREEN))
        val id = state.players.getValue(GREEN).tierPool(TierLevel.FOURTH).startToken()!!
        val before1st = state.players.getValue(YELLOW).tierPool(TierLevel.FIRST).inPlayCount

        val result = CorpuscleRotResolver.resolve(state, requestFor(state, YELLOW, "Corpuscle Rot"), CardTarget.Token(id))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(0, state.players.getValue(GREEN).tierPool(TierLevel.FOURTH).inPlayCount)
        assertEquals(before1st + 1, state.players.getValue(YELLOW).tierPool(TierLevel.FIRST).inPlayCount)
        assertEquals(1, state.players.getValue(YELLOW).tierPool(TierLevel.SECOND).inPlayCount)
    }

    @Test
    fun `Corpuscle Rot still resolves the destroy even if a construct Tier is fully exhausted, rather than crashing`() {
        // Corpuscle Rot's construct half calls TierTokenPool.startToken() on the 1st and 2nd
        // Tiers unconditionally. Since startToken() gracefully returns null (not a crash) once a
        // Tier's tokens are all already accounted for elsewhere (see TierTokenPoolTest's
        // "startToken returns null" regression test and CLAUDE.md's Tier-token resource/capacity
        // re-audit), Corpuscle Rot's own destroy half must still succeed even when one of its two
        // construct Tiers has nothing left to start — a "special/limited card" atomicity concern
        // that turned out to already be fixed by that more general startToken() correction.
        val state = GameState.newGame(listOf(YELLOW, GREEN))
        val id = state.players.getValue(GREEN).tierPool(TierLevel.FOURTH).startToken()!!
        val secondPool = state.players.getValue(YELLOW).tierPool(TierLevel.SECOND)
        repeat(6) { secondPool.startToken() } // drains the Ion Battery into play + Hatchery
        repeat(4) { secondPool.sendToStagingPile(secondPool.inPlayPositions.first()) } // and the Hatchery too
        assertEquals(0, secondPool.ionBattery)
        assertEquals(0, secondPool.hatchery)

        val result = CorpuscleRotResolver.resolve(state, requestFor(state, YELLOW, "Corpuscle Rot"), CardTarget.Token(id))

        assertIs<CardPlayResult.Resolved>(result) // the destroy half still applies, no crash
        assertEquals(0, state.players.getValue(GREEN).tierPool(TierLevel.FOURTH).inPlayCount)
        assertEquals(2, secondPool.inPlayCount) // 2nd Tier construct silently no-op'd — nothing to start
    }

    @Test
    fun `Corpuscle Rot rejects a target that isn't on the 4th Tier, gracefully rather than crashing`() {
        // Regression test: an earlier version of this check was a raw `require` that threw
        // IllegalArgumentException instead of returning CardPlayResult.Rejected — the one
        // inconsistency in an otherwise uniform "an illegal target is always Rejected, never a
        // crash" pattern across every other resolver in this file (found by
        // GameSimulationTest's randomized-play harness, which hit it on a real, player-reachable
        // target choice within the first few hundred simulated turns).
        val state = GameState.newGame(listOf(YELLOW, GREEN))
        val id = state.players.getValue(GREEN).tierPool(TierLevel.FIRST).idAt(0)!!

        val result = CorpuscleRotResolver.resolve(state, requestFor(state, YELLOW, "Corpuscle Rot"), CardTarget.Token(id))

        assertIs<CardPlayResult.Rejected>(result)
        assertIs<TargetValidationError.WrongTokenType>(result.reason)
        assertEquals(1, state.players.getValue(GREEN).tierPool(TierLevel.FIRST).inPlayCount) // untouched
        assertEquals(0, state.deck.discardPileSize) // Corpuscle Rot itself never discarded on an illegal target
    }

    @Test
    fun `Corpuscle Rot played by a non-Yellow player is rejected before any board mutation`() {
        val state = GameState.newGame(listOf(RED, GREEN))
        val id = state.players.getValue(GREEN).tierPool(TierLevel.FOURTH).startToken()!!

        val result = CorpuscleRotResolver.resolve(state, requestFor(state, RED, "Corpuscle Rot"), CardTarget.Token(id))

        assertIs<CardPlayResult.Rejected>(result)
        assertEquals(1, state.players.getValue(GREEN).tierPool(TierLevel.FOURTH).inPlayCount) // untouched
        assertEquals(0, state.deck.discardPileSize)
    }
}
