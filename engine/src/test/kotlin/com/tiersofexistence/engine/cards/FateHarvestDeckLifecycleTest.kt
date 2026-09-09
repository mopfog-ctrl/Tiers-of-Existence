package com.tiersofexistence.engine.cards

import com.tiersofexistence.engine.model.PlayerColor
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Focused, deterministic regression coverage for [FateHarvestDeck]'s own draw/discard/reshuffle
 * lifecycle — independent of `PlayerCountBenchmarkTest`/`GameSimulationTest` (neither of which
 * exercises this at the deck level in isolation; both only observe it indirectly through whole
 * games). Written specifically to close the gap that let the reshuffle non-determinism defect
 * (see `FateHarvestDeck`'s own class doc) go unnoticed at the unit level — this file is meant to
 * fail immediately and specifically if that regresses, rather than only showing up as a
 * hard-to-trace invariant violation deep in a 1000+-turn simulated game.
 *
 * [FateHarvestCard] is a plain value description with no per-copy identity — two physical copies
 * of the same named card are the literal same Kotlin object (see [FateHarvestCatalog.buildDeck]:
 * `List(card.rarity.copies) { card }` repeats the same reference). So "is this exact card still
 * in this exact zone" isn't a meaningful question above the multiplicity level; every check below
 * verifies exact *counts* per card name across zones instead, per [FateHarvestDeck.drawPileCards]
 * /[FateHarvestDeck.discardPileCards]'s own doc.
 */
class FateHarvestDeckLifecycleTest {

    private fun multiplicityOf(cards: List<FateHarvestCard>): Map<String, Int> = cards.groupingBy { it.name }.eachCount()

    @Test
    fun `drawing removes exactly one card from the draw pile immediately, and the pile shrinks by exactly one`() {
        val deck = FateHarvestDeck.newShuffled(Random(1))
        val sizeBefore = deck.drawPileSize
        val contentsBefore = multiplicityOf(deck.drawPileCards)

        val drawn = deck.draw()

        assertEquals(sizeBefore - 1, deck.drawPileSize, "draw pile must shrink by exactly one card")
        val contentsAfter = multiplicityOf(deck.drawPileCards)
        val expectedAfter = contentsBefore.toMutableMap().apply {
            merge(drawn.name, -1) { old, delta -> old + delta }
        }.filterValues { it > 0 }
        assertEquals(expectedAfter, contentsAfter, "the drawn card's own count must have decreased by exactly one, nothing else")
    }

    @Test
    fun `full 70-card deck - conservation holds across every draw, full exhaustion, forced reshuffle, and continued draws`() {
        verifyFullLifecycle(FateHarvestCatalog.buildDeck(), seed = 777L)
    }

    @Test
    fun `canonical filtered deck (unused Color cards removed) - same conservation and reshuffle guarantees`() {
        // Mirrors PlayerCountBenchmarkTest.buildDeckForColors for a representative 3-player game
        // (3 colors seated, 3 unseated) - not sharing code with it (that test is deliberately
        // self-contained), but exercising the same construction shape.
        val seated = setOf(PlayerColor.GREEN, PlayerColor.RED, PlayerColor.BLACK)
        val unseated = PlayerColor.entries.filterNot { it in seated }.toSet()
        val removedNames = unseated.flatMap { FateHarvestCatalog.colorCards[it].orEmpty() }.map { it.name }.toSet()
        val filtered = FateHarvestCatalog.buildDeck().filter { it.name !in removedNames }

        assertEquals(67, filtered.size, "70 - 3 unseated single-copy Color cards")
        assertTrue(removedNames.isNotEmpty())

        verifyFullLifecycle(filtered, seed = 9001L, forbiddenNames = removedNames)
    }

    /**
     * Drives a deck through its entire lifecycle — draw every card once (holding them, never
     * discarding, to isolate draw-pile-only behavior first), discard them all back, force at
     * least one reshuffle by drawing again with an empty draw pile, then drain the rest — while
     * asserting after *every single draw* that:
     * 1. the drawn card actually left the draw pile (checked by the caller via size deltas, same
     *    as the dedicated single-draw test above, implicitly re-verified here at scale);
     * 2. total multiplicity across draw pile + discard pile + "hand" (a plain local list standing
     *    in for a player's hand/currently-resolving card, since [FateHarvestDeck] itself has no
     *    concept of a hand) exactly equals [cards]' own multiplicity - never more, never less;
     * 3. every card drawn is a genuine member of the original [cards] list (no fabricated card),
     *    and, if [forbiddenNames] is given, never one of those names (a removed Color card must
     *    never resurface via any reshuffle).
     */
    private fun verifyFullLifecycle(cards: List<FateHarvestCard>, seed: Long, forbiddenNames: Set<String> = emptySet()) {
        val expected = multiplicityOf(cards)
        val random = Random(seed)
        val deck = FateHarvestDeck.forTesting(cards.shuffled(random), random)
        val hand = mutableListOf<FateHarvestCard>()

        fun totalMultiplicity(): Map<String, Int> = multiplicityOf(deck.drawPileCards + deck.discardPileCards + hand)
        fun assertConserved(context: String) {
            assertEquals(expected, totalMultiplicity(), "card conservation violated $context")
            assertEquals(cards.size, deck.drawPileSize + deck.discardPileSize + hand.size, "total count violated $context")
        }

        assertConserved("at start")

        // Draw the entire deck once, holding everything (never discarding yet) - proves the draw
        // pile alone, drained from full to empty, never loses or duplicates a card.
        repeat(cards.size) { i ->
            val drawn = deck.draw()
            assertTrue(cards.any { it.name == drawn.name }, "drawn card must be a real member of the original deck")
            assertTrue(drawn.name !in forbiddenNames, "a removed Color card must never be drawable: ${drawn.name}")
            hand += drawn
            assertConserved("after draw #${i + 1}")
        }
        assertEquals(0, deck.drawPileSize)
        assertEquals(0, deck.discardPileSize)
        assertEquals(cards.size, hand.size)

        // Discard everything back - proves the discard transfer itself is exact (no card gained
        // or lost in the hand -> discard pile move).
        val toDiscard = hand.toList()
        hand.clear()
        toDiscard.forEach { deck.discard(it) }
        assertEquals(cards.size, deck.discardPileSize)
        assertEquals(0, deck.drawPileSize)
        assertConserved("after discarding everything back")

        // Force a reshuffle: draw pile is empty, discard pile is full - draw() must reshuffle the
        // discard pile back into a fresh draw pile rather than crashing or fabricating a card.
        val firstPostReshuffle = deck.draw()
        assertEquals(cards.size - 1, deck.drawPileSize, "reshuffle must produce a draw pile of exactly (deck size - 1) after this draw")
        assertEquals(0, deck.discardPileSize, "the entire discard pile must have moved into the draw pile")
        assertTrue(cards.any { it.name == firstPostReshuffle.name })
        assertTrue(firstPostReshuffle.name !in forbiddenNames)
        hand += firstPostReshuffle
        assertConserved("immediately after the forced reshuffle")

        // Drain the rest post-reshuffle, re-asserting conservation after every draw - proves the
        // reshuffled pile itself has no duplication/loss, not just its first card.
        repeat(cards.size - 1) { i ->
            val drawn = deck.draw()
            assertTrue(drawn.name !in forbiddenNames)
            hand += drawn
            assertConserved("after post-reshuffle draw #${i + 1}")
        }
        assertEquals(0, deck.drawPileSize)
        assertEquals(cards.size, hand.size)
        assertConserved("at the end, fully drained twice over one reshuffle boundary")
    }

    @Test
    fun `same seed produces an identical draw order across the reshuffle boundary, in two independently constructed decks`() {
        // Exercises the exact regression scenario the FateHarvestDeck fix targets: the recycle
        // (reshuffle) boundary specifically, not just the initial shuffle every deck already got
        // right even before that fix.
        fun sequenceAcrossReshuffle(seed: Long): List<String> {
            val random = Random(seed)
            val deck = FateHarvestDeck.newShuffled(random)
            val sequence = mutableListOf<String>()
            val pendingDiscard = mutableListOf<FateHarvestCard>()
            // Two full passes through a 70-card deck, discarding in small batches as we go so the
            // draw pile empties and reshuffles more than once well before either pass completes.
            repeat(140) {
                val card = deck.draw()
                sequence += card.name
                pendingDiscard += card
                if (pendingDiscard.size == 7) {
                    pendingDiscard.forEach { deck.discard(it) }
                    pendingDiscard.clear()
                }
            }
            return sequence
        }

        val seed = 424242L
        val run1 = sequenceAcrossReshuffle(seed)
        val run2 = sequenceAcrossReshuffle(seed)

        assertEquals(run1, run2, "two independently constructed decks from the same seed must draw an identical sequence, including across every reshuffle boundary")
        assertEquals(140, run1.size)
    }

    @Test
    fun `discarding a card that was never drawn is still tracked exactly - no phantom cards invented on reshuffle`() {
        // Not a real gameplay scenario (a card always comes from a draw first) - but a targeted
        // check that FateHarvestDeck.discard/draw's own bookkeeping is purely additive/subtractive
        // over whatever is actually handed to it, never assuming or fabricating deck membership.
        val card = FateHarvestCatalog.all.first()
        val deck = FateHarvestDeck.forTesting(emptyList(), Random(5))
        deck.discard(card)
        assertEquals(0, deck.drawPileSize)
        assertEquals(1, deck.discardPileSize)

        // Draw pile is empty and discard pile has the one card - draw() reshuffles it into the
        // draw pile, then immediately removes it, leaving both piles empty again.
        val drawn = deck.draw()
        assertEquals(card.name, drawn.name)
        assertEquals(0, deck.drawPileSize)
        assertEquals(0, deck.discardPileSize)
    }
}
