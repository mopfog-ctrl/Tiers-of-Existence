package com.tiersofexistence.engine.cards

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals

class FateHarvestDeckTest {

    @Test
    fun `drawing every card then discarding reshuffles automatically`() {
        val deck = FateHarvestDeck.newShuffled(Random(42))
        val drawn = (1..70).map { deck.draw(Random(42)) }
        drawn.forEach { deck.discard(it) }
        assertEquals(0, deck.drawPileSize)
        assertEquals(70, deck.discardPileSize)

        val nextCard = deck.draw(Random(42))

        assertEquals(69, deck.drawPileSize)
        assertEquals(0, deck.discardPileSize)
        assertEquals(true, FateHarvestCatalog.all.any { it.name == nextCard.name })
    }
}
