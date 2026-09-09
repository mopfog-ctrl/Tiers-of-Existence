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
 * **PHASE 1C — experimental, NOT canonical.** Characterizes the effect of the "evidence-weighted
 * anti-stagnation" reshuffle rule ([ReincarnationConfig.ANTI_STAGNATION] —
 * [DynamicReincarnationRules] driven with [StagnationPressureConfig]) against BOTH Baseline A
 * (commit `d530a21`, `docs/benchmarks/player-count-benchmark.md`, fixed-composition
 * `PlainShuffle`) AND Phase 1B's own plain-evolution dynamic reincarnation (commit reported in
 * `docs/benchmarks/dynamic-reincarnation-benchmark.md`, hardcoded below as `BASELINE_B`) —
 * specifically to see whether progressively suppressing the card types Table G5 associated with
 * prolonged games counteracts Phase 1B's own tail-inflation (p95 blowout) finding while
 * preserving natural early-game deck evolution. Whether any of this should ever become canonical
 * is a separate, later decision this file does not make or recommend either way; it only
 * measures.
 *
 * **Generation-index threading**: unlike Phase 1B's [PlayerCountDynamicReincarnationBenchmarkTest]
 * (which calls [DynamicReincarnationRules.regenerate] with its own default `generationIndex = 0`
 * every time, since [ReincarnationConfig.DEFAULT] never reads it), this benchmark tracks a
 * per-game regeneration counter and passes it through explicitly — the exact mechanism
 * [StagnationPressureConfig.escalation] depends on to apply progressively stronger pressure at
 * later reshuffles while leaving a game's first reshuffle to evolve purely naturally.
 *
 * **Scale**: 1,000 games per player-count cohort (5,000 total), matching Phase 1B's own scale —
 * a screening characterization, not a canonical baseline needing Baseline A's 8,750-game rigor.
 *
 * Gated behind its own `toe.benchmark.antistagnation` system property (forwarded via
 * `engine/build.gradle.kts`'s `tasks.test` block) — skipped by default. Run via:
 * `./gradlew :engine:test --configure-on-demand -Dtoe.benchmark.antistagnation=true --tests
 * "com.tiersofexistence.engine.benchmark.PlayerCountAntiStagnationBenchmarkTest"`.
 */
class PlayerCountAntiStagnationBenchmarkTest {

    companion object {
        private const val MAX_TURNS_PER_GAME = 8000
        private const val GAMES_PER_COHORT = 1000
        private const val WARMUP_GAMES = 30
        private val PLAYER_COUNTS = listOf(2, 3, 4, 5, 6)

        // Independent of Baseline A's seed ranges (5.0e8+, 9.0e8+) and Phase 1B's (1.3e9+).
        private const val BASE_SEED = 1_700_000_000L
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

        /** Baseline A's own validated Stage 2 figures, commit `d530a21`. */
        private data class BaselineRow(
            val playerCount: Int, val meanTurns: Double, val semTurns: Double, val medianTurns: Double,
            val p90: Double, val p95: Double, val capRate: Double,
        )

        private val BASELINE_A = listOf(
            BaselineRow(2, 633.3, 11.43, 503.0, 1204.0, 1516.0, 0.000),
            BaselineRow(3, 926.9, 17.33, 724.0, 1774.0, 2207.0, 0.000),
            BaselineRow(4, 1286.2, 25.65, 1001.0, 2502.0, 3233.0, 0.001),
            BaselineRow(5, 1757.7, 36.81, 1291.0, 3621.0, 4745.0, 0.005),
            BaselineRow(6, 2419.2, 49.18, 1821.0, 5241.0, 6737.0, 0.027),
        )

        /** Phase 1B's own validated figures (plain, ungoverned dynamic reincarnation — no
         * anti-stagnation pressure), `docs/benchmarks/dynamic-reincarnation-benchmark.md`'s Table
         * G1/G3, hardcoded here rather than re-running Phase 1B (unchanged and already validated). */
        private val BASELINE_B = listOf(
            BaselineRow(2, 652.2, 18.92, 478.0, 1236.0, 1575.0, 0.000),
            BaselineRow(3, 988.9, 30.45, 688.0, 2007.0, 2956.0, 0.003),
            BaselineRow(4, 1572.3, 50.61, 1009.0, 3560.0, 4924.0, 0.020),
            BaselineRow(5, 2083.8, 66.81, 1213.0, 5738.0, 8000.0, 0.057),
            BaselineRow(6, 2614.0, 77.23, 1557.0, 8000.0, 8000.0, 0.104),
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
        val generationCount: Int,
        val totalRefills: Int,
        val totalCulls: Int,
        val finalMultiplicity: Map<String, Int>,
    )

    private data class CohortResult(val playerCount: Int, val games: List<GameMetrics>, val violations: List<InvariantViolation>) {
        val cappedCount get() = games.count { it.capped }
        val capRate get() = cappedCount.toDouble() / games.size
        val turns get() = games.map { it.turnsTaken }
        val meanTurns get() = turns.average()
        val stdDevTurns get() = stdDev(turns, meanTurns)
        val semTurns get() = sem(stdDevTurns, turns.size)
        val medianTurns get() = percentile(turns, 50.0)
        val p90Turns get() = percentile(turns, 90.0)
        val p95Turns get() = percentile(turns, 95.0)
        val meanGenerationCount get() = games.map { it.generationCount }.average()
        val medianGenerationCount get() = percentile(games.map { it.generationCount }, 50.0)
        val meanRefills get() = games.map { it.totalRefills }.average()
        val meanCulls get() = games.map { it.totalCulls }.average()
    }

    // --- deck construction: anti-stagnation strategy, generation-index tracked ---

    private data class DeckConstruction(
        val deck: FateHarvestDeck,
        val expectedTotal: Int,
        val events: MutableList<RegenerationEvent>,
    )

    private fun colorCardNamesFor(colors: Set<PlayerColor>): Set<String> = colors.flatMap { FateHarvestCatalog.colorCards[it].orEmpty() }.map { it.name }.toSet()

    private fun buildAntiStagnationDeckForColors(colors: List<PlayerColor>, random: Random, players: Map<PlayerColor, PlayerState>): DeckConstruction {
        val unusedColors = PlayerColor.entries.filterNot { it in colors }.toSet()
        val removedNames = colorCardNamesFor(unusedColors)
        val eligibleTypes = FateHarvestCatalog.all.filter { it.name !in removedNames }
        val filtered = FateHarvestCatalog.buildDeck().filter { it.name !in removedNames }
        val shuffled = filtered.shuffled(random)

        val events = mutableListOf<RegenerationEvent>()
        var generationIndex = 0
        val strategy = FateHarvestDeck.ReshuffleStrategy { discardPile, rnd ->
            // players' own PlayerState objects are the ones GameState.players ends up holding
            // (mutated in place) - live hand counts are always current at reshuffle time, and the
            // draw pile is guaranteed empty whenever a reshuffle fires, so hands are the entire
            // "outside the discard pile" population for the whole-game rarity ceiling.
            val handCounts = players.values.flatMap { it.hand }.groupingBy { it.name }.eachCount()
            val result = DynamicReincarnationRules.regenerate(discardPile, eligibleTypes, rnd, ReincarnationConfig.ANTI_STAGNATION, generationIndex, handCounts)
            events += RegenerationEvent(generationIndex + 1, result.targetSize, result.refillCount, result.cullCount, result.finalMultiplicity)
            generationIndex += 1
            result.regeneratedPile
        }

        return DeckConstruction(
            deck = FateHarvestDeck.forTesting(shuffled, random, strategy),
            expectedTotal = filtered.size,
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
        val deckConstruction = buildAntiStagnationDeckForColors(colors, random, players)
        val state = GameState(players = players, turnOrder = turnOrder, deck = deckConstruction.deck)

        val decisionsByPlayer: Map<PlayerColor, com.tiersofexistence.engine.rules.TurnDecisionProvider> =
            colors.associateWith { RandomLegalDecisionProvider(Random(random.nextLong())) }
        val driver = TurnDriver(decisionsByPlayer, rollForPhase = { phase -> Dice.rollForPhase(phase, random) })

        var turnsTaken = 0
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

        val events = deckConstruction.events
        val metrics = GameMetrics(
            playerCount = playerCount,
            seed = seed,
            turnsTaken = turnsTaken,
            roundsTaken = state.roundNumber,
            completed = completed,
            capped = !completed && violation == null,
            generationCount = events.size,
            totalRefills = events.sumOf { it.refillCount },
            totalCulls = events.sumOf { it.cullCount },
            finalMultiplicity = events.lastOrNull()?.finalMultiplicity ?: emptyMap(),
        )
        return metrics to violation
    }

    /** Same shape as [PlayerCountDynamicReincarnationBenchmarkTest]'s own — deliberately
     * duplicated, not shared, per this codebase's established convention for independent
     * benchmark harnesses. Checks total card conservation (never per-card-name multiplicity,
     * which is expected to evolve under this mode too), token conservation, Phase validity, no
     * dangling pending roll, and winner-square correctness. */
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
    fun `experimental anti-stagnation reincarnation benchmark, compared against Baseline A and Phase 1B`() {
        assumeTrue(
            System.getProperty("toe.benchmark.antistagnation") == "true",
            "Skipped by default (experimental, heavy) - run with -Dtoe.benchmark.antistagnation=true to execute.",
        )

        repeat(WARMUP_GAMES) { i -> runOneGame(PLAYER_COUNTS[i % PLAYER_COUNTS.size], seedFor(-1, i) - 1_000_000_000L, i) }

        val cohorts = PLAYER_COUNTS.map { pc -> runCohort(pc, GAMES_PER_COHORT) }
        val allViolations = cohorts.flatMap { it.violations }

        val report = buildReport(cohorts, allViolations)
        val reportFile = File("../docs/benchmarks/anti-stagnation-benchmark.md")
        reportFile.parentFile.mkdirs()
        reportFile.writeText(report)
        println(report)

        check(allViolations.isEmpty()) {
            "${allViolations.size} invariant violation(s):\n" + allViolations.joinToString("\n---\n") { it.message ?: it.toString() }
        }
    }

    private fun fmt(d: Double, digits: Int = 1) = "%.${digits}f".format(d)

    private fun buildReport(cohorts: List<CohortResult>, violations: List<InvariantViolation>): String {
        val sb = StringBuilder()
        sb.appendLine("# T.O.E. Experimental Anti-Stagnation Fate Harvest Reincarnation Benchmark (Phase 1C)")
        sb.appendLine()
        sb.appendLine("**Experimental, not canonical.** Compares the evidence-weighted anti-stagnation reshuffle rule " +
            "(`ReincarnationConfig.ANTI_STAGNATION`) against both Baseline A (commit `d530a21`, " +
            "`docs/benchmarks/player-count-benchmark.md`) and Phase 1B's own plain dynamic reincarnation " +
            "(`docs/benchmarks/dynamic-reincarnation-benchmark.md`). No balance or canon decision is made or " +
            "recommended by this report - it measures effects only, per the task's own explicit scope.")
        sb.appendLine()
        sb.appendLine("Scale: $GAMES_PER_COHORT games/cohort x 5 player counts = ${GAMES_PER_COHORT * 5} total games, single stage, " +
            "seed = $BASE_SEED + playerCount * $PLAYER_COUNT_SEED_STRIDE + gameIndex (independent of Baseline A's and Phase 1B's own seed ranges).")
        sb.appendLine()
        sb.appendLine("## Mechanism")
        sb.appendLine()
        sb.appendLine(
            "- **Same underlying transition/refill/cull rules as Phase 1B** " +
                "(`DynamicReincarnationRules`), with `ReincarnationConfig.stagnationPressure` set to " +
                "`StagnationPressureConfig.DEFAULT`.\n" +
                "- **Evidence-weighted suppression**: only card types Table G5 positively correlates with longer " +
                "games (Radiation Burst r=0.408, Graviton Rift r=0.267, Fluidic Wave r=0.254, Materialize Army " +
                "r=0.172, Parallel Phasing r=0.158) are ever suppressed, proportionally to their own correlation " +
                "strength - every negatively-correlated card (associated with shorter games) and every card with " +
                "no measured effect is left completely untouched, never suppressed merely for becoming common.\n" +
                "- **Multiplicity-state targeting**: suppression acts specifically on a weighted card's own " +
                "upward-count transitions (becoming more abundant) - shifting that probability mass toward its " +
                "downward transitions - and, in the 4+-copy bucket (which has no upward outcome to begin with), " +
                "boosts the existing drop-by-one probability instead.\n" +
                "- **Escalation across successive reshuffles**: a linear ramp from 0.0 pressure at a game's very " +
                "first reshuffle (pure natural evolution, exactly as asked) to full weighted pressure by roughly " +
                "the 7th reshuffle of that same game - the generation counter is tracked per game and threaded " +
                "through every regeneration event.",
        )
        sb.appendLine()

        sb.appendLine("## Table H1 - Anti-Stagnation (C) vs. Baseline A vs. Phase 1B (B) - game length")
        sb.appendLine()
        sb.appendLine("| Player count | C mean turns | B mean turns | A mean turns | C vs A % | C vs B % | C median | B median | A median | C p95 | B p95 | A p95 | C cap rate | B cap rate | A cap rate |")
        sb.appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        cohorts.forEach { c ->
            val a = BASELINE_A.first { it.playerCount == c.playerCount }
            val b = BASELINE_B.first { it.playerCount == c.playerCount }
            val vsA = (c.meanTurns - a.meanTurns) / a.meanTurns * 100
            val vsB = (c.meanTurns - b.meanTurns) / b.meanTurns * 100
            sb.appendLine(
                "| ${c.playerCount} | ${fmt(c.meanTurns)} | ${fmt(b.meanTurns)} | ${fmt(a.meanTurns)} | ${fmt(vsA, 2)}% | ${fmt(vsB, 2)}% | " +
                    "${fmt(c.medianTurns, 0)} | ${fmt(b.medianTurns, 0)} | ${fmt(a.medianTurns, 0)} | " +
                    "${fmt(c.p95Turns, 0)} | ${fmt(b.p95)} | ${fmt(a.p95)} | " +
                    "${fmt(c.capRate * 100, 2)}% | ${fmt(b.capRate * 100, 2)}% | ${fmt(a.capRate * 100, 2)}% |",
            )
        }
        sb.appendLine()

        sb.appendLine("## Table H2 - SEM and statistical distinguishability of C from A and from B")
        sb.appendLine()
        sb.appendLine("| Player count | C mean turns | C SEM | z (C vs A) | Distinguishable from A? | z (C vs B) | Distinguishable from B? |")
        sb.appendLine("|---|---|---|---|---|---|---|")
        cohorts.forEach { c ->
            val a = BASELINE_A.first { it.playerCount == c.playerCount }
            val b = BASELINE_B.first { it.playerCount == c.playerCount }
            val zA = (c.meanTurns - a.meanTurns) / kotlin.math.sqrt(c.semTurns * c.semTurns + a.semTurns * a.semTurns)
            val zB = (c.meanTurns - b.meanTurns) / kotlin.math.sqrt(c.semTurns * c.semTurns + b.semTurns * b.semTurns)
            sb.appendLine(
                "| ${c.playerCount} | ${fmt(c.meanTurns)} | ${fmt(c.semTurns, 2)} | ${fmt(zA, 2)} | " +
                    "${if (kotlin.math.abs(zA) > 1.96) "**Yes**" else "No"} | ${fmt(zB, 2)} | " +
                    "${if (kotlin.math.abs(zB) > 1.96) "**Yes**" else "No"} |",
            )
        }
        sb.appendLine()

        sb.appendLine("## Table H3 - Generation depth (deck regenerations per game)")
        sb.appendLine()
        sb.appendLine("| Player count | Mean generations/game | Median generations/game | Mean refill slots/game | Mean culled cards/game |")
        sb.appendLine("|---|---|---|---|---|")
        cohorts.forEach { c ->
            sb.appendLine("| ${c.playerCount} | ${fmt(c.meanGenerationCount)} | ${fmt(c.medianGenerationCount, 0)} | ${fmt(c.meanRefills, 2)} | ${fmt(c.meanCulls, 2)} |")
        }
        sb.appendLine()

        sb.appendLine("## Analysis")
        sb.appendLine()
        val byPc = cohorts.sortedBy { it.playerCount }
        val diffsVsB = byPc.map { c -> c.playerCount to (c.meanTurns - BASELINE_B.first { it.playerCount == c.playerCount }.meanTurns) }
        val allShorterThanB = diffsVsB.all { it.second < 0 }
        val allLongerThanB = diffsVsB.all { it.second > 0 }
        val directionVsB = when {
            allShorterThanB -> "consistently SHORTENS games relative to Phase 1B's plain evolution (every player count's mean turns is lower under Anti-Stagnation than Phase 1B)"
            allLongerThanB -> "consistently LENGTHENS games relative to Phase 1B's plain evolution (every player count's mean turns is higher under Anti-Stagnation than Phase 1B)"
            else -> "has a MIXED / player-count-dependent effect relative to Phase 1B's plain evolution (not uniformly shorter or longer across all 5 cohorts)"
        }
        sb.appendLine("1. **Effect on Phase 1B's own tail-inflation**: Anti-Stagnation $directionVsB. Per-player-count " +
            "absolute mean-turns difference vs. Phase 1B: " + diffsVsB.joinToString(", ") { (pc, d) -> "${pc}P: ${fmt(d)}" } + ".")
        val p95DiffsVsB = byPc.map { c -> c.p95Turns - BASELINE_B.first { it.playerCount == c.playerCount }.p95 }
        sb.appendLine("2. **Tail effect specifically (p95)**: p95 difference vs. Phase 1B by player count - " +
            byPc.zip(p95DiffsVsB).joinToString(", ") { (c, d) -> "${c.playerCount}P: ${fmt(d)}" } +
            ". A negative shift here indicates the mechanism is successfully compressing Phase 1B's own runaway-long-game tail; " +
            "the mechanism's whole design intent is to act preferentially on this tail (deeper games mean more regenerations, " +
            "which means more escalated pressure), so this is the single most direct test of whether it worked as designed.")
        val diffsVsA = byPc.map { c -> c.playerCount to (c.meanTurns - BASELINE_A.first { it.playerCount == c.playerCount }.meanTurns) }
        sb.appendLine("3. **Comparison against unmodified Baseline A**: per-player-count absolute mean-turns difference vs. " +
            "Baseline A - " + diffsVsA.joinToString(", ") { (pc, d) -> "${pc}P: ${fmt(d)}" } + " (Baseline A never uses any " +
            "reincarnation mechanic at all, so this shows the net effect of dynamic reincarnation-plus-suppression together, " +
            "not the suppression's effect in isolation - see point 1/2 above for that).")
        val capDiffsVsB = byPc.map { c -> (c.capRate - BASELINE_B.first { it.playerCount == c.playerCount }.capRate) * 100 }
        sb.appendLine("4. **Cap-rate effect vs. Phase 1B**: percentage-point difference in cap rate (hit the " +
            "$MAX_TURNS_PER_GAME-turn cap without a winner) - " + byPc.zip(capDiffsVsB).joinToString(", ") { (c, d) -> "${c.playerCount}P: ${fmt(d, 2)}pp" } +
            ". A negative value at a given player count means fewer games under Anti-Stagnation hit the cap than under Phase 1B's plain evolution.")
        sb.appendLine("5. **Statistical distinguishability**: see Table H2 - z-scores against both Baseline A and Phase 1B, " +
            "rather than treating any nonzero observed difference as necessarily real.")
        sb.appendLine("6. **Generation depth**: mean regenerations/game by player count - " +
            byPc.joinToString(", ") { "${it.playerCount}P: ${fmt(it.meanGenerationCount)}" } + " (see Table H3) - compare against " +
            "Phase 1B's own Table G4 figures to see whether suppression changed how often regeneration itself happens, not just its outcome.")
        sb.appendLine("7. **Correctness**: " +
            (if (violations.isEmpty()) "0 invariant violations across ${cohorts.sumOf { it.games.size }} experimental games (total-conservation, token-conservation, Phase-validity, and winner-square checks all held throughout; the fixed-composition per-card-name invariant was deliberately NOT applied to this experimental mode, matching Phase 1B's own design)."
            else "**${violations.size} violation(s) found** - see raw detail. Any correctness failure supersedes the statistical analysis above; these must be root-caused before treating any duration finding as reliable."))
        sb.appendLine()
        sb.appendLine("No balance change, canon decision, or recommendation is made based on the above - this is a measurement " +
            "of the current candidate mechanic's effect, reported for a later, separate decision.")
        sb.appendLine()
        sb.appendLine("Total experimental games run: ${cohorts.sumOf { it.games.size }}. Total invariant violations: ${violations.size}.")

        return sb.toString()
    }
}
