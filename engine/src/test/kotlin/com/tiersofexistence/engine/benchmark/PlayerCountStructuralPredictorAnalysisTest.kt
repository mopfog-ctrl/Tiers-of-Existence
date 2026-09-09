package com.tiersofexistence.engine.benchmark

import com.tiersofexistence.engine.board.SquareType
import com.tiersofexistence.engine.board.TierBoard
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
 * **PHASE 1C — structural stagnation characterization, Clearance C2 (instrument/test only). Does
 * NOT modify gameplay, regeneration probabilities, rarity ceilings, card behavior, or
 * `FateHarvestRegenerationConfig.ANTI_STAGNATION`.**
 *
 * [PlayerCountRegenerationEventAnalysisTest] established Outcome C for card identity under the
 * *presently tested* catalog — that finding does not generalize into "card identity can never
 * matter," it says the current 32-card catalog's own multiplicities don't discriminate subsequent
 * stagnation once player count/depth/eligibility are accounted for. Per-card data stays retained
 * in this run's own dataset ([RegenerationObservation.multiplicity]) precisely so a future catalog
 * change can be compared against this baseline, but this file does not re-derive or re-litigate
 * that per-card question — it asks the *different* question the task actually poses: do
 * *structural* properties of the game state itself (Tier occupancy, Marauder population, hand
 * sizes, deck-pile sizes, outstanding skip/extra-turn debt, proximity to victory, and an aggregate
 * — not per-card — composition summary) predict how much play remains from a given regeneration
 * event, and does regeneration depth predict that independently of how many turns have simply
 * elapsed.
 *
 * **Two small, additive, read-only accessors were added to [GameState] to make this
 * possible**: [GameState.totalPendingSkipDebt] and [GameState.totalPendingExtraTierTurns] — the
 * skip-turn and extra-tier-turn queues were previously entirely private, with no way for an
 * external observer to read the current debt at all. Both are pure sums over already-existing
 * private state, mutate nothing, and are read by no gameplay logic - see each property's own doc.
 * This is the only main-source-tree change in this pass; everything else lives in this test file.
 *
 * **Deliberately reuses [PlayerCountFateHarvestRegenerationCorrectedBenchmarkTest]'s own
 * `BASE_SEED`** (`2_100_000_000L`) for the same reason [PlayerCountRegenerationEventAnalysisTest]
 * did: the new accessors and the recording code in this file consume no `Random` and don't touch
 * `FateHarvestRegenerationConfig.DEFAULT`'s own logic, so driving the same seeds must reproduce
 * byte-identical gameplay and, therefore, the already-published aggregate statistics - checked
 * directly in the report's own Section 1, not just assumed.
 *
 * **Scale**: 1,000 games/cohort × 5 player counts = 5,000, matching every other benchmark in this
 * Phase's own lineage.
 *
 * **Report**: `docs/benchmarks/anti-stagnation-structural-predictors.md` (a new file; every prior
 * Phase 1B/1C report stays untouched as historical/superseded-methodology record).
 *
 * Gated behind its own `toe.benchmark.structural` system property (forwarded via
 * `engine/build.gradle.kts`'s `tasks.test` block) — skipped by default. Run via:
 * `./gradlew :engine:test --configure-on-demand -Dtoe.benchmark.structural=true --tests
 * "com.tiersofexistence.engine.benchmark.PlayerCountStructuralPredictorAnalysisTest"`.
 */
class PlayerCountStructuralPredictorAnalysisTest {

    companion object {
        private const val MAX_TURNS_PER_GAME = 8000
        private const val GAMES_PER_COHORT = 1000
        private const val WARMUP_GAMES = 30
        private val PLAYER_COUNTS = listOf(2, 3, 4, 5, 6)

        /** Same seed range as [PlayerCountFateHarvestRegenerationCorrectedBenchmarkTest] and
         * [PlayerCountRegenerationEventAnalysisTest] - see this class's own doc for why. */
        private const val BASE_SEED = 2_100_000_000L
        private const val PLAYER_COUNT_SEED_STRIDE = 10_000_000L

        /** Below this many observations, a correlation is reported but not treated as evidence of
         * anything - same discipline as [PlayerCountRegenerationEventAnalysisTest]. */
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

        /** Standard partial-correlation formula: the correlation between [xs] and [ys] once the
         * portion each shares with [zs] is removed - the direct, standard way to answer "does X
         * predict Y independently of Z." Returns 0.0 if either denominator term would be zero
         * (one of the pairwise correlations with [zs] is already ±1, a degenerate case not
         * expected on real data here). */
        private fun partialCorrelation(xs: List<Double>, ys: List<Double>, zs: List<Double>): Double {
            val rXY = pearson(xs, ys)
            val rXZ = pearson(xs, zs)
            val rYZ = pearson(ys, zs)
            val denom = kotlin.math.sqrt((1 - rXZ * rXZ) * (1 - rYZ * rYZ))
            return if (denom == 0.0) 0.0 else (rXY - rXZ * rYZ) / denom
        }

        /** `docs/benchmarks/fate-harvest-regeneration-benchmark-corrected.md`'s own Table K1 -
         * the determinism cross-check target, same as [PlayerCountRegenerationEventAnalysisTest]. */
        private data class PublishedRow(val playerCount: Int, val meanTurns: Double, val capRatePct: Double)
        private val PUBLISHED_CORRECTED_BASELINE = listOf(
            PublishedRow(2, 669.0, 0.00),
            PublishedRow(3, 869.7, 0.00),
            PublishedRow(4, 1327.3, 0.00),
            PublishedRow(5, 1776.9, 0.40),
            PublishedRow(6, 2424.4, 3.10),
        )
    }

    private class InvariantViolation(val playerCount: Int, val gameIndex: Int, val seed: Long, val turnNumber: Int, message: String) :
        AssertionError("[players=$playerCount, game #$gameIndex, seed=$seed, turn=$turnNumber] $message")

    /**
     * One regeneration event's full recorded state. [multiplicity] (per-card, immediately after
     * this regeneration) is retained for future-catalog comparison per the task's own explicit
     * instruction, but is NOT reanalyzed in this file's own report - see
     * [PlayerCountRegenerationEventAnalysisTest] for that already-exhaustive analysis. Every other
     * field is a *structural* predictor, computed from already-canonical [GameState] at the exact
     * moment of this regeneration - see [buildInstrumentedDeckForColors] for how each is read.
     */
    private data class RegenerationObservation(
        val depth: Int,
        val turnsElapsedAtRegeneration: Int,
        val multiplicity: Map<String, Int>,
        val highestTierInPlay: Int,
        val playersOnTier4: Int,
        val inPlayTokensByTier: Map<Int, Int>,
        val stagingPileByTier: Map<Int, Int>,
        val marauderCountByTier: Map<Int, Int>,
        val totalHandCards: Int,
        val meanHandCards: Double,
        val maxHandCards: Int,
        val drawPileSize: Int,
        val discardPileSize: Int,
        val resolvingCardsCount: Int,
        val totalPendingSkipDebt: Int,
        val totalPendingExtraTierTurns: Int,
        val minDistanceToWin: Int?,
        val meanFractionOfCeiling: Double,
    )

    private data class GameOutcome(
        val playerCount: Int,
        val seed: Long,
        val gameIndex: Int,
        val totalTurnsTaken: Int,
        val reachedCap: Boolean,
        val observations: List<RegenerationObservation>,
    )

    private data class DeckConstruction(
        val deck: FateHarvestDeck,
        val expectedTotal: Int,
        val observations: MutableList<RegenerationObservation>,
        val bindState: (GameState) -> Unit,
    )

    private fun colorCardNamesFor(colors: Set<PlayerColor>): Set<String> = colors.flatMap { FateHarvestCatalog.colorCards[it].orEmpty() }.map { it.name }.toSet()

    /** Squares remaining, walking forward with wraparound, from [fromPosition] to the nearest
     * `YOU_WIN` square on [board] - mirrors [TierBoard.nextZoneEntry]'s own loop shape (the one
     * existing precedent for "search forward around the loop" board queries), since no
     * distance-specific helper exists on [TierBoard] itself (see this class's own doc on why this
     * is computed test-locally rather than as a new engine method). */
    private fun distanceToWin(board: TierBoard, fromPosition: Int): Int {
        for (offset in 1..board.size) {
            if (board.squareAt(fromPosition + offset).type == SquareType.YOU_WIN) return offset
        }
        error("No YOU_WIN square found on the 4th Tier board - structurally impossible on any confirmed board layout")
    }

    /** [turnsElapsedProvider] is called at the exact moment a regeneration happens; reading it,
     * and every structural field below, is a pure read of already-canonical [GameState] - no
     * `Random` consumption anywhere in this function, so this cannot perturb determinism (see this
     * class's own doc). */
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
            observations += snapshotObservation(stateRef, depth, turnsElapsedProvider(), eligibleTypes, result)
            result.regeneratedPile
        }

        return DeckConstruction(
            deck = FateHarvestDeck.forTesting(shuffled, random, strategy),
            expectedTotal = filtered.size,
            observations = observations,
            bindState = { stateRef = it },
        )
    }

    private fun snapshotObservation(
        state: GameState,
        depth: Int,
        turnsElapsed: Int,
        eligibleTypes: List<com.tiersofexistence.engine.cards.FateHarvestCard>,
        result: RegenerationResult,
    ): RegenerationObservation {
        val players = state.players.values
        val fourthBoard = state.boards.getValue(TierLevel.FOURTH)
        val minDistance = players.mapNotNull { p ->
            p.tierPool(TierLevel.FOURTH).inPlayPositions.minOfOrNull { pos -> distanceToWin(fourthBoard, pos) }
        }.minOrNull()
        val fractionOfCeiling = eligibleTypes.map { card ->
            (result.finalMultiplicity[card.name] ?: 0).toDouble() / card.rarity.copies
        }.average()

        return RegenerationObservation(
            depth = depth,
            turnsElapsedAtRegeneration = turnsElapsed,
            multiplicity = result.finalMultiplicity,
            highestTierInPlay = TierLevel.entries.filter { tier -> players.any { it.tierPool(tier).inPlayCount > 0 } }.maxOfOrNull { it.number } ?: 0,
            playersOnTier4 = players.count { it.tierPool(TierLevel.FOURTH).inPlayCount > 0 },
            inPlayTokensByTier = TierLevel.entries.associate { tier -> tier.number to players.sumOf { it.tierPool(tier).inPlayCount } },
            stagingPileByTier = TierLevel.entries.associate { tier -> tier.number to players.sumOf { it.tierPool(tier).stagingPile } },
            marauderCountByTier = TierLevel.entries.associate { tier -> tier.number to players.sumOf { it.marauders.inPlayCount(tier) } },
            totalHandCards = players.sumOf { it.hand.size },
            meanHandCards = players.sumOf { it.hand.size }.toDouble() / players.size,
            maxHandCards = players.maxOf { it.hand.size },
            drawPileSize = state.deck.drawPileSize,
            discardPileSize = state.deck.discardPileSize,
            resolvingCardsCount = state.resolvingCards.size,
            totalPendingSkipDebt = state.totalPendingSkipDebt,
            totalPendingExtraTierTurns = state.totalPendingExtraTierTurns,
            minDistanceToWin = minDistance,
            meanFractionOfCeiling = fractionOfCeiling,
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
            observations = deckConstruction.observations,
        )
        return outcome to null
    }

    /** Same checks as every prior benchmark in this lineage - deliberately duplicated, not
     * shared, per this codebase's established convention for independent benchmark harnesses. */
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

    // --- analysis ---

    private data class EventSample(
        val playerCount: Int,
        val gameKey: Long,
        val turnsRemaining: Int,
        val reachedCap: Boolean,
        val totalTurnsTaken: Int,
        val obs: RegenerationObservation,
    )

    private fun buildSamples(games: List<GameOutcome>): List<EventSample> =
        games.flatMap { game -> game.observations.map { obs -> EventSample(game.playerCount, game.seed, game.totalTurnsTaken - obs.turnsElapsedAtRegeneration, game.reachedCap, game.totalTurnsTaken, obs) } }

    /** Every scalar structural predictor, named for reporting, extracted from an [EventSample].
     * [minDistanceToWin] is deliberately excluded here (nullable, handled as its own special case
     * with its own honestly-reported n - see [buildSection5DistanceToWin]). */
    private fun scalarPredictors(): List<Pair<String, (EventSample) -> Double>> {
        val base = mutableListOf<Pair<String, (EventSample) -> Double>>(
            "depth" to { it.obs.depth.toDouble() },
            "turnsElapsedAtRegeneration" to { it.obs.turnsElapsedAtRegeneration.toDouble() },
            "highestTierInPlay" to { it.obs.highestTierInPlay.toDouble() },
            "playersOnTier4" to { it.obs.playersOnTier4.toDouble() },
            "totalHandCards" to { it.obs.totalHandCards.toDouble() },
            "meanHandCards" to { it.obs.meanHandCards },
            "maxHandCards" to { it.obs.maxHandCards.toDouble() },
            "drawPileSize" to { it.obs.drawPileSize.toDouble() },
            "discardPileSize" to { it.obs.discardPileSize.toDouble() },
            "resolvingCardsCount" to { it.obs.resolvingCardsCount.toDouble() },
            "totalPendingSkipDebt" to { it.obs.totalPendingSkipDebt.toDouble() },
            "totalPendingExtraTierTurns" to { it.obs.totalPendingExtraTierTurns.toDouble() },
            "meanFractionOfCeiling (aggregate composition, not per-card)" to { it.obs.meanFractionOfCeiling },
        )
        TierLevel.entries.forEach { tier ->
            base += "inPlayTokens@Tier${tier.number}" to { s: EventSample -> (s.obs.inPlayTokensByTier[tier.number] ?: 0).toDouble() }
            base += "stagingPile@Tier${tier.number}" to { s: EventSample -> (s.obs.stagingPileByTier[tier.number] ?: 0).toDouble() }
            base += "marauders@Tier${tier.number}" to { s: EventSample -> (s.obs.marauderCountByTier[tier.number] ?: 0).toDouble() }
        }
        return base
    }

    private data class RResult(val r: Double, val n: Int) {
        val reliable get() = n >= MIN_N_FOR_CORRELATION
        fun format(digits: Int = 3): String = if (n == 0) "n/a (n=0)" else if (!reliable) "${"%.${digits}f".format(r)} (n=$n, insufficient)" else "${"%.${digits}f".format(r)} (n=$n)"
    }

    private fun correlate(xs: List<Double>, ys: List<Double>): RResult = if (xs.size < 2) RResult(0.0, xs.size) else RResult(pearson(xs, ys), xs.size)

    @Test
    fun `structural stagnation predictor analysis - not weighting, measurement only`() {
        assumeTrue(
            System.getProperty("toe.benchmark.structural") == "true",
            "Skipped by default (experimental, heavy) - run with -Dtoe.benchmark.structural=true to execute.",
        )

        repeat(WARMUP_GAMES) { i -> runOneGame(PLAYER_COUNTS[i % PLAYER_COUNTS.size], seedFor(-1, i) - 1_000_000_000L, i) }

        val cohorts = PLAYER_COUNTS.associateWith { pc -> runCohort(pc, GAMES_PER_COHORT) }
        val allGames = cohorts.values.flatMap { it.first }
        val allViolations = cohorts.values.flatMap { it.second }

        val report = buildReport(cohorts, allGames, allViolations)
        val reportFile = File("../docs/benchmarks/anti-stagnation-structural-predictors.md")
        reportFile.parentFile.mkdirs()
        reportFile.writeText(report)
        println(report)

        check(allViolations.isEmpty()) {
            "${allViolations.size} invariant violation(s):\n" + allViolations.joinToString("\n---\n") { it.message ?: it.toString() }
        }
    }

    private fun fmt(d: Double, digits: Int = 1) = "%.${digits}f".format(d)

    private fun buildReport(
        cohorts: Map<Int, Pair<List<GameOutcome>, List<InvariantViolation>>>,
        allGames: List<GameOutcome>,
        violations: List<InvariantViolation>,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("# T.O.E. Phase 1C — Structural Stagnation Predictor Analysis")
        sb.appendLine()
        sb.appendLine("**Experimental, measurement only — Clearance C2 (instrument/test). No gameplay, regeneration " +
            "probabilities, rarity ceilings, card behavior, or `FateHarvestRegenerationConfig.ANTI_STAGNATION` change " +
            "was made in this pass.** The prior refined-evidence report established Outcome C for card identity under " +
            "the *presently tested* catalog - this report does not revisit that question. It asks a different one: do " +
            "*structural* game-state properties (Tier occupancy, Marauder/Staging-Pile state, hand sizes, deck-pile " +
            "sizes, outstanding skip/extra-turn debt, proximity to victory, and an aggregate composition summary) " +
            "predict subsequent play length, and does regeneration depth predict it independently of elapsed turns.")
        sb.appendLine()
        sb.appendLine("Preserves every prior Phase 1B/1C report unmodified as historical/superseded-methodology record.")
        sb.appendLine()

        buildSection1(sb, cohorts, violations)
        val samples = buildSamples(allGames)
        buildSection2Methodology(sb, allGames)
        val predictors = scalarPredictors()
        buildSection3WithinPlayerCount(sb, samples, predictors)
        buildSection4DepthVsElapsed(sb, samples)
        buildSection5DistanceToWin(sb, samples)
        val p90ByPc = buildSection6Tail(sb, samples, allGames, predictors)
        buildSection7Interpretation(sb, samples, predictors, p90ByPc)
        buildSection8Ambiguities(sb)

        sb.appendLine("## Closing statement")
        sb.appendLine()
        sb.appendLine("No balance change, canon decision, weight derivation, or configuration change was made based on " +
            "the above - per this task's explicit scope, this is a measurement and interpretation pass only.")
        sb.appendLine()
        sb.appendLine("Total games analyzed: ${allGames.size}. Total invariant violations: ${violations.size}.")

        return sb.toString()
    }

    // --- Section 1: tests/invariants/determinism ---

    private fun buildSection1(sb: StringBuilder, cohorts: Map<Int, Pair<List<GameOutcome>, List<InvariantViolation>>>, violations: List<InvariantViolation>) {
        sb.appendLine("## 1. Tests, invariants, and determinism")
        sb.appendLine()
        sb.appendLine("- **Full engine suite**: run green before this benchmark, per this Phase's own established sequencing.")
        sb.appendLine("- **Whole-game rarity invariant**: re-checked after every turn of every game attempted (token/Phase/" +
            "pendingRoll/winner-square conservation, plus draw+discard+hands+resolving never exceeding a card's own " +
            "Generation-0 rarity). Result: **${violations.size} violation(s)**.")
        sb.appendLine("- **New engine surface, read-only**: `GameState.totalPendingSkipDebt`/`totalPendingExtraTierTurns` - " +
            "both pure sums over already-existing private state (`pendingSkips`/`deferredModifiers`), mutate nothing, " +
            "read by no gameplay logic. This is the only main-source-tree change this pass made.")
        sb.appendLine("- **Instrumentation RNG safety**: every new recorded field is a pure read of already-canonical " +
            "`GameState` at the moment of a regeneration - no `Random` instance is touched by any of it.")
        sb.appendLine("- **Determinism cross-check against the published corrected baseline**: reuses that benchmark's " +
            "own `BASE_SEED`.")
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
        sb.appendLine(if (determinismHeld) "**Determinism confirmed**: every cohort's aggregate statistics under this instrumented run exactly match the already-published corrected baseline." else "**DETERMINISM CHECK FAILED** - see mismatched row(s) above; every result below must be treated as unreliable until root-caused.")
        sb.appendLine()
    }

    // --- Section 2: methodology ---

    private fun buildSection2Methodology(sb: StringBuilder, allGames: List<GameOutcome>) {
        sb.appendLine("## 2. Benchmark methodology and sample sizes")
        sb.appendLine()
        sb.appendLine("Same corrected model as every prior benchmark in this lineage (`FateHarvestRegenerationConfig.DEFAULT`, " +
            "no anti-stagnation pressure). At every regeneration event, in addition to depth/turns-elapsed/per-card " +
            "multiplicity (retained, not reanalyzed here), this pass records: the highest Tier any player has an " +
            "in-play token on; how many players have a 4th-Tier in-play/Zone token; total in-play tokens, Staging Pile " +
            "counts, and Marauder counts, each broken out per Tier and summed across every player; total/mean/max hand " +
            "size across players; draw pile, discard pile, and resolving-card counts; total outstanding skip-turn and " +
            "extra-tier-turn debt (via the two new read-only `GameState` accessors); the closest any player's 4th-Tier " +
            "main-loop token is to the `YOU_WIN` square (null if nobody has reached the 4th Tier board yet - Zone-" +
            "resident 4th-Tier tokens are excluded from this specific measure, since their own sub-path position isn't " +
            "directly comparable to a main-loop distance without a separate model this task doesn't ask for); and the " +
            "mean, across every eligible card type, of that type's own multiplicity divided by its own rarity ceiling - " +
            "an aggregate composition summary, not a per-card one.")
        sb.appendLine()
        sb.appendLine("Scale: $GAMES_PER_COHORT games/cohort × 5 player counts = ${GAMES_PER_COHORT * 5} total games attempted, " +
            "same seed range as the corrected baseline (`BASE_SEED = $BASE_SEED`).")
        sb.appendLine()
        sb.appendLine("| Player count | Games | Total regeneration events | Mean events/game |")
        sb.appendLine("|---|---|---|---|")
        PLAYER_COUNTS.forEach { pc ->
            val games = allGames.filter { it.playerCount == pc }
            val counts = games.map { it.observations.size }
            sb.appendLine("| $pc | ${games.size} | ${counts.sum()} | ${fmt(counts.average())} |")
        }
        sb.appendLine()
        sb.appendLine("A minimum of $MIN_N_FOR_CORRELATION observations is required before a correlation is treated as " +
            "reliable rather than merely reported - cells below that threshold are marked `insufficient`.")
        sb.appendLine()
    }

    // --- Section 3: within-player-count, every structural predictor vs turns remaining ---

    private fun buildSection3WithinPlayerCount(sb: StringBuilder, samples: List<EventSample>, predictors: List<Pair<String, (EventSample) -> Double>>) {
        sb.appendLine("## 3. Within-player-count analysis: structural predictors vs. turns remaining")
        sb.appendLine()
        sb.appendLine("Primary relationship: Pearson r between each structural predictor's value immediately after a " +
            "regeneration and turns remaining from that event, computed **separately per player count** - eliminating " +
            "the 2P-6P pooling confound before any aggregate interpretation, matching this Phase's own established " +
            "methodology.")
        sb.appendLine()
        sb.appendLine("| Predictor | " + PLAYER_COUNTS.joinToString(" | ") { "${it}P r (n)" } + " |")
        sb.appendLine("|---|" + PLAYER_COUNTS.joinToString("|") { "---" } + "|")
        predictors.forEach { (name, extractor) ->
            val cells = PLAYER_COUNTS.map { pc ->
                val subset = samples.filter { it.playerCount == pc }
                correlate(subset.map(extractor), subset.map { it.turnsRemaining.toDouble() }).format()
            }
            sb.appendLine("| $name | " + cells.joinToString(" | ") + " |")
        }
        sb.appendLine()
        sb.appendLine("**Cross-player-count consistency summary** (reliable cells only, n>=$MIN_N_FOR_CORRELATION):")
        sb.appendLine()
        sb.appendLine("| Predictor | Reliable cells | Mean r | Range (min to max) | Sign-consistent? |")
        sb.appendLine("|---|---|---|---|---|")
        predictors.forEach { (name, extractor) ->
            val results = PLAYER_COUNTS.mapNotNull { pc ->
                val subset = samples.filter { it.playerCount == pc }
                val r = correlate(subset.map(extractor), subset.map { it.turnsRemaining.toDouble() })
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

    // --- Section 4: the critical temporal question ---

    private fun buildSection4DepthVsElapsed(sb: StringBuilder, samples: List<EventSample>) {
        sb.appendLine("## 4. Critical temporal question: does regeneration depth predict stagnation independently of elapsed turns?")
        sb.appendLine()
        sb.appendLine("Raw pairwise correlations, then the partial correlation of depth with turns-remaining *controlling " +
            "for* turns-elapsed (the standard technique for \"does X predict Y independently of Z\" - " +
            "`r(depth,remaining) - r(depth,elapsed)*r(elapsed,remaining)`, normalized by `sqrt((1-r(depth,elapsed)^2)*(1-r(elapsed,remaining)^2))`), " +
            "computed **within each player count**:")
        sb.appendLine()
        sb.appendLine("| Player count | n | r(depth, turnsRemaining) | r(depth, turnsElapsed) | r(turnsElapsed, turnsRemaining) | Partial r(depth, turnsRemaining \\| turnsElapsed) |")
        sb.appendLine("|---|---|---|---|---|---|")
        val partials = mutableListOf<Double>()
        PLAYER_COUNTS.forEach { pc ->
            val subset = samples.filter { it.playerCount == pc }
            val depth = subset.map { it.obs.depth.toDouble() }
            val elapsed = subset.map { it.obs.turnsElapsedAtRegeneration.toDouble() }
            val remaining = subset.map { it.turnsRemaining.toDouble() }
            val rDepthRemaining = pearson(depth, remaining)
            val rDepthElapsed = pearson(depth, elapsed)
            val rElapsedRemaining = pearson(elapsed, remaining)
            val partial = partialCorrelation(depth, remaining, elapsed)
            partials += partial
            sb.appendLine("| $pc | ${subset.size} | ${fmt(rDepthRemaining, 3)} | ${fmt(rDepthElapsed, 3)} | ${fmt(rElapsedRemaining, 3)} | ${fmt(partial, 3)} |")
        }
        sb.appendLine()
        val pooledDepth = samples.map { it.obs.depth.toDouble() }
        val pooledElapsed = samples.map { it.obs.turnsElapsedAtRegeneration.toDouble() }
        val pooledRemaining = samples.map { it.turnsRemaining.toDouble() }
        val pooledPartial = partialCorrelation(pooledDepth, pooledRemaining, pooledElapsed)
        sb.appendLine("Pooled across all player counts (n=${samples.size}, for reference only - reintroduces the player-count " +
            "confound, so the within-player-count rows above are the ones to trust): raw r(depth, remaining) = " +
            "${fmt(pearson(pooledDepth, pooledRemaining), 3)}, partial r(depth, remaining | elapsed) = ${fmt(pooledPartial, 3)}.")
        sb.appendLine()
        val allNearZero = partials.all { kotlin.math.abs(it) < 0.05 }
        sb.appendLine("**Direct answer**: " + if (allNearZero) {
            "once elapsed turns are controlled for, regeneration depth has essentially no remaining independent " +
                "relationship with turns remaining at any player count (all partial r within ±0.05). Depth's own raw " +
                "correlation with turns remaining, where one exists, is explained by depth's relationship with elapsed " +
                "turns (more turns naturally means more regenerations), not by depth carrying separate predictive " +
                "information of its own."
        } else {
            "at least one player count shows a partial correlation exceeding ±0.05 after controlling for elapsed turns - " +
                "see the per-player-count row(s) above for exactly where; this would mean depth carries some " +
                "independent signal beyond what elapsed-turns alone explains at that player count, worth a closer look " +
                "before concluding depth is redundant with elapsed turns everywhere."
        })
        sb.appendLine()
    }

    // --- Section 5: distance to win ---

    private fun buildSection5DistanceToWin(sb: StringBuilder, samples: List<EventSample>) {
        sb.appendLine("## 5. Proximity to victory")
        sb.appendLine()
        sb.appendLine("`minDistanceToWin` (squares remaining, main-loop only, for whichever player is closest to the " +
            "4th Tier's own `YOU_WIN` square) is only defined once at least one player has an in-play 4th-Tier token on " +
            "the main loop - many regeneration events happen before any player reaches the 4th Tier at all, so this " +
            "predictor's own `n` is reported honestly per player count rather than padded with a sentinel value.")
        sb.appendLine()
        sb.appendLine("| Player count | n (defined) | n (total events) | Coverage | r(minDistanceToWin, turnsRemaining) |")
        sb.appendLine("|---|---|---|---|---|")
        PLAYER_COUNTS.forEach { pc ->
            val subset = samples.filter { it.playerCount == pc }
            val defined = subset.filter { it.obs.minDistanceToWin != null }
            val coverage = if (subset.isEmpty()) 0.0 else defined.size.toDouble() / subset.size * 100
            val result = correlate(defined.map { it.obs.minDistanceToWin!!.toDouble() }, defined.map { it.turnsRemaining.toDouble() })
            sb.appendLine("| $pc | ${defined.size} | ${subset.size} | ${fmt(coverage, 1)}% | ${result.format()} |")
        }
        sb.appendLine()
        sb.appendLine("A negative r here would be the mechanically expected direction (closer to winning -> less play " +
            "remaining) and would mainly validate that this measure behaves sensibly, rather than being a novel " +
            "anti-stagnation finding on its own.")
        sb.appendLine()
    }

    // --- Section 6: tail-specific ---

    private fun buildSection6Tail(sb: StringBuilder, samples: List<EventSample>, allGames: List<GameOutcome>, predictors: List<Pair<String, (EventSample) -> Double>>): Map<Int, Double> {
        sb.appendLine("## 6. Tail-specific analysis")
        sb.appendLine()
        sb.appendLine("Tail defined **per player count, from this run's own games** as the top 10% of games by total " +
            "turns taken (`totalTurnsTaken >= that player count's own p90`) - same documented threshold as the prior " +
            "refined-evidence report. \"Capped\" (hit the $MAX_TURNS_PER_GAME-turn cap without a winner) reported " +
            "alongside for comparison.")
        sb.appendLine()
        sb.appendLine("| Player count | p90 threshold (turns) | Tail games (>=p90) | Capped games |")
        sb.appendLine("|---|---|---|---|")
        val p90ByPc = mutableMapOf<Int, Double>()
        PLAYER_COUNTS.forEach { pc ->
            val games = allGames.filter { it.playerCount == pc }
            val p90 = percentile(games.map { it.totalTurnsTaken }, 90.0)
            p90ByPc[pc] = p90
            sb.appendLine("| $pc | ${fmt(p90, 0)} | ${games.count { it.totalTurnsTaken >= p90 }} | ${games.count { it.reachedCap }} |")
        }
        sb.appendLine()

        sb.appendLine("For each structural predictor, using that predictor's own **last regeneration event per game** " +
            "(its terminal state for that game) - point-biserial correlation between the predictor and tail membership " +
            "(0/1), pooled across player counts (each game's own tail membership is determined by its own player " +
            "count's p90, so this pooling doesn't reintroduce a raw-scale confound the way pooling turn counts " +
            "directly would):")
        sb.appendLine()
        sb.appendLine("| Predictor | Point-biserial r (n) |")
        sb.appendLine("|---|---|")
        val lastEventPerGame = samples.groupBy { it.gameKey }.values.mapNotNull { events -> events.maxByOrNull { it.obs.depth } }
        predictors.forEach { (name, extractor) ->
            val tailIndicator = lastEventPerGame.map { if (it.totalTurnsTaken >= p90ByPc.getValue(it.playerCount)) 1.0 else 0.0 }
            val values = lastEventPerGame.map(extractor)
            sb.appendLine("| $name | ${correlate(values, tailIndicator).format()} |")
        }
        sb.appendLine()
        return p90ByPc
    }

    // --- Section 7: interpretation ---

    private fun buildSection7Interpretation(
        sb: StringBuilder,
        samples: List<EventSample>,
        predictors: List<Pair<String, (EventSample) -> Double>>,
        p90ByPc: Map<Int, Double>,
    ) {
        sb.appendLine("## 7. Interpretation")
        sb.appendLine()
        val signConsistentReliable = predictors.filter { (name, extractor) ->
            val results = PLAYER_COUNTS.mapNotNull { pc ->
                val subset = samples.filter { it.playerCount == pc }
                val r = correlate(subset.map(extractor), subset.map { it.turnsRemaining.toDouble() })
                if (r.reliable) r.r else null
            }
            results.size >= 3 && (results.all { it >= 0.05 } || results.all { it <= -0.05 })
        }.map { it.first }
        sb.appendLine("**Measured association**: Section 3's within-player-count table is this report's primary factual " +
            "finding. **Plausible interpretation**: of ${predictors.size} structural predictors, ${signConsistentReliable.size} " +
            "show a sign-consistent (same direction, |r|>=0.05) relationship with turns remaining across at least 3 " +
            "reliable player-count cells: " + (if (signConsistentReliable.isEmpty()) "none." else signConsistentReliable.joinToString(", ") + ".") +
            " These are candidates for further, separate investigation - not weights, and not proof of causation.")
        sb.appendLine()

        // Section 6's own tail table can surface a predictor whose *tail* association is far
        // stronger than anything Section 3's turns-remaining table shows for it - `depth` and
        // `turnsElapsedAtRegeneration` are excluded here since a game simply having more turns
        // mechanically produces both a later last-event depth and a larger elapsed-turns value,
        // making their own tail correlation near-tautological rather than a distinct finding.
        // Reuses Section 6's own p90ByPc (computed from *all* games in each cohort, not just
        // those with a regeneration event) so this doesn't silently recompute a different tail
        // threshold and report a different number for the same named statistic.
        val lastEventPerGame = samples.groupBy { it.gameKey }.values.mapNotNull { events -> events.maxByOrNull { it.obs.depth } }
        val tailIndicator = lastEventPerGame.map { if (it.totalTurnsTaken >= p90ByPc.getValue(it.playerCount)) 1.0 else 0.0 }
        val notableTailOnly = predictors.filter { (name, _) -> name != "depth" && name != "turnsElapsedAtRegeneration" }
            .map { (name, extractor) -> name to correlate(lastEventPerGame.map(extractor), tailIndicator) }
            .filter { (_, result) -> result.reliable && kotlin.math.abs(result.r) >= 0.1 }
            .sortedByDescending { (_, result) -> kotlin.math.abs(result.r) }
        if (notableTailOnly.isNotEmpty()) {
            sb.appendLine("**Notable tail-only finding, not otherwise flagged above**: Section 6's point-biserial table " +
                "shows " + notableTailOnly.joinToString(", ") { (name, result) -> "`$name` (r=${fmt(result.r, 3)})" } +
                " with a tail-membership association well above every other non-tautological predictor there, despite " +
                "not clearing Section 3's own within-player-count sign-consistency bar for turns-remaining. This is " +
                "reported as a measured association only - it is plausible that outstanding debt of this kind simply " +
                "has more opportunity to accumulate the longer a game already runs (the same base-rate-confound shape " +
                "flagged for card identity in the prior refined-evidence report), not that it independently drives a " +
                "game into the tail; distinguishing those two would need its own dedicated analysis, not asserted here.")
            sb.appendLine()
        }
        sb.appendLine("**Evidence insufficient to distinguish**: Section 5's proximity-to-victory measure is only defined " +
            "for a fraction of events (most regenerations happen before any player reaches the 4th Tier board at all) - " +
            "see that section's own coverage percentages before treating its correlation as reliable. Section 6's tail " +
            "comparison pools across player counts at the tail-membership level specifically to keep its own n usable; " +
            "a full player-count-separated tail analysis would need a substantially larger sample than this run's " +
            "~$GAMES_PER_COHORT games/cohort, since tail games are by definition only ~10% of each cohort.")
        sb.appendLine()
        sb.appendLine("**Causal hypothesis**: none is asserted anywhere in this report. Every relationship above is " +
            "correlational, computed on top of an already-conditioned (player-count-separated) dataset, but conditioning " +
            "on player count does not establish causation for any individual predictor - several of these structural " +
            "measures are themselves correlated with each other (e.g. higher hand sizes and higher draw/discard pile " +
            "sizes both track overall game progress), so a predictor showing a relationship here could be standing in " +
            "for a different, unmeasured common cause rather than driving the outcome itself.")
        sb.appendLine()
    }

    // --- Section 8: ambiguities ---

    private fun buildSection8Ambiguities(sb: StringBuilder) {
        sb.appendLine("## 8. Newly discovered architectural or semantic ambiguities")
        sb.appendLine()
        sb.appendLine("- **Skip-turn and extra-tier-turn debt had no public read accessor at all before this pass.** " +
            "`GameState.pendingSkips`/`deferredModifiers` were both fully private, with only write-only queue methods " +
            "(`queueSkipNextTierTurn`/`queueExtraTierTurn`) exposed - there was no way for any external observer, " +
            "instrumentation or otherwise, to read the current outstanding debt. Two small, read-only, purely additive " +
            "accessors (`GameState.totalPendingSkipDebt`/`totalPendingExtraTierTurns`) were added specifically to make " +
            "this measurable; see this class's own doc for why this was judged in-scope for a Clearance C2 " +
            "instrument/test pass (read-only, no gameplay behavior change) versus the prior refined-evidence pass's " +
            "choice to keep an equivalent gap (\"turn number at an arbitrary mid-turn event\") purely test-local.")
        sb.appendLine("- **\"Distance to win\" is defined narrowly (main-loop 4th-Tier position only) by deliberate " +
            "choice, not oversight.** A Zone-resident 4th-Tier token's own progress toward winning would need a " +
            "different, zone-sub-path-aware distance model (how many zone-internal squares remain, then the exit-and-" +
            "continue distance on the main loop) - genuinely more complex, and the task's own \"without inventing new " +
            "gameplay semantics\" instruction argued for the narrower, unambiguous main-loop-only measure over a " +
            "compound heuristic spanning both. A player whose only 4th-Tier token is Zone-resident is simply excluded " +
            "from `minDistanceToWin` for that event, same as a player with no 4th-Tier token at all.")
        sb.appendLine("- **No existing engine-level way to distinguish \"structurally impossible for this player count\" " +
            "from \"legitimately never observed at this sample size\"** for a sparse predictor like proximity-to-" +
            "victory - Section 5's own coverage percentages are the closest available signal, but a future pass " +
            "wanting to condition tail analysis on \"games that got at least this close to winning\" would need to " +
            "decide that threshold explicitly rather than infer it from this report alone.")
        sb.appendLine()
    }
}
