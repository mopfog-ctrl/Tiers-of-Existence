package com.tiersofexistence.engine.state

import com.tiersofexistence.engine.cards.FateHarvestCatalog
import com.tiersofexistence.engine.model.PlayerColor
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [GameState.resolvingCards] is the fourth explicit card zone (alongside the deck's own draw
 * pile/discard pile and every player's hand) — see that property's own class doc for the full
 * design rationale and the real defect (73 whole-game-rarity-ceiling violations in
 * `PlayerCountFateHarvestRegenerationCorrectedBenchmarkTest`'s first attempt) it exists to close.
 * These tests exercise the zone-transition mechanism directly, independent of any real turn being
 * driven — [beginResolvingCard]/[endResolvingCard]'s own contract: every card enters exactly once
 * and leaves exactly once, multiple cards can be resolving simultaneously (nesting), and
 * [liveCardCountsOutsideDiscardPile] correctly reflects both hands and resolving cards together.
 */
class GameStateResolvingCardsTest {

    private val radiationBurst = FateHarvestCatalog.all.single { it.name == "Radiation Burst" }
    private val corpuscleRot = FateHarvestCatalog.all.single { it.name == "Corpuscle Rot" }
    private val dwarfStar = FateHarvestCatalog.all.single { it.name == "Dwarf Star" }

    private fun newGame() = GameState.newGame(listOf(PlayerColor.RED, PlayerColor.BLUE))

    @Test
    fun `resolvingCards is empty on a freshly constructed game`() {
        val state = newGame()
        assertTrue(state.resolvingCards.isEmpty())
    }

    @Test
    fun `beginResolvingCard then endResolvingCard is a symmetric, complete transition`() {
        val state = newGame()
        state.beginResolvingCard(radiationBurst)
        assertEquals(listOf(radiationBurst), state.resolvingCards)

        state.endResolvingCard(radiationBurst)
        assertTrue(state.resolvingCards.isEmpty())
    }

    @Test
    fun `endResolvingCard throws if the card was never begun - never a silent no-op`() {
        val state = newGame()
        val exception = assertFailsWith<IllegalStateException> {
            state.endResolvingCard(radiationBurst)
        }
        assertTrue(exception.message?.contains("Radiation Burst") == true, "expected a descriptive message naming the card, got: ${exception.message}")
    }

    @Test
    fun `endResolvingCard throws if called a second time for the same already-ended card - no double-removal`() {
        val state = newGame()
        state.beginResolvingCard(radiationBurst)
        state.endResolvingCard(radiationBurst)
        assertFailsWith<IllegalStateException> {
            state.endResolvingCard(radiationBurst)
        }
    }

    @Test
    fun `multiple physical cards can be resolving simultaneously - nesting`() {
        // Models the exact scenario the discovered defect came from: one card's own resolution
        // (still open) causing a second, unrelated card to be drawn and begin resolving before
        // the first has finished - both must be visible in resolvingCards at once, and each ends
        // independently without disturbing the other.
        val state = newGame()
        state.beginResolvingCard(radiationBurst)
        state.beginResolvingCard(corpuscleRot)
        assertEquals(listOf(radiationBurst, corpuscleRot), state.resolvingCards)

        // The nested (second-drawn) card finishes resolving first - a plausible real ordering,
        // since it was drawn as a side effect partway through the outer card's own resolution.
        state.endResolvingCard(corpuscleRot)
        assertEquals(listOf(radiationBurst), state.resolvingCards)

        state.endResolvingCard(radiationBurst)
        assertTrue(state.resolvingCards.isEmpty())
    }

    @Test
    fun `two simultaneously-resolving copies of the same-named card are each tracked and removed independently`() {
        // FateHarvestCard has no per-copy identity (see that class's own doc) - two physical
        // copies of the same card are the literal same object - so this must work via list
        // membership (one entry per physical copy), not object/key uniqueness.
        val state = newGame()
        state.beginResolvingCard(radiationBurst)
        state.beginResolvingCard(radiationBurst)
        assertEquals(listOf(radiationBurst, radiationBurst), state.resolvingCards)

        state.endResolvingCard(radiationBurst)
        assertEquals(listOf(radiationBurst), state.resolvingCards, "one copy ending should leave exactly one still resolving, not zero or two")

        state.endResolvingCard(radiationBurst)
        assertTrue(state.resolvingCards.isEmpty())
    }

    @Test
    fun `resolvingCards returns a defensive copy - mutating it never affects GameState's own tracking`() {
        val state = newGame()
        state.beginResolvingCard(radiationBurst)
        val snapshot = state.resolvingCards
        // resolvingCards' own declared type is List<FateHarvestCard> (read-only) - there's no
        // mutator to call on `snapshot` itself, so the guarantee under test is that a caller
        // holding this snapshot never observes it change out from under them.
        state.beginResolvingCard(corpuscleRot)
        assertEquals(listOf(radiationBurst), snapshot, "an earlier snapshot must not reflect a later beginResolvingCard call")
        assertEquals(listOf(radiationBurst, corpuscleRot), state.resolvingCards)
    }

    @Test
    fun `liveCardCountsOutsideDiscardPile combines hands and resolving cards, per card name`() {
        val state = newGame()
        state.players.getValue(PlayerColor.RED).hand += dwarfStar
        state.players.getValue(PlayerColor.BLUE).hand += radiationBurst
        state.beginResolvingCard(radiationBurst) // a second Radiation Burst, mid-resolution
        state.beginResolvingCard(corpuscleRot)

        val counts = state.liveCardCountsOutsideDiscardPile()

        assertEquals(1, counts["Dwarf Star"])
        assertEquals(2, counts["Radiation Burst"], "one in a hand plus one resolving must both count")
        assertEquals(1, counts["Corpuscle Rot"])
    }

    @Test
    fun `liveCardCountsOutsideDiscardPile is empty when nothing is held or resolving`() {
        val state = newGame()
        assertTrue(state.liveCardCountsOutsideDiscardPile().isEmpty())
    }

    @Test
    fun `liveCardCountsOutsideDiscardPile reflects live hand mutations, not a stale snapshot`() {
        val state = newGame()
        val hand = state.players.getValue(PlayerColor.RED).hand
        hand += dwarfStar
        assertEquals(1, state.liveCardCountsOutsideDiscardPile()["Dwarf Star"])
        hand.remove(dwarfStar)
        assertTrue("Dwarf Star" !in state.liveCardCountsOutsideDiscardPile())
    }
}
