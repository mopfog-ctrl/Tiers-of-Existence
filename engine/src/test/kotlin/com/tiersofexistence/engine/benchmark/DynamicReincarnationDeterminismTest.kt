package com.tiersofexistence.engine.benchmark

import com.tiersofexistence.engine.cards.FateHarvestCatalog
import com.tiersofexistence.engine.cards.FateHarvestDeck
import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.rules.Dice
import com.tiersofexistence.engine.rules.TurnDriver
import com.tiersofexistence.engine.rules.TurnOrder
import com.tiersofexistence.engine.simulation.RandomLegalDecisionProvider
import com.tiersofexistence.engine.state.GameState
import com.tiersofexistence.engine.state.PlayerState
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The experimental-invariant equivalent of `TurnEngineDeckReshuffleDeterminismTest` (which covers
 * Baseline A's `PlainShuffle` strategy) — proves "same initial state + same seed + same decisions
 * = identical regeneration outcomes and gameplay" holds for the experimental dynamic-reincarnation
 * strategy specifically, driven through the real `TurnEngine`/`TurnDriver` path, not a hand-rolled
 * loop. Uses a small deck (`FateHarvestCatalog.all` filtered to one seated color, ~5-6 legal
 * types) so regenerations happen quickly within a short turn budget.
 */
class DynamicReincarnationDeterminismTest {

    private val colors = listOf(PlayerColor.RED, PlayerColor.BLUE)
    private val turnBudget = 400

    /** One unrestricted [com.tiersofexistence.engine.cards.CardTiming.IMMEDIATE] card per rarity
     * bucket (1/2/3/4 copies) - Immediate cards always resolve and discard within the same turn
     * they're drawn regardless of what a `TurnDecisionProvider` decides (unlike a Held card that
     * can sit unplayed in a hand indefinitely), so this 10-physical-card deck reliably cycles
     * back to the discard pile fast, and its 4 distinct types exercise every transition bucket. */
    private fun smallEligibleTypeSet() =
        listOf("Galactic Roundabout", "Divine Assistance", "Evasive Action", "Phase Control").map { name ->
            FateHarvestCatalog.all.single { it.name == name }
        }

    private fun fingerprint(state: GameState): String {
        val deckPart = "draw=${state.deck.drawPileSize} discard=${state.deck.discardPileSize} " +
            "drawCards=${state.deck.drawPileCards.map { it.name }.sorted()} discardCards=${state.deck.discardPileCards.map { it.name }.sorted()}"
        val playersPart = colors.joinToString("|") { color ->
            val ps = state.players.getValue(color)
            val hand = ps.hand.map { it.name }.sorted()
            val tiers = TierLevel.entries.joinToString(",") { tier ->
                val pool = ps.tierPool(tier)
                "${pool.inPlayPositions.sorted()}/${pool.stagingPile}/${pool.zoneResidents}"
            }
            "$color:hand=$hand:tiers=$tiers"
        }
        return "$deckPart||$playersPart||winners=${state.winners}||turn=${state.currentTurn}||phase=${state.currentPhase}"
    }

    private fun driveAndFingerprint(seed: Long): List<String> {
        val random = Random(seed)
        val players = colors.associateWith { PlayerState(it) }
        players.values.forEach { it.tierPool(TierLevel.FIRST).startToken() }

        val eligibleTypes = smallEligibleTypeSet()
        val filtered = FateHarvestCatalog.buildDeck().filter { it.name in eligibleTypes.map { t -> t.name } }
        val shuffled = filtered.shuffled(random)
        val strategy = FateHarvestDeck.ReshuffleStrategy { discardPile, rnd ->
            DynamicReincarnationRules.regenerate(discardPile, eligibleTypes, rnd).regeneratedPile
        }
        val deck = FateHarvestDeck.forTesting(shuffled, random, strategy)
        val state = GameState(players = players, turnOrder = TurnOrder(colors), deck = deck)
        val decisionsByPlayer = colors.associateWith { RandomLegalDecisionProvider(Random(random.nextLong())) }
        val driver = TurnDriver(decisionsByPlayer, rollForPhase = { phase -> Dice.rollForPhase(phase, random) })

        state.skipEmptyPhases()
        val fingerprints = mutableListOf(fingerprint(state))
        var turnsTaken = 0
        while (turnsTaken < turnBudget && state.winners.isEmpty() && state.currentTurn != null) {
            driver.driveOneTurn(state)
            turnsTaken += 1
            fingerprints += fingerprint(state)
        }
        return fingerprints
    }

    @Test
    fun `two independently constructed games using the experimental dynamic-reincarnation strategy from the same seed produce identical turn-by-turn fingerprints`() {
        val seed = 271828182L
        val run1 = driveAndFingerprint(seed)
        val run2 = driveAndFingerprint(seed)

        assertEquals(run1.size, run2.size)
        val firstDivergence = run1.indices.firstOrNull { run1[it] != run2[it] }
        assertEquals(null, firstDivergence, "runs diverged at index ${firstDivergence ?: -1}:\n  run1=${firstDivergence?.let { run1[it] }}\n  run2=${firstDivergence?.let { run2[it] }}")
    }

    @Test
    fun `the small deck actually regenerates via the dynamic-reincarnation strategy at least once within the turn budget`() {
        val random = Random(3L)
        val players = colors.associateWith { PlayerState(it) }
        players.values.forEach { it.tierPool(TierLevel.FIRST).startToken() }
        val eligibleTypes = smallEligibleTypeSet()
        val filtered = FateHarvestCatalog.buildDeck().filter { it.name in eligibleTypes.map { t -> t.name } }
        var regenerations = 0
        val strategy = FateHarvestDeck.ReshuffleStrategy { discardPile, rnd ->
            regenerations += 1
            DynamicReincarnationRules.regenerate(discardPile, eligibleTypes, rnd).regeneratedPile
        }
        val deck = FateHarvestDeck.forTesting(filtered.shuffled(random), random, strategy)
        val state = GameState(players = players, turnOrder = TurnOrder(colors), deck = deck)
        val decisionsByPlayer = colors.associateWith { RandomLegalDecisionProvider(Random(random.nextLong())) }
        val driver = TurnDriver(decisionsByPlayer, rollForPhase = { phase -> Dice.rollForPhase(phase, random) })
        state.skipEmptyPhases()
        var turnsTaken = 0
        while (turnsTaken < turnBudget && state.winners.isEmpty() && state.currentTurn != null) {
            driver.driveOneTurn(state)
            turnsTaken += 1
        }
        assertTrue(regenerations > 0, "expected at least one dynamic-reincarnation regeneration within $turnBudget turns")
    }
}
