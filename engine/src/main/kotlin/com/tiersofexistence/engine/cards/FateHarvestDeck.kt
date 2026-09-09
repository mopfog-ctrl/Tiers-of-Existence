package com.tiersofexistence.engine.cards

import kotlin.random.Random

/**
 * A shuffled draw pile plus discard pile of [FateHarvestCard]s, mirroring rulebook rule #6:
 * "When the Fate Harvest pile is empty, shuffle the discard pile ... and put the cards back
 * onto the Fate Harvest Pile."
 *
 * **Owns its own [random] for the deck's entire lifetime, fixed at construction.** Previously
 * [draw] took an optional `random` parameter defaulting to the ambient/global `kotlin.random
 * .Random` — every caller in `TurnEngine` actually called `state.deck.draw()` with no argument,
 * so every reshuffle (triggered the first time a sufficiently long game exhausts the ~66-70 card
 * draw pile — routine in a game running hundreds/thousands of turns) silently drew from
 * unseeded, non-reproducible randomness, even though [newShuffled]'s own initial shuffle was
 * correctly seeded. This broke the "same seed -> same game" guarantee every seeded
 * simulation/test in this codebase relies on (`GameSimulationTest`, `PlayerCountBenchmarkTest`,
 * ...) the moment any game ran long enough to reshuffle even once — found while investigating an
 * invariant violation in `PlayerCountBenchmarkTest` that turned out to be irreproducible from its
 * own reported seed, tracing back to exactly this. Fixed by having the deck itself retain the
 * [Random] instance it was constructed with and reuse it for every later reshuffle — a seeded
 * deck is now deterministic for its whole lifetime, not just its first shuffle.
 */
class FateHarvestDeck private constructor(
    private val drawPile: ArrayDeque<FateHarvestCard>,
    private val discardPile: MutableList<FateHarvestCard> = mutableListOf(),
    private val random: Random = Random,
) {
    val drawPileSize: Int get() = drawPile.size
    val discardPileSize: Int get() = discardPile.size

    /** Read-only snapshots of each pile's actual contents (defensive copies — mutating the
     * returned list never affects this deck) — for card-conservation/multiplicity checking that
     * needs more than just a count (e.g. verifying no card type's copy count ever drifts from its
     * canonical multiplicity, not just that the aggregate total stays right). Every
     * [FateHarvestCard] is a plain value description with no per-copy identity (two physical
     * copies of the same named card are the literal same object — see [FateHarvestCatalog
     * .buildDeck]), so "which zone a card is in" is meaningful only in aggregate, by name — these
     * are for exactly that. */
    val drawPileCards: List<FateHarvestCard> get() = drawPile.toList()
    val discardPileCards: List<FateHarvestCard> get() = discardPile.toList()

    /** Draws the top card, reshuffling the discard pile into a fresh draw pile first if needed,
     * using this deck's own [random] (see class doc) rather than a caller-supplied one. */
    fun draw(): FateHarvestCard {
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
            FateHarvestDeck(ArrayDeque(FateHarvestCatalog.buildDeck().shuffled(random)), random = random)

        /** A deck whose draw pile is exactly [cards], in order (the first element draws first) —
         * for deterministic tests that need a specific card to come up next, rather than the
         * default shuffled 70-card deck. [random] governs only later reshuffles (once this
         * explicit draw order is exhausted and cards have been discarded back into it) — pass a
         * seeded instance for a fully reproducible long-running game built on a specific starting
         * order. */
        fun forTesting(cards: List<FateHarvestCard>, random: Random = Random): FateHarvestDeck =
            FateHarvestDeck(ArrayDeque(cards), random = random)
    }
}
