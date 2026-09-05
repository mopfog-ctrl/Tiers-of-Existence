package com.tiersofexistence.engine.rules.precedence

import com.tiersofexistence.engine.cards.FateHarvestCatalog
import com.tiersofexistence.engine.cards.play.CardPlayRequest
import com.tiersofexistence.engine.cards.play.TriggeringEvent
import com.tiersofexistence.engine.model.PlayerColor.BLACK
import com.tiersofexistence.engine.model.PlayerColor.BLUE
import com.tiersofexistence.engine.model.PlayerColor.GREEN
import com.tiersofexistence.engine.model.PlayerColor.RED
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InteractionChainTest {

    private fun cardNamed(name: String) = FateHarvestCatalog.all.single { it.name == name }

    private fun playRequest(player: com.tiersofexistence.engine.model.PlayerColor, cardName: String) = CardPlayRequest(
        sourcePlayer = player,
        card = cardNamed(cardName),
        triggeringEvent = TriggeringEvent.RespondingInChain(chainId = 0),
    )

    private fun openChain(
        suspendedAction: SuspendedAction = SuspendedAction.PendingRoll(RED),
        players: List<com.tiersofexistence.engine.model.PlayerColor> = listOf(RED, GREEN, BLACK, BLUE),
    ) = InteractionChain.open(suspendedAction, players)

    // --- Basic lifecycle: pending -> respond/pass -> close -> reverse-order resolve ---

    @Test
    fun `a fresh chain is open with everyone eligible and nobody passed`() {
        val chain = openChain()

        assertTrue(chain.isOpen)
        assertEquals(listOf(RED, GREEN, BLACK, BLUE), chain.currentlyEligibleToAct())
        assertFalse(chain.hasPassed(RED))
    }

    @Test
    fun `passing removes a player from currently-eligible until a new entry resets it`() {
        val chain = openChain()

        chain.pass(RED)

        assertTrue(chain.hasPassed(RED))
        assertEquals(listOf(GREEN, BLACK, BLUE), chain.currentlyEligibleToAct())
        assertTrue(chain.isOpen) // not everyone has passed yet
    }

    @Test
    fun `the window closes once every eligible player has passed since the last entry`() {
        val chain = openChain(players = listOf(RED, GREEN))

        chain.pass(RED)
        assertTrue(chain.isOpen)
        chain.pass(GREEN)

        assertEquals(ChainState.CLOSED, chain.state)
        assertTrue(chain.currentlyEligibleToAct().isEmpty())
    }

    @Test
    fun `a new entry re-opens the response round, including for players who'd already passed`() {
        val chain = openChain(players = listOf(RED, GREEN, BLACK))
        chain.pass(RED)
        chain.pass(GREEN)
        assertTrue(chain.isOpen) // BLACK hasn't passed yet

        chain.respond(BLACK, playRequest(BLACK, "Tactical Step"))

        assertFalse(chain.hasPassed(RED))
        assertFalse(chain.hasPassed(GREEN))
        assertEquals(listOf(RED, GREEN, BLACK), chain.currentlyEligibleToAct())
    }

    @Test
    fun `multiple Precedence entries resolve in reverse order of play`() {
        val chain = openChain(players = listOf(RED, GREEN, BLACK))
        val first = chain.respond(RED, playRequest(RED, "Tactical Step"))
        val second = chain.respond(GREEN, playRequest(GREEN, "Tactical Motion"))
        val third = chain.respond(BLACK, playRequest(BLACK, "Graviton Rift"))
        chain.pass(RED)
        chain.pass(GREEN)
        chain.pass(BLACK)

        val order = chain.resolve()

        assertEquals(listOf(third.id, second.id, first.id), order.map { it.id })
    }

    // --- Precedence-only enforcement ---

    @Test
    fun `a card without Precedence cannot respond in a chain`() {
        val chain = openChain()
        val nonPrecedence = playRequest(RED, "Dwarf Star")

        assertFailsWith<IllegalArgumentException> { chain.respond(RED, nonPrecedence) }
    }

    @Test
    fun `an ineligible player cannot respond or pass`() {
        val chain = openChain(players = listOf(RED, GREEN))

        assertFailsWith<IllegalArgumentException> { chain.respond(BLACK, playRequest(BLACK, "Tactical Step")) }
        assertFailsWith<IllegalArgumentException> { chain.pass(BLACK) }
    }

    @Test
    fun `acting on a closed chain fails`() {
        val chain = openChain(players = listOf(RED))
        chain.pass(RED)
        assertEquals(ChainState.CLOSED, chain.state)

        assertFailsWith<IllegalStateException> { chain.respond(RED, playRequest(RED, "Tactical Step")) }
        assertFailsWith<IllegalStateException> { chain.pass(RED) }
    }

    // --- Annulment splice semantics (rule 22) ---

    @Test
    fun `Annulment cancels the immediately preceding entry, which is excluded from resolution`() {
        val chain = openChain(players = listOf(RED, GREEN))
        val victim = chain.respond(RED, playRequest(RED, "Tactical Step"))
        val annulment = chain.respond(GREEN, playRequest(GREEN, "Annulment (Antimatter)"))
        chain.pass(RED)
        chain.pass(GREEN)

        val order = chain.resolve()

        assertEquals(annulment.id, chain.entriesSnapshot().first { it.id == victim.id }.cancelledByEntryId)
        assertTrue(order.none { it.id == victim.id })
        assertTrue(order.none { it.id == annulment.id }) // Annulment itself never "resolves" as an effect
        assertTrue(order.isEmpty())
    }

    @Test
    fun `cards played after an Annulment resolve as if the cancelled card and the Annulment never existed`() {
        val chain = openChain(players = listOf(RED, GREEN, BLACK))
        val a = chain.respond(RED, playRequest(RED, "Tactical Step"))
        val b = chain.respond(GREEN, playRequest(GREEN, "Tactical Motion")) // will be cancelled
        val annulment = chain.respond(BLACK, playRequest(BLACK, "Annulment (Antimatter)"))
        val c = chain.respond(RED, playRequest(RED, "Last Gasp"))
        chain.pass(RED)
        chain.pass(GREEN)
        chain.pass(BLACK)

        val order = chain.resolve()

        // Effective chain is [a, c] (b and the Annulment spliced out) — reverse order is [c, a].
        assertEquals(listOf(c.id, a.id), order.map { it.id })
        assertTrue(order.none { it.id == b.id || it.id == annulment.id })
    }

    @Test
    fun `Annulment as the chain's first entry cancels the suspended card resolution itself`() {
        val pendingCard = playRequest(RED, "Divine Assistance")
        val chain = openChain(suspendedAction = SuspendedAction.PendingCardResolution(pendingCard), players = listOf(GREEN))

        chain.respond(GREEN, playRequest(GREEN, "Annulment (Antimatter)"))

        assertTrue(chain.isSuspendedActionCancelled)
        chain.pass(GREEN)
        assertTrue(chain.resolve().isEmpty())
    }

    @Test
    fun `Annulment as the first entry against a pending roll has nothing to cancel and no-ops structurally`() {
        val chain = openChain(suspendedAction = SuspendedAction.PendingRoll(RED), players = listOf(GREEN))

        chain.respond(GREEN, playRequest(GREEN, "Annulment (Antimatter)"))

        assertFalse(chain.isSuspendedActionCancelled) // nothing to cancel — PendingRoll has no card
    }

    @Test
    fun `double Annulment cancels the first Annulment, but the card it had already cancelled stays cancelled`() {
        // Engine choice documented in InteractionChain's class doc and card-mechanics-matrix.md
        // §4 Q11: a cancellation, once applied, is permanent — it is not undone by later
        // cancelling the Annulment that caused it. This test locks in that specific behavior.
        val chain = openChain(players = listOf(RED, GREEN, BLACK))
        val x = chain.respond(RED, playRequest(RED, "Tactical Step"))
        val firstAnnulment = chain.respond(GREEN, playRequest(GREEN, "Annulment (Antimatter)")) // cancels x
        val secondAnnulment = chain.respond(BLACK, playRequest(BLACK, "Annulment (Antimatter)")) // cancels firstAnnulment
        chain.pass(RED)
        chain.pass(GREEN)
        chain.pass(BLACK)

        val order = chain.resolve()

        assertEquals(secondAnnulment.id, chain.entriesSnapshot().first { it.id == firstAnnulment.id }.cancelledByEntryId)
        // x is still cancelled — NOT restored by firstAnnulment itself being cancelled.
        assertTrue(chain.entriesSnapshot().first { it.id == x.id }.isCancelled)
        assertTrue(order.isEmpty())
    }

    // --- resolve()/finishResolving() state machine ---

    @Test
    fun `resolve requires the chain to be closed first`() {
        val chain = openChain(players = listOf(RED))
        assertFailsWith<IllegalStateException> { chain.resolve() }
    }

    @Test
    fun `finishResolving transitions RESOLVING to RESOLVED`() {
        val chain = openChain(players = listOf(RED))
        chain.pass(RED)
        chain.resolve()
        assertEquals(ChainState.RESOLVING, chain.state)

        chain.finishResolving()

        assertEquals(ChainState.RESOLVED, chain.state)
    }
}
