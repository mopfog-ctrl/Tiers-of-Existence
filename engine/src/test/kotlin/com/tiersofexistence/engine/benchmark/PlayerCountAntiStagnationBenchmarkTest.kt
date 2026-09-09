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
 * **PHASE 1C — experimental, NOT canonical.** Characterizes the effect of the evidence-weighted
 * anti-stagnation reshuffle rule ([FateHarvestRegenerationConfig.ANTI_STAGNATION] —
 * [FateHarvestRegenerationRules] driven with [StagnationPressureConfig], whose
 * [StagnationPressureConfig.CORRECTED_BASELINE_STAGNATION_WEIGHTS] are now real, derived from the
 * clean corrected-model run below) against **the clean corrected-model baseline as the control**
 * (`PlayerCountFateHarvestRegenerationCorrectedBenchmarkTest`, `docs/benchmarks/fate-harvest-
 * regeneration-benchmark-corrected.md`, 5,000 games, 0 violations — hardcoded below as
 * `CORRECTED_BASELINE`, per the user's own explicit instruction to treat that run as the control),
 * with unmodified Baseline A (commit `d530a21`, `docs/benchmarks/player-count-benchmark.md`,
 * fixed-composition `PlainShuffle`, no regeneration mechanic at all) shown alongside for
 * additional context only. This measures whether progressively suppressing the card types the
 * corrected baseline's own Table K5 positively correlates with longer games shifts game length
 * relative to that same corrected model with the suppression turned off. Neither this file nor the
 * weight derivation it depends on alters the regeneration semantics or rarity ceilings
 * [FateHarvestRegenerationRules]/[FateHarvestRegenerationConfig] already implement — only
 * [StagnationPressureConfig]'s own suppression weights are new. Whether any of this should ever
 * become canonical is a separate, later decision this file does not make or recommend either way;
 * it only measures.
 *
 * **Generation-index threading**: unlike Phase 1B's [PlayerCountFateHarvestRegenerationBenchmarkTest]
 * (which calls [FateHarvestRegenerationRules.regenerate] with its own default `generationIndex = 0`
 * every time, since [FateHarvestRegenerationConfig.DEFAULT] never reads it), this benchmark tracks a
 * per-game regeneration counter and passes it through explicitly — the exact mechanism
 * [StagnationPressureConfig.escalation] depends on to apply progressively stronger pressure at
 * later reshuffles while leaving a game's first reshuffle to evolve purely naturally.
 *
 * **Scale**: 1,000 games per player-count cohort (5,000 total), matching the corrected baseline's
 * own Phase 1B-derived scale — a screening characterization, not a canonical baseline needing
 * Baseline A's 8,750-game rigor.
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

        // Independent of Baseline A's seed ranges (5.0e8+, 9.0e8+), Phase 1B's (1.3e9+), and the
        // corrected-model control's own (2.1e9+).
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

        /** **The control for this benchmark**, per the user's own explicit instruction — the clean
         * corrected-model baseline's own validated figures (rarity-ceiling-respecting regeneration,
         * `FateHarvestRegenerationConfig.DEFAULT`, no anti-stagnation pressure at all):
         * `PlayerCountFateHarvestRegenerationCorrectedBenchmarkTest`, `BASE_SEED = 2_100_000_000L`,
         * 5,000 games, 0 invariant violations, `docs/benchmarks/fate-harvest-regeneration-
         * benchmark-corrected.md`'s own Tables K1/K3, hardcoded here rather than re-running that
         * benchmark (unchanged and already validated). Superseded name `BASELINE_B` (the original,
         * uncapped Phase 1B model's own figures) no longer applies — this benchmark now compares
         * against the corrected model specifically, not the superseded uncapped one. */
        private val CORRECTED_BASELINE = listOf(
            BaselineRow(2, 669.0, 15.25, 535.0, 1265.0, 1601.0, 0.000),
            BaselineRow(3, 869.7, 19.67, 705.0, 1674.0, 2164.0, 0.000),
            BaselineRow(4, 1327.3, 31.51, 1067.0, 2635.0, 3416.0, 0.000),
            BaselineRow(5, 1776.9, 42.69, 1421.0, 3578.0, 4544.0, 0.004),
            BaselineRow(6, 2424.4, 61.14, 1796.0, 5373.0, 6888.0, 0.031),
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
        /** Must be called with the real [GameState] right after it's constructed - see
         * [FateHarvestRegenerationRules]'s own callers for why this indirection exists. */
        val bindState: (GameState) -> Unit,
    )

    private fun colorCardNamesFor(colors: Set<PlayerColor>): Set<String> = colors.flatMap { FateHarvestCatalog.colorCards[it].orEmpty() }.map { it.name }.toSet()

    private fun buildAntiStagnationDeckForColors(colors: List<PlayerColor>, random: Random): DeckConstruction {
        val unusedColors = PlayerColor.entries.filterNot { it in colors }.toSet()
        val removedNames = colorCardNamesFor(unusedColors)
        val eligibleTypes = FateHarvestCatalog.all.filter { it.name !in removedNames }
        val filtered = FateHarvestCatalog.buildDeck().filter { it.name !in removedNames }
        val shuffled = filtered.shuffled(random)

        val events = mutableListOf<RegenerationEvent>()
        var generationIndex = 0
        lateinit var stateRef: GameState
        val strategy = FateHarvestDeck.ReshuffleStrategy { discardPile, rnd ->
            val result = FateHarvestRegenerationRules.regenerate(discardPile, eligibleTypes, rnd, FateHarvestRegenerationConfig.ANTI_STAGNATION, generationIndex, stateRef.liveCardCountsOutsideDiscardPile())
            events += RegenerationEvent(generationIndex + 1, result.targetSize, result.refillCount, result.cullCount, result.finalMultiplicity)
            generationIndex += 1
            result.regeneratedPile
        }

        return DeckConstruction(
            deck = FateHarvestDeck.forTesting(shuffled, random, strategy),
            expectedTotal = filtered.size,
            events = events,
            bindState = { stateRef = it },
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
        val deckConstruction = buildAntiStagnationDeckForColors(colors, random)
        val state = GameState(players = players, turnOrder = turnOrder, deck = deckConstruction.deck)
        deckConstruction.bindState(state)

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

    /** Same shape as [PlayerCountFateHarvestRegenerationBenchmarkTest]'s own — deliberately
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
    fun `experimental anti-stagnation benchmark, compared against the clean corrected baseline (control) and Baseline A`() {
        assumeTrue(
            System.getProperty("toe.benchmark.antistagnation") == "true",
            "Skipped by default (experimental, heavy) - run with -Dtoe.benchmark.antistagnation=true to execute.",
        )

        repeat(WARMUP_GAMES) { i -> runOneGame(PLAYER_COUNTS[i % PLAYER_COUNTS.size], seedFor(-1, i) - 1_000_000_000L, i) }

        val cohorts = PLAYER_COUNTS.map { pc -> runCohort(pc, GAMES_PER_COHORT) }
        val allViolations = cohorts.flatMap { it.violations }

        val report = buildReport(cohorts, allViolations)
        val reportFile = File("../docs/benchmarks/anti-stagnation-benchmark-corrected.md")
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
        sb.appendLine("# T.O.E. Experimental Anti-Stagnation Fate Harvest Regeneration Benchmark (Phase 1C, vs. corrected baseline)")
        sb.appendLine()
        sb.appendLine("**Experimental, not canonical.** Compares the evidence-weighted anti-stagnation reshuffle rule " +
            "(`FateHarvestRegenerationConfig.ANTI_STAGNATION`) against **the clean corrected-model baseline as the " +
            "control** (`PlayerCountFateHarvestRegenerationCorrectedBenchmarkTest`, `docs/benchmarks/fate-harvest-" +
            "regeneration-benchmark-corrected.md`, 5,000 games, 0 violations, hardcoded below as `CORRECTED_BASELINE`), " +
            "with unmodified Baseline A (commit `d530a21`, `docs/benchmarks/player-count-benchmark.md`) shown alongside " +
            "for additional context only. Neither `FateHarvestRegenerationRules`'s transition/refill/cull mechanics nor " +
            "the whole-game rarity ceiling are altered here - only `StagnationPressureConfig`'s evidence-derived " +
            "suppression weights (see `FateHarvestRegenerationRules.kt`'s own class doc for their derivation from the " +
            "corrected baseline's full Table K5) are new. No balance or canon decision is made or recommended by this " +
            "report - it measures effects only, per the task's own explicit scope.")
        sb.appendLine()
        sb.appendLine("Scale: $GAMES_PER_COHORT games/cohort x 5 player counts = ${GAMES_PER_COHORT * 5} total games, single stage, " +
            "seed = $BASE_SEED + playerCount * $PLAYER_COUNT_SEED_STRIDE + gameIndex (independent of Baseline A's and the corrected baseline's own seed ranges).")
        sb.appendLine()
        sb.appendLine("## Mechanism")
        sb.appendLine()
        sb.appendLine(
            "- **Same underlying transition/refill/cull rules and whole-game rarity ceiling as the corrected-model " +
                "control** (`FateHarvestRegenerationRules`, `FateHarvestRegenerationConfig.DEFAULT`), with " +
                "`FateHarvestRegenerationConfig.stagnationPressure` additionally set to `StagnationPressureConfig.DEFAULT` " +
                "- the only difference from the control.\n" +
                "- **Evidence-weighted suppression, derived from the corrected baseline's own full Table K5** (all 32 " +
                "card types, not an excerpt): every card type in the deck showed a positive pooled correlation with " +
                "game length on this clean data (r = 0.189 to 0.368, none negative - see " +
                "`StagnationPressureConfig`'s own class doc for that finding and the likely base-rate confound behind " +
                "it), so `StagnationPressureConfig.CORRECTED_BASELINE_STAGNATION_WEIGHTS` suppresses every card type, " +
                "each proportionally to its own measured correlation strength - the strongest (Graviton Rift, 0.368) " +
                "gets roughly double the pressure of the weakest (Corpuscle Rot, 0.189). Had any card shown a negative " +
                "correlation (associated with shorter games) or no measured correlation at all, it would default to " +
                "weight 0.0 and never be suppressed regardless of how common it becomes - that carve-out is still " +
                "implemented, it simply never fires on this data.\n" +
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

        sb.appendLine("## Table H1 - Anti-Stagnation (C) vs. corrected-baseline control (Control) vs. Baseline A - game length")
        sb.appendLine()
        sb.appendLine("| Player count | C mean turns | Control mean turns | A mean turns | C vs Control % | C vs A % | C median | Control median | A median | C p95 | Control p95 | A p95 | C cap rate | Control cap rate | A cap rate |")
        sb.appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        cohorts.forEach { c ->
            val a = BASELINE_A.first { it.playerCount == c.playerCount }
            val control = CORRECTED_BASELINE.first { it.playerCount == c.playerCount }
            val vsControl = (c.meanTurns - control.meanTurns) / control.meanTurns * 100
            val vsA = (c.meanTurns - a.meanTurns) / a.meanTurns * 100
            sb.appendLine(
                "| ${c.playerCount} | ${fmt(c.meanTurns)} | ${fmt(control.meanTurns)} | ${fmt(a.meanTurns)} | ${fmt(vsControl, 2)}% | ${fmt(vsA, 2)}% | " +
                    "${fmt(c.medianTurns, 0)} | ${fmt(control.medianTurns, 0)} | ${fmt(a.medianTurns, 0)} | " +
                    "${fmt(c.p95Turns, 0)} | ${fmt(control.p95)} | ${fmt(a.p95)} | " +
                    "${fmt(c.capRate * 100, 2)}% | ${fmt(control.capRate * 100, 2)}% | ${fmt(a.capRate * 100, 2)}% |",
            )
        }
        sb.appendLine()

        sb.appendLine("## Table H2 - SEM and statistical distinguishability of C from the corrected-baseline control and from Baseline A")
        sb.appendLine()
        sb.appendLine("| Player count | C mean turns | C SEM | z (C vs Control) | Distinguishable from Control? | z (C vs A) | Distinguishable from A? |")
        sb.appendLine("|---|---|---|---|---|---|---|")
        cohorts.forEach { c ->
            val a = BASELINE_A.first { it.playerCount == c.playerCount }
            val control = CORRECTED_BASELINE.first { it.playerCount == c.playerCount }
            val zControl = (c.meanTurns - control.meanTurns) / kotlin.math.sqrt(c.semTurns * c.semTurns + control.semTurns * control.semTurns)
            val zA = (c.meanTurns - a.meanTurns) / kotlin.math.sqrt(c.semTurns * c.semTurns + a.semTurns * a.semTurns)
            sb.appendLine(
                "| ${c.playerCount} | ${fmt(c.meanTurns)} | ${fmt(c.semTurns, 2)} | ${fmt(zControl, 2)} | " +
                    "${if (kotlin.math.abs(zControl) > 1.96) "**Yes**" else "No"} | ${fmt(zA, 2)} | " +
                    "${if (kotlin.math.abs(zA) > 1.96) "**Yes**" else "No"} |",
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
        val diffsVsControl = byPc.map { c -> c.playerCount to (c.meanTurns - CORRECTED_BASELINE.first { it.playerCount == c.playerCount }.meanTurns) }
        val allShorterThanControl = diffsVsControl.all { it.second < 0 }
        val allLongerThanControl = diffsVsControl.all { it.second > 0 }
        val directionVsControl = when {
            allShorterThanControl -> "consistently SHORTENS games relative to the corrected-baseline control (every player count's mean turns is lower under Anti-Stagnation than the control)"
            allLongerThanControl -> "consistently LENGTHENS games relative to the corrected-baseline control (every player count's mean turns is higher under Anti-Stagnation than the control)"
            else -> "has a MIXED / player-count-dependent effect relative to the corrected-baseline control (not uniformly shorter or longer across all 5 cohorts)"
        }
        sb.appendLine("1. **Effect vs. the corrected-baseline control**: Anti-Stagnation $directionVsControl. Per-player-count " +
            "absolute mean-turns difference vs. the control: " + diffsVsControl.joinToString(", ") { (pc, d) -> "${pc}P: ${fmt(d)}" } + ".")
        val p95DiffsVsControl = byPc.map { c -> c.p95Turns - CORRECTED_BASELINE.first { it.playerCount == c.playerCount }.p95 }
        sb.appendLine("2. **Tail effect specifically (p95)**: p95 difference vs. the corrected-baseline control by player count - " +
            byPc.zip(p95DiffsVsControl).joinToString(", ") { (c, d) -> "${c.playerCount}P: ${fmt(d)}" } +
            ". A negative shift here indicates the mechanism is successfully compressing the control's own long-game tail; " +
            "the mechanism's whole design intent is to act preferentially on this tail (deeper games mean more regenerations, " +
            "which means more escalated pressure), so this is the single most direct test of whether it worked as designed.")
        val diffsVsA = byPc.map { c -> c.playerCount to (c.meanTurns - BASELINE_A.first { it.playerCount == c.playerCount }.meanTurns) }
        sb.appendLine("3. **Comparison against unmodified Baseline A**: per-player-count absolute mean-turns difference vs. " +
            "Baseline A - " + diffsVsA.joinToString(", ") { (pc, d) -> "${pc}P: ${fmt(d)}" } + " (Baseline A never uses any " +
            "regeneration mechanic at all, so this shows the net effect of rarity-ceiling regeneration-plus-suppression " +
            "together, not the suppression's effect in isolation - see point 1/2 above for that).")
        val capDiffsVsControl = byPc.map { c -> (c.capRate - CORRECTED_BASELINE.first { it.playerCount == c.playerCount }.capRate) * 100 }
        sb.appendLine("4. **Cap-rate effect vs. the corrected-baseline control**: percentage-point difference in cap rate (hit the " +
            "$MAX_TURNS_PER_GAME-turn cap without a winner) - " + byPc.zip(capDiffsVsControl).joinToString(", ") { (c, d) -> "${c.playerCount}P: ${fmt(d, 2)}pp" } +
            ". A negative value at a given player count means fewer games under Anti-Stagnation hit the cap than under the control.")
        sb.appendLine("5. **Statistical distinguishability**: see Table H2 - z-scores against both the corrected-baseline control and Baseline A, " +
            "rather than treating any nonzero observed difference as necessarily real.")
        sb.appendLine("6. **Generation depth**: mean regenerations/game by player count - " +
            byPc.joinToString(", ") { "${it.playerCount}P: ${fmt(it.meanGenerationCount)}" } + " (see Table H3) - compare against " +
            "the corrected baseline's own Table K4 figures to see whether suppression changed how often regeneration itself happens, not just its outcome.")
        sb.appendLine("7. **Correctness**: " +
            (if (violations.isEmpty()) "0 invariant violations across ${cohorts.sumOf { it.games.size }} experimental games (total-conservation, token-conservation, Phase-validity, and winner-square checks all held throughout; the fixed-composition per-card-name invariant was deliberately NOT applied to this experimental mode, since per-type multiplicity evolving is the whole point of both the corrected model and this suppression variant of it)."
            else "**${violations.size} violation(s) found** - see raw detail. Any correctness failure supersedes the statistical analysis above; these must be root-caused before treating any duration finding as reliable."))
        sb.appendLine()
        sb.appendLine("No balance change, canon decision, or recommendation is made based on the above - this is a measurement " +
            "of the current candidate mechanic's effect, reported for a later, separate decision.")
        sb.appendLine()
        sb.appendLine("Total experimental games run: ${cohorts.sumOf { it.games.size }}. Total invariant violations: ${violations.size}.")

        return sb.toString()
    }
}
