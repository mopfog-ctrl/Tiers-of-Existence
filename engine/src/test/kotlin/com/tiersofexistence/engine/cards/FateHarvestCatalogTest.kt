package com.tiersofexistence.engine.cards

import com.tiersofexistence.engine.model.PlayerColor
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FateHarvestCatalogTest {

    @Test
    fun `deck totals 70 cards matching the Parts List`() {
        assertEquals(70, FateHarvestCatalog.deckSize())
        assertEquals(70, FateHarvestCatalog.buildDeck().size)
    }

    @Test
    fun `rarity copy counts match the rulebook sections`() {
        val byRarity = FateHarvestCatalog.all.groupBy { it.rarity }
        assertEquals(10, byRarity.getValue(CardRarity.SINGLE).size)
        assertEquals(10, byRarity.getValue(CardRarity.DOUBLE).size)
        assertEquals(8, byRarity.getValue(CardRarity.TRIPLE).size)
        assertEquals(4, byRarity.getValue(CardRarity.QUADRUPLE).size)
    }

    @Test
    fun `every player color has exactly one restricted color card`() {
        for (color in PlayerColor.entries) {
            val cards = FateHarvestCatalog.colorCards[color].orEmpty()
            assertEquals(1, cards.size, "Expected exactly one Color card for $color")
        }
    }

    @Test
    fun `card names are unique`() {
        val names = FateHarvestCatalog.all.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `precedence cards are flagged`() {
        val precedenceCardNames = FateHarvestCatalog.all.filter { it.hasPrecedence }.map { it.name }.toSet()
        assertTrue(precedenceCardNames.containsAll(listOf("Annulment (Antimatter)", "Last Gasp", "Graviton Rift", "Fluidic Wave")))
    }
}
