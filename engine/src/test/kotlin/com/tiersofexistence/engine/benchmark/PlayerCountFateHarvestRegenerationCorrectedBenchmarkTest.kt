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
 * **PHASE 1B, CORRECTED MODEL — experimental, NOT canonical.** Re-establishes Phase 1B's own
 * Fate Harvest regeneration baseline under the corrected whole-game rarity-ceiling model (see
 * `FateHarvestRegenerationRules`'s own class doc) using [FateHarvestRegenerationConfig.DEFAULT] —
 * no anti-stagnation pressure, same transition/refill/cull rules the original Phase 1B run used,
 * now genuinely respecting each card type's own canonical rarity as a hard ceiling across the
 * *whole live game* — draw pile + discard pile + every hand + every currently-resolving card
 * ([GameState.resolvingCards]) — not merely within the discard pile being regenerated.
 *
 * **This is the second attempt at this corrected-model baseline, from clean seeds, after fixing a
 * real defect the first attempt found.** The first run
 * (`docs/benchmarks/dynamic-reincarnation-benchmark-corrected-diagnostic.md`, preserved as
 * diagnostic record, not reused as weighting evidence) found 73 whole-game-rarity-ceiling
 * violations across 5,000 games — not a flaw in the ceiling model itself, but a genuine engine
 * accounting gap: a drawn `CardTiming.IMMEDIATE` card was invisible to the "outside the discard
 * pile" count while it was still being resolved (a local variable in whatever code was resolving
 * it, not tracked in any zone at all), so a reshuffle that happened to fire mid-resolution could
 * undercount that card's type and let it exceed its own rarity once the in-flight card was later
 * discarded. Fixed (Direction 1, the user's own explicit choice: fix the state representation, not
 * gameplay resolution order) by adding [GameState.resolvingCards] as a genuine fourth card zone —
 * see that property's own class doc for the full design — and this benchmark now reads
 * [GameState.liveCardCountsOutsideDiscardPile] (hands *and* resolving cards) instead of hand
 * counts alone.
 *
 * **Why this file exists rather than re-running [PlayerCountFateHarvestRegenerationBenchmarkTest]
 * itself**: that file's own report (`docs/benchmarks/dynamic-reincarnation-benchmark.md`) is
 * preserved, unmodified, explicit historical record of the *original, uncapped* model — the user
 * ruled it "remains valuable evidence about the earlier uncapped experimental model, but it is not
 * the baseline for the corrected rarity-preserving model," and must never be silently overwritten.
 * This file duplicates the harness instead (this codebase's established convention for independent
 * benchmark variants) so every report stays coexisting and clearly attributed to its own model/run.
 *
 * **Only a genuinely clean run of this file (0 whole-game-rarity-ceiling violations) may supply
 * evidence for Phase 1C's own weights** ([StagnationPressureConfig.CORRECTED_BASELINE_STAGNATION_WEIGHTS])
 * — per the user's own explicit instruction, this file's job is strictly to establish and report
 * that clean baseline; deriving or applying weights from it is a separate, later step this file
 * does not do itself.
 *
 * **Scale**: 1,000 games per player-count cohort (5,000 total), matching the original Phase 1B
 * run's own scale for a like-for-like comparison.
 *
 * Gated behind its own `toe.benchmark.dynamic.corrected` system property (forwarded via
 * `engine/build.gradle.kts`'s `tasks.test` block) — skipped by default. Run via:
 * `./gradlew :engine:test --configure-on-demand -Dtoe.benchmark.dynamic.corrected=true --tests
 * "com.tiersofexistence.engine.benchmark.PlayerCountFateHarvestRegenerationCorrectedBenchmarkTest"`.
 */
class PlayerCountFateHarvestRegenerationCorrectedBenchmarkTest {

    companion object {
        private const val MAX_TURNS_PER_GAME = 8000
        private const val GAMES_PER_COHORT = 1000
        private const val WARMUP_GAMES = 30
        private val PLAYER_COUNTS = listOf(2, 3, 4, 5, 6)

        // Independent of Baseline A's seed ranges (5.0e8+, 9.0e8+), the original uncapped Phase 1B
        // run's (1.3e9+), the anti-stagnation benchmark's (1.7e9+), and the first (contaminated,
        // diagnostic-only) corrected-model attempt's (2.0e9+) - a genuinely fresh, clean seed range
        // for this second, post-lifecycle-fix attempt.
        private const val BASE_SEED = 2_100_000_000L
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

        private data class BaselineARow(
            val playerCount: Int, val meanTurns: Double, val semTurns: Double, val medianTurns: Double,
            val p90: Double, val p95: Double, val capRate: Double,
        )

        /** Baseline A's own validated Stage 2 figures, commit `d530a21`. */
        private val BASELINE_A = listOf(
            BaselineARow(2, 633.3, 11.43, 503.0, 1204.0, 1516.0, 0.000),
            BaselineARow(3, 926.9, 17.33, 724.0, 1774.0, 2207.0, 0.000),
            BaselineARow(4, 1286.2, 25.65, 1001.0, 2502.0, 3233.0, 0.001),
            BaselineARow(5, 1757.7, 36.81, 1291.0, 3621.0, 4745.0, 0.005),
            BaselineARow(6, 2419.2, 49.18, 1821.0, 5241.0, 6737.0, 0.027),
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

    private data class DeckConstruction(
        val deck: FateHarvestDeck,
        val expectedTotal: Int,
        val events: MutableList<RegenerationEvent>,
        /** Must be called with the real [GameState] right after it's constructed - the
         * [FateHarvestDeck.ReshuffleStrategy] closure needs a live reference to it (for
         * [GameState.liveCardCountsOutsideDiscardPile]) but `state` doesn't exist yet when this
         * deck itself is built. */
        val bindState: (GameState) -> Unit,
    )

    private fun colorCardNamesFor(colors: Set<PlayerColor>): Set<String> = colors.flatMap { FateHarvestCatalog.colorCards[it].orEmpty() }.map { it.name }.toSet()

    private fun buildCorrectedDeckForColors(colors: List<PlayerColor>, random: Random): DeckConstruction {
        val unusedColors = PlayerColor.entries.filterNot { it in colors }.toSet()
        val removedNames = colorCardNamesFor(unusedColors)
        val eligibleTypes = FateHarvestCatalog.all.filter { it.name !in removedNames }
        val filtered = FateHarvestCatalog.buildDeck().filter { it.name !in removedNames }
        val shuffled = filtered.shuffled(random)

        val events = mutableListOf<RegenerationEvent>()
        var index = 0
        lateinit var stateRef: GameState
        val strategy = FateHarvestDeck.ReshuffleStrategy { discardPile, rnd ->
            index += 1
            // FateHarvestRegenerationConfig.DEFAULT - the corrected model, no anti-stagnation
            // pressure. liveCardCountsOutsideDiscardPile() includes both hands AND any card
            // currently mid-resolution - the fix for the defect this file's own class doc explains.
            val result = FateHarvestRegenerationRules.regenerate(discardPile, eligibleTypes, rnd, liveCountsOutsideDiscardPile = stateRef.liveCardCountsOutsideDiscardPile())
            events += RegenerationEvent(index, result.targetSize, result.refillCount, result.cullCount, result.finalMultiplicity)
            result.regeneratedPile
        }

        return DeckConstruction(
            deck = FateHarvestDeck.forTesting(shuffled, random, strategy),
            expectedTotal = filtered.size,
            events = events,
            bindState = { stateRef = it },
        )
    }

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
        val deckConstruction = buildCorrectedDeckForColors(colors, random)
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

        // Corrected-model-specific invariant, not checked by the original uncapped Phase 1B
        // harness: no card type's live population may ever exceed its own canonical rarity.
        // resolvingCards is always empty here (just checked above), but included anyway so this
        // check reads as the same whole-game population liveCardCountsOutsideDiscardPile()
        // itself uses, rather than a parallel definition that could drift from it.
        val allCardNames = state.deck.drawPileCards.map { it.name } + state.deck.discardPileCards.map { it.name } +
            state.players.values.flatMap { it.hand }.map { it.name } + state.resolvingCards.map { it.name }
        val liveCounts = allCardNames.groupingBy { it }.eachCount()
        liveCounts.forEach { (name, count) ->
            val ceiling = FateHarvestCatalog.all.single { it.name == name }.rarity.copies
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

    @Test
    fun `corrected-model Fate Harvest regeneration baseline, clean rerun after the resolving-card lifecycle fix, compared against Baseline A`() {
        assumeTrue(
            System.getProperty("toe.benchmark.dynamic.corrected") == "true",
            "Skipped by default (experimental, heavy) - run with -Dtoe.benchmark.dynamic.corrected=true to execute.",
        )

        repeat(WARMUP_GAMES) { i -> runOneGame(PLAYER_COUNTS[i % PLAYER_COUNTS.size], seedFor(-1, i) - 1_000_000_000L, i) }

        val cohorts = PLAYER_COUNTS.map { pc -> runCohort(pc, GAMES_PER_COHORT) }
        val allViolations = cohorts.flatMap { it.violations }

        val report = buildReport(cohorts, allViolations)
        val reportFile = File("../docs/benchmarks/fate-harvest-regeneration-benchmark-corrected.md")
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
        sb.appendLine("# T.O.E. Fate Harvest Regeneration Benchmark - CORRECTED whole-game rarity-ceiling model, clean rerun (Phase 1B, corrected)")
        sb.appendLine()
        sb.appendLine("**Experimental, not canonical.** Re-establishes Phase 1B's own Fate Harvest regeneration baseline under the " +
            "corrected whole-game rarity-ceiling model (`FateHarvestRegenerationRules`) - no card type may ever exceed its own " +
            "canonical rarity across draw pile + discard pile + every hand + every currently-resolving card, for the whole game. " +
            "This is the clean rerun after fixing the resolving-card accounting gap the first attempt found - see " +
            "`docs/benchmarks/dynamic-reincarnation-benchmark-corrected-diagnostic.md` (preserved, not reused as weighting " +
            "evidence) for that defect's own record. Compared against the validated Baseline A fixed-composition benchmark " +
            "(commit `d530a21`, `docs/benchmarks/player-count-benchmark.md`). The original, uncapped-model Phase 1B run " +
            "(`docs/benchmarks/dynamic-reincarnation-benchmark.md`) remains preserved, unmodified, as historical record of that " +
            "superseded model. No balance or canon decision is made or recommended by this report either - it measures effects " +
            "only; deriving Phase 1C weights from Table K5 below is a separate, later step.")
        sb.appendLine()
        sb.appendLine("Scale: $GAMES_PER_COHORT games/cohort x 5 player counts = ${GAMES_PER_COHORT * 5} total games, single stage, " +
            "seed = $BASE_SEED + playerCount * $PLAYER_COUNT_SEED_STRIDE + gameIndex (independent of every other benchmark's own seed range).")
        sb.appendLine()
        sb.appendLine("## What changed since the first (contaminated) attempt")
        sb.appendLine()
        sb.appendLine(
            "- **`GameState.resolvingCards`**: a new, explicit fourth card zone (alongside draw pile / discard pile / hands) - " +
                "a drawn `CardTiming.IMMEDIATE` card lives here from the instant `TurnEngine` draws it until whatever resolves " +
                "it (`TurnDriver.resolveImmediateCard`, or `discardStrandedImmediateCard` for a card-driven-move landing) " +
                "discards it, closing the exact window the first attempt's 73 violations came from.\n" +
                "- **`GameState.liveCardCountsOutsideDiscardPile()`**: the new single source of truth for \"how many copies of " +
                "each type currently exist outside the discard pile being regenerated\" - every player's hand plus every " +
                "resolving card - replacing the earlier hand-only computation every `ReshuffleStrategy` closure had to " +
                "reimplement itself.\n" +
                "- Every transition/refill/cull rule and the rarity-ceiling model itself are otherwise completely unchanged " +
                "from the first attempt - this is a state-accounting fix, not a rule or resolution-order change (the user's " +
                "own explicit instruction: \"Fix state representation, not game sequencing\").",
        )
        sb.appendLine()

        sb.appendLine("## Table K1 - Corrected-model B vs. Baseline A (game-length and cost)")
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

        sb.appendLine("## Table K3 - SEM and statistical distinguishability from Baseline A")
        sb.appendLine()
        sb.appendLine("| Player count | B mean turns | B SEM | z (B vs A) | Distinguishable from A? |")
        sb.appendLine("|---|---|---|---|---|")
        cohorts.forEach { c ->
            val a = BASELINE_A.first { it.playerCount == c.playerCount }
            val z = (c.meanTurns - a.meanTurns) / kotlin.math.sqrt(c.semTurns * c.semTurns + a.semTurns * a.semTurns)
            sb.appendLine("| ${c.playerCount} | ${fmt(c.meanTurns)} | ${fmt(c.semTurns, 2)} | ${fmt(z, 2)} | ${if (kotlin.math.abs(z) > 1.96) "**Yes**" else "No"} |")
        }
        sb.appendLine()

        sb.appendLine("## Table K4 - Generation depth (deck regenerations per game)")
        sb.appendLine()
        sb.appendLine("| Player count | Mean generations/game | Median generations/game | Mean refill slots/game | Mean culled cards/game |")
        sb.appendLine("|---|---|---|---|---|")
        cohorts.forEach { c ->
            sb.appendLine("| ${c.playerCount} | ${fmt(c.meanGenerationCount)} | ${fmt(c.medianGenerationCount, 0)} | ${fmt(c.meanRefills, 2)} | ${fmt(c.meanCulls, 2)} |")
        }
        sb.appendLine()

        val allGames = cohorts.flatMap { it.games }
        val allTypeNames = allGames.flatMap { it.finalMultiplicity.keys }.toSet()
        val turnsList = allGames.map { it.turnsTaken.toDouble() }
        val correlations = allTypeNames.map { name ->
            val counts = allGames.map { (it.finalMultiplicity[name] ?: 0).toDouble() }
            name to pearson(counts, turnsList)
        }.sortedByDescending { it.second }

        sb.appendLine("## Table K5 - Card-type final-multiplicity vs. game-length correlation, corrected model, clean run (pooled across all player counts, n=${allGames.size})")
        sb.appendLine()
        sb.appendLine("Pearson correlation between a card type's *final* multiplicity (bounded by its own canonical rarity - " +
            "at that game's last regeneration, or Generation 0 if none occurred) and that game's total turn count. " +
            "Correlational, not causal - pooled across player counts (a confound: player count itself strongly affects both " +
            "game length and how many regenerations occur - see Table K4). **This table - from this clean run, not the " +
            "contaminated first attempt or the original superseded uncapped-model Table G5 - is the evidence source for " +
            "`StagnationPressureConfig.CORRECTED_BASELINE_STAGNATION_WEIGHTS`, once the weight re-derivation step actually " +
            "runs (a separate, later step this file does not itself perform).**")
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
        sb.appendLine("1. **Direction vs. Baseline A**: per-player-count absolute mean-turns difference - " +
            diffs.joinToString(", ") { (pc, d) -> "${pc}P: ${fmt(d)}" } + ".")
        sb.appendLine("2. **Correctness**: " +
            (if (violations.isEmpty()) "0 invariant violations across ${cohorts.sumOf { it.games.size }} games, including the " +
                "whole-game rarity-ceiling invariant (no card type's live population - draw pile + discard pile + hands + " +
                "resolving cards - ever exceeded its own canonical rarity) and resolvingCards being empty between every turn. " +
                "This is the clean result the first attempt's 73 violations were meant to become."
            else "**${violations.size} violation(s) found** - see raw detail. Per the user's own explicit instruction, a " +
                "non-zero count here means this is NOT yet a clean baseline: stop and investigate rather than proceeding to " +
                "any Phase 1C weight derivation."))
        sb.appendLine()
        sb.appendLine("No balance change, canon decision, or recommendation is made based on the above - this is a corrected-model " +
            "measurement, reported for a later, separate decision, and (only if clean) the intended evidence basis for Phase 1C's " +
            "own re-derived weights.")
        sb.appendLine()
        sb.appendLine("Total games run: ${cohorts.sumOf { it.games.size }}. Total invariant violations: ${violations.size}.")

        return sb.toString()
    }
}
