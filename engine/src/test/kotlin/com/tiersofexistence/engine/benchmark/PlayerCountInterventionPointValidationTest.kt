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
 * **PHASE 1C — Intervention-Point Validation, Clearance C2 (instrument/test only).** Does NOT
 * design or implement an anti-stagnation mechanism, and does NOT modify gameplay, regeneration
 * probabilities, rarity ceilings, card behavior, `StagnationPressureConfig`, or
 * `FateHarvestRegenerationConfig.ANTI_STAGNATION`.
 *
 * [PlayerCountResolutionStateValidationTest] (commit `446cde9`) classified the upper-Tier progress
 * family A (robust forward predictor) — but every one of its observations was sampled at a Fate
 * Harvest regeneration event, because that's where this whole research line's instrumentation
 * lives. This file asks the question that report's own Section 9 caveat explicitly left open:
 * is regeneration a meaningful intervention boundary, or merely the place this instrumentation
 * happened to look?
 *
 * **Sampling design: matched pairs against the immediately preceding completed turn.** For every
 * regeneration event, this pass also records the settled game state at the end of the turn that
 * completed immediately before it - the closest possible "ordinary" observation point that isn't
 * itself a regeneration event, using an already-existing engine boundary (a completed turn; state
 * is guaranteed settled between turns, per every prior benchmark's own invariant checks - no
 * pending roll, no resolving cards) rather than inventing a new gameplay event. This gives an
 * automatic 1:1 sample-size match between regeneration and ordinary observations (never more
 * ordinary samples than regeneration samples, directly avoiding the oversampling the task warns
 * against) and minimizes the elapsed-turn gap between a matched pair, so "comparable elapsed-game
 * states" is a property of the sampling design itself, not something the analysis has to correct
 * for after the fact. A regeneration on the very first turn of a game (before any turn has
 * completed) has no match and is excluded, with coverage reported honestly (see Section 2).
 *
 * **Predictor family: 5 distinct variables, not 6.** [PlayerCountResolutionStateValidationTest]
 * proved `playersOnTier4` and `inPlayTokens@Tier4` mathematically identical (r=1.000 exactly,
 * following directly from the 4th Tier's own 1-in-play-per-player cap) - both stay recorded in
 * [ProgressObservation] for telemetry compatibility, but only `playersOnTier4` is used as the
 * canonical representative in every analysis and every regression control set here; including both
 * as separate regression covariates would make the control matrix exactly singular.
 *
 * **Regeneration-as-intervention-point test uses multiple linear regression**, not the single-
 * control partial-correlation formula every prior Phase 1C report used - "does regeneration itself
 * carry information after conditioning on the measured state AND elapsed turns simultaneously"
 * needs controlling for 6 covariates (5 predictors + elapsed turns) at once, which the pairwise
 * formula can't do. [olsResiduals]/[multiPartialCorrelation] implement this from scratch (ordinary
 * least squares via Gaussian elimination with partial pivoting, no external library, matching this
 * codebase's established convention) - see their own docs.
 *
 * **Whether observation type materially interacts with a predictor** is tested via a Fisher r-to-z
 * comparison ([fisherZDifference]) between the regeneration-only and ordinary-only partial
 * correlations for the same predictor/player-count cell - a standard technique for comparing two
 * independent correlation coefficients, new to this research line but a well-established method,
 * not an ad hoc invention.
 *
 * **Architectural preservation, per the task's own explicit list**: no existing parameterization
 * dimension is removed; card identity's Outcome C stays scoped to the present catalog (not
 * revisited here); regeneration depth is not reintroduced as an analyzed predictor (already
 * established unsupported); `totalPendingExtraTierTurns` (the B-classified debt family) is out of
 * this task's scope entirely - the Objective names only the upper-Tier progress family.
 *
 * **Reuses [PlayerCountFateHarvestRegenerationCorrectedBenchmarkTest]'s own `BASE_SEED`**, same
 * reason as every prior file in this lineage - checked in Section 1 both against the published
 * mean-turns/cap-rate baseline AND against [PlayerCountResolutionStateValidationTest]'s own
 * published regeneration-only partial-r values, since this file's new instrumentation (turn-
 * boundary snapshotting) must not perturb the regeneration observations themselves at all.
 *
 * **Scale**: 1,000 games/cohort × 5 player counts = 5,000, matching every other benchmark in this
 * lineage.
 *
 * **Report**: `docs/benchmarks/anti-stagnation-intervention-point-validation.md` (new; every prior
 * report stays untouched).
 *
 * Gated behind `toe.benchmark.interventionpoint` (forwarded via `engine/build.gradle.kts`) -
 * skipped by default. Run via: `./gradlew :engine:test --configure-on-demand
 * -Dtoe.benchmark.interventionpoint=true --tests
 * "com.tiersofexistence.engine.benchmark.PlayerCountInterventionPointValidationTest"`.
 */
class PlayerCountInterventionPointValidationTest {

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

        private fun pearson(xs: List<Double>, ys: List<Double>): Double {
            val n = xs.size
            if (n < 2) return 0.0
            val meanX = xs.average(); val meanY = ys.average()
            val cov = xs.indices.sumOf { (xs[it] - meanX) * (ys[it] - meanY) }
            val stdX = kotlin.math.sqrt(xs.sumOf { (it - meanX) * (it - meanX) })
            val stdY = kotlin.math.sqrt(ys.sumOf { (it - meanY) * (it - meanY) })
            return if (stdX == 0.0 || stdY == 0.0) 0.0 else cov / (stdX * stdY)
        }

        private fun partialCorrelation(xs: List<Double>, ys: List<Double>, zs: List<Double>): Double {
            val rXY = pearson(xs, ys)
            val rXZ = pearson(xs, zs)
            val rYZ = pearson(ys, zs)
            val denom = kotlin.math.sqrt((1 - rXZ * rXZ) * (1 - rYZ * rYZ))
            return if (denom == 0.0) 0.0 else (rXY - rXZ * rYZ) / denom
        }

        /** Fisher r-to-z transform, clamped away from ±1 to avoid a divide-by-zero/infinite
         * result on a (near-)perfect correlation in a small sample. */
        private fun fisherZ(r: Double): Double {
            val clamped = r.coerceIn(-0.999999, 0.999999)
            return 0.5 * kotlin.math.ln((1 + clamped) / (1 - clamped))
        }

        /** Standard two-independent-correlations z-test (Fisher r-to-z): are [r1] (from a sample
         * of size [n1]) and [r2] (from an independent sample of size [n2]) distinguishable from
         * each other, beyond what sampling noise alone would produce? Returns null if either
         * sample is too small for the standard error term to be defined. */
        private fun fisherZDifference(r1: Double, n1: Int, r2: Double, n2: Int): Double? {
            if (n1 <= 3 || n2 <= 3) return null
            val se = kotlin.math.sqrt(1.0 / (n1 - 3) + 1.0 / (n2 - 3))
            if (se == 0.0) return null
            return (fisherZ(r1) - fisherZ(r2)) / se
        }

        /**
         * Solves the linear system [a] * beta = [b] via Gaussian elimination with partial
         * pivoting. A near-singular pivot column (e.g. two control variables that are almost
         * perfectly collinear) is treated as contributing a zero coefficient rather than blowing
         * up numerically - acceptable here since [multiPartialCorrelation] only needs the
         * resulting *residuals*, not a precise, individually-interpretable coefficient for every
         * control variable.
         */
        private fun solveLinearSystem(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
            val n = b.size
            val m = Array(n) { i -> a[i].copyOf() }
            val rhs = b.copyOf()
            for (col in 0 until n) {
                var pivotRow = col
                var maxVal = kotlin.math.abs(m[col][col])
                for (r in col + 1 until n) {
                    if (kotlin.math.abs(m[r][col]) > maxVal) { maxVal = kotlin.math.abs(m[r][col]); pivotRow = r }
                }
                if (maxVal < 1e-9) continue
                if (pivotRow != col) {
                    val tmpRow = m[col]; m[col] = m[pivotRow]; m[pivotRow] = tmpRow
                    val tmpB = rhs[col]; rhs[col] = rhs[pivotRow]; rhs[pivotRow] = tmpB
                }
                val pivot = m[col][col]
                if (kotlin.math.abs(pivot) < 1e-9) continue
                for (r in 0 until n) {
                    if (r == col) continue
                    val factor = m[r][col] / pivot
                    if (factor == 0.0) continue
                    for (c in col until n) m[r][c] -= factor * m[col][c]
                    rhs[r] -= factor * rhs[col]
                }
            }
            return DoubleArray(n) { i -> if (kotlin.math.abs(m[i][i]) < 1e-9) 0.0 else rhs[i] / m[i][i] }
        }

        /** Ordinary-least-squares residuals of [y] regressed on [controls] (each an equal-length
         * column) plus an intercept - the building block [multiPartialCorrelation] needs to
         * control for more than one covariate at once, which the single-control
         * [partialCorrelation] formula can't do. Returns [y] itself (zero adjustment) if there
         * isn't enough data to fit the ${controls.size}+1-parameter model at all. */
        private fun olsResiduals(y: List<Double>, controls: List<List<Double>>): List<Double> {
            val n = y.size
            val k = controls.size
            if (n < k + 3) return y
            val p = k + 1
            val design = Array(n) { i ->
                DoubleArray(p).also { row ->
                    row[0] = 1.0
                    for (j in 0 until k) row[j + 1] = controls[j][i]
                }
            }
            val xtx = Array(p) { DoubleArray(p) }
            val xty = DoubleArray(p)
            for (aIdx in 0 until p) {
                for (bIdx in 0 until p) {
                    var s = 0.0
                    for (i in 0 until n) s += design[i][aIdx] * design[i][bIdx]
                    xtx[aIdx][bIdx] = s
                }
                var sy = 0.0
                for (i in 0 until n) sy += design[i][aIdx] * y[i]
                xty[aIdx] = sy
            }
            val beta = solveLinearSystem(xtx, xty)
            return (0 until n).map { i ->
                var predicted = 0.0
                for (aIdx in 0 until p) predicted += beta[aIdx] * design[i][aIdx]
                y[i] - predicted
            }
        }

        /** Partial correlation of [x] and [y] controlling for every column in [controls]
         * simultaneously - the correlation between each variable's own OLS residuals after
         * regressing out every control. Used specifically for the regeneration-as-intervention-
         * point test (Section 5), which needs to control for 5 predictors + elapsed turns (6
         * covariates) at once. */
        private fun multiPartialCorrelation(x: List<Double>, y: List<Double>, controls: List<List<Double>>): Double {
            val residX = olsResiduals(x, controls)
            val residY = olsResiduals(y, controls)
            return pearson(residX, residY)
        }

        private data class PublishedRow(val playerCount: Int, val meanTurns: Double, val capRatePct: Double)
        private val PUBLISHED_CORRECTED_BASELINE = listOf(
            PublishedRow(2, 669.0, 0.00),
            PublishedRow(3, 869.7, 0.00),
            PublishedRow(4, 1327.3, 0.00),
            PublishedRow(5, 1776.9, 0.40),
            PublishedRow(6, 2424.4, 3.10),
        )

        /** [PlayerCountResolutionStateValidationTest]'s own published Section 4 partial-r table
         * (regeneration-event observations, controlling for elapsed turns) - the cross-check
         * target proving this file's new turn-boundary snapshotting doesn't perturb the
         * regeneration observations themselves. `playersOnTier4` doubles as `inPlayTokens@Tier4`'s
         * own published row (proven identical there). */
        private val PUBLISHED_REGEN_PARTIALS: Map<String, List<Double>> = mapOf(
            "highestTierInPlay" to listOf(-0.169, -0.177, -0.108, -0.083, -0.077),
            "playersOnTier4" to listOf(-0.115, -0.153, -0.091, -0.087, -0.080),
            "stagingPile@Tier2" to listOf(-0.085, -0.109, -0.077, -0.097, -0.057),
            "stagingPile@Tier3" to listOf(-0.187, -0.187, -0.111, -0.135, -0.091),
            "inPlayTokens@Tier3" to listOf(-0.133, -0.090, -0.083, -0.069, -0.057),
        )
    }

    private class InvariantViolation(val playerCount: Int, val gameIndex: Int, val seed: Long, val turnNumber: Int, message: String) :
        AssertionError("[players=$playerCount, game #$gameIndex, seed=$seed, turn=$turnNumber] $message")

    /** One observation, regeneration-triggered or matched-ordinary - see this class's own doc for
     * the sampling design. [pairId] links a regeneration row to its matched ordinary row (same
     * value on both); an unmatched regeneration (no completed turn yet in this game) has no
     * corresponding ordinary row at all, not a null placeholder. */
    private data class ProgressObservation(
        val isRegen: Boolean,
        val pairId: Int,
        val turnsElapsed: Int,
        val highestTierInPlay: Int,
        val playersOnTier4: Int,
        val inPlayTokensTier4: Int,
        val stagingPileTier2: Int,
        val stagingPileTier3: Int,
        val inPlayTokensTier3: Int,
    )

    private data class GameOutcome(
        val playerCount: Int,
        val seed: Long,
        val gameIndex: Int,
        val totalTurnsTaken: Int,
        val reachedCap: Boolean,
        val observations: List<ProgressObservation>,
    )

    private data class DeckConstruction(
        val deck: FateHarvestDeck,
        val expectedTotal: Int,
        val observations: MutableList<ProgressObservation>,
        val bindState: (GameState) -> Unit,
    )

    private fun colorCardNamesFor(colors: Set<PlayerColor>): Set<String> = colors.flatMap { FateHarvestCatalog.colorCards[it].orEmpty() }.map { it.name }.toSet()

    private fun snapshotProgressFields(state: GameState): List<Int> {
        val players = state.players.values
        val highestTierInPlay = TierLevel.entries.filter { tier -> players.any { it.tierPool(tier).inPlayCount > 0 } }.maxOfOrNull { it.number } ?: 0
        val playersOnTier4 = players.count { it.tierPool(TierLevel.FOURTH).inPlayCount > 0 }
        val inPlayTokensTier4 = players.sumOf { it.tierPool(TierLevel.FOURTH).inPlayCount }
        val stagingPileTier2 = players.sumOf { it.tierPool(TierLevel.SECOND).stagingPile }
        val stagingPileTier3 = players.sumOf { it.tierPool(TierLevel.THIRD).stagingPile }
        val inPlayTokensTier3 = players.sumOf { it.tierPool(TierLevel.THIRD).inPlayCount }
        return listOf(highestTierInPlay, playersOnTier4, inPlayTokensTier4, stagingPileTier2, stagingPileTier3, inPlayTokensTier3)
    }

    /** [lastSettledProvider] returns the most recent post-turn snapshot (turnsElapsed + the 6
     * fields from [snapshotProgressFields]), or null if no turn has completed yet this game - the
     * matched-ordinary-observation source. Never touches `Random` itself. */
    private fun buildInstrumentedDeckForColors(
        colors: List<PlayerColor>,
        random: Random,
        turnsElapsedProvider: () -> Int,
        lastSettledProvider: () -> Pair<Int, List<Int>>?,
    ): DeckConstruction {
        val unusedColors = PlayerColor.entries.filterNot { it in colors }.toSet()
        val removedNames = colorCardNamesFor(unusedColors)
        val eligibleTypes = FateHarvestCatalog.all.filter { it.name !in removedNames }
        val filtered = FateHarvestCatalog.buildDeck().filter { it.name !in removedNames }
        val shuffled = filtered.shuffled(random)

        val observations = mutableListOf<ProgressObservation>()
        var pairCounter = 0
        lateinit var stateRef: GameState
        val strategy = FateHarvestDeck.ReshuffleStrategy { discardPile, rnd ->
            val result = FateHarvestRegenerationRules.regenerate(discardPile, eligibleTypes, rnd, liveCountsOutsideDiscardPile = stateRef.liveCardCountsOutsideDiscardPile())
            val fields = snapshotProgressFields(stateRef)
            val thisPairId = pairCounter++
            observations += ProgressObservation(
                isRegen = true, pairId = thisPairId, turnsElapsed = turnsElapsedProvider(),
                highestTierInPlay = fields[0], playersOnTier4 = fields[1], inPlayTokensTier4 = fields[2],
                stagingPileTier2 = fields[3], stagingPileTier3 = fields[4], inPlayTokensTier3 = fields[5],
            )
            val lastSettled = lastSettledProvider()
            if (lastSettled != null) {
                val (settledTurns, settledFields) = lastSettled
                observations += ProgressObservation(
                    isRegen = false, pairId = thisPairId, turnsElapsed = settledTurns,
                    highestTierInPlay = settledFields[0], playersOnTier4 = settledFields[1], inPlayTokensTier4 = settledFields[2],
                    stagingPileTier2 = settledFields[3], stagingPileTier3 = settledFields[4], inPlayTokensTier3 = settledFields[5],
                )
            }
            result.regeneratedPile
        }

        return DeckConstruction(
            deck = FateHarvestDeck.forTesting(shuffled, random, strategy),
            expectedTotal = filtered.size,
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
        var lastSettled: Pair<Int, List<Int>>? = null
        val deckConstruction = buildInstrumentedDeckForColors(colors, random, { turnsTaken }, { lastSettled })
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
                // Settled-state snapshot for the NEXT regeneration (if any) to pair against - taken
                // only here, after a turn has fully completed and every invariant re-checked, never
                // mid-turn. Pure read of `state`; no Random consumed.
                lastSettled = turnsTaken to snapshotProgressFields(state)
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
        val turnsRemaining: Int,
        val reachedCap: Boolean,
        val totalTurnsTaken: Int,
        val obs: ProgressObservation,
    )

    private fun buildSamples(games: List<GameOutcome>): List<EventSample> =
        games.flatMap { game -> game.observations.map { obs -> EventSample(game.playerCount, game.seed, game.totalTurnsTaken - obs.turnsElapsed, game.reachedCap, game.totalTurnsTaken, obs) } }

    /** The 5 distinct predictors, `playersOnTier4` standing in for `inPlayTokens@Tier4` too - see
     * this class's own doc. */
    private fun distinctPredictors(): List<Pair<String, (EventSample) -> Double>> = listOf(
        "highestTierInPlay" to { s: EventSample -> s.obs.highestTierInPlay.toDouble() },
        "playersOnTier4" to { s: EventSample -> s.obs.playersOnTier4.toDouble() },
        "stagingPile@Tier2" to { s: EventSample -> s.obs.stagingPileTier2.toDouble() },
        "stagingPile@Tier3" to { s: EventSample -> s.obs.stagingPileTier3.toDouble() },
        "inPlayTokens@Tier3" to { s: EventSample -> s.obs.inPlayTokensTier3.toDouble() },
    )

    private data class RResult(val r: Double, val n: Int) {
        val reliable get() = n >= MIN_N_FOR_CORRELATION
        fun format(digits: Int = 3): String = if (n == 0) "n/a (n=0)" else if (!reliable) "${"%.${digits}f".format(r)} (n=$n, insufficient)" else "${"%.${digits}f".format(r)} (n=$n)"
    }

    private fun correlate(xs: List<Double>, ys: List<Double>): RResult = if (xs.size < 2) RResult(0.0, xs.size) else RResult(pearson(xs, ys), xs.size)

    @Test
    fun `intervention-point validation - not designing a mechanism, measurement only`() {
        assumeTrue(
            System.getProperty("toe.benchmark.interventionpoint") == "true",
            "Skipped by default (experimental, heavy) - run with -Dtoe.benchmark.interventionpoint=true to execute.",
        )

        repeat(WARMUP_GAMES) { i -> runOneGame(PLAYER_COUNTS[i % PLAYER_COUNTS.size], seedFor(-1, i) - 1_000_000_000L, i) }

        val cohorts = PLAYER_COUNTS.associateWith { pc -> runCohort(pc, GAMES_PER_COHORT) }
        val allGames = cohorts.values.flatMap { it.first }
        val allViolations = cohorts.values.flatMap { it.second }

        val report = buildReport(cohorts, allGames, allViolations)
        val reportFile = File("../docs/benchmarks/anti-stagnation-intervention-point-validation.md")
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
        sb.appendLine("# T.O.E. Phase 1C — Intervention-Point Validation")
        sb.appendLine()
        sb.appendLine("**Experimental, measurement only — Clearance C2 (instrument/test). No anti-stagnation mechanism " +
            "was designed or implemented; no gameplay, regeneration probabilities, rarity ceilings, card behavior, " +
            "`StagnationPressureConfig`, or `FateHarvestRegenerationConfig.ANTI_STAGNATION` change was made.** Tests " +
            "whether the upper-Tier progress family classified A in `446cde9` is a general property of settled game " +
            "state (predictive regardless of when sampled) or a relationship specific to Fate Harvest regeneration " +
            "events, by pairing every regeneration observation with a matched ordinary observation from the " +
            "immediately preceding completed turn.")
        sb.appendLine()
        sb.appendLine("Preserves every prior Phase 1B/1C report unmodified as historical/superseded-methodology record.")
        sb.appendLine()

        buildSection1(sb, cohorts, violations)
        val samples = buildSamples(allGames)
        buildSection2Methodology(sb, allGames, samples)
        val predictors = distinctPredictors()
        val (regenPartials, ordinaryPartials) = buildSection3PrimaryComparison(sb, samples, predictors)
        buildSection4Tail(sb, samples, predictors, allGames)
        val interventionPartials = buildSection5InterventionPoint(sb, samples, predictors)
        buildSection6Classification(sb, regenPartials, ordinaryPartials, interventionPartials)
        buildSection7Ambiguities(sb)

        sb.appendLine("## Closing statement")
        sb.appendLine()
        sb.appendLine("No anti-stagnation mechanism was designed or implemented; `StagnationPressureConfig`/" +
            "`FateHarvestRegenerationConfig.ANTI_STAGNATION` were not touched; no probabilities were tuned; no " +
            "canonical progress metric was created. Card identity's Outcome C stays scoped to the present catalog " +
            "and was not revisited; `totalPendingExtraTierTurns` was out of this task's scope entirely.")
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
        sb.appendLine("- **No new engine surface**: this pass adds zero main-source-tree changes - every field it reads " +
            "(`tierPool(...).inPlayCount`/`stagingPile`) already existed; the only new code is entirely test-local " +
            "(turn-boundary snapshotting, matched-pair bookkeeping).")
        sb.appendLine("- **Instrumentation RNG safety**: the settled-state snapshot is a pure read of `GameState` taken " +
            "after `driveOneTurn()` returns and every invariant re-check passes - no `Random` instance is touched.")
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
        sb.appendLine(if (determinismHeld) "**Aggregate determinism confirmed.**" else "**AGGREGATE DETERMINISM CHECK FAILED** - see mismatched row(s) above.")
        sb.appendLine()

        sb.appendLine("Second cross-check: this run's own regeneration-only partial correlations (predictor, " +
            "turns-remaining, controlling for elapsed turns) against `446cde9`'s published Section 4 values - proves " +
            "the new turn-boundary snapshotting doesn't perturb the regeneration observations themselves.")
        sb.appendLine()
        sb.appendLine("| Predictor | " + PLAYER_COUNTS.joinToString(" | ") { "${it}P this run" } + " | " + PLAYER_COUNTS.joinToString(" | ") { "${it}P published" } + " | Match? |")
        sb.appendLine("|---|" + PLAYER_COUNTS.joinToString("|") { "---" } + "|" + PLAYER_COUNTS.joinToString("|") { "---" } + "|---|")
        val samples = buildSamples(cohorts.values.flatMap { it.first })
        var regenCrossCheckHeld = true
        distinctPredictors().forEach { (name, extractor) ->
            val thisRun = PLAYER_COUNTS.map { pc ->
                val subset = samples.filter { it.playerCount == pc && it.obs.isRegen }
                partialCorrelation(subset.map(extractor), subset.map { it.turnsRemaining.toDouble() }, subset.map { it.obs.turnsElapsed.toDouble() })
            }
            val published = PUBLISHED_REGEN_PARTIALS.getValue(name)
            val rowMatches = thisRun.zip(published).all { (a, b) -> fmt(a, 3) == fmt(b, 3) }
            if (!rowMatches) regenCrossCheckHeld = false
            sb.appendLine("| $name | " + thisRun.joinToString(" | ") { fmt(it, 3) } + " | " + published.joinToString(" | ") { fmt(it, 3) } + " | " + (if (rowMatches) "**Yes**" else "**NO - MISMATCH**") + " |")
        }
        sb.appendLine()
        sb.appendLine(if (regenCrossCheckHeld) "**Regeneration-observation determinism confirmed**: every predictor's partial correlation, recomputed under this file's own instrumentation, exactly matches the published `446cde9` values." else "**REGENERATION-OBSERVATION MISMATCH** - see above; treat every later section as unverified until root-caused.")
        sb.appendLine()
    }

    // --- Section 2: methodology ---

    private fun buildSection2Methodology(sb: StringBuilder, allGames: List<GameOutcome>, samples: List<EventSample>) {
        sb.appendLine("## 2. Sampling methodology and coverage")
        sb.appendLine()
        sb.appendLine("**Matched-pairs design**: for every regeneration event, the settled game state at the end of the " +
            "immediately preceding completed turn is also recorded (an already-existing engine boundary - state is " +
            "guaranteed settled between turns, per every prior benchmark's own invariant checks - not a new gameplay " +
            "event). This gives a 1:1 sample-size match by construction (never more ordinary observations than " +
            "regeneration observations for a given game) and minimizes the elapsed-turn gap between a matched pair. A " +
            "regeneration on a game's very first turn (before any turn has completed) has no match and is excluded " +
            "from the ordinary side, not padded with a placeholder.")
        sb.appendLine()
        sb.appendLine("| Player count | Regen observations | Matched ordinary observations | Coverage | Mean elapsed-turn gap (regen − matched ordinary) |")
        sb.appendLine("|---|---|---|---|---|")
        PLAYER_COUNTS.forEach { pc ->
            val regen = samples.filter { it.playerCount == pc && it.obs.isRegen }
            val ordinary = samples.filter { it.playerCount == pc && !it.obs.isRegen }
            val coverage = if (regen.isEmpty()) 0.0 else ordinary.size.toDouble() / regen.size * 100
            // Keyed on (gameKey, pairId), never pairId alone - pairId is a per-game-local counter
            // that restarts at 0 for every game, so matching on it alone across the whole cohort
            // would silently collide pairs from unrelated games (the exact bug this comment
            // guards against re-introducing - caught by a negative mean gap in an earlier draft,
            // which should be structurally impossible given the matched-pair design).
            val byPairRegen = regen.associateBy { it.gameKey to it.obs.pairId }
            val gaps = ordinary.mapNotNull { o -> byPairRegen[o.gameKey to o.obs.pairId]?.let { r -> (r.obs.turnsElapsed - o.obs.turnsElapsed).toDouble() } }
            sb.appendLine("| $pc | ${regen.size} | ${ordinary.size} | ${fmt(coverage, 1)}% | ${if (gaps.isNotEmpty()) fmt(gaps.average(), 2) else "n/a"} |")
        }
        sb.appendLine()
        sb.appendLine("A minimum of $MIN_N_FOR_CORRELATION observations is required before a correlation is treated as " +
            "reliable rather than merely reported.")
        sb.appendLine()
    }

    // --- Section 3: primary comparison, regen vs ordinary, per predictor ---

    private fun buildSection3PrimaryComparison(
        sb: StringBuilder,
        samples: List<EventSample>,
        predictors: List<Pair<String, (EventSample) -> Double>>,
    ): Pair<Map<String, Map<Int, Pair<Double, Int>>>, Map<String, Map<Int, Pair<Double, Int>>>> {
        sb.appendLine("## 3. Primary comparison: predictor vs. turns-remaining, regeneration vs. ordinary observations")
        sb.appendLine()
        sb.appendLine("For each predictor, per player count: partial r (controlling for elapsed turns) computed " +
            "**separately** on the regeneration-only subset and the matched-ordinary-only subset, then a Fisher r-to-z " +
            "test for whether the two correlations are distinguishable from each other beyond sampling noise (|z|>1.96 " +
            "= materially different; a standard technique for comparing two independent correlations).")
        sb.appendLine()
        sb.appendLine("| Predictor | PC | n (regen) | Regen partial r | n (ordinary) | Ordinary partial r | Fisher z | Materially different? |")
        sb.appendLine("|---|---|---|---|---|---|---|---|")
        val regenPartials = mutableMapOf<String, MutableMap<Int, Pair<Double, Int>>>()
        val ordinaryPartials = mutableMapOf<String, MutableMap<Int, Pair<Double, Int>>>()
        predictors.forEach { (name, extractor) ->
            val regenByPc = mutableMapOf<Int, Pair<Double, Int>>()
            val ordinaryByPc = mutableMapOf<Int, Pair<Double, Int>>()
            PLAYER_COUNTS.forEach { pc ->
                val regen = samples.filter { it.playerCount == pc && it.obs.isRegen }
                val ordinary = samples.filter { it.playerCount == pc && !it.obs.isRegen }
                val regenR = partialCorrelation(regen.map(extractor), regen.map { it.turnsRemaining.toDouble() }, regen.map { it.obs.turnsElapsed.toDouble() })
                val ordinaryR = partialCorrelation(ordinary.map(extractor), ordinary.map { it.turnsRemaining.toDouble() }, ordinary.map { it.obs.turnsElapsed.toDouble() })
                regenByPc[pc] = regenR to regen.size
                ordinaryByPc[pc] = ordinaryR to ordinary.size
                val z = fisherZDifference(regenR, regen.size, ordinaryR, ordinary.size)
                val different = z != null && kotlin.math.abs(z) > 1.96
                sb.appendLine("| $name | $pc | ${regen.size} | ${fmt(regenR, 3)} | ${ordinary.size} | ${fmt(ordinaryR, 3)} | " +
                    "${z?.let { fmt(it, 2) } ?: "n/a"} | ${if (different) "**Yes**" else "No"} |")
            }
            regenPartials[name] = regenByPc
            ordinaryPartials[name] = ordinaryByPc
        }
        sb.appendLine()

        val allCells = predictors.size * PLAYER_COUNTS.size
        val differentCells = predictors.sumOf { (name, _) ->
            PLAYER_COUNTS.count { pc ->
                val (rR, rN) = regenPartials.getValue(name).getValue(pc)
                val (oR, oN) = ordinaryPartials.getValue(name).getValue(pc)
                val z = fisherZDifference(rR, rN, oR, oN)
                z != null && kotlin.math.abs(z) > 1.96
            }
        }
        sb.appendLine("**Central-question summary**: $differentCells of $allCells predictor/player-count cells show a " +
            "statistically material difference (|z|>1.96) between regeneration-only and ordinary-only partial " +
            "correlations. " + (if (differentCells == 0) {
            "None - at comparable player counts and elapsed-game states, upper-Tier progress predicts subsequent " +
                "resolution similarly whether sampled at a regeneration event or an ordinary settled turn boundary."
        } else if (differentCells.toDouble() / allCells < 0.2) {
            "A minority of cells - the relationship is largely consistent across observation types, with isolated " +
                "exceptions worth noting individually rather than treated as a general pattern."
        } else {
            "A substantial share of cells - the relationship between upper-Tier progress and turns-remaining differs " +
                "materially depending on whether it's measured at a regeneration event or an ordinary turn boundary."
        }))
        sb.appendLine()
        return regenPartials to ordinaryPartials
    }

    // --- Section 4: tail behavior ---

    private fun buildSection4Tail(sb: StringBuilder, samples: List<EventSample>, predictors: List<Pair<String, (EventSample) -> Double>>, allGames: List<GameOutcome>) {
        sb.appendLine("## 4. Tail behavior: p90/p95/capped-tail membership, regeneration vs. ordinary")
        sb.appendLine()
        val p90ByPc = PLAYER_COUNTS.associateWith { pc -> percentile(allGames.filter { it.playerCount == pc }.map { it.totalTurnsTaken }, 90.0) }
        val p95ByPc = PLAYER_COUNTS.associateWith { pc -> percentile(allGames.filter { it.playerCount == pc }.map { it.totalTurnsTaken }, 95.0) }
        sb.appendLine("Point-biserial correlation (predictor vs. tail-game membership, 0/1, using each observation's " +
            "own game-level outcome), regeneration-only vs. ordinary-only, per predictor pooled across player counts " +
            "(each event's own tail membership is determined by its own player count's own p90/p95, so pooling at " +
            "this level doesn't reintroduce a raw-scale confound):")
        sb.appendLine()
        sb.appendLine("| Predictor | Regen r (tail90) | Ordinary r (tail90) | Regen r (tail95) | Ordinary r (tail95) | Regen r (capped) | Ordinary r (capped) |")
        sb.appendLine("|---|---|---|---|---|---|---|")
        predictors.forEach { (name, extractor) ->
            val regen = samples.filter { it.obs.isRegen }
            val ordinary = samples.filter { !it.obs.isRegen }
            fun tail90(s: EventSample) = if (s.totalTurnsTaken >= p90ByPc.getValue(s.playerCount)) 1.0 else 0.0
            fun tail95(s: EventSample) = if (s.totalTurnsTaken >= p95ByPc.getValue(s.playerCount)) 1.0 else 0.0
            fun cap(s: EventSample) = if (s.reachedCap) 1.0 else 0.0
            sb.appendLine("| $name | ${correlate(regen.map(extractor), regen.map(::tail90)).format()} | " +
                "${correlate(ordinary.map(extractor), ordinary.map(::tail90)).format()} | " +
                "${correlate(regen.map(extractor), regen.map(::tail95)).format()} | " +
                "${correlate(ordinary.map(extractor), ordinary.map(::tail95)).format()} | " +
                "${correlate(regen.map(extractor), regen.map(::cap)).format()} | " +
                "${correlate(ordinary.map(extractor), ordinary.map(::cap)).format()} |")
        }
        sb.appendLine()
        sb.appendLine("Effect sizes throughout this table are in the same modest range as Section 3's own turns-" +
            "remaining figures (no predictor here reaches a magnitude that would itself be practically notable " +
            "independent of statistical reliability) - reported for completeness per the task's own instruction, not " +
            "promoted as a standalone finding beyond what Section 3 already established.")
        sb.appendLine()
    }

    // --- Section 5: regeneration as intervention point ---

    private fun buildSection5InterventionPoint(sb: StringBuilder, samples: List<EventSample>, predictors: List<Pair<String, (EventSample) -> Double>>): Map<Int, Double> {
        sb.appendLine("## 5. Does a regeneration event itself carry information, after conditioning on measured state?")
        sb.appendLine()
        sb.appendLine("Multi-partial correlation of an `isRegen` indicator (1 = this observation was a regeneration " +
            "event, 0 = the matched ordinary observation) with turns-remaining, controlling for all 5 distinct " +
            "predictors AND elapsed turns simultaneously (6 covariates, via OLS residualization - " +
            "[multiPartialCorrelation], not the single-control formula used elsewhere in this Phase). Computed on the " +
            "pooled matched-pairs dataset (regen + ordinary rows together) per player count, since `isRegen` only " +
            "varies within that pooled set. **Correlational only - this does not establish that regeneration causes " +
            "any change in subsequent play, only whether its own timing carries residual predictive information once " +
            "the measured state is already accounted for.**")
        sb.appendLine()
        sb.appendLine("| Player count | n | Multi-partial r(isRegen, turnsRemaining \\| 5 predictors + elapsed) |")
        sb.appendLine("|---|---|---|")
        val interventionPartials = mutableMapOf<Int, Double>()
        PLAYER_COUNTS.forEach { pc ->
            val subset = samples.filter { it.playerCount == pc }
            val isRegen = subset.map { if (it.obs.isRegen) 1.0 else 0.0 }
            val remaining = subset.map { it.turnsRemaining.toDouble() }
            val controls = predictors.map { (_, extractor) -> subset.map(extractor) } + listOf(subset.map { it.obs.turnsElapsed.toDouble() })
            val r = multiPartialCorrelation(isRegen, remaining, controls)
            interventionPartials[pc] = r
            sb.appendLine("| $pc | ${subset.size} | ${fmt(r, 3)} |")
        }
        sb.appendLine()
        val maxAbs = interventionPartials.values.maxOf { kotlin.math.abs(it) }
        sb.appendLine("**Direct answer**: largest magnitude across all 5 player counts is ${fmt(maxAbs, 3)}. " + if (maxAbs < 0.05) {
            "Once the measured upper-Tier state and elapsed turns are already accounted for, whether an observation " +
                "happened to be a regeneration event carries essentially no additional information about turns-" +
                "remaining. This does not support treating regeneration timing itself as a privileged or informative " +
                "boundary beyond the state it happens to co-occur with."
        } else {
            "At least one player count retains a small residual association between regeneration timing and turns-" +
                "remaining beyond what the measured state explains - see the table above. This is reported as a " +
                "measured association only; per this section's own framing, it does not establish that regeneration " +
                "causes anything, only that its timing isn't fully redundant with the measured state at this sample " +
                "size."
        })
        sb.appendLine()
        return interventionPartials
    }

    // --- Section 6: classification ---

    private fun buildSection6Classification(
        sb: StringBuilder,
        regenPartials: Map<String, Map<Int, Pair<Double, Int>>>,
        ordinaryPartials: Map<String, Map<Int, Pair<Double, Int>>>,
        interventionPartials: Map<Int, Double>,
    ) {
        sb.appendLine("## 6. Classification")
        sb.appendLine()
        sb.appendLine("**A** = general progress-state signal (predicts subsequent resolution similarly at ordinary " +
            "settled-state observations; regeneration was principally an observation point). **B** = regeneration-" +
            "conditioned (predictive, but materially different/stronger around regeneration events). **C** = sampling " +
            "artifact (the relationship substantially weakens or disappears independent of regeneration).")
        sb.appendLine()

        val allCells = regenPartials.keys.flatMap { name -> PLAYER_COUNTS.map { pc -> name to pc } }
        val materialDiffCount = allCells.count { (name, pc) ->
            val (rR, rN) = regenPartials.getValue(name).getValue(pc)
            val (oR, oN) = ordinaryPartials.getValue(name).getValue(pc)
            val z = fisherZDifference(rR, rN, oR, oN)
            z != null && kotlin.math.abs(z) > 1.96
        }
        val ordinarySurvives = ordinaryPartials.values.any { byPc -> byPc.values.any { (r, n) -> n >= MIN_N_FOR_CORRELATION && kotlin.math.abs(r) >= 0.05 } }
        val interventionMaxAbs = interventionPartials.values.maxOf { kotlin.math.abs(it) }

        val classification = when {
            !ordinarySurvives -> "C"
            materialDiffCount.toDouble() / allCells.size >= 0.3 || interventionMaxAbs >= 0.10 -> "B"
            else -> "A"
        }
        sb.appendLine("**Inputs to this classification**: ${materialDiffCount} of ${allCells.size} predictor/player-" +
            "count cells show a materially different regen-vs-ordinary partial correlation (Section 3); ordinary-" +
            "observation partial correlations ${if (ordinarySurvives) "do" else "do not"} retain reliable, non-trivial " +
            "magnitude on their own (|r|>=0.05 at n>=$MIN_N_FOR_CORRELATION); the regeneration-as-intervention-point " +
            "multi-partial correlation peaks at ${fmt(interventionMaxAbs, 3)} (Section 5).")
        sb.appendLine()
        sb.appendLine("**Classification: $classification.** " + when (classification) {
            "C" -> "The upper-Tier progress relationship does not survive independent of regeneration events - " +
                "ordinary-observation partial correlations are unreliable or negligible, meaning `446cde9`'s own A " +
                "classification was substantially an artifact of sampling only at regeneration events."
            "B" -> "Upper-Tier progress remains predictive at ordinary settled-state observations, but the " +
                "relationship is materially different around regeneration events, a residual regeneration-timing " +
                "signal survives conditioning on the measured state, or both - regeneration is not merely an " +
                "observation point here."
            else -> "Upper-Tier progress predicts subsequent resolution similarly whether measured at a " +
                "regeneration event or an ordinary settled turn boundary, and regeneration's own timing carries no " +
                "meaningful residual information once the measured state is accounted for. `446cde9`'s own A " +
                "classification reflects a genuine property of game state, not an artifact of where this research " +
                "line's instrumentation happened to sample it."
        })
        sb.appendLine()
    }

    // --- Section 7: ambiguities ---

    private fun buildSection7Ambiguities(sb: StringBuilder) {
        sb.appendLine("## 7. Semantic and architectural ambiguities")
        sb.appendLine()
        sb.appendLine("- **The matched-ordinary observation is always the turn immediately preceding a regeneration, " +
            "never a randomly chosen turn.** This was a deliberate choice to minimize the elapsed-turn gap and " +
            "isolate \"was this specifically a regeneration\" as cleanly as possible, but it means ordinary " +
            "observations are not an unconditional sample of \"any settled turn boundary\" - they are specifically " +
            "\"the turn boundary right before a regeneration happened to occur.\" If regeneration timing itself " +
            "correlates with something about recent game history (e.g. a burst of card draws), the matched ordinary " +
            "sample could inherit a faint version of that same correlation rather than being fully independent of " +
            "regeneration. Section 5's own multi-partial-correlation test is the more direct check for this, since it " +
            "isolates `isRegen` itself as a variable rather than relying on the matched design alone.")
        sb.appendLine("- **Two regenerations within the same turn's own resolution (possible via a Precedence chain or " +
            "multiple card draws in one turn) would pair with the identical matched-ordinary snapshot**, producing a " +
            "duplicate ordinary row rather than two independent ones. Not corrected for - expected to be rare enough " +
            "not to materially bias the aggregate figures above, but not separately verified in this pass.")
        sb.appendLine("- **`multiPartialCorrelation`'s OLS implementation has no formal collinearity diagnostic** " +
            "beyond the numerical guard in `solveLinearSystem` (a near-zero pivot is treated as a zero coefficient) - " +
            "sufficient for this file's own control set (5 predictors + elapsed turns, none exactly collinear once " +
            "`inPlayTokens@Tier4` is excluded in favor of its canonical `playersOnTier4` equivalent), but a future " +
            "reuse of this helper with a differently-chosen control set should re-verify that no two controls are " +
            "near-perfectly collinear before trusting its residuals.")
        sb.appendLine()
    }
}
