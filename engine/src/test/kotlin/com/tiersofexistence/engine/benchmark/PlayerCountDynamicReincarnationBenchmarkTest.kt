package com.tiersofexistence.engine.benchmark

import com.tiersofexistence.engine.board.SquareType
import com.tiersofexistence.engine.cards.FateHarvestCard
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
 * **PHASE 1B — experimental, NOT canonical.** Characterizes the effect of a candidate "dynamic
 * Fate Harvest reincarnation" reshuffle rule ([DynamicReincarnationRules]) against the validated
 * Baseline A fixed-composition benchmark (commit `d530a21`,
 * `docs/benchmarks/player-count-benchmark.md`) — whether it should ever become canonical is a
 * *separate, later* decision this file does not make or recommend either way; it only measures.
 *
 * Baseline A ([PlayerCountBenchmarkTest], `FateHarvestDeck.ReshuffleStrategy.PlainShuffle`) is
 * completely untouched by this file's existence — `FateHarvestDeck` gained a purely additive,
 * default-preserving `ReshuffleStrategy` seam specifically so this experimental mode could plug
 * in without altering a single byte of Baseline A's own behavior (verified: the full pre-existing
 * suite, including the permanent 2000-game `GameSimulationTest` and the complete 8,750-game
 * `PlayerCountBenchmarkTest`, stayed green and byte-identical after that seam was added).
 *
 * **Scale**: 1,000 games per player-count cohort (5,000 total), single stage, its own independent
 * seed range — deliberately smaller than Baseline A's 8,750-game two-stage rigor, since this is a
 * *screening* characterization of a candidate mechanic (with substantially more per-game
 * instrumentation to carry: every regeneration event's multiplicity outcome, not just turn
 * counts), not a canonical baseline needing that same statistical bar. Still ample for the
 * comparisons and correlations below (SEM-based 95% CIs are reported throughout, same as Baseline
 * A, so the achieved precision is visible rather than assumed).
 *
 * Gated behind its own `toe.benchmark.dynamic` system property (forwarded into the forked test
 * JVM via `engine/build.gradle.kts`'s `tasks.test` block, same mechanism as
 * [PlayerCountBenchmarkTest]'s own `toe.benchmark`) — skipped by default. Run via:
 * `./gradlew :engine:test --configure-on-demand -Dtoe.benchmark.dynamic=true --tests
 * "com.tiersofexistence.engine.benchmark.PlayerCountDynamicReincarnationBenchmarkTest"`.
 */
class PlayerCountDynamicReincarnationBenchmarkTest {

    companion object {
        private const val MAX_TURNS_PER_GAME = 8000
        private const val GAMES_PER_COHORT = 1000
        private const val WARMUP_GAMES = 30
        private val PLAYER_COUNTS = listOf(2, 3, 4, 5, 6)

        // Independent of both Baseline A seed ranges (Stage 1: 5.0e8+, Stage 2: 9.0e8+) - see
        // PlayerCountBenchmarkTest's own seed-scheme doc for those.
        private const val BASE_SEED = 1_300_000_000L
        private const val PLAYER_COUNT_SEED_STRIDE = 10_000_000L

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

        private fun stdDev(values: List<Int>, mean: Double): Double {
            if (values.size < 2) return 0.0
            return kotlin.math.sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
        }

        private fun sem(stdDevValue: Double, n: Int): Double = stdDevValue / kotlin.math.sqrt(n.toDouble())

        private fun pearson(xs: List<Double>, ys: List<Double>): Double {
            val n = xs.size
            if (n < 2) return 0.0
            val meanX = xs.average(); val meanY = ys.average()
            val cov = xs.indices.sumOf { (xs[it] - meanX) * (ys[it] - meanY) }
            val stdX = kotlin.math.sqrt(xs.sumOf { (it - meanX) * (it - meanX) })
            val stdY = kotlin.math.sqrt(ys.sumOf { (it - meanY) * (it - meanY) })
            return if (stdX == 0.0 || stdY == 0.0) 0.0 else cov / (stdX * stdY)
        }

        /** Baseline A's own validated Stage 2 (1,500 games/cohort) figures, commit `d530a21`,
         * `docs/benchmarks/player-count-benchmark.md`'s Table C — hardcoded here rather than
         * re-running Baseline A (which is unchanged and already validated) purely to build this
         * file's own comparison tables against it. */
        private data class BaselineARow(
            val playerCount: Int, val meanTurns: Double, val semTurns: Double, val medianTurns: Double,
            val p90: Double, val p95: Double, val capRate: Double, val meanRounds: Double,
            val meanHumanSeatTurns: Double, val meanDecisionOpportunities: Double,
            val meanRuntimePerGameMs: Double, val meanRuntimePerTurnMs: Double,
        )

        private val BASELINE_A = listOf(
            BaselineARow(2, 633.3, 11.43, 503.0, 1204.0, 1516.0, 0.000, 220.4, 316.6, 1877.7, 1.247, 0.0020),
            BaselineARow(3, 926.9, 17.33, 724.0, 1774.0, 2207.0, 0.000, 223.8, 309.0, 2251.4, 2.212, 0.0024),
            BaselineARow(4, 1286.2, 25.65, 1001.0, 2502.0, 3233.0, 0.001, 239.7, 321.5, 2799.2, 3.417, 0.0027),
            BaselineARow(5, 1757.7, 36.81, 1291.0, 3621.0, 4745.0, 0.005, 267.2, 351.5, 3580.9, 5.402, 0.0031),
            BaselineARow(6, 2419.2, 49.18, 1821.0, 5241.0, 6737.0, 0.027, 312.8, 403.2, 4739.5, 8.253, 0.0034),
        )
    }

    private class InvariantViolation(val playerCount: Int, val gameIndex: Int, val seed: Long, val turnNumber: Int, message: String) :
        AssertionError("[players=$playerCount, game #$gameIndex, seed=$seed, turn=$turnNumber] $message")

    private data class RegenerationEvent(
        val index: Int,
        val targetSize: Int,
        val refillCount: Int,
        val cullCount: Int,
        val finalMultiplicity: Map<String, Int>,
    )

    private data class GameMetrics(
        val playerCount: Int,
        val seed: Long,
        val turnsTaken: Int,
        val roundsTaken: Int,
        val completed: Boolean,
        val capped: Boolean,
        val wallClockNanos: Long,
        val seatTurns: List<Int>,
        val seatDecisionsAsked: List<Long>,
        val generationCount: Int,
        val totalRefills: Int,
        val totalCulls: Int,
        val gen0Multiplicity: Map<String, Int>,
        val finalMultiplicity: Map<String, Int>,
    )

    private data class CohortResult(val playerCount: Int, val games: List<GameMetrics>, val violations: List<InvariantViolation>) {
        val finished get() = games.count { it.completed }
        val cappedCount get() = games.count { it.capped }
        val capRate get() = cappedCount.toDouble() / games.size
        val turns get() = games.map { it.turnsTaken }
        val meanTurns get() = turns.average()
        val stdDevTurns get() = stdDev(turns, meanTurns)
        val semTurns get() = sem(stdDevTurns, turns.size)
        val medianTurns get() = percentile(turns, 50.0)
        val p90Turns get() = percentile(turns, 90.0)
        val p95Turns get() = percentile(turns, 95.0)
        val meanRounds get() = games.map { it.roundsTaken }.average()
        val meanHumanSeatTurns get() = games.flatMap { it.seatTurns }.average()
        val meanDecisionOpportunities get() = games.flatMap { it.seatDecisionsAsked }.map { it.toDouble() }.average()
        val meanRuntimePerGameNanos get() = games.map { it.wallClockNanos }.average()
        val meanRuntimePerTurnNanos get() = games.sumOf { it.wallClockNanos }.toDouble() / turns.sum().toDouble()
        val meanGenerationCount get() = games.map { it.generationCount }.average()
        val medianGenerationCount get() = percentile(games.map { it.generationCount }, 50.0)
        val meanRefills get() = games.map { it.totalRefills }.average()
        val meanCulls get() = games.map { it.totalCulls }.average()
    }

    // --- deck construction: experimental reincarnation strategy ---

    private data class ExperimentalDeckConstruction(
        val deck: FateHarvestDeck,
        val expectedTotal: Int,
        val gen0Multiplicity: Map<String, Int>,
        val events: MutableList<RegenerationEvent>,
    )

    private fun colorCardNamesFor(colors: Set<PlayerColor>): Set<String> = colors.flatMap { FateHarvestCatalog.colorCards[it].orEmpty() }.map { it.name }.toSet()

    private fun buildExperimentalDeckForColors(colors: List<PlayerColor>, random: Random): ExperimentalDeckConstruction {
        val unusedColors = PlayerColor.entries.filterNot { it in colors }.toSet()
        val removedNames = colorCardNamesFor(unusedColors)
        val eligibleTypes = FateHarvestCatalog.all.filter { it.name !in removedNames }
        val filtered = FateHarvestCatalog.buildDeck().filter { it.name !in removedNames }
        val gen0Multiplicity = filtered.groupingBy { it.name }.eachCount()
        val shuffled = filtered.shuffled(random)

        val events = mutableListOf<RegenerationEvent>()
        var index = 0
        val strategy = FateHarvestDeck.ReshuffleStrategy { discardPile, rnd ->
            index += 1
            val result = DynamicReincarnationRules.regenerate(discardPile, eligibleTypes, rnd)
            events += RegenerationEvent(index, result.targetSize, result.refillCount, result.cullCount, result.finalMultiplicity)
            result.regeneratedPile
        }

        return ExperimentalDeckConstruction(
            deck = FateHarvestDeck.forTesting(shuffled, random, strategy),
            expectedTotal = filtered.size,
            gen0Multiplicity = gen0Multiplicity,
            events = events,
        )
    }

    // --- game driving ---

    private fun runCohort(playerCount: Int, gameCount: Int): CohortResult {
        val games = mutableListOf<GameMetrics>()
        val violations = mutableListOf<InvariantViolation>()
        repeat(gameCount) { gameIndex ->
            val seed = seedFor(playerCount, gameIndex)
            val (metrics, violation) = runOneGame(playerCount, seed, gameIndex)
            games += metrics
            if (violation != null) violations += violation
        }
        return CohortResult(playerCount, games, violations)
    }

    private fun runOneGame(playerCount: Int, seed: Long, gameIndex: Int): Pair<GameMetrics, InvariantViolation?> {
        val random = Random(seed)
        val colors = rotatedColors(playerCount, gameIndex)
        val turnOrder = TurnOrder(colors)
        val players = colors.associateWith { PlayerState(it) }
        players.values.forEach { it.tierPool(TierLevel.FIRST).startToken() }
        val deckConstruction = buildExperimentalDeckForColors(colors, random)
        val state = GameState(players = players, turnOrder = turnOrder, deck = deckConstruction.deck)

        val seatStats: Map<PlayerColor, SeatDecisionStats> = colors.associateWith { SeatDecisionStats() }
        val decisionsByPlayer: Map<PlayerColor, com.tiersofexistence.engine.rules.TurnDecisionProvider> =
            colors.associateWith { color -> InstrumentedDecisionProvider(RandomLegalDecisionProvider(Random(random.nextLong())), seatStats.getValue(color)) }
        val driver = TurnDriver(decisionsByPlayer, rollForPhase = { phase -> Dice.rollForPhase(phase, random) })

        val seatTurnCounts = colors.associateWith { 0 }.toMutableMap()
        var turnsTaken = 0
        var completed = false
        var violation: InvariantViolation? = null
        val start = System.nanoTime()
        try {
            state.skipEmptyPhases()
            checkInvariants(state, playerCount, gameIndex, seed, turnsTaken, deckConstruction.expectedTotal)
            while (turnsTaken < MAX_TURNS_PER_GAME && state.winners.isEmpty() && state.currentTurn != null) {
                val turnPlayer = state.currentTurn
                driver.driveOneTurn(state)
                turnsTaken += 1
                if (turnPlayer != null) seatTurnCounts[turnPlayer] = (seatTurnCounts[turnPlayer] ?: 0) + 1
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
        val elapsed = System.nanoTime() - start

        val events = deckConstruction.events
        val metrics = GameMetrics(
            playerCount = playerCount,
            seed = seed,
            turnsTaken = turnsTaken,
            roundsTaken = state.roundNumber,
            completed = completed,
            capped = !completed && violation == null,
            wallClockNanos = elapsed,
            seatTurns = colors.map { seatTurnCounts.getValue(it) },
            seatDecisionsAsked = colors.map { seatStats.getValue(it).totalAsked() },
            generationCount = events.size,
            totalRefills = events.sumOf { it.refillCount },
            totalCulls = events.sumOf { it.cullCount },
            gen0Multiplicity = deckConstruction.gen0Multiplicity,
            finalMultiplicity = events.lastOrNull()?.finalMultiplicity ?: deckConstruction.gen0Multiplicity,
        )
        return metrics to violation
    }

    /** Weaker than [PlayerCountBenchmarkTest]'s own `checkInvariants` by explicit design (per the
     * task's own "Do not apply the fixed-composition multiplicity invariant to Experimental B"):
     * checks total physical card conservation (never per-card-name multiplicity, which is
     * *expected* to evolve here), token conservation, Phase validity, and winner-square
     * correctness — everything Baseline A checks except the one invariant that's deliberately not
     * true of this experimental mode by design. */
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

        if (state.winners.isNotEmpty()) {
            val board = state.boards.getValue(TierLevel.FOURTH)
            state.winners.forEach { color ->
                val ps = state.players.getValue(color)
                val onYouWin = ps.tierPool(TierLevel.FOURTH).inPlayPositions.any { board.squareAt(it).type == SquareType.YOU_WIN }
                if (!onYouWin) fail("Winner $color declared but has no 4th-Tier in-play token on a YOU_WIN square (positions: ${ps.tierPool(TierLevel.FOURTH).inPlayPositions})")
            }
        }
    }

    @Test
    fun `experimental dynamic Fate Harvest reincarnation benchmark, compared against Baseline A`() {
        assumeTrue(
            System.getProperty("toe.benchmark.dynamic") == "true",
            "Skipped by default (experimental, heavy) - run with -Dtoe.benchmark.dynamic=true to execute.",
        )

        repeat(WARMUP_GAMES) { i -> runOneGame(PLAYER_COUNTS[i % PLAYER_COUNTS.size], seedFor(-1, i) - 1_000_000_000L, i) }

        val cohorts = PLAYER_COUNTS.map { pc -> runCohort(pc, GAMES_PER_COHORT) }
        val allViolations = cohorts.flatMap { it.violations }

        val report = buildReport(cohorts, allViolations)
        val reportFile = File("../docs/benchmarks/dynamic-reincarnation-benchmark.md")
        reportFile.parentFile.mkdirs()
        reportFile.writeText(report)
        println(report)

        check(allViolations.isEmpty()) {
            "${allViolations.size} invariant violation(s):\n" + allViolations.joinToString("\n---\n") { it.message ?: it.toString() }
        }
    }

    private fun fmt(d: Double, digits: Int = 1) = "%.${digits}f".format(d)
    private fun ms(nanos: Double) = nanos / 1_000_000.0

    private fun buildReport(cohorts: List<CohortResult>, violations: List<InvariantViolation>): String {
        val sb = StringBuilder()
        sb.appendLine("# T.O.E. Experimental Dynamic Fate Harvest Reincarnation Benchmark (Phase 1B)")
        sb.appendLine()
        sb.appendLine("**Experimental, not canonical.** Compares the candidate dynamic-reincarnation reshuffle rule " +
            "(`DynamicReincarnationRules`) against the validated Baseline A fixed-composition benchmark (commit " +
            "`d530a21`, `docs/benchmarks/player-count-benchmark.md`). No balance or canon decision is made or " +
            "recommended by this report - it measures effects only, per the task's own explicit scope.")
        sb.appendLine()
        sb.appendLine("Scale: $GAMES_PER_COHORT games/cohort x 5 player counts = ${GAMES_PER_COHORT * 5} total games, single stage, " +
            "seed = $BASE_SEED + playerCount * $PLAYER_COUNT_SEED_STRIDE + gameIndex (independent of both Baseline A seed ranges).")
        sb.appendLine()
        sb.appendLine("## Resolved experimental parameters")
        sb.appendLine()
        sb.appendLine(
            "- **1-copy transition** (corrected from the originally-stated duplicated-outcome version): " +
                "1->2: 33%, 1->0: 33%, remain at 1: 34%.\n" +
                "- **Overflow correction**: applied only after every card type's own transition is resolved (never " +
                "mid-sequence, so no card type gets a structural ordering advantage) - uniform random removal " +
                "*without replacement across individual physical cards*, not card types, so a type with more " +
                "provisional copies gets proportionately more removal exposure.\n" +
                "- **Underflow correction**: remaining slots filled one at a time, each an independent uniform " +
                "random pick *with replacement* across every color-legal card type (not weighted by canonical " +
                "rarity) - a type currently at 0 copies is exactly as eligible as any other.\n" +
                "- **Regeneration target size**: exactly the discard pile's own size at that moment, never the " +
                "game's global deck size - required for whole-game card-total conservation, since cards currently " +
                "held in a hand aren't touched by a regeneration event. Enforced as a hard invariant inside " +
                "`FateHarvestDeck.draw()` itself (a strategy that returns the wrong size throws immediately).\n" +
                "- **\"Current multiplicity\"**: the literal count of each type physically present in the discard " +
                "pile about to regenerate - not a separately-tracked persistent ecology counter. See " +
                "`DynamicReincarnationRules`'s own class doc for the full reasoning.",
        )
        sb.appendLine()

        sb.appendLine("## Table G1 - Experimental B vs. Baseline A (game-length and cost)")
        sb.appendLine()
        sb.appendLine("| Player count | B mean turns | A mean turns | Abs diff | % diff | B median | A median | B p90 | A p90 | B p95 | A p95 | B cap rate | A cap rate |")
        sb.appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        cohorts.forEach { c ->
            val a = BASELINE_A.first { it.playerCount == c.playerCount }
            val absDiff = c.meanTurns - a.meanTurns
            val pctDiff = (absDiff / a.meanTurns) * 100
            sb.appendLine(
                "| ${c.playerCount} | ${fmt(c.meanTurns)} | ${fmt(a.meanTurns)} | ${fmt(absDiff)} | ${fmt(pctDiff)}% | " +
                    "${fmt(c.medianTurns, 0)} | ${fmt(a.medianTurns, 0)} | ${fmt(c.p90Turns, 0)} | ${fmt(a.p90)} | " +
                    "${fmt(c.p95Turns, 0)} | ${fmt(a.p95)} | ${fmt(c.capRate * 100, 2)}% | ${fmt(a.capRate * 100, 2)}% |",
            )
        }
        sb.appendLine()

        sb.appendLine("## Table G2 - Experimental B vs. Baseline A (structure, seat load, engine cost)")
        sb.appendLine()
        sb.appendLine("| Player count | B Rounds/game | A Rounds/game | B human-seat turns | A human-seat turns | B decisions/seat | A decisions/seat | B ms/game | A ms/game | B ms/turn | A ms/turn |")
        sb.appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
        cohorts.forEach { c ->
            val a = BASELINE_A.first { it.playerCount == c.playerCount }
            sb.appendLine(
                "| ${c.playerCount} | ${fmt(c.meanRounds)} | ${fmt(a.meanRounds)} | ${fmt(c.meanHumanSeatTurns)} | ${fmt(a.meanHumanSeatTurns)} | " +
                    "${fmt(c.meanDecisionOpportunities)} | ${fmt(a.meanDecisionOpportunities)} | ${fmt(ms(c.meanRuntimePerGameNanos), 3)} | ${fmt(a.meanRuntimePerGameMs, 3)} | " +
                    "${fmt(ms(c.meanRuntimePerTurnNanos), 4)} | ${fmt(a.meanRuntimePerTurnMs, 4)} |",
            )
        }
        sb.appendLine()

        sb.appendLine("## Table G3 - SEM and 95% CI (Experimental B mean turns), and statistical distinguishability from Baseline A")
        sb.appendLine()
        sb.appendLine("| Player count | B mean turns | B SEM | B 95% CI | A mean turns | A SEM | z (B vs A) | Distinguishable from A? |")
        sb.appendLine("|---|---|---|---|---|---|---|---|")
        cohorts.forEach { c ->
            val a = BASELINE_A.first { it.playerCount == c.playerCount }
            val z = (c.meanTurns - a.meanTurns) / kotlin.math.sqrt(c.semTurns * c.semTurns + a.semTurns * a.semTurns)
            sb.appendLine(
                "| ${c.playerCount} | ${fmt(c.meanTurns)} | ${fmt(c.semTurns, 2)} | [${fmt(c.meanTurns - 1.96 * c.semTurns)}, ${fmt(c.meanTurns + 1.96 * c.semTurns)}] | " +
                    "${fmt(a.meanTurns)} | ${fmt(a.semTurns, 2)} | ${fmt(z, 2)} | ${if (kotlin.math.abs(z) > 1.96) "**Yes** (|z|>1.96)" else "No"} |",
            )
        }
        sb.appendLine()

        sb.appendLine("## Table G4 - Generation depth (deck regenerations per game)")
        sb.appendLine()
        sb.appendLine("| Player count | Mean generations/game | Median generations/game | Mean refill slots/game | Mean culled cards/game |")
        sb.appendLine("|---|---|---|---|---|")
        cohorts.forEach { c ->
            sb.appendLine("| ${c.playerCount} | ${fmt(c.meanGenerationCount)} | ${fmt(c.medianGenerationCount, 0)} | ${fmt(c.meanRefills, 2)} | ${fmt(c.meanCulls, 2)} |")
        }
        sb.appendLine()

        // --- correlation analysis: pooled across all player counts ---
        val allGames = cohorts.flatMap { it.games }
        val allTypeNames = allGames.flatMap { it.finalMultiplicity.keys }.toSet()
        val turnsList = allGames.map { it.turnsTaken.toDouble() }
        val correlations = allTypeNames.map { name ->
            val counts = allGames.map { (it.finalMultiplicity[name] ?: 0).toDouble() }
            name to pearson(counts, turnsList)
        }.sortedByDescending { it.second }

        sb.appendLine("## Table G5 - Card-type final-multiplicity vs. game-length correlation (pooled across all player counts, n=${allGames.size})")
        sb.appendLine()
        sb.appendLine("Pearson correlation between a card type's *final* multiplicity (at that game's last regeneration, or " +
            "Generation 0 if none occurred) and that game's total turn count. Correlational, not causal - from randomized " +
            "simulation, pooled across player counts (a confound: player count itself strongly affects both game length " +
            "and how many regenerations occur - see Table G4 - so a correlation here reflects both any direct card-type " +
            "effect AND this pooling confound; not separated further in this pass).")
        sb.appendLine()
        sb.appendLine("**Top 5 positively correlated with longer games:**")
        sb.appendLine()
        sb.appendLine("| Card | r |")
        sb.appendLine("|---|---|")
        correlations.take(5).forEach { (name, r) -> sb.appendLine("| $name | ${fmt(r, 3)} |") }
        sb.appendLine()
        sb.appendLine("**Top 5 negatively correlated (associated with shorter games):**")
        sb.appendLine()
        sb.appendLine("| Card | r |")
        sb.appendLine("|---|---|")
        correlations.takeLast(5).reversed().forEach { (name, r) -> sb.appendLine("| $name | ${fmt(r, 3)} |") }
        sb.appendLine()

        sb.appendLine("## Analysis")
        sb.appendLine()
        val byPc = cohorts.sortedBy { it.playerCount }
        val diffs = byPc.map { c -> c.playerCount to (c.meanTurns - BASELINE_A.first { it.playerCount == c.playerCount }.meanTurns) }
        val allShorter = diffs.all { it.second < 0 }
        val allLonger = diffs.all { it.second > 0 }
        val direction = when {
            allShorter -> "consistently SHORTENS games (every player count's mean turns is lower under Experimental B than Baseline A)"
            allLonger -> "consistently LENGTHENS games (every player count's mean turns is higher under Experimental B than Baseline A)"
            else -> "has a MIXED / player-count-dependent effect on mean game length (not uniformly shorter or longer across all 5 cohorts)"
        }
        sb.appendLine("1. **Direction and consistency of effect**: dynamic reincarnation $direction. Per-player-count " +
            "absolute mean-turns difference vs. Baseline A: " + diffs.joinToString(", ") { (pc, d) -> "${pc}P: ${fmt(d)}" } + ".")
        val meanPctDiffs = byPc.map { c -> val a = BASELINE_A.first { it.playerCount == c.playerCount }; (c.meanTurns - a.meanTurns) / a.meanTurns * 100 }
        sb.appendLine("2. **Player-count dependence**: percentage difference from Baseline A by player count - " +
            byPc.zip(meanPctDiffs).joinToString(", ") { (c, pct) -> "${c.playerCount}P: ${fmt(pct, 2)}%" } + ".")
        val medianDiffs = byPc.map { c -> c.medianTurns - BASELINE_A.first { it.playerCount == c.playerCount }.medianTurns }
        val p95Diffs = byPc.map { c -> c.p95Turns - BASELINE_A.first { it.playerCount == c.playerCount }.p95 }
        sb.appendLine("3. **Central tendency vs. tails**: median difference from Baseline A by player count - " +
            byPc.zip(medianDiffs).joinToString(", ") { (c, d) -> "${c.playerCount}P: ${fmt(d)}" } +
            "; p95 difference - " + byPc.zip(p95Diffs).joinToString(", ") { (c, d) -> "${c.playerCount}P: ${fmt(d)}" } +
            ". A p95 shift materially larger (in either direction) than the median shift at the same player count indicates " +
            "the mechanic is primarily reshaping the tail, not the typical game.")
        val capDiffs = byPc.map { c -> (c.capRate - BASELINE_A.first { it.playerCount == c.playerCount }.capRate) * 100 }
        sb.appendLine("4. **Cap-rate effect**: percentage-point difference in cap rate (hit the $MAX_TURNS_PER_GAME-turn cap without " +
            "a winner) vs. Baseline A - " + byPc.zip(capDiffs).joinToString(", ") { (c, d) -> "${c.playerCount}P: ${fmt(d, 2)}pp" } + ".")
        sb.appendLine("5. **Statistical distinguishability**: see Table G3 - the z-score column reports, per player count, whether " +
            "Experimental B's mean turns is distinguishable from Baseline A's given sampling noise (|z|>1.96), rather than " +
            "treating any nonzero observed difference as necessarily real.")
        sb.appendLine("6. **Generation depth**: mean regenerations/game by player count - " +
            byPc.joinToString(", ") { "${it.playerCount}P: ${fmt(it.meanGenerationCount)}" } + " (see Table G4). A mechanic that " +
            "only acts at recycle boundaries has more opportunity to influence sufficiently long games - higher-player-count " +
            "cohorts (longer games, per both Baseline A and this experimental run) see correspondingly more regenerations.")
        sb.appendLine("7. **Card types most associated with game length**: see Table G5 - reported as correlations from " +
            "randomized simulation, explicitly not causal claims, and explicitly confounded with player count (pooled " +
            "across cohorts rather than separated, in this pass).")
        sb.appendLine("8. **Correctness**: " +
            (if (violations.isEmpty()) "0 invariant violations across ${cohorts.sumOf { it.games.size }} experimental games (total-conservation, token-conservation, Phase-validity, and winner-square checks all held throughout; the fixed-composition per-card-name invariant was deliberately NOT applied to this experimental mode, per its own design)."
            else "**${violations.size} violation(s) found** - see raw detail. Any correctness failure supersedes the statistical analysis above; these must be root-caused before treating any duration/correlation finding as reliable."))
        sb.appendLine()
        sb.appendLine("No balance change, canon decision, or recommendation is made based on the above - this is a measurement " +
            "of the current candidate mechanic's effect, reported for a later, separate decision.")
        sb.appendLine()
        sb.appendLine("Total experimental games run: ${cohorts.sumOf { it.games.size }}. Total invariant violations: ${violations.size}.")

        return sb.toString()
    }
}
