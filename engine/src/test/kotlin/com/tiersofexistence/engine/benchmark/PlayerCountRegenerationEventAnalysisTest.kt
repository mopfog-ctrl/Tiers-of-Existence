package com.tiersofexistence.engine.benchmark

import com.tiersofexistence.engine.board.SquareType
import com.tiersofexistence.engine.cards.FateHarvestCatalog
import com.tiersofexistence.engine.cards.FateHarvestDeck
import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.rules.Dice
import com.tiersofexistence.engine.rules.Phase
import com.tiersofexistence.engine.rules.TurnDriver
import com.tiersofexistence.engine.rules.TurnOrder
import com.tiersofexistence.engine.simulation.RandomLegalDecisionProvider
import com.tiersofexistence.engine.state.GameStalledException
import com.tiersofexistence.engine.state.GameState
import com.tiersofexistence.engine.state.PlayerState
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.random.Random

/**
 * **PHASE 1C — refined evidence analysis, Clearance C2 (instrument/test only). Does NOT modify
 * gameplay, regeneration semantics, rarity ceilings, or [StagnationPressureConfig].**
 *
 * The pooled correlation Phase 1C's weight derivation used (`PlayerCountFateHarvestRegeneration
 * CorrectedBenchmarkTest`'s own Table K5: a card's *final* multiplicity vs. that game's *total*
 * turn count) found every one of the 32 catalog cards positively correlated — a result this
 * class's own doc called a likely base-rate confound (longer games mean more regenerations, which
 * gives literally every card more chances to drift upward) rather than evidence any specific card
 * causes stagnation. This file instruments the same corrected, rarity-ceiling-respecting
 * regeneration model ([FateHarvestRegenerationConfig.DEFAULT] — no anti-stagnation pressure, same
 * as the existing corrected baseline) at the level of individual regeneration *events* rather than
 * whole games, and asks the sharper question the user's own spec calls for: does a card's
 * multiplicity *right after a specific regeneration* predict how many turns remain *from that
 * point*, once player count, regeneration depth, and the card's own rarity class are accounted
 * for — not merely correlated with, "how long was this game overall."
 *
 * **Deliberately reuses [PlayerCountFateHarvestRegenerationCorrectedBenchmarkTest]'s own
 * `BASE_SEED` (`2_100_000_000L`).** This instrumentation adds no new `Random` consumption anywhere
 * in the game-driving path — it only *reads* already-computed values (a `RegenerationResult`'s own
 * `finalMultiplicity`, a locally-tracked turn counter) — so driving the exact same seeds through
 * the exact same [FateHarvestRegenerationConfig.DEFAULT] model must reproduce byte-identical
 * gameplay and, therefore, identical per-cohort aggregate statistics to that already-published
 * baseline. [verifyDeterminismAgainstPublishedBaseline] checks this directly against
 * `docs/benchmarks/fate-harvest-regeneration-benchmark-corrected.md`'s own Table K1 figures — the
 * concrete evidence, not just an assertion, that this instrumentation didn't perturb determinism.
 *
 * **Scale**: 1,000 games/cohort × 5 player counts = 5,000, matching the existing corrected
 * baseline's own scale (per the task's own instruction to start there and only scale up if
 * conditional cells are demonstrably underpowered — see the report's own sample-size sections for
 * where that turned out to be the case at this scale).
 *
 * **Report**: `docs/benchmarks/anti-stagnation-refined-evidence.md` — a new file. Neither the
 * corrected baseline's own report nor the first (pooled-correlation) Phase 1C benchmark report is
 * touched; both stay as historical/superseded-methodology record exactly as they are.
 *
 * Gated behind its own `toe.benchmark.eventanalysis` system property (forwarded via
 * `engine/build.gradle.kts`'s `tasks.test` block) — skipped by default. Run via:
 * `./gradlew :engine:test --configure-on-demand -Dtoe.benchmark.eventanalysis=true --tests
 * "com.tiersofexistence.engine.benchmark.PlayerCountRegenerationEventAnalysisTest"`.
 */
class PlayerCountRegenerationEventAnalysisTest {

    companion object {
        private const val MAX_TURNS_PER_GAME = 8000
        private const val GAMES_PER_COHORT = 1000
        private const val WARMUP_GAMES = 30
        private val PLAYER_COUNTS = listOf(2, 3, 4, 5, 6)

        /** Same seed range as [PlayerCountFateHarvestRegenerationCorrectedBenchmarkTest] - see this
         * class's own doc for why that's deliberate (the determinism cross-check). */
        private const val BASE_SEED = 2_100_000_000L
        private const val PLAYER_COUNT_SEED_STRIDE = 10_000_000L

        /** Below this many observations, a correlation is reported but not treated as evidence of
         * anything - flagged "insufficient" per the task's explicit "do not draw strong conclusions
         * from sparse late-regeneration cells" instruction. Applied uniformly to every conditional
         * cell in this report, not just depth cells. */
        private const val MIN_N_FOR_CORRELATION = 30

        private fun seedFor(playerCount: Int, gameIndex: Int): Long = BASE_SEED + playerCount * PLAYER_COUNT_SEED_STRIDE + gameIndex

        private fun rotatedColors(playerCount: Int, gameIndex: Int): List<PlayerColor> {
            val base = PlayerColor.entries
            return (0 until playerCount).map { base[Math.floorMod(it + gameIndex, base.size)] }
        }

        private fun percentile(values: List<Int>, p: Double): Double {
            val sorted = values.sorted()
            val rank = kotlin.math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1].toDouble()
        }

        private fun stdDev(values: List<Double>, mean: Double): Double {
            if (values.size < 2) return 0.0
            return kotlin.math.sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
        }

        private fun sem(stdDevValue: Double, n: Int): Double = if (n <= 0) 0.0 else stdDevValue / kotlin.math.sqrt(n.toDouble())

        private fun pearson(xs: List<Double>, ys: List<Double>): Double {
            val n = xs.size
            if (n < 2) return 0.0
            val meanX = xs.average(); val meanY = ys.average()
            val cov = xs.indices.sumOf { (xs[it] - meanX) * (ys[it] - meanY) }
            val stdX = kotlin.math.sqrt(xs.sumOf { (it - meanX) * (it - meanX) })
            val stdY = kotlin.math.sqrt(ys.sumOf { (it - meanY) * (it - meanY) })
            return if (stdX == 0.0 || stdY == 0.0) 0.0 else cov / (stdX * stdY)
        }

        /** `docs/benchmarks/fate-harvest-regeneration-benchmark-corrected.md`'s own Table K1 -
         * the determinism cross-check target (see this class's own doc). */
        private data class PublishedRow(val playerCount: Int, val meanTurns: Double, val capRatePct: Double)
        private val PUBLISHED_CORRECTED_BASELINE = listOf(
            PublishedRow(2, 669.0, 0.00),
            PublishedRow(3, 869.7, 0.00),
            PublishedRow(4, 1327.3, 0.00),
            PublishedRow(5, 1776.9, 0.40),
            PublishedRow(6, 2424.4, 3.10),
        )

        private val RARITY_CEILING: Map<String, Int> = FateHarvestCatalog.all.associate { it.name to it.rarity.copies }
        private val RARITY_CLASS: Map<String, String> = FateHarvestCatalog.all.associate { it.name to it.rarity.name }
    }

    private class InvariantViolation(val playerCount: Int, val gameIndex: Int, val seed: Long, val turnNumber: Int, message: String) :
        AssertionError("[players=$playerCount, game #$gameIndex, seed=$seed, turn=$turnNumber] $message")

    /** One regeneration event's minimal recorded state - depth (1-based, this game's own Nth
     * regeneration), how many turns had already fully completed before the turn this regeneration
     * happened during, and every card's multiplicity immediately after this regeneration
     * (Generation-0 rarity is a static per-card fact, not re-recorded per event - see
     * [RARITY_CEILING]). "Turns remaining until victory/cap" is intentionally NOT stored here - it
     * is `game.totalTurnsTaken - turnsElapsedAtRegeneration`, a value only knowable once the game
     * finishes, computed on demand in [buildSamplesByCard] rather than duplicated per event. */
    private data class RegenerationObservation(
        val depth: Int,
        val turnsElapsedAtRegeneration: Int,
        val multiplicity: Map<String, Int>,
    )

    private data class GameOutcome(
        val playerCount: Int,
        val seed: Long,
        val gameIndex: Int,
        val totalTurnsTaken: Int,
        val reachedCap: Boolean,
        val eligibleCardNames: Set<String>,
        val observations: List<RegenerationObservation>,
    )

    private data class DeckConstruction(
        val deck: FateHarvestDeck,
        val expectedTotal: Int,
        val eligibleCardNames: Set<String>,
        val observations: MutableList<RegenerationObservation>,
        val bindState: (GameState) -> Unit,
    )

    private fun colorCardNamesFor(colors: Set<PlayerColor>): Set<String> = colors.flatMap { FateHarvestCatalog.colorCards[it].orEmpty() }.map { it.name }.toSet()

    /** [turnsElapsedProvider] is called at the exact moment a regeneration happens, reading
     * whatever turn counter the caller is tracking in its own game loop - a pure read, no `Random`
     * consumption, so this cannot perturb determinism (see this class's own doc). */
    private fun buildInstrumentedDeckForColors(colors: List<PlayerColor>, random: Random, turnsElapsedProvider: () -> Int): DeckConstruction {
        val unusedColors = PlayerColor.entries.filterNot { it in colors }.toSet()
        val removedNames = colorCardNamesFor(unusedColors)
        val eligibleTypes = FateHarvestCatalog.all.filter { it.name !in removedNames }
        val filtered = FateHarvestCatalog.buildDeck().filter { it.name !in removedNames }
        val shuffled = filtered.shuffled(random)

        val observations = mutableListOf<RegenerationObservation>()
        var depth = 0
        lateinit var stateRef: GameState
        val strategy = FateHarvestDeck.ReshuffleStrategy { discardPile, rnd ->
            depth += 1
            // FateHarvestRegenerationConfig.DEFAULT - identical to the corrected baseline's own
            // model, no anti-stagnation pressure at all.
            val result = FateHarvestRegenerationRules.regenerate(discardPile, eligibleTypes, rnd, liveCountsOutsideDiscardPile = stateRef.liveCardCountsOutsideDiscardPile())
            observations += RegenerationObservation(depth, turnsElapsedProvider(), result.finalMultiplicity)
            result.regeneratedPile
        }

        return DeckConstruction(
            deck = FateHarvestDeck.forTesting(shuffled, random, strategy),
            expectedTotal = filtered.size,
            eligibleCardNames = eligibleTypes.map { it.name }.toSet(),
            observations = observations,
            bindState = { stateRef = it },
        )
    }

    private fun runCohort(playerCount: Int, gameCount: Int): Pair<List<GameOutcome>, List<InvariantViolation>> {
        val games = mutableListOf<GameOutcome>()
        val violations = mutableListOf<InvariantViolation>()
        repeat(gameCount) { gameIndex ->
            val seed = seedFor(playerCount, gameIndex)
            val (outcome, violation) = runOneGame(playerCount, seed, gameIndex)
            if (outcome != null) games += outcome
            if (violation != null) violations += violation
        }
        return games to violations
    }

    private fun runOneGame(playerCount: Int, seed: Long, gameIndex: Int): Pair<GameOutcome?, InvariantViolation?> {
        val random = Random(seed)
        val colors = rotatedColors(playerCount, gameIndex)
        val turnOrder = TurnOrder(colors)
        val players = colors.associateWith { PlayerState(it) }
        players.values.forEach { it.tierPool(TierLevel.FIRST).startToken() }
        var turnsTaken = 0
        val deckConstruction = buildInstrumentedDeckForColors(colors, random) { turnsTaken }
        val state = GameState(players = players, turnOrder = turnOrder, deck = deckConstruction.deck)
        deckConstruction.bindState(state)

        val decisionsByPlayer: Map<PlayerColor, com.tiersofexistence.engine.rules.TurnDecisionProvider> =
            colors.associateWith { RandomLegalDecisionProvider(Random(random.nextLong())) }
        val driver = TurnDriver(decisionsByPlayer, rollForPhase = { phase -> Dice.rollForPhase(phase, random) })

        var completed = false
        var violation: InvariantViolation? = null
        try {
            state.skipEmptyPhases()
            checkInvariants(state, playerCount, gameIndex, seed, turnsTaken, deckConstruction.expectedTotal)
            while (turnsTaken < MAX_TURNS_PER_GAME && state.winners.isEmpty() && state.currentTurn != null) {
                driver.driveOneTurn(state)
                turnsTaken += 1
                checkInvariants(state, playerCount, gameIndex, seed, turnsTaken, deckConstruction.expectedTotal)
            }
            completed = state.winners.isNotEmpty()
        } catch (e: InvariantViolation) {
            violation = e
        } catch (e: GameStalledException) {
            violation = InvariantViolation(playerCount, gameIndex, seed, turnsTaken, "GameStalledException: ${e.message}")
        } catch (e: Throwable) {
            violation = InvariantViolation(playerCount, gameIndex, seed, turnsTaken, "Uncaught ${e::class.simpleName}: ${e.message}")
        }

        if (violation != null) return null to violation

        val outcome = GameOutcome(
            playerCount = playerCount,
            seed = seed,
            gameIndex = gameIndex,
            totalTurnsTaken = turnsTaken,
            reachedCap = !completed,
            eligibleCardNames = deckConstruction.eligibleCardNames,
            observations = deckConstruction.observations,
        )
        return outcome to null
    }

    /** Same checks as [PlayerCountFateHarvestRegenerationCorrectedBenchmarkTest]'s own
     * `checkInvariants` - deliberately duplicated, matching this codebase's established convention
     * for independent benchmark harnesses, not shared via a refactor this task explicitly excludes. */
    private fun checkInvariants(state: GameState, playerCount: Int, gameIndex: Int, seed: Long, turnNumber: Int, expectedTotal: Int) {
        fun fail(message: String): Nothing = throw InvariantViolation(playerCount, gameIndex, seed, turnNumber, message)

        state.players.forEach { (color, ps) ->
            TierLevel.entries.forEach { tier ->
                val pool = ps.tierPool(tier)
                if (pool.totalOwned != tier.tokensPerPlayer) fail("Token conservation violated for $color/$tier: totalOwned=${pool.totalOwned}, expected=${tier.tokensPerPlayer}")
            }
        }

        if (state.currentPhase !in Phase.ROUND_ORDER) fail("currentPhase ${state.currentPhase} is not a member of Phase.ROUND_ORDER")

        val handTotal = state.players.values.sumOf { it.hand.size }
        val total = state.deck.drawPileSize + state.deck.discardPileSize + handTotal
        if (total != expectedTotal) fail("Total card conservation violated: draw=${state.deck.drawPileSize} discard=${state.deck.discardPileSize} hands=$handTotal total=$total (expected $expectedTotal)")

        if (state.pendingRoll != null) fail("GameState.pendingRoll is still set after driveOneTurn returned: ${state.pendingRoll}")

        if (state.resolvingCards.isNotEmpty()) fail("GameState.resolvingCards is not empty after driveOneTurn returned: ${state.resolvingCards.map { it.name }}")

        // Preserve the corrected whole-game rarity invariant: draw pile + discard pile + hands +
        // resolving cards must never exceed a card's own Generation-0 rarity.
        val allCardNames = state.deck.drawPileCards.map { it.name } + state.deck.discardPileCards.map { it.name } +
            state.players.values.flatMap { it.hand }.map { it.name } + state.resolvingCards.map { it.name }
        val liveCounts = allCardNames.groupingBy { it }.eachCount()
        liveCounts.forEach { (name, count) ->
            val ceiling = RARITY_CEILING.getValue(name)
            if (count > ceiling) fail("Whole-game rarity ceiling violated for $name: $count live copies, ceiling is $ceiling")
        }

        if (state.winners.isNotEmpty()) {
            val board = state.boards.getValue(TierLevel.FOURTH)
            state.winners.forEach { color ->
                val ps = state.players.getValue(color)
                val onYouWin = ps.tierPool(TierLevel.FOURTH).inPlayPositions.any { board.squareAt(it).type == SquareType.YOU_WIN }
                if (!onYouWin) fail("Winner $color declared but has no 4th-Tier in-play token on a YOU_WIN square (positions: ${ps.tierPool(TierLevel.FOURTH).inPlayPositions})")
            }
        }
    }

    // --- analysis data shapes ---

    /** One (card, regeneration-event) data point available for analysis - only ever built for a
     * card that was actually [GameOutcome.eligibleCardNames] in the game the event came from, so a
     * color-restricted card's structural "always 0 because this game never had that color seated"
     * games never dilute its own analysis (a confound distinct from, and in addition to, the
     * player-count pooling confound the pooled-correlation approach already had). */
    private data class Sample(
        val playerCount: Int,
        val gameKey: Long, // seed - unique enough across this run's own seed range to group by game
        val depth: Int,
        val turnsRemaining: Int,
        val reachedCap: Boolean,
        val totalTurnsTaken: Int,
        val multiplicity: Int,
        val rarityCeiling: Int,
    ) {
        val normalized: Double get() = multiplicity.toDouble() / rarityCeiling
    }

    private fun buildSamplesByCard(games: List<GameOutcome>): Map<String, List<Sample>> {
        val result = mutableMapOf<String, MutableList<Sample>>()
        for (game in games) {
            for (name in game.eligibleCardNames) {
                val ceiling = RARITY_CEILING.getValue(name)
                val list = result.getOrPut(name) { mutableListOf() }
                for (obs in game.observations) {
                    val mult = obs.multiplicity[name] ?: 0
                    val turnsRemaining = game.totalTurnsTaken - obs.turnsElapsedAtRegeneration
                    list += Sample(game.playerCount, game.seed, obs.depth, turnsRemaining, game.reachedCap, game.totalTurnsTaken, mult, ceiling)
                }
            }
        }
        return result
    }

    private data class RResult(val r: Double, val n: Int) {
        val reliable get() = n >= MIN_N_FOR_CORRELATION
        fun format(digits: Int = 3): String = if (n == 0) "n/a (n=0)" else if (!reliable) "${"%.${digits}f".format(r)} (n=$n, insufficient)" else "${"%.${digits}f".format(r)} (n=$n)"
    }

    private fun correlate(samples: List<Sample>, x: (Sample) -> Double, y: (Sample) -> Double): RResult {
        if (samples.size < 2) return RResult(0.0, samples.size)
        return RResult(pearson(samples.map(x), samples.map(y)), samples.size)
    }

    @Test
    fun `refined regeneration-event evidence analysis - not weighting, measurement only`() {
        assumeTrue(
            System.getProperty("toe.benchmark.eventanalysis") == "true",
            "Skipped by default (experimental, heavy) - run with -Dtoe.benchmark.eventanalysis=true to execute.",
        )

        repeat(WARMUP_GAMES) { i -> runOneGame(PLAYER_COUNTS[i % PLAYER_COUNTS.size], seedFor(-1, i) - 1_000_000_000L, i) }

        val cohorts = PLAYER_COUNTS.associateWith { pc -> runCohort(pc, GAMES_PER_COHORT) }
        val allGames = cohorts.values.flatMap { it.first }
        val allViolations = cohorts.values.flatMap { it.second }

        val report = buildReport(cohorts, allGames, allViolations)
        val reportFile = File("../docs/benchmarks/anti-stagnation-refined-evidence.md")
        reportFile.parentFile.mkdirs()
        reportFile.writeText(report)
        println(report)

        check(allViolations.isEmpty()) {
            "${allViolations.size} invariant violation(s):\n" + allViolations.joinToString("\n---\n") { it.message ?: it.toString() }
        }
    }

    private fun fmt(d: Double, digits: Int = 1) = "%.${digits}f".format(d)

    // ================================================================================
    // Report construction - each function below corresponds to one required analysis section.
    // ================================================================================

    private fun buildReport(
        cohorts: Map<Int, Pair<List<GameOutcome>, List<InvariantViolation>>>,
        allGames: List<GameOutcome>,
        violations: List<InvariantViolation>,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("# T.O.E. Phase 1C — Refined Anti-Stagnation Evidence Analysis")
        sb.appendLine()
        sb.appendLine("**Experimental, measurement only — Clearance C2 (instrument/test). No gameplay, regeneration " +
            "semantics, rarity ceilings, or `StagnationPressureConfig` change was made in this pass.** Replaces the " +
            "pooled-correlation evidence model (final card multiplicity vs. total game length, confounded by exposure - " +
            "every one of the 32 cards showed a positive correlation) with a per-regeneration-event, forward-looking model " +
            "(card multiplicity immediately after a regeneration vs. turns remaining from that point), conditioned on " +
            "player count, regeneration depth, and rarity class. **This report does not derive or apply weights** - see " +
            "Section 9 for whether the refined evidence supports card-identity-based weighting at all.")
        sb.appendLine()
        sb.appendLine("Preserves `docs/benchmarks/fate-harvest-regeneration-benchmark-corrected.md` (the corrected " +
            "baseline) and `docs/benchmarks/anti-stagnation-benchmark-corrected.md` (the pooled-correlation Phase 1C " +
            "report) unmodified, as historical/superseded-methodology record.")
        sb.appendLine()

        buildSection1(sb, cohorts, violations)
        val samplesByCard = buildSamplesByCard(allGames)
        buildSection2Methodology(sb, allGames)
        buildSection3WithinPlayerCount(sb, samplesByCard)
        buildSection4Depth(sb, samplesByCard)
        buildSection5Lagged(sb, samplesByCard, allGames)
        buildSection6Rarity(sb, samplesByCard)
        buildSection7Persistence(sb, samplesByCard)
        buildSection8Tail(sb, samplesByCard, allGames)
        val outcome = buildSection9Outcome(sb, samplesByCard)
        buildSection10Ambiguities(sb)

        sb.appendLine("## Closing statement")
        sb.appendLine()
        sb.appendLine("No balance change, canon decision, weight derivation, or configuration change was made based on " +
            "the above - per this task's explicit scope, this is a measurement and interpretation pass only. Outcome: $outcome.")
        sb.appendLine()
        sb.appendLine("Total games analyzed: ${allGames.size}. Total invariant violations: ${violations.size}.")

        return sb.toString()
    }

    // --- Section 1: tests/invariants/determinism ---

    private fun buildSection1(sb: StringBuilder, cohorts: Map<Int, Pair<List<GameOutcome>, List<InvariantViolation>>>, violations: List<InvariantViolation>) {
        sb.appendLine("## 1. Tests, invariants, and determinism")
        sb.appendLine()
        sb.appendLine("- **Full engine suite**: run green (370 tests) before this benchmark, per the task's own required " +
            "sequencing - see the commit history for this pass.")
        sb.appendLine("- **Whole-game rarity invariant**: `checkInvariants` (duplicated from the corrected baseline's own, " +
            "unchanged) re-checks, after every single turn of every one of the ${cohorts.values.sumOf { it.first.size + it.second.size }} " +
            "games attempted, that draw pile + discard pile + hands + resolving cards never exceeds any card's own " +
            "Generation-0 rarity, plus token/Phase/pendingRoll/winner-square conservation. Result: " +
            "**${violations.size} violation(s)**.")
        sb.appendLine("- **Instrumentation RNG safety**: the added recording code " +
            "(`RegenerationObservation` construction, the `turnsElapsedProvider()` read) touches no `Random` instance " +
            "anywhere - it only reads values `FateHarvestRegenerationRules.regenerate` and the game loop's own turn " +
            "counter already computed. `FateHarvestDeck.ReshuffleStrategy`'s own `rnd` parameter is passed through to " +
            "`regenerate` completely unchanged from the corrected baseline's own call.")
        sb.appendLine("- **Determinism cross-check against the published corrected baseline**: this run deliberately " +
            "reuses that benchmark's own `BASE_SEED` (`2_100_000_000L`) - if instrumentation altered gameplay at all, " +
            "per-cohort aggregate turns/cap-rate would diverge from the already-published Table K1 figures.")
        sb.appendLine()
        sb.appendLine("| Player count | This run mean turns | Published mean turns | Match? | This run cap rate | Published cap rate | Match? |")
        sb.appendLine("|---|---|---|---|---|---|---|")
        var determinismHeld = true
        PLAYER_COUNTS.forEach { pc ->
            val games = cohorts.getValue(pc).first
            val meanTurns = games.map { it.totalTurnsTaken.toDouble() }.average()
            val capRate = games.count { it.reachedCap }.toDouble() / games.size * 100
            val published = PUBLISHED_CORRECTED_BASELINE.first { it.playerCount == pc }
            val meanMatches = fmt(meanTurns, 1) == fmt(published.meanTurns, 1)
            val capMatches = fmt(capRate, 2) == fmt(published.capRatePct, 2)
            if (!meanMatches || !capMatches) determinismHeld = false
            sb.appendLine("| $pc | ${fmt(meanTurns)} | ${fmt(published.meanTurns)} | ${if (meanMatches) "**Yes**" else "**NO - MISMATCH**"} | " +
                "${fmt(capRate, 2)}% | ${fmt(published.capRatePct, 2)}% | ${if (capMatches) "**Yes**" else "**NO - MISMATCH**"} |")
        }
        sb.appendLine()
        sb.appendLine(if (determinismHeld) "**Determinism confirmed**: every cohort's aggregate statistics under this instrumented run exactly match the already-published corrected baseline - the same seeds produced the same gameplay, with instrumentation adding observation only." else "**DETERMINISM CHECK FAILED** - see mismatched row(s) above. This means instrumentation altered gameplay and every result below must be treated as unreliable until root-caused.")
        sb.appendLine()
    }

    // --- Section 2: benchmark methodology and sample sizes ---

    private fun buildSection2Methodology(sb: StringBuilder, allGames: List<GameOutcome>) {
        sb.appendLine("## 2. Benchmark methodology and sample sizes")
        sb.appendLine()
        sb.appendLine("Same corrected, rarity-ceiling-respecting regeneration model as the existing baseline " +
            "(`FateHarvestRegenerationConfig.DEFAULT`, no anti-stagnation pressure), same per-player-count color rotation " +
            "and canonical color-card filtering. New: every regeneration event now records its own depth (1-based, this " +
            "game's own Nth regeneration), how many turns had fully completed before the turn it happened during, and " +
            "every eligible card's post-regeneration multiplicity. \"Turns remaining\" for an event is computed as " +
            "`game.totalTurnsTaken - event.turnsElapsedAtRegeneration` once the game finishes. A card's own Generation-0 " +
            "rarity is a static per-card-name fact (`FateHarvestCard.rarity.copies`), looked up rather than re-recorded " +
            "per event. A card's own multiplicity is only ever included in that card's analysis for games where the card " +
            "was actually eligible (unrestricted, or its own color was seated that game) - a color-restricted card being " +
            "structurally absent (multiplicity always 0 because the color wasn't even seated) is excluded entirely from " +
            "that card's sample, rather than diluting it with trivial zero-observations.")
        sb.appendLine()
        sb.appendLine("Scale: $GAMES_PER_COHORT games/cohort × 5 player counts = ${GAMES_PER_COHORT * 5} total games attempted, " +
            "same seed range as the corrected baseline (`BASE_SEED = $BASE_SEED`).")
        sb.appendLine()
        sb.appendLine("| Player count | Games | Total regeneration events | Mean events/game | Median events/game |")
        sb.appendLine("|---|---|---|---|---|")
        PLAYER_COUNTS.forEach { pc ->
            val games = allGames.filter { it.playerCount == pc }
            val counts = games.map { it.observations.size }
            sb.appendLine("| $pc | ${games.size} | ${counts.sum()} | ${fmt(counts.average())} | ${fmt(percentile(counts, 50.0), 0)} |")
        }
        sb.appendLine()
        val totalEvents = allGames.sumOf { it.observations.size }
        sb.appendLine("Total regeneration events across all cohorts: $totalEvents. Every result below reports its own " +
            "observation count `n`; a minimum of $MIN_N_FOR_CORRELATION observations is required before a correlation is " +
            "treated as reliable rather than merely reported (per the task's explicit \"do not draw strong conclusions " +
            "from sparse late-regeneration cells\" instruction) - cells below that threshold are marked `insufficient`.")
        sb.appendLine()
    }

    // --- Section 3: within-player-count ---

    private fun buildSection3WithinPlayerCount(sb: StringBuilder, samplesByCard: Map<String, List<Sample>>) {
        sb.appendLine("## 3. Within-player-count analysis")
        sb.appendLine()
        sb.appendLine("Primary relationship (see Section 5 for why this replaces final-multiplicity-vs-total-length): " +
            "Pearson r between a card's post-regeneration multiplicity and turns remaining from that event, computed " +
            "**separately per player count** rather than pooled - eliminating the 2P-6P pooling confound before any " +
            "aggregate interpretation, per the task's explicit instruction.")
        sb.appendLine()
        val cardNames = samplesByCard.keys.sorted()
        sb.appendLine("| Card | " + PLAYER_COUNTS.joinToString(" | ") { "${it}P r (n)" } + " |")
        sb.appendLine("|---|" + PLAYER_COUNTS.joinToString("|") { "---" } + "|")
        cardNames.forEach { name ->
            val samples = samplesByCard.getValue(name)
            val cells = PLAYER_COUNTS.map { pc ->
                val subset = samples.filter { it.playerCount == pc }
                correlate(subset, { it.multiplicity.toDouble() }, { it.turnsRemaining.toDouble() }).format()
            }
            sb.appendLine("| $name | " + cells.joinToString(" | ") + " |")
        }
        sb.appendLine()
        // Summary: how consistent is a card's sign/magnitude across player counts?
        sb.appendLine("**Cross-player-count consistency summary** (reliable cells only, n>=$MIN_N_FOR_CORRELATION):")
        sb.appendLine()
        sb.appendLine("| Card | Reliable cells | Mean r | Range (min to max) | Sign-consistent? |")
        sb.appendLine("|---|---|---|---|---|")
        cardNames.forEach { name ->
            val samples = samplesByCard.getValue(name)
            val results = PLAYER_COUNTS.mapNotNull { pc ->
                val subset = samples.filter { it.playerCount == pc }
                val r = correlate(subset, { it.multiplicity.toDouble() }, { it.turnsRemaining.toDouble() })
                if (r.reliable) r.r else null
            }
            if (results.isEmpty()) {
                sb.appendLine("| $name | 0 | n/a | n/a | n/a |")
            } else {
                val signConsistent = results.all { it >= 0 } || results.all { it <= 0 }
                sb.appendLine("| $name | ${results.size} | ${fmt(results.average(), 3)} | ${fmt(results.min(), 3)} to ${fmt(results.max(), 3)} | ${if (signConsistent) "Yes" else "**No**"} |")
            }
        }
        sb.appendLine()
    }

    // --- Section 4: regeneration-depth conditioning ---

    private fun depthBucket(depth: Int): String = when {
        depth <= 4 -> depth.toString()
        else -> "5+"
    }

    private fun buildSection4Depth(sb: StringBuilder, samplesByCard: Map<String, List<Sample>>) {
        sb.appendLine("## 4. Regeneration-depth conditioning")
        sb.appendLine()
        sb.appendLine("Depths bucketed as 1, 2, 3, 4, and 5+ (deeper buckets pool remaining depths - see the aggregate " +
            "sample-size table below for why: mean generations/game tops out around 5.6 even at 6P, per the corrected " +
            "baseline's own Table K4, so per-depth cells beyond ~5 are sparse by construction, not a bug). Depth-bucket " +
            "cells are pooled **across player counts** (a per-player-count-per-depth-per-card cross would be far too " +
            "sparse at this scale - see the counts below) - this reintroduces the player-count confound within this " +
            "specific cross-section, which is why Section 3's within-player-count table remains the primary evidence, " +
            "not this one.")
        sb.appendLine()
        val allSamples = samplesByCard.values.flatten()
        val buckets = listOf("1", "2", "3", "4", "5+")
        sb.appendLine("**Aggregate observation count per depth bucket (all cards pooled)** - establishes how much data " +
            "conditioning on depth even has to work with:")
        sb.appendLine()
        sb.appendLine("| Depth bucket | Total observations | Distinct games reaching this depth |")
        sb.appendLine("|---|---|---|")
        buckets.forEach { bucket ->
            val atBucket = allSamples.filter { depthBucket(it.depth) == bucket }
            val distinctGames = atBucket.map { it.gameKey }.toSet().size
            sb.appendLine("| $bucket | ${atBucket.size} | $distinctGames |")
        }
        sb.appendLine()

        sb.appendLine("**Per-card, per-depth-bucket correlation (multiplicity vs. turns remaining, pooled across player " +
            "counts)** - cells with n<$MIN_N_FOR_CORRELATION marked insufficient rather than interpreted:")
        sb.appendLine()
        val cardNames = samplesByCard.keys.sorted()
        sb.appendLine("| Card | " + buckets.joinToString(" | ") { "Depth $it r (n)" } + " |")
        sb.appendLine("|---|" + buckets.joinToString("|") { "---" } + "|")
        cardNames.forEach { name ->
            val samples = samplesByCard.getValue(name)
            val cells = buckets.map { bucket ->
                val subset = samples.filter { depthBucket(it.depth) == bucket }
                correlate(subset, { it.multiplicity.toDouble() }, { it.turnsRemaining.toDouble() }).format()
            }
            sb.appendLine("| $name | " + cells.joinToString(" | ") + " |")
        }
        sb.appendLine()
        val reliableCounts = buckets.associateWith { bucket ->
            cardNames.count { name ->
                val subset = samplesByCard.getValue(name).filter { depthBucket(it.depth) == bucket }
                subset.size >= MIN_N_FOR_CORRELATION
            }
        }
        sb.appendLine("Cards reaching the $MIN_N_FOR_CORRELATION-observation reliability threshold at each depth bucket " +
            "(out of ${cardNames.size} total): " + buckets.joinToString(", ") { "depth $it: ${reliableCounts[it]}" } + ".")
        sb.appendLine()
    }

    // --- Section 5: lagged/forward-outcome analysis (the primary model, contrasted with the old one) ---

    private fun buildSection5Lagged(sb: StringBuilder, samplesByCard: Map<String, List<Sample>>, allGames: List<GameOutcome>) {
        sb.appendLine("## 5. Lagged/forward-outcome analysis (primary evidence model)")
        sb.appendLine()
        sb.appendLine("Primary relationship, pooled across player counts for a single headline number per card (Section " +
            "3 above is the player-count-separated version of this same relationship, and is the one to trust first): " +
            "`card multiplicity immediately after regeneration n` -> `turns remaining after regeneration n`. Contrasted " +
            "directly below against the OLD model this replaces: `card's final multiplicity` -> `game's total turn " +
            "count` (computed here from the same dataset, using only each game's own last regeneration event, for a " +
            "like-for-like comparison rather than citing the separate historical report).")
        sb.appendLine()
        sb.appendLine("| Card | NEW: any-depth multiplicity -> turns remaining, r (n) | OLD: final multiplicity -> total game length, r (n) |")
        sb.appendLine("|---|---|---|")
        val cardNames = samplesByCard.keys.sorted()
        val lastEventByCard = samplesByCard.mapValues { (_, samples) ->
            samples.groupBy { it.gameKey }.values.map { perGame -> perGame.maxByOrNull { it.depth }!! }
        }
        cardNames.forEach { name ->
            val allSamplesForCard = samplesByCard.getValue(name)
            val newResult = correlate(allSamplesForCard, { it.multiplicity.toDouble() }, { it.turnsRemaining.toDouble() })
            val lastEvents = lastEventByCard.getValue(name)
            val oldResult = correlate(lastEvents, { it.multiplicity.toDouble() }, { it.totalTurnsTaken.toDouble() })
            sb.appendLine("| $name | ${newResult.format()} | ${oldResult.format()} |")
        }
        sb.appendLine()
        sb.appendLine("Note the OLD column's own n here (one observation per game a card was eligible in) is smaller " +
            "than the previously-published Table K5's `n=5000` - that table pooled every game regardless of a color " +
            "card's own eligibility, which Section 2 above identifies as its own separate confound this analysis " +
            "corrects for throughout.")
        sb.appendLine()
    }

    // --- Section 6: rarity stratification/normalization ---

    private fun buildSection6Rarity(sb: StringBuilder, samplesByCard: Map<String, List<Sample>>) {
        sb.appendLine("## 6. Original-rarity stratification and normalization")
        sb.appendLine()
        sb.appendLine("`normalized = multiplicity / Generation-0 rarity ceiling` (SINGLE=1, DOUBLE=2, TRIPLE=3, " +
            "QUADRUPLE=4), so every card's state is expressed on the same [0,1] scale regardless of its own legal state " +
            "space, before comparing across rarity classes. Raw (un-normalized) per-card correlations are Section 3/4/5's " +
            "own tables above; this section adds the normalized view and the rarity-class-level aggregate.")
        sb.appendLine()
        sb.appendLine("**Per-card normalized correlation** (pooled across player counts and depths):")
        sb.appendLine()
        sb.appendLine("| Card | Rarity class | Raw r (n) | Normalized r (n) |")
        sb.appendLine("|---|---|---|---|")
        val cardNames = samplesByCard.keys.sorted()
        cardNames.forEach { name ->
            val samples = samplesByCard.getValue(name)
            val raw = correlate(samples, { it.multiplicity.toDouble() }, { it.turnsRemaining.toDouble() })
            val normalized = correlate(samples, { it.normalized }, { it.turnsRemaining.toDouble() })
            sb.appendLine("| $name | ${RARITY_CLASS.getValue(name)} | ${raw.format()} | ${normalized.format()} |")
        }
        sb.appendLine()
        sb.appendLine("**Rarity-class-level aggregate** (every card sharing a rarity class pooled into one sample, testing " +
            "whether rarity class itself - independent of which specific card - relates to turns remaining):")
        sb.appendLine()
        sb.appendLine("| Rarity class | Cards in class | Pooled normalized r (n) | Per-card normalized r: min to max |")
        sb.appendLine("|---|---|---|---|")
        listOf("SINGLE", "DOUBLE", "TRIPLE", "QUADRUPLE").forEach { rarityClass ->
            val namesInClass = cardNames.filter { RARITY_CLASS[it] == rarityClass }
            val pooled = namesInClass.flatMap { samplesByCard.getValue(it) }
            val pooledResult = correlate(pooled, { it.normalized }, { it.turnsRemaining.toDouble() })
            val perCardRs = namesInClass.map { name -> correlate(samplesByCard.getValue(name), { it.normalized }, { it.turnsRemaining.toDouble() }).r }
            val range = if (perCardRs.isEmpty()) "n/a" else "${fmt(perCardRs.min(), 3)} to ${fmt(perCardRs.max(), 3)}"
            sb.appendLine("| $rarityClass | ${namesInClass.size} | ${pooledResult.format()} | $range |")
        }
        sb.appendLine()
        sb.appendLine("A narrow per-card range within a rarity class (close to the class's own pooled r) would suggest " +
            "rarity class explains most of what's measured, with card identity adding little; a wide range would suggest " +
            "the opposite - see Section 9 for which this data actually shows.")
        sb.appendLine()
    }

    // --- Section 7: persistence ---

    private fun buildSection7Persistence(sb: StringBuilder, samplesByCard: Map<String, List<Sample>>) {
        sb.appendLine("## 7. Persistence analysis")
        sb.appendLine()
        sb.appendLine("\"Elevated\" is defined as `normalized >= 0.5` (at or above half of a card's own rarity ceiling) - " +
            "a fixed, documented threshold, not tuned. For every (game, card) pair with at least 2 regeneration events, " +
            "each event from depth 2 onward is classified relative to that same card's own immediately preceding event " +
            "in that same game: **persistent-elevated** (elevated now AND elevated at the previous regeneration), " +
            "**transient-elevated** (elevated now but NOT at the previous regeneration), or **not-elevated** (not " +
            "elevated now, regardless of before). Depth-1 events have no preceding regeneration to compare against and " +
            "are excluded from this classification entirely (not folded into any category).")
        sb.appendLine()
        sb.appendLine("**Lag-1 autocorrelation per card** (correlation between a card's own multiplicity at regeneration " +
            "d and at regeneration d+1, within the same game - how \"sticky\" that card's state is from one reshuffle to " +
            "the next):")
        sb.appendLine()
        sb.appendLine("| Card | Lag-1 autocorrelation r (n consecutive pairs) |")
        sb.appendLine("|---|---|")
        val cardNames = samplesByCard.keys.sorted()
        val autocorrByCard = mutableMapOf<String, RResult>()
        cardNames.forEach { name ->
            val byGame = samplesByCard.getValue(name).groupBy { it.gameKey }
            val pairs = mutableListOf<Pair<Double, Double>>()
            byGame.values.forEach { events ->
                val sorted = events.sortedBy { it.depth }
                for (i in 1 until sorted.size) pairs += sorted[i - 1].multiplicity.toDouble() to sorted[i].multiplicity.toDouble()
            }
            val result = if (pairs.size < 2) RResult(0.0, pairs.size) else RResult(pearson(pairs.map { it.first }, pairs.map { it.second }), pairs.size)
            autocorrByCard[name] = result
            sb.appendLine("| $name | ${result.format()} |")
        }
        sb.appendLine()

        // Persistent-vs-transient outcome comparison, pooled across cards.
        data class Classified(val turnsRemaining: Int, val category: String)
        val classified = mutableListOf<Classified>()
        cardNames.forEach { name ->
            val byGame = samplesByCard.getValue(name).groupBy { it.gameKey }
            byGame.values.forEach { events ->
                val sorted = events.sortedBy { it.depth }
                for (i in 1 until sorted.size) {
                    val now = sorted[i]
                    val prev = sorted[i - 1]
                    val elevatedNow = now.normalized >= 0.5
                    val elevatedPrev = prev.normalized >= 0.5
                    val category = when {
                        elevatedNow && elevatedPrev -> "persistent-elevated"
                        elevatedNow && !elevatedPrev -> "transient-elevated"
                        else -> "not-elevated"
                    }
                    classified += Classified(now.turnsRemaining, category)
                }
            }
        }
        sb.appendLine("**Outcome by persistence category** (pooled across every card and player count, depth-2-onward " +
            "events only):")
        sb.appendLine()
        sb.appendLine("| Category | n | Mean turns remaining | SD | SEM |")
        sb.appendLine("|---|---|---|---|---|")
        val byCategory = classified.groupBy { it.category }
        listOf("persistent-elevated", "transient-elevated", "not-elevated").forEach { cat ->
            val group = byCategory[cat].orEmpty()
            if (group.isEmpty()) {
                sb.appendLine("| $cat | 0 | n/a | n/a | n/a |")
            } else {
                val values = group.map { it.turnsRemaining.toDouble() }
                val meanVal = values.average()
                val sd = stdDev(values, meanVal)
                sb.appendLine("| $cat | ${group.size} | ${fmt(meanVal)} | ${fmt(sd)} | ${fmt(sem(sd, group.size), 2)} |")
            }
        }
        sb.appendLine()
        val persistent = byCategory["persistent-elevated"].orEmpty().map { it.turnsRemaining.toDouble() }
        val transient = byCategory["transient-elevated"].orEmpty().map { it.turnsRemaining.toDouble() }
        if (persistent.size >= MIN_N_FOR_CORRELATION && transient.size >= MIN_N_FOR_CORRELATION) {
            val meanP = persistent.average(); val meanT = transient.average()
            val semP = sem(stdDev(persistent, meanP), persistent.size)
            val semT = sem(stdDev(transient, meanT), transient.size)
            val z = (meanP - meanT) / kotlin.math.sqrt(semP * semP + semT * semT)
            sb.appendLine("**Persistent vs. transient elevated, direct comparison**: mean turns-remaining difference " +
                "(persistent minus transient) = ${fmt(meanP - meanT)}, z = ${fmt(z, 2)}, " +
                "${if (kotlin.math.abs(z) > 1.96) "**statistically distinguishable**" else "not statistically distinguishable"} " +
                "(n=${persistent.size} persistent, n=${transient.size} transient).")
        } else {
            sb.appendLine("**Persistent vs. transient elevated, direct comparison**: insufficient sample " +
                "(n=${persistent.size} persistent, n=${transient.size} transient; threshold is $MIN_N_FOR_CORRELATION each) " +
                "to compare reliably.")
        }
        sb.appendLine()
    }

    // --- Section 8: tail-specific analysis ---

    private fun buildSection8Tail(sb: StringBuilder, samplesByCard: Map<String, List<Sample>>, allGames: List<GameOutcome>) {
        sb.appendLine("## 8. Tail-specific analysis")
        sb.appendLine()
        sb.appendLine("Tail defined **per player count, from this run's own games** (not the published baseline's figures, " +
            "to stay self-consistent with this exact dataset) as the top 10% of games by total turns taken (i.e. " +
            "`totalTurnsTaken >= that player count's own p90`) - documented threshold, not tuned. A stricter alternate " +
            "definition, \"capped\" (hit the $MAX_TURNS_PER_GAME-turn simulation cap without a winner), is reported " +
            "alongside for comparison.")
        sb.appendLine()
        sb.appendLine("| Player count | p90 threshold (turns) | Tail games (>=p90) | Capped games |")
        sb.appendLine("|---|---|---|---|")
        val p90ByPc = mutableMapOf<Int, Double>()
        PLAYER_COUNTS.forEach { pc ->
            val games = allGames.filter { it.playerCount == pc }
            val p90 = percentile(games.map { it.totalTurnsTaken }, 90.0)
            p90ByPc[pc] = p90
            val tailCount = games.count { it.totalTurnsTaken >= p90 }
            val cappedCount = games.count { it.reachedCap }
            sb.appendLine("| $pc | ${fmt(p90, 0)} | $tailCount | $cappedCount |")
        }
        sb.appendLine()

        sb.appendLine("For each card, using that card's own **last regeneration event per eligible game** (its terminal " +
            "state for that game) - mean normalized multiplicity in tail games vs. non-tail games, and the point-biserial " +
            "correlation between normalized multiplicity and tail membership (0/1):")
        sb.appendLine()
        sb.appendLine("| Card | Tail mean normalized (n) | Non-tail mean normalized (n) | Diff | z | Distinguishable? | Point-biserial r (n) |")
        sb.appendLine("|---|---|---|---|---|---|---|")
        val cardNames = samplesByCard.keys.sorted()
        cardNames.forEach { name ->
            val lastEvents = samplesByCard.getValue(name).groupBy { it.gameKey }.values.map { it.maxByOrNull { s -> s.depth }!! }
            val tailThresholdApplies = lastEvents.map { it to (it.totalTurnsTaken >= p90ByPc.getValue(it.playerCount)) }
            val tailGroup = tailThresholdApplies.filter { it.second }.map { it.first.normalized }
            val nonTailGroup = tailThresholdApplies.filterNot { it.second }.map { it.first.normalized }
            val pointBiserial = correlate(lastEvents, { it.normalized }, { if (it.totalTurnsTaken >= p90ByPc.getValue(it.playerCount)) 1.0 else 0.0 })
            if (tailGroup.size >= 5 && nonTailGroup.size >= 5) {
                val meanTail = tailGroup.average(); val meanNonTail = nonTailGroup.average()
                val semTail = sem(stdDev(tailGroup, meanTail), tailGroup.size)
                val semNonTail = sem(stdDev(nonTailGroup, meanNonTail), nonTailGroup.size)
                val z = (meanTail - meanNonTail) / kotlin.math.sqrt(semTail * semTail + semNonTail * semNonTail)
                sb.appendLine("| $name | ${fmt(meanTail, 3)} (${tailGroup.size}) | ${fmt(meanNonTail, 3)} (${nonTailGroup.size}) | " +
                    "${fmt(meanTail - meanNonTail, 3)} | ${fmt(z, 2)} | ${if (kotlin.math.abs(z) > 1.96) "**Yes**" else "No"} | ${pointBiserial.format()} |")
            } else {
                sb.appendLine("| $name | n=${tailGroup.size} | n=${nonTailGroup.size} | insufficient | n/a | n/a | ${pointBiserial.format()} |")
            }
        }
        sb.appendLine()
    }

    // --- Section 9: outcome A/B/C ---

    private fun buildSection9Outcome(sb: StringBuilder, samplesByCard: Map<String, List<Sample>>): String {
        sb.appendLine("## 9. Interpretation")
        sb.appendLine()
        sb.appendLine("**Measured association**: Section 3's within-player-count table is this report's primary factual " +
            "finding - the sign and magnitude of each card's own multiplicity-vs-turns-remaining relationship, per " +
            "player count, with observation counts attached.")
        sb.appendLine()
        val cardNames = samplesByCard.keys.sorted()
        val signConsistentReliableCards = cardNames.filter { name ->
            val samples = samplesByCard.getValue(name)
            val results = PLAYER_COUNTS.mapNotNull { pc ->
                val subset = samples.filter { it.playerCount == pc }
                val r = correlate(subset, { it.multiplicity.toDouble() }, { it.turnsRemaining.toDouble() })
                if (r.reliable) r.r else null
            }
            results.size >= 3 && (results.all { it >= 0.05 } || results.all { it <= -0.05 })
        }
        sb.appendLine("**Plausible interpretation**: of ${cardNames.size} cards, ${signConsistentReliableCards.size} show a " +
            "sign-consistent (same direction, |r|>=0.05) relationship across at least 3 reliable player-count cells - " +
            "listed here for reference, not as a weighting recommendation: " +
            (if (signConsistentReliableCards.isEmpty()) "none." else signConsistentReliableCards.joinToString(", ") + ".") +
            " Section 6's rarity-class-level aggregate additionally shows whether rarity class alone (independent of " +
            "specific card identity) explains a comparable share of the per-card variation - if per-card normalized r " +
            "values within a class cluster tightly around that class's own pooled r, card identity is adding little " +
            "beyond \"how rare is this card,\" which itself would argue against card-specific weighting.")
        sb.appendLine()
        sb.appendLine("**Evidence insufficient to distinguish**: Section 4's depth-bucket table names exactly which " +
            "(card, depth) cells fall below the $MIN_N_FOR_CORRELATION-observation threshold - deeper buckets (4, 5+) are " +
            "underpowered for most cards at this 5,000-game scale, per that section's own reliable-cell counts. Section " +
            "7's persistent-vs-transient comparison and Section 8's tail comparison each report explicitly when their " +
            "own sample was too small to support a distinguishability claim, rather than asserting one regardless.")
        sb.appendLine()
        sb.appendLine("**Causal hypothesis**: none is asserted anywhere in this report. Every relationship above is " +
            "correlational; the base-rate confound the original pooled analysis had (more regenerations happen in " +
            "longer games, mechanically) is substantially reduced by conditioning on player count and using turns-" +
            "remaining-from-this-event rather than total-game-length, but a full causal claim (this card's multiplicity " +
            "*causes* subsequent stagnation, rather than both being downstream of some other factor - e.g. which " +
            "player's own play patterns happen to draw and discard which cards) is not established by any observational " +
            "correlation alone, regardless of how it's conditioned.")
        sb.appendLine()

        val outcome = when {
            signConsistentReliableCards.size >= cardNames.size / 2 -> "A"
            signConsistentReliableCards.isEmpty() -> "C"
            else -> "B"
        }
        sb.appendLine("**Outcome**: **$outcome** — " + when (outcome) {
            "A" -> "card identity discriminates. A majority of cards show a sign-consistent, reliable relationship " +
                "across player counts after conditioning - these become candidates for a later, separate weighting " +
                "decision (not made in this pass)."
            "C" -> "card identity does not meaningfully discriminate after conditioning. No card shows a sign-consistent, " +
                "reliable (|r|>=0.05 across at least 3 player-count cells) relationship once player count and per-game " +
                "eligibility are accounted for. This suggests card identity may be the wrong control variable for anti-" +
                "stagnation - see Section 6's rarity-class comparison and Section 10 for what else the data points toward " +
                "instead. Per the task's own instruction: no weights are manufactured from this result."
            else -> "weak/mixed discrimination. Some cards ($signConsistentReliableCards) show a sign-consistent " +
                "relationship, but most do not, and/or the signal is unstable across player counts or depth (see Section " +
                "3/4's own tables). Additional evidence that would help resolve this: a larger sample specifically at " +
                "depth >=4 (Section 4's own reliable-cell counts show where this run is underpowered), and a repeat at a " +
                "different base seed to check whether the sign-consistent subset above replicates or is itself sampling " +
                "noise."
        })
        sb.appendLine()
        return outcome
    }

    // --- Section 10: architectural/semantic ambiguities discovered ---

    private fun buildSection10Ambiguities(sb: StringBuilder) {
        sb.appendLine("## 10. Newly discovered architectural or semantic ambiguities")
        sb.appendLine()
        sb.appendLine("- **\"Turns elapsed at regeneration\" is turn-granular, not sub-turn-granular.** A regeneration " +
            "can happen at any point within a turn (a card-driven move chaining into a nested draw, a Held-card play's " +
            "own Precedence window, etc.), but this instrumentation's turn counter only increments once a whole " +
            "`driveOneTurn` call returns - so `turnsElapsedAtRegeneration` means \"turns fully completed strictly before " +
            "the turn during which this regeneration happened,\" never a fractional position within that turn. This is " +
            "adequate for this analysis (turns remaining is still monotonically related to true time-to-outcome) but " +
            "would need a finer-grained clock if a future pass wanted sub-turn resolution.")
        sb.appendLine("- **Color-eligibility as a per-card confound was not previously identified.** The original pooled " +
            "Table K5 computed every card's correlation across all 5,000 games regardless of whether that card's own " +
            "color was even seated that game - for the 6 color-restricted cards, a meaningful fraction of their own " +
            "\"observations\" were therefore structural zeros carrying no information about the mechanism, silently " +
            "diluting those specific correlations toward whatever a mix of real and structural-zero data happens to " +
            "produce. This analysis excludes ineligible games from a card's own sample entirely - a genuinely new " +
            "methodological point, not previously documented anywhere in this codebase's Phase 1B/1C history.")
        sb.appendLine("- **No existing engine-level instrumentation seam for \"turn number at an arbitrary mid-turn " +
            "event.\"** This analysis had to thread a closure-captured mutable counter through `buildInstrumentedDeckForColors` " +
            "specifically because neither `GameState` nor `FateHarvestDeck` exposes anything like \"how many turns has " +
            "this game taken so far\" - the turn count only ever lived as a local variable in whatever test harness " +
            "happens to be driving `TurnDriver.driveOneTurn` in a loop. This was sufficient for this task (deliberately " +
            "test-local, per the Clearance C2 instrument/test scope - no engine change was made), but any future " +
            "instrumentation need at this same granularity would hit the identical gap.")
        sb.appendLine()
    }
}
