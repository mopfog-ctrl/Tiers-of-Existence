package com.tiersofexistence.engine.rules

import com.tiersofexistence.engine.cards.CardTiming
import com.tiersofexistence.engine.cards.FateHarvestCard
import com.tiersofexistence.engine.cards.FateHarvestCatalog
import com.tiersofexistence.engine.cards.FateHarvestDeck
import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.simulation.RandomLegalDecisionProvider
import com.tiersofexistence.engine.state.GameState
import com.tiersofexistence.engine.state.PlayerState
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration-level regression coverage proving the `FateHarvestDeck` determinism fix (see its
 * own class doc) holds specifically at the exact call sites where the original defect lived:
 * `TurnEngine`'s two `state.deck.draw()` calls, reached only through real gameplay via
 * [TurnDriver]/[TurnEngine] — not through a hand-rolled draw loop like
 * `FateHarvestDeckLifecycleTest`'s own coverage (deliberately complementary to that file, not a
 * duplicate of it: that file proves `FateHarvestDeck` itself is correct in isolation, this file
 * proves nothing between it and a real driven game reintroduces non-determinism at the boundary).
 *
 * Uses a deliberately small 3-card deck (via [FateHarvestDeck.forTesting]) so ordinary randomized
 * play reshuffles it repeatedly within a small, fast turn budget, rather than needing hundreds of
 * turns to exhaust a full 66-70 card deck the way real play would.
 */
class TurnEngineDeckReshuffleDeterminismTest {

    private val colors = listOf(PlayerColor.RED, PlayerColor.BLUE)
    private val turnBudget = 300

    /** 3 unrestricted [CardTiming.IMMEDIATE] cards — Immediate cards are always resolved and
     * (whether the resolution succeeds or is rejected) explicitly discarded within the same turn
     * they're drawn, regardless of anything a [RandomLegalDecisionProvider] decides, unlike a Held
     * card which can sit unplayed in a hand indefinitely — this is what actually guarantees a
     * small deck built from these reliably cycles back into the discard pile fast, rather than
     * getting "stuck" waiting on a decision that might never come. */
    private fun threeUnrestrictedImmediateCards(): List<FateHarvestCard> =
        listOf("Galactic Roundabout", "Parallel Phasing", "Divine Assistance").map { name ->
            FateHarvestCatalog.all.single { it.name == name }.also { require(it.timing == CardTiming.IMMEDIATE && it.restrictedTo == null) }
        }

    /** A single turn's worth of comparable, primitive-only state - deliberately broad (deck pile
     * sizes, every player's hand contents, every player's token positions across all 4 Tiers and
     * their Marauders, and whether a winner has been declared) so a divergence introduced
     * anywhere in the driven path shows up here, not just in the deck's own bookkeeping. */
    private fun fingerprint(state: GameState): String {
        val deckPart = "draw=${state.deck.drawPileSize} discard=${state.deck.discardPileSize}"
        val playersPart = colors.joinToString("|") { color ->
            val ps = state.players.getValue(color)
            val hand = ps.hand.map { it.name }.sorted()
            val tiers = TierLevel.entries.joinToString(",") { tier ->
                val pool = ps.tierPool(tier)
                "${pool.inPlayPositions.sorted()}/${pool.stagingPile}/${pool.zoneResidents}"
            }
            val marauders = TierLevel.entries.joinToString(",") { ps.marauders.inPlayIds(it).size.toString() }
            "$color:hand=$hand:tiers=$tiers:marauders=$marauders"
        }
        return "$deckPart||$playersPart||winners=${state.winners}||turn=${state.currentTurn}||phase=${state.currentPhase}"
    }

    /** Drives [turnBudget] turns of a fresh, independently constructed game from [seed], returning
     * one fingerprint per turn (including the pre-turn-zero starting fingerprint) - a divergence
     * between two independent runs of the same seed shows up as the first differing index. */
    private fun driveAndFingerprint(seed: Long): List<String> {
        val random = Random(seed)
        val players = colors.associateWith { PlayerState(it) }
        players.values.forEach { it.tierPool(TierLevel.FIRST).startToken() }
        val smallDeck = FateHarvestDeck.forTesting(threeUnrestrictedImmediateCards().shuffled(random), random)
        val state = GameState(players = players, turnOrder = TurnOrder(colors), deck = smallDeck)
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
    fun `two independently constructed games from the same seed produce an identical turn-by-turn fingerprint sequence, including across the reshuffle boundary`() {
        val seed = 314159265L
        val run1 = driveAndFingerprint(seed)
        val run2 = driveAndFingerprint(seed)

        assertEquals(run1.size, run2.size, "both runs must drive the same number of turns from an identical seed")
        val firstDivergence = run1.indices.firstOrNull { run1[it] != run2[it] }
        assertEquals(
            null,
            firstDivergence,
            "runs diverged at fingerprint index ${firstDivergence ?: -1}:\n  run1=${firstDivergence?.let { run1[it] }}\n  run2=${firstDivergence?.let { run2[it] }}",
        )
        assertEquals(run1, run2)
    }

    @Test
    fun `the small deck actually reshuffles at least once within the turn budget - otherwise this test wouldn't be exercising the recycle boundary at all`() {
        val random = Random(1L)
        val players = colors.associateWith { PlayerState(it) }
        players.values.forEach { it.tierPool(TierLevel.FIRST).startToken() }
        val smallDeck = FateHarvestDeck.forTesting(threeUnrestrictedImmediateCards().shuffled(random), random)
        val state = GameState(players = players, turnOrder = TurnOrder(colors), deck = smallDeck)
        val decisionsByPlayer = colors.associateWith { RandomLegalDecisionProvider(Random(random.nextLong())) }
        val driver = TurnDriver(decisionsByPlayer, rollForPhase = { phase -> Dice.rollForPhase(phase, random) })

        state.skipEmptyPhases()
        var sawReshuffle = false
        var turnsTaken = 0
        var lastDiscardSize = state.deck.discardPileSize
        while (turnsTaken < turnBudget && state.winners.isEmpty() && state.currentTurn != null) {
            driver.driveOneTurn(state)
            turnsTaken += 1
            // A reshuffle just happened iff the discard pile shrank (its contents moved into the
            // draw pile) since the last check.
            if (state.deck.discardPileSize < lastDiscardSize) sawReshuffle = true
            lastDiscardSize = state.deck.discardPileSize
        }
        assertTrue(sawReshuffle, "expected at least one discard-pile reshuffle within $turnBudget turns of a 3-card deck - if this fails, the test setup itself needs revisiting, not the determinism guarantee")
    }
}
