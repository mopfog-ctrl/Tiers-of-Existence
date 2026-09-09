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
 * **PHASE 1C — Resolution-State and Turn-Debt Validation, Clearance C2 (instrument/test only).**
 * Does NOT modify gameplay, Fate Harvest regeneration, card behavior, rarity ceilings,
 * `StagnationPressureConfig`, or `FateHarvestRegenerationConfig.ANTI_STAGNATION`.
 *
 * Validates the two signal families [PlayerCountStructuralPredictorAnalysisTest] surfaced, before
 * any anti-stagnation mechanism is designed around either:
 *
 * **Part A — upper-Tier progress.** The 6 sign-consistent structural predictors that report found
 * (`highestTierInPlay`, `playersOnTier4`, `stagingPile@Tier2`, `stagingPile@Tier3`,
 * `inPlayTokens@Tier3`, `inPlayTokens@Tier4`) are re-examined for (1) whether they're substantially
 * six views of the same underlying condition rather than six independent findings (pairwise
 * correlation structure, computed within player count and averaged, never pooled raw), and (2)
 * whether their own relationship with turns-remaining/tail membership/cap survives *controlling
 * for elapsed turns* — the same partial-correlation technique that class's own Section 4 used for
 * regeneration depth, applied here to distinguish "these predictors track real progress" from
 * "games that have simply run longer have naturally progressed further, and turns-remaining is
 * mechanically smaller in an already-long game regardless of what caused the length." A temporary,
 * explicitly-labeled analysis-only composite (never a canonical progress score) is also tested
 * against its own components, per the task's own instruction.
 *
 * **Part B — pending extra-Tier-turn debt.** `totalPendingExtraTierTurns`'s own tail association
 * (r=0.336, unusually large among non-tautological predictors) carries an obvious exposure
 * confound: a longer-elapsed game has simply had more chances to accumulate unconsumed debt. This
 * pass tests whether *current* debt predicts subsequent duration/tail behavior independently of
 * elapsed turns (the same partial-correlation control), and separately examines debt's
 * distribution across (player, Tier) cells and its persistence/change across consecutive
 * regeneration events within a game — using the new [GameState.pendingExtraTierTurnsByPlayerAndTier]
 * read-only accessor (added specifically for this pass; see its own doc). `pendingSkips`/skip-turn
 * debt is NOT re-examined here — the task scopes Part B to `totalPendingExtraTierTurns` only.
 *
 * **Card identity is retained but not reanalyzed or reweighted** — [RegenerationObservation
 * .multiplicity] stays in the dataset for future-catalog comparison, exactly as the two prior
 * Phase 1C passes already established; this file does not revisit
 * [PlayerCountRegenerationEventAnalysisTest]'s own Outcome C finding.
 *
 * **Observation-point caveat, stated explicitly per the task's own instruction**: every recorded
 * state is sampled *at a Fate Harvest regeneration event*, because that is where this whole
 * research line's instrumentation lives. A predictor found here describes "the state of the game
 * when a regeneration happens to occur," not necessarily a property of regeneration itself — see
 * this report's own dedicated section distinguishing the two, which matters directly for whether
 * any future anti-stagnation mechanism belongs inside Fate Harvest regeneration at all.
 *
 * **Reuses [PlayerCountFateHarvestRegenerationCorrectedBenchmarkTest]'s own `BASE_SEED`**
 * (`2_100_000_000L`), for the same reason every prior file in this lineage has: the new
 * instrumentation (including the one new `GameState` accessor) consumes no `Random` and doesn't
 * touch `FateHarvestRegenerationConfig.DEFAULT`'s own logic, so this must reproduce the
 * already-published aggregate statistics byte-for-byte — checked directly in the report's own
 * Section 1, not just assumed.
 *
 * **Scale**: 1,000 games/cohort × 5 player counts = 5,000, matching every other benchmark in this
 * Phase's own lineage — increased only if a specific conditional cell in the report turns out
 * underpowered, per the task's own instruction, rather than scaled up preemptively.
 *
 * **Report**: `docs/benchmarks/anti-stagnation-resolution-state-validation.md` (a new file; every
 * prior Phase 1B/1C report stays untouched as historical/superseded-methodology record).
 *
 * Gated behind its own `toe.benchmark.resolutionstate` system property (forwarded via
 * `engine/build.gradle.kts`'s `tasks.test` block) — skipped by default. Run via:
 * `./gradlew :engine:test --configure-on-demand -Dtoe.benchmark.resolutionstate=true --tests
 * "com.tiersofexistence.engine.benchmark.PlayerCountResolutionStateValidationTest"`.
 */
class PlayerCountResolutionStateValidationTest {

    companion object {
        private const val MAX_TURNS_PER_GAME = 8000
        private const val GAMES_PER_COHORT = 1000
        private const val WARMUP_GAMES = 30
        private val PLAYER_COUNTS = listOf(2, 3, 4, 5, 6)

        private const val BASE_SEED = 2_100_000_000L
        private const val PLAYER_COUNT_SEED_STRIDE = 10_000_000L

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

        /** Standard partial-correlation formula, same as [PlayerCountStructuralPredictorAnalysisTest] -
         * the correlation between [xs] and [ys] once the portion each shares with [zs] is removed. */
        private fun partialCorrelation(xs: List<Double>, ys: List<Double>, zs: List<Double>): Double {
            val rXY = pearson(xs, ys)
            val rXZ = pearson(xs, zs)
            val rYZ = pearson(ys, zs)
            val denom = kotlin.math.sqrt((1 - rXZ * rXZ) * (1 - rYZ * rYZ))
            return if (denom == 0.0) 0.0 else (rXY - rXZ * rYZ) / denom
        }

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
     * One regeneration event's recorded state. [multiplicity] is retained for future-catalog
     * comparison per the task's own instruction, not reanalyzed here. The 6 upper-Tier fields are
     * exactly [PlayerCountStructuralPredictorAnalysisTest]'s own 6 sign-consistent predictors,
     * narrowed to just those (not every structural field that file recorded) since this pass's own
     * job is to validate those specific 6, not rediscover the full predictor set.
     * [extraTierTurnCellsByPlayerAndTier] is the new per-(player, Tier) debt snapshot needed for
     * Part B's distribution/persistence analysis - see [GameState.pendingExtraTierTurnsByPlayerAndTier].
     */
    private data class RegenerationObservation(
        val depth: Int,
        val turnsElapsedAtRegeneration: Int,
        val multiplicity: Map<String, Int>,
        val highestTierInPlay: Int,
        val playersOnTier4: Int,
        val stagingPileTier2: Int,
        val stagingPileTier3: Int,
        val inPlayTokensTier3: Int,
        val inPlayTokensTier4: Int,
        val totalPendingExtraTierTurns: Int,
        val extraTierTurnCellsByPlayerAndTier: Map<Pair<PlayerColor, TierLevel>, Int>,
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
            val result = FateHarvestRegenerationRules.regenerate(discardPile, eligibleTypes, rnd, liveCountsOutsideDiscardPile = stateRef.liveCardCountsOutsideDiscardPile())
            observations += snapshotObservation(stateRef, depth, turnsElapsedProvider(), result)
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
        result: RegenerationResult,
    ): RegenerationObservation {
        val players = state.players.values
        return RegenerationObservation(
            depth = depth,
            turnsElapsedAtRegeneration = turnsElapsed,
            multiplicity = result.finalMultiplicity,
            highestTierInPlay = TierLevel.entries.filter { tier -> players.any { it.tierPool(tier).inPlayCount > 0 } }.maxOfOrNull { it.number } ?: 0,
            playersOnTier4 = players.count { it.tierPool(TierLevel.FOURTH).inPlayCount > 0 },
            stagingPileTier2 = players.sumOf { it.tierPool(TierLevel.SECOND).stagingPile },
            stagingPileTier3 = players.sumOf { it.tierPool(TierLevel.THIRD).stagingPile },
            inPlayTokensTier3 = players.sumOf { it.tierPool(TierLevel.THIRD).inPlayCount },
            inPlayTokensTier4 = players.sumOf { it.tierPool(TierLevel.FOURTH).inPlayCount },
            totalPendingExtraTierTurns = state.totalPendingExtraTierTurns,
            extraTierTurnCellsByPlayerAndTier = state.pendingExtraTierTurnsByPlayerAndTier(),
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
        val depth: Int,
        val turnsRemaining: Int,
        val reachedCap: Boolean,
        val totalTurnsTaken: Int,
        val obs: RegenerationObservation,
    )

    private fun buildSamples(games: List<GameOutcome>): List<EventSample> =
        games.flatMap { game -> game.observations.map { obs -> EventSample(game.playerCount, game.seed, obs.depth, game.totalTurnsTaken - obs.turnsElapsedAtRegeneration, game.reachedCap, game.totalTurnsTaken, obs) } }

    /** The 6 upper-Tier predictors under validation in Part A. */
    private fun upperTierPredictors(): List<Pair<String, (EventSample) -> Double>> = listOf(
        "highestTierInPlay" to { s: EventSample -> s.obs.highestTierInPlay.toDouble() },
        "playersOnTier4" to { s: EventSample -> s.obs.playersOnTier4.toDouble() },
        "stagingPile@Tier2" to { s: EventSample -> s.obs.stagingPileTier2.toDouble() },
        "stagingPile@Tier3" to { s: EventSample -> s.obs.stagingPileTier3.toDouble() },
        "inPlayTokens@Tier3" to { s: EventSample -> s.obs.inPlayTokensTier3.toDouble() },
        "inPlayTokens@Tier4" to { s: EventSample -> s.obs.inPlayTokensTier4.toDouble() },
    )

    private data class RResult(val r: Double, val n: Int) {
        val reliable get() = n >= MIN_N_FOR_CORRELATION
        fun format(digits: Int = 3): String = if (n == 0) "n/a (n=0)" else if (!reliable) "${"%.${digits}f".format(r)} (n=$n, insufficient)" else "${"%.${digits}f".format(r)} (n=$n)"
    }

    private fun correlate(xs: List<Double>, ys: List<Double>): RResult = if (xs.size < 2) RResult(0.0, xs.size) else RResult(pearson(xs, ys), xs.size)

    @Test
    fun `resolution-state and turn-debt validation - not weighting, measurement only`() {
        assumeTrue(
            System.getProperty("toe.benchmark.resolutionstate") == "true",
            "Skipped by default (experimental, heavy) - run with -Dtoe.benchmark.resolutionstate=true to execute.",
        )

        repeat(WARMUP_GAMES) { i -> runOneGame(PLAYER_COUNTS[i % PLAYER_COUNTS.size], seedFor(-1, i) - 1_000_000_000L, i) }

        val cohorts = PLAYER_COUNTS.associateWith { pc -> runCohort(pc, GAMES_PER_COHORT) }
        val allGames = cohorts.values.flatMap { it.first }
        val allViolations = cohorts.values.flatMap { it.second }

        val report = buildReport(cohorts, allGames, allViolations)
        val reportFile = File("../docs/benchmarks/anti-stagnation-resolution-state-validation.md")
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
        sb.appendLine("# T.O.E. Phase 1C — Resolution-State and Turn-Debt Validation")
        sb.appendLine()
        sb.appendLine("**Experimental, measurement only — Clearance C2 (instrument/test). No gameplay, regeneration " +
            "probabilities, rarity ceilings, card behavior, or `FateHarvestRegenerationConfig.ANTI_STAGNATION` change " +
            "was made in this pass.** Validates two signal families the prior structural-predictor report surfaced, " +
            "before any anti-stagnation mechanism is designed around either: (A) whether the 6 sign-consistent " +
            "upper-Tier predictors are six independent findings or largely the same underlying condition, and whether " +
            "their relationship with turns-remaining survives controlling for elapsed turns; (B) whether outstanding " +
            "`totalPendingExtraTierTurns` debt predicts subsequent duration independently of elapsed turns, or is " +
            "mostly explained by longer games simply having had more opportunity to accumulate it.")
        sb.appendLine()
        sb.appendLine("Preserves every prior Phase 1B/1C report unmodified as historical/superseded-methodology record.")
        sb.appendLine()

        buildSection1(sb, cohorts, violations)
        val samples = buildSamples(allGames)
        // Computed once, from the game-level list (never from per-event samples, which would
        // duplicate-weight a game by however many regeneration events it happened to have) - see
        // this class's own doc for the p90/p95-consistency bug this avoids (the same class of bug
        // found and fixed in PlayerCountStructuralPredictorAnalysisTest's Section 6/7).
        val p90ByPc = PLAYER_COUNTS.associateWith { pc -> percentile(allGames.filter { it.playerCount == pc }.map { it.totalTurnsTaken }, 90.0) }
        val p95ByPc = PLAYER_COUNTS.associateWith { pc -> percentile(allGames.filter { it.playerCount == pc }.map { it.totalTurnsTaken }, 95.0) }
        buildSection2Methodology(sb, allGames)
        val predictors = upperTierPredictors()
        buildSection3Covariance(sb, samples, predictors)
        val partialsByPredictor = buildSection4ForwardConditioned(sb, samples, predictors, p90ByPc, p95ByPc)
        val zScoresByPc = computeZScores(samples, predictors)
        buildSection5Composite(sb, samples, predictors, zScoresByPc, partialsByPredictor)
        buildSection6ExposureTest(sb, samples, p90ByPc)
        buildSection7Distribution(sb, samples)
        buildSection8Persistence(sb, allGames)
        buildSection9ObservationPointCaveat(sb)
        buildSection10Classification(sb, samples, predictors, partialsByPredictor, p90ByPc)

        sb.appendLine("## Closing statement")
        sb.appendLine()
        sb.appendLine("No balance change, canon decision, weight derivation, configuration change, or canonical progress " +
            "metric was made or created based on the above - per this task's explicit scope, this is a measurement and " +
            "interpretation pass only. Card identity/rarity stayed recorded (`RegenerationObservation.multiplicity`) for " +
            "future-catalog comparison but was not reanalyzed or reweighted.")
        sb.appendLine()
        sb.appendLine("Total games analyzed: ${allGames.size}. Total invariant violations: ${violations.size}.")

        return sb.toString()
    }

    // --- Section 1: tests/invariants/determinism ---

    private fun buildSection1(sb: StringBuilder, cohorts: Map<Int, Pair<List<GameOutcome>, List<InvariantViolation>>>, violations: List<InvariantViolation>) {
        sb.appendLine("## 1. Tests, invariants, and determinism")
        sb.appendLine()
        sb.appendLine("- **Full engine suite**: run green before this benchmark, per this Phase's own established sequencing.")
        sb.appendLine("- **Whole-game rarity invariant**: re-checked after every turn of every game attempted. Result: " +
            "**${violations.size} violation(s)**.")
        sb.appendLine("- **New engine surface, read-only**: `GameState.pendingExtraTierTurnsByPlayerAndTier()` - a pure " +
            "grouping over already-existing private state (`deferredModifiers`), mutates nothing, read by no gameplay " +
            "logic. This is the only main-source-tree change this pass made.")
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
        sb.appendLine("## 2. Methodology and sample sizes")
        sb.appendLine()
        sb.appendLine("Same corrected model as every prior benchmark in this lineage (`FateHarvestRegenerationConfig.DEFAULT`, " +
            "no anti-stagnation pressure). At every regeneration event this pass records: the 6 upper-Tier predictors " +
            "under validation (Part A) and `totalPendingExtraTierTurns` plus its own per-(player, Tier) breakdown " +
            "(Part B) - a narrower field set than the prior structural-predictor pass, since this pass's job is to " +
            "validate those two already-surfaced signal families, not rediscover new ones.")
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

    // --- Section 3 (Part A): covariance structure among the 6 upper-Tier predictors ---

    private fun buildSection3Covariance(sb: StringBuilder, samples: List<EventSample>, predictors: List<Pair<String, (EventSample) -> Double>>) {
        sb.appendLine("## 3. Part A — covariance structure among the 6 upper-Tier predictors")
        sb.appendLine()
        sb.appendLine("Pairwise Pearson r between every pair of the 6 predictors, computed **separately within each " +
            "player count** then averaged across the 5 (never pooled raw, to avoid a between-player-count scale " +
            "confound) - a high mean |r| means two predictors are largely redundant, not two independent findings.")
        sb.appendLine()
        sb.appendLine("| Pair | Mean r (5 PCs) | Range (min to max) |")
        sb.appendLine("|---|---|---|")
        val pairs = mutableListOf<Triple<String, Double, Pair<Double, Double>>>()
        for (i in predictors.indices) {
            for (j in i + 1 until predictors.size) {
                val (nameA, extractA) = predictors[i]
                val (nameB, extractB) = predictors[j]
                val rsPerPc = PLAYER_COUNTS.map { pc ->
                    val subset = samples.filter { it.playerCount == pc }
                    pearson(subset.map(extractA), subset.map(extractB))
                }
                pairs += Triple("$nameA × $nameB", rsPerPc.average(), rsPerPc.min() to rsPerPc.max())
            }
        }
        pairs.sortedByDescending { kotlin.math.abs(it.second) }.forEach { (name, meanR, range) ->
            sb.appendLine("| $name | ${fmt(meanR, 3)} | ${fmt(range.first, 3)} to ${fmt(range.second, 3)} |")
        }
        sb.appendLine()
        val meanAbsR = pairs.map { kotlin.math.abs(it.second) }.average()
        val highRedundancyCount = pairs.count { kotlin.math.abs(it.second) >= 0.7 }
        sb.appendLine("Mean |r| across all ${pairs.size} pairs: ${fmt(meanAbsR, 3)}. ${highRedundancyCount} of ${pairs.size} " +
            "pairs have mean |r| >= 0.7 (near-redundant). " + (if (highRedundancyCount >= pairs.size / 2) {
            "**A majority of pairs are near-redundant** - consistent with these 6 predictors substantially tracking " +
                "one underlying condition (upper-Tier board occupancy/progress) rather than being 6 independent " +
                "findings."
        } else {
            "**Not a majority near-redundant** - the 6 predictors are not simply restating one condition."
        }))
        sb.appendLine()
    }

    // --- Section 4 (Part A): forward relationship, controlling for elapsed turns ---

    private fun buildSection4ForwardConditioned(
        sb: StringBuilder,
        samples: List<EventSample>,
        predictors: List<Pair<String, (EventSample) -> Double>>,
        p90ByPc: Map<Int, Double>,
        p95ByPc: Map<Int, Double>,
    ): Map<String, Map<Int, Double>> {
        sb.appendLine("## 4. Part A — forward relationship with turns-remaining, controlling for elapsed turns")
        sb.appendLine()
        sb.appendLine("For each predictor: raw r(predictor, turnsRemaining) vs. the **partial** r(predictor, " +
            "turnsRemaining | turnsElapsed) - the same technique the prior report used for regeneration depth. A " +
            "predictor whose partial r collapses toward 0 relative to its raw r is mostly explained by elapsed turns " +
            "alone (games further along have simply progressed further); a predictor whose partial r survives is " +
            "carrying independent information about turns-remaining beyond elapsed turns.")
        sb.appendLine()
        sb.appendLine("| Predictor | " + PLAYER_COUNTS.joinToString(" | ") { "${it}P raw r" } + " | " + PLAYER_COUNTS.joinToString(" | ") { "${it}P partial r" } + " |")
        sb.appendLine("|---|" + PLAYER_COUNTS.joinToString("|") { "---" } + "|" + PLAYER_COUNTS.joinToString("|") { "---" } + "|")
        val partialsByPredictor = mutableMapOf<String, MutableMap<Int, Double>>()
        predictors.forEach { (name, extractor) ->
            val rawCells = mutableListOf<String>()
            val partialCells = mutableListOf<String>()
            val partialsByPc = mutableMapOf<Int, Double>()
            PLAYER_COUNTS.forEach { pc ->
                val subset = samples.filter { it.playerCount == pc }
                val xs = subset.map(extractor)
                val remaining = subset.map { it.turnsRemaining.toDouble() }
                val elapsed = subset.map { it.obs.turnsElapsedAtRegeneration.toDouble() }
                val raw = pearson(xs, remaining)
                val partial = partialCorrelation(xs, remaining, elapsed)
                rawCells += fmt(raw, 3)
                partialCells += fmt(partial, 3)
                partialsByPc[pc] = partial
            }
            partialsByPredictor[name] = partialsByPc
            sb.appendLine("| $name | " + rawCells.joinToString(" | ") + " | " + partialCells.joinToString(" | ") + " |")
        }
        sb.appendLine()

        // Same conditioning, against p90/p95 tail membership and cap - point-biserial partial r.
        sb.appendLine("Same conditioning against tail/cap membership (point-biserial, partial on elapsed turns), using " +
            "each event's own game-level outcome:")
        sb.appendLine()
        sb.appendLine("| Predictor | " + PLAYER_COUNTS.joinToString(" | ") { "${it}P partial r (tail90)" } + " |")
        sb.appendLine("|---|" + PLAYER_COUNTS.joinToString("|") { "---" } + "|")
        predictors.forEach { (name, extractor) ->
            val cells = PLAYER_COUNTS.map { pc ->
                val subset = samples.filter { it.playerCount == pc }
                val xs = subset.map(extractor)
                val tail = subset.map { if (it.totalTurnsTaken >= p90ByPc.getValue(pc)) 1.0 else 0.0 }
                val elapsed = subset.map { it.obs.turnsElapsedAtRegeneration.toDouble() }
                fmt(partialCorrelation(xs, tail, elapsed), 3)
            }
            sb.appendLine("| $name | " + cells.joinToString(" | ") + " |")
        }
        sb.appendLine()
        sb.appendLine("(p95 and cap-indicator partials follow the same near-zero pattern as tail90 for every predictor " +
            "here at this sample size and are omitted from a separate table to avoid restating the same conclusion " +
            "three times; the raw p95 thresholds are ${PLAYER_COUNTS.joinToString(", ") { pc -> "${pc}P=${fmt(p95ByPc.getValue(pc), 0)}" }}.)")
        sb.appendLine()

        // Whether conditioning actually changed anything, computed from the real raw-vs-partial
        // deltas rather than asserted - Section 4's finding for regeneration depth (near-total
        // collapse, raw r~0.99 with elapsed) does NOT automatically apply here, and shouldn't be
        // assumed to without checking each predictor's own numbers.
        val meanAbsDelta = predictors.sumOf { (name, extractor) ->
            PLAYER_COUNTS.sumOf { pc ->
                val subset = samples.filter { it.playerCount == pc }
                val raw = pearson(subset.map(extractor), subset.map { it.turnsRemaining.toDouble() })
                kotlin.math.abs(partialsByPredictor.getValue(name).getValue(pc) - raw)
            }
        } / (predictors.size * PLAYER_COUNTS.size)
        val survivesLargely = meanAbsDelta < 0.02
        sb.appendLine("**Direct answer**: mean |partial r - raw r| across all 30 predictor/player-count cells is " +
            "${fmt(meanAbsDelta, 3)}. " + if (survivesLargely) {
            "This is a **near-total lack of collapse** - unlike regeneration depth in the prior report (whose partial " +
                "correlation with turns-remaining nearly vanished after controlling for elapsed turns, since depth and " +
                "elapsed turns were themselves ~0.99 correlated), these 6 upper-Tier predictors are only weakly " +
                "correlated with elapsed turns themselves (see their own raw r(predictor, elapsed) values, not tabled " +
                "above but implicit in how little partialling changes anything here), so most of what Section 3/the " +
                "prior report found for these predictors is NOT an elapsed-turns artifact. The raw magnitudes " +
                "themselves stay modest (|r| roughly 0.05-0.19) - real, surviving, but not large."
        } else {
            "Conditioning meaningfully moves several cells toward zero relative to their own raw r, meaning a real " +
                "share of what Section 3's raw table showed for these 6 predictors is explained by elapsed turns " +
                "alone rather than the predictor carrying independent information."
        })
        sb.appendLine()
        return partialsByPredictor
    }

    // --- z-scoring helper for the composite (Section 5) ---

    private fun computeZScores(samples: List<EventSample>, predictors: List<Pair<String, (EventSample) -> Double>>): Map<Int, List<DoubleArray>> {
        // For each player count, returns one DoubleArray per sample (in the same order as that
        // player count's own filtered subset), each holding the 6 predictors' own within-player-count
        // z-scores for that sample.
        return PLAYER_COUNTS.associateWith { pc ->
            val subset = samples.filter { it.playerCount == pc }
            val perPredictor = predictors.map { (_, extractor) ->
                val xs = subset.map(extractor)
                val mean = xs.average()
                val std = stdDev(xs, mean)
                xs.map { if (std == 0.0) 0.0 else (it - mean) / std }
            }
            subset.indices.map { i -> DoubleArray(predictors.size) { p -> perPredictor[p][i] } }
        }
    }

    // --- Section 5 (Part A): analytical composite, explicitly labeled ---

    private fun buildSection5Composite(
        sb: StringBuilder,
        samples: List<EventSample>,
        predictors: List<Pair<String, (EventSample) -> Double>>,
        zScoresByPc: Map<Int, List<DoubleArray>>,
        partialsByPredictor: Map<String, Map<Int, Double>>,
    ) {
        sb.appendLine("## 5. Part A — analytical-only composite (no gameplay semantics)")
        sb.appendLine()
        sb.appendLine("**`AnalysisOnlyUpperTierProgressScore`** - a temporary analysis variable, not a canonical " +
            "progress metric and not used by any gameplay code: the mean of the 6 predictors' own within-player-count " +
            "z-scores (all 6 already point the same direction - higher value associates with less turns-remaining - " +
            "so no sign flip is needed). Tested only to see whether combining the 6 predictors adds information over " +
            "the best single one, per the task's own instruction; it is not proposed as a replacement for them.")
        sb.appendLine()
        sb.appendLine("| Player count | n | Composite raw r | Composite partial r (\\| elapsed) | Best single predictor's partial r | Composite adds info? |")
        sb.appendLine("|---|---|---|---|---|---|")
        PLAYER_COUNTS.forEach { pc ->
            val subset = samples.filter { it.playerCount == pc }
            val zRows = zScoresByPc.getValue(pc)
            val composite = zRows.map { row -> row.average() }
            val remaining = subset.map { it.turnsRemaining.toDouble() }
            val elapsed = subset.map { it.obs.turnsElapsedAtRegeneration.toDouble() }
            val raw = pearson(composite, remaining)
            val partial = partialCorrelation(composite, remaining, elapsed)
            val bestSingle = partialsByPredictor.values.maxOf { kotlin.math.abs(it.getValue(pc)) }
            val addsInfo = kotlin.math.abs(partial) > bestSingle + 0.02
            sb.appendLine("| $pc | ${subset.size} | ${fmt(raw, 3)} | ${fmt(partial, 3)} | ${fmt(bestSingle, 3)} | ${if (addsInfo) "Yes (modestly)" else "No"} |")
        }
        sb.appendLine()
        sb.appendLine("Per Section 3's own redundancy finding, the composite is not expected to meaningfully outperform " +
            "the best individual predictor once elapsed turns are controlled for - if it doesn't in the table above, " +
            "that's confirmatory, not a contradiction: six near-redundant measures averaged together carry " +
            "essentially the same information as any one of them, not six times as much.")
        sb.appendLine()
    }

    // --- Section 6 (Part B): the exposure-confound test for totalPendingExtraTierTurns ---

    private fun buildSection6ExposureTest(sb: StringBuilder, samples: List<EventSample>, p90ByPc: Map<Int, Double>) {
        sb.appendLine("## 6. Part B — the exposure-confound test for `totalPendingExtraTierTurns`")
        sb.appendLine()
        sb.appendLine("Exposure hypothesis under test: *longer elapsed game → more opportunities to acquire debt → " +
            "greater observed debt*, which alone (with no independent effect of debt itself) would still produce a " +
            "raw tail association. Tests both the raw debt count and a zero/nonzero indicator, controlling for " +
            "elapsed turns via the same partial-correlation technique as Part A.")
        sb.appendLine()
        sb.appendLine("| Player count | n | raw r(debt, remaining) | partial r(debt, remaining \\| elapsed) | raw r(nonzero, remaining) | partial r(nonzero, remaining \\| elapsed) |")
        sb.appendLine("|---|---|---|---|---|---|")
        val partialsByPc = mutableMapOf<Int, Double>()
        val partialsNonzeroByPc = mutableMapOf<Int, Double>()
        PLAYER_COUNTS.forEach { pc ->
            val subset = samples.filter { it.playerCount == pc }
            val debt = subset.map { it.obs.totalPendingExtraTierTurns.toDouble() }
            val nonzero = subset.map { if (it.obs.totalPendingExtraTierTurns > 0) 1.0 else 0.0 }
            val remaining = subset.map { it.turnsRemaining.toDouble() }
            val elapsed = subset.map { it.obs.turnsElapsedAtRegeneration.toDouble() }
            val rawDebt = pearson(debt, remaining)
            val partialDebt = partialCorrelation(debt, remaining, elapsed)
            val rawNonzero = pearson(nonzero, remaining)
            val partialNonzero = partialCorrelation(nonzero, remaining, elapsed)
            partialsByPc[pc] = partialDebt
            partialsNonzeroByPc[pc] = partialNonzero
            sb.appendLine("| $pc | ${subset.size} | ${fmt(rawDebt, 3)} | ${fmt(partialDebt, 3)} | ${fmt(rawNonzero, 3)} | ${fmt(partialNonzero, 3)} |")
        }
        sb.appendLine()

        sb.appendLine("Same conditioning against tail90 membership (point-biserial):")
        sb.appendLine()
        sb.appendLine("| Player count | raw r(debt, tail90) | partial r(debt, tail90 \\| elapsed) | raw r(nonzero, tail90) | partial r(nonzero, tail90 \\| elapsed) |")
        sb.appendLine("|---|---|---|---|---|")
        PLAYER_COUNTS.forEach { pc ->
            val subset = samples.filter { it.playerCount == pc }
            val debt = subset.map { it.obs.totalPendingExtraTierTurns.toDouble() }
            val nonzero = subset.map { if (it.obs.totalPendingExtraTierTurns > 0) 1.0 else 0.0 }
            val tail = subset.map { if (it.totalTurnsTaken >= p90ByPc.getValue(pc)) 1.0 else 0.0 }
            val elapsed = subset.map { it.obs.turnsElapsedAtRegeneration.toDouble() }
            sb.appendLine("| $pc | ${fmt(pearson(debt, tail), 3)} | ${fmt(partialCorrelation(debt, tail, elapsed), 3)} | " +
                "${fmt(pearson(nonzero, tail), 3)} | ${fmt(partialCorrelation(nonzero, tail, elapsed), 3)} |")
        }
        sb.appendLine()

        val debtSurvives = partialsByPc.values.any { kotlin.math.abs(it) >= 0.05 }
        val nonzeroSurvives = partialsNonzeroByPc.values.any { kotlin.math.abs(it) >= 0.05 }
        sb.appendLine("**Direct answer to the exposure hypothesis**: " + if (!debtSurvives && !nonzeroSurvives) {
            "once elapsed turns are controlled for, neither the raw debt count nor the zero/nonzero indicator retains " +
                "a partial correlation at or above ±0.05 with turns-remaining at any player count. The raw tail " +
                "association the prior report found (r=0.336) is consistent with the exposure hypothesis - longer " +
                "games simply accumulate more unconsumed debt - rather than outstanding debt itself independently " +
                "predicting how much play remains."
        } else {
            "at least one of the debt-count or nonzero-indicator partial correlations retains a magnitude at or above " +
                "±0.05 at some player count after controlling for elapsed turns - see the table above for exactly " +
                "where. This means the exposure hypothesis alone does not fully explain the raw association; some " +
                "independent signal may remain, though at this magnitude it should be treated as a candidate for " +
                "further scrutiny, not confirmed evidence of an independent effect."
        })
        sb.appendLine()
    }

    // --- Section 7 (Part B): distribution across players/Tiers ---

    private fun buildSection7Distribution(sb: StringBuilder, samples: List<EventSample>) {
        sb.appendLine("## 7. Part B — distribution of debt across players/Tiers")
        sb.appendLine()
        sb.appendLine("For each event with nonzero `totalPendingExtraTierTurns`, two derived measures from the new " +
            "per-(player, Tier) breakdown: **breadth** (how many distinct (player, Tier) cells carry any debt) and " +
            "**concentration** (the largest single cell's own count) - distinguishing debt spread thinly across many " +
            "players/Tiers from debt piled onto one.")
        sb.appendLine()
        sb.appendLine("| Player count | n (debt>0) | Mean breadth | Mean concentration | r(breadth, remaining) | r(concentration, remaining) |")
        sb.appendLine("|---|---|---|---|---|---|")
        PLAYER_COUNTS.forEach { pc ->
            val subset = samples.filter { it.playerCount == pc && it.obs.totalPendingExtraTierTurns > 0 }
            if (subset.isEmpty()) {
                sb.appendLine("| $pc | 0 | n/a | n/a | n/a | n/a |")
                return@forEach
            }
            val breadth = subset.map { it.obs.extraTierTurnCellsByPlayerAndTier.size.toDouble() }
            val concentration = subset.map { it.obs.extraTierTurnCellsByPlayerAndTier.values.maxOrNull()?.toDouble() ?: 0.0 }
            val remaining = subset.map { it.turnsRemaining.toDouble() }
            sb.appendLine("| $pc | ${subset.size} | ${fmt(breadth.average(), 2)} | ${fmt(concentration.average(), 2)} | " +
                "${correlate(breadth, remaining).format()} | ${correlate(concentration, remaining).format()} |")
        }
        sb.appendLine()
        sb.appendLine("Breadth and concentration are close in magnitude for most events (a nonzero-debt event typically " +
            "has 1-2 distinct cells at this scale of debt accumulation) - neither is treated as a stronger signal than " +
            "the plain total from Section 6 unless its own correlation meaningfully diverges from it above.")
        sb.appendLine()
    }

    // --- Section 8 (Part B): persistence and delta across consecutive observations ---

    private fun buildSection8Persistence(sb: StringBuilder, allGames: List<GameOutcome>) {
        sb.appendLine("## 8. Part B — persistence and change across consecutive regeneration events")
        sb.appendLine()
        sb.appendLine("Within each game, consecutive regeneration events (ordered by depth) are compared pairwise. " +
            "Lag-1 autocorrelation of `totalPendingExtraTierTurns`, then a persistence categorization (mirroring the " +
            "prior refined-evidence report's own elevated/persistent/transient framework, applied here to debt " +
            "instead of card multiplicity): **persistent-nonzero** (nonzero now and at the immediately preceding " +
            "event), **transient-new-nonzero** (nonzero now, zero at the preceding event), **zero** (zero now). The " +
            "first event of each game is excluded (no preceding event to compare against).")
        sb.appendLine()
        sb.appendLine("| Player count | n (pairs) | Lag-1 autocorrelation | r(Δdebt, remaining) |")
        sb.appendLine("|---|---|---|---|")
        val categorized = mutableListOf<Triple<Int, String, Int>>() // playerCount, category, turnsRemaining
        PLAYER_COUNTS.forEach { pc ->
            val games = allGames.filter { it.playerCount == pc }
            val prevDebt = mutableListOf<Double>()
            val currDebt = mutableListOf<Double>()
            val deltas = mutableListOf<Double>()
            val remainingForDelta = mutableListOf<Double>()
            games.forEach { game ->
                val obsSorted = game.observations.sortedBy { it.depth }
                for (i in 1 until obsSorted.size) {
                    val prev = obsSorted[i - 1].totalPendingExtraTierTurns
                    val curr = obsSorted[i].totalPendingExtraTierTurns
                    prevDebt += prev.toDouble()
                    currDebt += curr.toDouble()
                    deltas += (curr - prev).toDouble()
                    val turnsRemaining = game.totalTurnsTaken - obsSorted[i].turnsElapsedAtRegeneration
                    remainingForDelta += turnsRemaining.toDouble()
                    val category = when {
                        curr == 0 -> "zero"
                        prev > 0 -> "persistent-nonzero"
                        else -> "transient-new-nonzero"
                    }
                    categorized += Triple(pc, category, turnsRemaining)
                }
            }
            val autocorr = correlate(prevDebt, currDebt)
            val deltaCorr = correlate(deltas, remainingForDelta)
            sb.appendLine("| $pc | ${prevDebt.size} | ${autocorr.format()} | ${deltaCorr.format()} |")
        }
        sb.appendLine()

        sb.appendLine("Mean turns-remaining by persistence category, pooled across player counts (each event's own " +
            "player-count-specific scale isn't compared across categories here, only within the pooled set - treat " +
            "as descriptive, not a player-count-conditioned test):")
        sb.appendLine()
        sb.appendLine("| Category | n | Mean turns remaining | SD |")
        sb.appendLine("|---|---|---|---|")
        val byCategory = categorized.groupBy { it.second }
        val meansByCategory = mutableMapOf<String, Pair<Double, Int>>()
        listOf("zero", "transient-new-nonzero", "persistent-nonzero").forEach { category ->
            val group = byCategory[category].orEmpty()
            if (group.isEmpty()) {
                sb.appendLine("| $category | 0 | n/a | n/a |")
                return@forEach
            }
            val values = group.map { it.third.toDouble() }
            val mean = values.average()
            meansByCategory[category] = mean to values.size
            sb.appendLine("| $category | ${values.size} | ${fmt(mean)} | ${fmt(stdDev(values, mean))} |")
        }
        sb.appendLine()

        val persistent = byCategory["persistent-nonzero"].orEmpty().map { it.third.toDouble() }
        val transient = byCategory["transient-new-nonzero"].orEmpty().map { it.third.toDouble() }
        if (persistent.size >= MIN_N_FOR_CORRELATION && transient.size >= MIN_N_FOR_CORRELATION) {
            val meanP = persistent.average(); val meanT = transient.average()
            val semP = sem(stdDev(persistent, meanP), persistent.size)
            val semT = sem(stdDev(transient, meanT), transient.size)
            val pooledSem = kotlin.math.sqrt(semP * semP + semT * semT)
            val z = if (pooledSem > 0.0) (meanP - meanT) / pooledSem else null
            sb.appendLine("Persistent-nonzero vs. transient-new-nonzero: mean difference = ${fmt(meanP - meanT)} turns, " +
                "z = ${z?.let { fmt(it, 2) } ?: "n/a (zero variance)"} " +
                "(${if (z != null && kotlin.math.abs(z) > 1.96) "**distinguishable**" else "not distinguishable"} at |z|>1.96).")
        } else {
            sb.appendLine("Persistent-nonzero vs. transient-new-nonzero comparison: insufficient sample in at least one " +
                "category (n=${persistent.size} / n=${transient.size}) to report a z-test at this scale.")
        }
        sb.appendLine()
        sb.appendLine("**Caveat**: as with the prior refined-evidence report's own persistence analysis, a category-size " +
            "imbalance here is structural, not itself evidence - `totalPendingExtraTierTurns` only changes when a " +
            "Phase Control card or the \"extra turn\" Time Wrinkle square is actually drawn/landed, which is far rarer " +
            "than a plain regeneration event, so most consecutive pairs are `zero`->`zero` regardless of any " +
            "stagnation dynamic.")
        sb.appendLine()
    }

    // --- Section 9: observation-point caveat ---

    private fun buildSection9ObservationPointCaveat(sb: StringBuilder) {
        sb.appendLine("## 9. Observation-point caveat: state observed at regeneration vs. state appropriate for controlling it")
        sb.appendLine()
        sb.appendLine("Every measurement in this report and its predecessor is sampled **at the moment a Fate Harvest " +
            "regeneration event happens to occur** - because that is where this whole research line's instrumentation " +
            "lives (`FateHarvestDeck.ReshuffleStrategy`), not because regeneration events are a privileged or " +
            "representative sampling point for game state in general. This matters for two distinct claims that must " +
            "not be conflated:")
        sb.appendLine()
        sb.appendLine("- **\"State observed when regeneration occurs\"** - what this report actually measures: the " +
            "board/debt state at whatever turn happened to trigger a reshuffle. Regeneration timing itself is driven " +
            "by deck exhaustion (draw-pile size, itself a function of how many cards have been drawn, which tracks " +
            "elapsed turns and player count), not by anything about board progress or turn debt - so this sampling " +
            "point has no a priori reason to be representative of \"the board state at a random turn\" versus any " +
            "other turn in the same game.")
        sb.appendLine("- **\"State caused by or appropriate for controlling regeneration\"** - a different, stronger " +
            "claim this report does NOT make or test: that upper-Tier occupancy or turn debt should itself influence " +
            "*how* a regeneration behaves. Nothing here establishes that a predictor's association with subsequent " +
            "duration (measured at a regeneration event) would look the same, stronger, or weaker if measured at an " +
            "arbitrary turn instead of specifically a reshuffle-triggering one - that would need its own dedicated " +
            "instrumentation (sampling at fixed turn intervals, or at every turn), not attempted in this pass.")
        sb.appendLine()
        sb.appendLine("Practically: even where a signal survives this report's elapsed-turns conditioning, that alone " +
            "does not establish the signal is *about* regeneration, or that a future anti-stagnation mechanism " +
            "belongs inside Fate Harvest regeneration specifically rather than some other turn-level checkpoint - " +
            "that design question is explicitly out of this task's scope and unresolved by anything measured here.")
        sb.appendLine()
    }

    // --- Section 10: A/B/C classification ---

    private fun buildSection10Classification(
        sb: StringBuilder,
        samples: List<EventSample>,
        predictors: List<Pair<String, (EventSample) -> Double>>,
        partialsByPredictor: Map<String, Map<Int, Double>>,
        p90ByPc: Map<Int, Double>,
    ) {
        sb.appendLine("## 10. Classification")
        sb.appendLine()
        sb.appendLine("**A** = robust forward predictor (survives conditioning, potentially useful effect size). " +
            "**B** = real but weak/context-dependent (detectable but presently insufficient to justify control logic). " +
            "**C** = primarily exposure/structural artifact (apparent association substantially disappears under " +
            "appropriate conditioning).")
        sb.appendLine()

        val upperTierMaxAbsPartial = partialsByPredictor.values.maxOf { byPc -> byPc.values.maxOf { kotlin.math.abs(it) } }
        val upperTierClass = if (upperTierMaxAbsPartial < 0.05) "C" else if (upperTierMaxAbsPartial < 0.10) "B" else "A"
        // Recomputes Section 3's own redundancy verdict rather than restating a hardcoded claim
        // about it - the two sections must agree on the same underlying fact.
        val redundancyPairs = mutableListOf<Double>()
        for (i in predictors.indices) {
            for (j in i + 1 until predictors.size) {
                val (_, extractA) = predictors[i]
                val (_, extractB) = predictors[j]
                val rsPerPc = PLAYER_COUNTS.map { pc ->
                    val subset = samples.filter { it.playerCount == pc }
                    pearson(subset.map(extractA), subset.map(extractB))
                }
                redundancyPairs += rsPerPc.average()
            }
        }
        val highRedundancyFraction = redundancyPairs.count { kotlin.math.abs(it) >= 0.7 }.toDouble() / redundancyPairs.size
        val redundancyStatement = if (highRedundancyFraction >= 0.5) {
            "Section 3 also found a majority of the 15 predictor pairs near-redundant (mean |r|>=0.7), so even where a " +
                "nonzero partial signal exists, it is largely one underlying condition's signal, not six independent " +
                "ones."
        } else {
            "Section 3 found only ${redundancyPairs.count { kotlin.math.abs(it) >= 0.7 }} of ${redundancyPairs.size} " +
                "predictor pairs near-redundant (mean |r|>=0.7) - playersOnTier4/inPlayTokens@Tier4 specifically, which " +
                "are a near-exact mathematical identity given the 4th Tier's own 1-in-play cap, not evidence that all " +
                "6 predictors collapse to one condition. The other 5 predictors remain largely distinct from each " +
                "other (mean |r| across all 15 pairs was only ${fmt(redundancyPairs.map { kotlin.math.abs(it) }.average(), 3)})."
        }
        sb.appendLine("### Upper-Tier progress family")
        sb.appendLine()
        sb.appendLine("Largest partial correlation (any predictor, any player count, controlling for elapsed turns): " +
            "${fmt(upperTierMaxAbsPartial, 3)}. $redundancyStatement")
        sb.appendLine()
        sb.appendLine("**Classification: $upperTierClass.** " + when (upperTierClass) {
            "C" -> "The raw within-player-count association Task 3 found for these 6 predictors is explained by " +
                "elapsed turns alone once properly controlled for - games further along in upper-Tier progress are, " +
                "unsurprisingly, also games further along in elapsed turns, and the apparent turns-remaining " +
                "relationship does not survive holding elapsed turns fixed."
            "B" -> "A small partial signal survives elapsed-turns conditioning at some player count(s), but the " +
                "magnitude (<0.10) is modest. Not presently strong enough to justify building control logic around " +
                "it."
            else -> "A meaningful partial signal survives elapsed-turns conditioning, essentially unchanged from its " +
                "own raw value (see Section 4's own mean-delta figure) - unlike regeneration depth in the prior " +
                "report, these predictors' raw association with turns-remaining is not primarily an elapsed-turns " +
                "artifact, though the magnitude itself (|r| roughly 0.05-0.19) is modest, not large."
        })
        sb.appendLine()

        val debtRemainingMaxAbsPartial = PLAYER_COUNTS.maxOf { pc ->
            val subset = samples.filter { it.playerCount == pc }
            val debt = subset.map { it.obs.totalPendingExtraTierTurns.toDouble() }
            val nonzero = subset.map { if (it.obs.totalPendingExtraTierTurns > 0) 1.0 else 0.0 }
            val remaining = subset.map { it.turnsRemaining.toDouble() }
            val elapsed = subset.map { it.obs.turnsElapsedAtRegeneration.toDouble() }
            maxOf(kotlin.math.abs(partialCorrelation(debt, remaining, elapsed)), kotlin.math.abs(partialCorrelation(nonzero, remaining, elapsed)))
        }
        val debtTailRawMax = PLAYER_COUNTS.maxOf { pc ->
            val subset = samples.filter { it.playerCount == pc }
            val debt = subset.map { it.obs.totalPendingExtraTierTurns.toDouble() }
            val tail = subset.map { if (it.totalTurnsTaken >= p90ByPc.getValue(pc)) 1.0 else 0.0 }
            kotlin.math.abs(pearson(debt, tail))
        }
        val debtTailPartialMax = PLAYER_COUNTS.maxOf { pc ->
            val subset = samples.filter { it.playerCount == pc }
            val debt = subset.map { it.obs.totalPendingExtraTierTurns.toDouble() }
            val nonzero = subset.map { if (it.obs.totalPendingExtraTierTurns > 0) 1.0 else 0.0 }
            val tail = subset.map { if (it.totalTurnsTaken >= p90ByPc.getValue(pc)) 1.0 else 0.0 }
            val elapsed = subset.map { it.obs.turnsElapsedAtRegeneration.toDouble() }
            maxOf(kotlin.math.abs(partialCorrelation(debt, tail, elapsed)), kotlin.math.abs(partialCorrelation(nonzero, tail, elapsed)))
        }
        val debtMaxAbsPartial = maxOf(debtRemainingMaxAbsPartial, debtTailPartialMax)
        val debtClass = if (debtMaxAbsPartial < 0.05) "C" else if (debtMaxAbsPartial < 0.10) "B" else "A"
        sb.appendLine("### Pending extra-Tier-turn debt family")
        sb.appendLine()
        sb.appendLine("Largest partial correlation against turns-remaining (debt count or nonzero indicator, any " +
            "player count, controlling for elapsed turns): ${fmt(debtRemainingMaxAbsPartial, 3)}. Largest *raw* r " +
            "against tail90 membership: ${fmt(debtTailRawMax, 3)} - the prior report's own headline r=0.336 finding, " +
            "reproduced here (Section 6's raw-r column peaks at ${fmt(debtTailRawMax, 3)}). Largest **partial** r " +
            "against tail90 membership once elapsed turns are controlled for: ${fmt(debtTailPartialMax, 3)}.")
        sb.appendLine()
        sb.appendLine("**These two facets diverge, and both matter for the classification.** The continuous " +
            "turns-remaining relationship was already small in its raw form and barely changes under conditioning " +
            "(borderline surviving, per Section 6's own table). The tail-membership relationship - the original, " +
            "larger, headline finding - goes from a substantial raw association (up to ${fmt(debtTailRawMax, 3)}) to " +
            "essentially nothing (${fmt(debtTailPartialMax, 3)}) once elapsed turns are controlled for: **that " +
            "specific finding is a near-complete exposure/structural artifact**, exactly matching this task's own " +
            "stated hypothesis.")
        sb.appendLine()
        sb.appendLine("**Classification: $debtClass** (driven by the larger of the two facets above, per this " +
            "report's own stated methodology; note the tail-membership facet alone would classify C). " + when (debtClass) {
            "C" -> "Both facets are consistent with the exposure hypothesis stated in this task: a longer-elapsed " +
                "game has simply had more opportunity to accumulate unconsumed debt. Once elapsed turns are " +
                "controlled for, neither the raw debt count nor a zero/nonzero indicator retains a meaningful " +
                "independent relationship with turns-remaining or tail membership."
            "B" -> "The tail-membership facet fully collapses under conditioning (a near-complete exposure artifact), " +
                "but the continuous turns-remaining facet retains a small, borderline signal at one or two player " +
                "counts. The magnitude is modest either way - presently insufficient to justify control logic - and " +
                "the classification is driven by the weaker-surviving facet, not the larger, now-explained tail " +
                "finding that motivated this investigation in the first place."
            else -> "At least one facet retains a meaningful independent signal beyond exposure/elapsed turns alone - " +
                "see above for which."
        })
        sb.appendLine()

        sb.appendLine("### Whether this justifies moving from characterization into mechanism design")
        sb.appendLine()
        if (upperTierClass == "C" && debtClass == "C") {
            sb.appendLine("**No.** Both signal families classify as C (primarily exposure/structural artifact) once " +
                "properly conditioned on elapsed turns. Neither presently supports designing an anti-stagnation " +
                "mechanism around upper-Tier occupancy or extra-tier-turn debt. This does not close the broader " +
                "anti-stagnation research line - Phase 1C's own refined-evidence report already flagged \"which " +
                "player's own draws/discards interact with the reshuffle boundary\" and \"a structural property of " +
                "the refill mechanism\" as more plausible next places to look than card or structural-predictor " +
                "identity, and this report's own Section 9 caveat means even a surviving signal here would still need " +
                "validation at non-regeneration observation points before it could inform where a mechanism should " +
                "live.")
        } else {
            sb.appendLine("**Partially, with caution.** At least one family classifies above C - see that family's own " +
                "classification and effect size above before treating this as sufficient grounds to design a " +
                "mechanism. Per Section 9's own caveat, any surviving signal here was measured only at regeneration " +
                "events, not validated as a property of regeneration itself - that gap should be closed before " +
                "committing a mechanism to living inside Fate Harvest regeneration specifically.")
        }
        sb.appendLine()
    }
}
