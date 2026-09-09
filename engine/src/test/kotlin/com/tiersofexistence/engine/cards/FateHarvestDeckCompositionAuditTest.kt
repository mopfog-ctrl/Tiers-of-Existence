package com.tiersofexistence.engine.cards

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A stronger drift guard than [FateHarvestCatalogTest]'s existing aggregate rarity-bucket counts
 * (10/10/8/4): this asserts the EXACT physical multiplicity of every one of the 32 named cards,
 * transcribed directly from `docs/rulebook.txt`'s own "Fate Harvest Card List" (p.11+, lines
 * 489-800ish — the `Singles`/`Doubles`/`Triples`/`Quadruples` section headers and the card name
 * under each), re-verified against that file line-for-line as part of the
 * `PlayerCountBenchmarkTest` deck audit (see that class's own doc and `docs/benchmarks/`) rather
 * than trusted from memory or from the catalog's own prior "already cross-checked" claim.
 *
 * Aggregate rarity-bucket counts alone can't catch every possible drift (e.g. a duplicated
 * card definition replacing a different, now-missing one while every bucket size stays right)
 * — this closes that gap by pinning every individual name to its own multiplicity, so any
 * future edit to `FateHarvestCatalog.all` that adds, removes, renames, or reclassifies a single
 * card fails here with a specific, actionable diff instead of only tripping a generic total-count
 * assertion elsewhere.
 */
class FateHarvestDeckCompositionAuditTest {

    /** name -> expected physical copies, per the rulebook's own Card List groupings. */
    private val expectedMultiplicity: Map<String, Int> = mapOf(
        // Singles (x1) — docs/rulebook.txt:494-590
        "Corpuscle Rot" to 1,
        "Galactic Roundabout" to 1,
        "Dwarf Star" to 1,
        "Radiation Burst" to 1,
        "Materialize Army" to 1,
        "Graviton Rift" to 1,
        "Fluidic Wave" to 1,
        "Parallel Phasing" to 1,
        "Plasma Burst" to 1,
        "Verdant Growth" to 1,
        // Doubles (x2) — docs/rulebook.txt:592-680
        "Infernal Abyss" to 2,
        "Divine Assistance" to 2,
        "Planetary Nebula" to 2,
        "Luckier Nebula" to 2,
        "Essence Assimilator" to 2,
        "Skip, Hop, and Jump (Dimensional)" to 2,
        "Materialize Help" to 2,
        "Tactical Motion" to 2,
        "Insidious Flux" to 2,
        "Phase Loss" to 2,
        // Triples (x3) — docs/rulebook.txt:681-755
        "Annulment (Antimatter)" to 3,
        "Evasive Action" to 3,
        "Lucky Nebula" to 3,
        "Tactical Step" to 3,
        "Elemental Rebirth" to 3,
        "Cleansing (Atmospheric)" to 3,
        "Delayed Motion" to 3,
        "Emitting Nebula" to 3,
        // Quadruples (x4) — docs/rulebook.txt:756-820ish
        "Last Gasp" to 4,
        "Phase Control" to 4,
        "Circulate (Elemental)" to 4,
        "Sidestep (Extinction Avoidance)" to 4,
    )

    @Test
    fun `every card definition occurs at exactly its rulebook multiplicity - no card absent, no extra copies`() {
        val actualMultiplicity = FateHarvestCatalog.buildDeck().groupingBy { it.name }.eachCount()

        assertEquals(expectedMultiplicity.keys, actualMultiplicity.keys, "Card name set differs from the rulebook's 32 named cards")
        expectedMultiplicity.forEach { (name, expectedCopies) ->
            assertEquals(expectedCopies, actualMultiplicity.getValue(name), "$name: expected $expectedCopies physical copies")
        }
    }

    @Test
    fun `the built deck totals exactly 70 physical cards`() {
        assertEquals(70, expectedMultiplicity.values.sum()) // sanity on the table itself
        assertEquals(70, FateHarvestCatalog.buildDeck().size)
    }

    @Test
    fun `FateHarvestDeck newShuffled builds the full unfiltered 70-card catalog regardless of player count`() {
        // No color-based filtering exists anywhere in FateHarvestDeck/FateHarvestCatalog today —
        // newShuffled takes no colors/player-count parameter at all, so this is true
        // unconditionally. Documented explicitly here because a future "optional Color-card
        // removal by unseated player color" mode (see CLAUDE.md) must NOT silently change this
        // without an equally explicit, separately-named construction path — this test pins
        // today's actual behavior.
        val deck = FateHarvestDeck.newShuffled()
        assertEquals(70, deck.drawPileSize)
        assertEquals(0, deck.discardPileSize)
        assertTrue(FateHarvestCatalog.all.none { it.isColorCard && it.rarity.copies != 1 }) // color cards are all Singles
    }
}
