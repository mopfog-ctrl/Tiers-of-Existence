package com.tiersofexistence.engine.cards

import kotlin.random.Random

/**
 * A shuffled draw pile plus discard pile of [FateHarvestCard]s, mirroring rulebook rule #6:
 * "When the Fate Harvest pile is empty, shuffle the discard pile ... and put the cards back
 * onto the Fate Harvest Pile."
 */
class FateHarvestDeck private constructor(
    private val drawPile: ArrayDeque<FateHarvestCard>,
    private val discardPile: MutableList<FateHarvestCard> = mutableListOf(),
) {
    val drawPileSize: Int get() = drawPile.size
    val discardPileSize: Int get() = discardPile.size

    /** Draws the top card, reshuffling the discard pile into a fresh draw pile first if needed. */
    fun draw(random: Random = Random): FateHarvestCard {
        if (drawPile.isEmpty()) {
            require(discardPile.isNotEmpty()) { "Fate Harvest deck and discard pile are both empty" }
            drawPile.addAll(discardPile.shuffled(random))
            discardPile.clear()
        }
        return drawPile.removeFirst()
    }

    /** A played card always goes to the discard pile (Fate Harvest Card Rules #5). */
    fun discard(card: FateHarvestCard) {
        discardPile += card
    }

    companion object {
        fun newShuffled(random: Random = Random): FateHarvestDeck =
            FateHarvestDeck(ArrayDeque(FateHarvestCatalog.buildDeck().shuffled(random)))
    }
}
