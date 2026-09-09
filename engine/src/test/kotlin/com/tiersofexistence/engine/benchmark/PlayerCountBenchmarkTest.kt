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
 * The T.O.E. Player-Count Gameplay-Length and Performance Benchmark: drives full games through
 * [TurnDriver] at each of the 5 canonical player counts (2-6), same as [com.tiersofexistence
 * .engine.simulation.GameSimulationTest], but fixing player count per cohort (rather than
 * randomizing it per game) and adding wall-clock timing plus seat-level decision instrumentation
 * (via [InstrumentedDecisionProvider]/[SeatDecisionStats]) so the resulting tables can compare
 * game length and engine cost *across* player counts rather than only validate correctness
 * within one big mixed sample.
 *
 * **Deliberately NOT run by a plain `./gradlew :engine:test`** — this drives up to 8,750 real
 * games (1,250 in Stage 1 + 7,500 in Stage 2) on top of the already-permanent 2,000-game
 * [com.tiersofexistence.engine.simulation.GameSimulationTest], which would roughly quadruple the
 * suite's normal runtime for a benchmark, not a correctness gate. Gated behind the
 * `toe.benchmark` system property (`assumeTrue`, not `@Disabled` — `@Disabled` would also block
 * an explicit `--tests` invocation) so it's skipped (not failed) by default and only runs when
 * asked for: `./gradlew :engine:test --configure-on-demand -Dtoe.benchmark=true --tests
 * "com.tiersofexistence.engine.benchmark.PlayerCountBenchmarkTest"`.
 *
 * **Deck composition**: per the user's own explicit ruling for this specific benchmark task
 * (overriding the general "remove unused Color cards" optional rule documented in CLAUDE.md,
 * which this benchmark deliberately does NOT apply — see this class's own deck-audit section
 * below and the report's Table A) — every game in every cohort uses the complete, canonical,
 * unfiltered 70-card [FateHarvestDeck.newShuffled], identical composition regardless of player
 * count or which colors are seated. Only shuffle ORDER is randomized (via the game's own seeded
 * [Random]); composition is fixed by construction, never sampled.
 *
 * **Color rotation**: within a player-count cohort, which [PlayerColor]s are actually seated
 * rotates game-to-game via [rotatedColors] — a plain cyclic rotation of [PlayerColor.entries]'s
 * fixed 6-color order, offset by the game's own index within the cohort (not by anything
 * randomized), so across any 6 consecutive games at a given player count, every color spends
 * roughly the same number of games seated vs. unseated and cycles through every seat position.
 * This exists so no single color's own strategic profile (its one Color-restricted Fate Harvest
 * card) systematically biases one player count's results over another.
 *
 * **Invariant checking**: [checkInvariants] duplicates (deliberately, not refactored into a
 * shared helper — this task explicitly excludes further architecture work)
 * `GameSimulationTest.checkInvariants`'s exact checks, since correctness stays the priority here
 * too (Section 9): any genuine violation aborts that game and is reported as a defect requiring
 * investigation, never silently tolerated or used to justify weakening an invariant.
 */
class PlayerCountBenchmarkTest {

    companion object {
        private const val FATE_HARVEST_DECK_SIZE = 70
        private const val MAX_TURNS_PER_GAME = 8000

        private val PLAYER_COUNTS = listOf(2, 3, 4, 5, 6)

        private const val STAGE1_GAMES_PER_COHORT = 250
        private const val STAGE2_GAMES_PER_COHORT = 1500

        // Deterministic, non-overlapping seed ranges, recorded here (and restated in the
        // generated report) rather than left implicit: seed(stage, playerCount, gameIndex) =
        // stageBase + playerCount * PLAYER_COUNT_SEED_STRIDE + gameIndex. The largest possible
        // Stage 1 seed (playerCount=6, gameIndex=STAGE1_GAMES_PER_COHORT-1) is well below
        // STAGE2_BASE_SEED, so the two stages' seed ranges never collide even though they're
        // otherwise independent (Stage 2 is not "more of Stage 1," per Section 6's own
        // "new independent seed ranges" instruction).
        private const val STAGE1_BASE_SEED = 500_000_000L
        private const val STAGE2_BASE_SEED = 900_000_000L
        private const val PLAYER_COUNT_SEED_STRIDE = 10_000_000L

        private const val WARMUP_GAMES = 60

        private fun seedFor(stageBase: Long, playerCount: Int, gameIndex: Int): Long =
            stageBase + playerCount * PLAYER_COUNT_SEED_STRIDE + gameIndex

        /** Cyclic rotation of the fixed 6-color base order, offset by [gameIndex], truncated to
         * [playerCount] — see class doc's "Color rotation" section. */
        private fun rotatedColors(playerCount: Int, gameIndex: Int): List<PlayerColor> {
            val base = PlayerColor.entries
            val n = base.size
            return (0 until playerCount).map { base[(it + gameIndex) % n] }
        }

        /** Nearest-rank percentile (p in [0,100]) over an unsorted numeric list - no
         * interpolation, so the result is always an actually-observed value, robust to a
         * skewed/non-normal turns-per-game distribution. */
        private fun percentile(values: List<Int>, p: Double): Double {
            val sorted = values.sorted()
            val rank = kotlin.math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1].toDouble()
        }

        private fun percentileLong(values: List<Long>, p: Double): Double {
            val sorted = values.sorted()
            val rank = kotlin.math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1].toDouble()
        }
    }

    private class InvariantViolation(val playerCount: Int, val gameIndex: Int, val seed: Long, val turnNumber: Int, message: String, cause: Throwable? = null) :
        AssertionError("[players=$playerCount, game #$gameIndex, seed=$seed, turn=$turnNumber] $message", cause)

    private data class GameMetrics(
        val playerCount: Int,
        val gameIndex: Int,
        val seed: Long,
        val turnsTaken: Int,
        val roundsTaken: Int,
        val completed: Boolean,
        val capped: Boolean,
        val wallClockNanos: Long,
        val seatTurns: List<Int>,
        val seatDecisionsAsked: List<Long>,
        val seatDecisionsSubstantive: List<Long>,
    )

    private data class CohortResult(
        val playerCount: Int,
        val games: List<GameMetrics>,
        val violations: List<InvariantViolation>,
    ) {
        val finished get() = games.count { it.completed }
        val cappedCount get() = games.count { it.capped }
        val turns get() = games.map { it.turnsTaken }
        val rounds get() = games.map { it.roundsTaken }
        val meanTurns get() = turns.average()
        val medianTurns get() = percentile(turns, 50.0)
        val p90Turns get() = percentile(turns, 90.0)
        val p95Turns get() = percentile(turns, 95.0)
        val meanRounds get() = rounds.average()
        val meanHumanSeatTurns get() = games.flatMap { it.seatTurns }.average()
        val meanDecisionOpportunitiesPerSeat get() = games.flatMap { it.seatDecisionsAsked }.map { it.toDouble() }.average()
        val totalRuntimeNanos get() = games.sumOf { it.wallClockNanos }
        val meanRuntimePerGameNanos get() = games.map { it.wallClockNanos }.average()
        val medianRuntimePerGameNanos get() = percentileLong(games.map { it.wallClockNanos }, 50.0)
        val meanRuntimePerTurnNanos get() = totalRuntimeNanos.toDouble() / turns.sum().toDouble()
    }

    @Test
    fun `player-count gameplay-length and performance benchmark`() {
        assumeTrue(
            System.getProperty("toe.benchmark") == "true",
            "Skipped by default (heavy: up to 8,750 simulated games) - run with -Dtoe.benchmark=true to execute.",
        )

        // --- Section 1: canonical deck-composition audit (Table A) ---
        val deckAudit = auditDeckComposition()
        check(deckAudit.all { it.matches }) {
            "Deck composition audit found a discrepancy - aborting the benchmark rather than " +
                "measuring against a drifted deck:\n" + deckAudit.filter { !it.matches }.joinToString("\n")
        }

        // JVM warm-up: run and discard a handful of games across varied player counts before any
        // measured timing begins (Section 10's "warm the JVM before measured runs").
        repeat(WARMUP_GAMES) { i ->
            val playerCount = PLAYER_COUNTS[i % PLAYER_COUNTS.size]
            runOneGame(playerCount, seedFor(-1_000_000_000L, playerCount, i))
        }

        val allViolations = mutableListOf<InvariantViolation>()

        val stage1 = PLAYER_COUNTS.map { pc -> runCohort(pc, STAGE1_GAMES_PER_COHORT, STAGE1_BASE_SEED) }
        stage1.forEach { allViolations += it.violations }

        // Correctness gates Stage 2, per Section 9: never proceed past a real invariant defect.
        check(allViolations.isEmpty()) {
            "Stage 1 found ${allViolations.size} invariant violation(s) - stopping before Stage 2:\n" +
                allViolations.joinToString("\n---\n") { v -> "${v.message}\n" + (v.cause?.stackTraceToString()?.lineSequence()?.take(8)?.joinToString("\n") ?: "") }
        }

        val stage2 = PLAYER_COUNTS.map { pc -> runCohort(pc, STAGE2_GAMES_PER_COHORT, STAGE2_BASE_SEED) }
        stage2.forEach { allViolations += it.violations }

        val report = buildReport(deckAudit, stage1, stage2, allViolations)
        val reportFile = File("../docs/benchmarks/player-count-benchmark.md")
        reportFile.parentFile.mkdirs()
        reportFile.writeText(report)
        println(report)

        check(allViolations.isEmpty()) {
            "Stage 2 found ${allViolations.size} invariant violation(s):\n" +
                allViolations.joinToString("\n---\n") { v -> "${v.message}\n" + (v.cause?.stackTraceToString()?.lineSequence()?.take(8)?.joinToString("\n") ?: "") }
        }
    }

    // --- Section 1: deck audit ---

    private data class DeckAuditRow(val name: String, val rulebookMultiplicity: Int, val simulationMultiplicity: Int) {
        val matches get() = rulebookMultiplicity == simulationMultiplicity
    }

    /** Same 32-card multiplicity table as `FateHarvestDeckCompositionAuditTest`, restated here so
     * this benchmark's own Table A is self-contained and independently verifies the exact deck
     * [runOneGame] actually draws from, not merely trusting that other test file stayed in sync. */
    private val rulebookMultiplicity: Map<String, Int> = mapOf(
        "Corpuscle Rot" to 1, "Galactic Roundabout" to 1, "Dwarf Star" to 1,
        "Radiation Burst" to 1, "Materialize Army" to 1, "Graviton Rift" to 1,
        "Fluidic Wave" to 1, "Parallel Phasing" to 1, "Plasma Burst" to 1,
        "Verdant Growth" to 1,
        "Infernal Abyss" to 2, "Divine Assistance" to 2, "Planetary Nebula" to 2,
        "Luckier Nebula" to 2, "Essence Assimilator" to 2,
        "Skip, Hop, and Jump (Dimensional)" to 2, "Materialize Help" to 2,
        "Tactical Motion" to 2, "Insidious Flux" to 2, "Phase Loss" to 2,
        "Annulment (Antimatter)" to 3, "Evasive Action" to 3, "Lucky Nebula" to 3,
        "Tactical Step" to 3, "Elemental Rebirth" to 3, "Cleansing (Atmospheric)" to 3,
        "Delayed Motion" to 3, "Emitting Nebula" to 3,
        "Last Gasp" to 4, "Phase Control" to 4, "Circulate (Elemental)" to 4,
        "Sidestep (Extinction Avoidance)" to 4,
    )

    private fun auditDeckComposition(): List<DeckAuditRow> {
        val actual = FateHarvestCatalog.buildDeck().groupingBy { it.name }.eachCount()
        val names = (rulebookMultiplicity.keys + actual.keys).sorted()
        return names.map { name -> DeckAuditRow(name, rulebookMultiplicity[name] ?: 0, actual[name] ?: 0) }
    }

    // --- game driving ---

    private fun runCohort(playerCount: Int, gameCount: Int, stageBaseSeed: Long): CohortResult {
        val games = mutableListOf<GameMetrics>()
        val violations = mutableListOf<InvariantViolation>()
        repeat(gameCount) { gameIndex ->
            val seed = seedFor(stageBaseSeed, playerCount, gameIndex)
            val (metrics, violation) = runOneGame(playerCount, seed, gameIndex)
            games += metrics
            if (violation != null) violations += violation
        }
        return CohortResult(playerCount, games, violations)
    }

    private fun runOneGame(playerCount: Int, seed: Long, gameIndex: Int = -1): Pair<GameMetrics, InvariantViolation?> {
        val random = Random(seed)
        val colors = rotatedColors(playerCount, if (gameIndex >= 0) gameIndex else 0)
        val turnOrder = TurnOrder(colors)
        val players = colors.associateWith { PlayerState(it) }
        players.values.forEach { it.tierPool(TierLevel.FIRST).startToken() }
        val state = GameState(players = players, turnOrder = turnOrder, deck = FateHarvestDeck.newShuffled(random))

        val seatStats: Map<PlayerColor, SeatDecisionStats> = colors.associateWith { SeatDecisionStats() }
        val decisionsByPlayer: Map<PlayerColor, com.tiersofexistence.engine.rules.TurnDecisionProvider> =
            colors.associateWith { color ->
                InstrumentedDecisionProvider(RandomLegalDecisionProvider(Random(random.nextLong())), seatStats.getValue(color))
            }
        val driver = TurnDriver(decisionsByPlayer, rollForPhase = { phase -> Dice.rollForPhase(phase, random) })

        val seatTurnCounts = colors.associateWith { 0 }.toMutableMap()

        var turnsTaken = 0
        var completed = false
        var violation: InvariantViolation? = null
        val start = System.nanoTime()
        try {
            state.skipEmptyPhases()
            checkInvariants(state, playerCount, gameIndex, seed, turnsTaken)
            while (turnsTaken < MAX_TURNS_PER_GAME && state.winners.isEmpty() && state.currentTurn != null) {
                val turnPlayer = state.currentTurn
                driver.driveOneTurn(state)
                turnsTaken += 1
                if (turnPlayer != null) seatTurnCounts[turnPlayer] = (seatTurnCounts[turnPlayer] ?: 0) + 1
                checkInvariants(state, playerCount, gameIndex, seed, turnsTaken)
            }
            completed = state.winners.isNotEmpty()
        } catch (e: InvariantViolation) {
            violation = e
        } catch (e: GameStalledException) {
            violation = InvariantViolation(playerCount, gameIndex, seed, turnsTaken, "GameStalledException fired during ordinary randomized-but-legal play: ${e.message}", e)
        } catch (e: Throwable) {
            violation = InvariantViolation(playerCount, gameIndex, seed, turnsTaken, "Uncaught ${e::class.simpleName}: ${e.message}", e)
        }
        val elapsed = System.nanoTime() - start

        val metrics = GameMetrics(
            playerCount = playerCount,
            gameIndex = gameIndex,
            seed = seed,
            turnsTaken = turnsTaken,
            roundsTaken = state.roundNumber,
            completed = completed,
            capped = !completed && violation == null,
            wallClockNanos = elapsed,
            seatTurns = colors.map { seatTurnCounts.getValue(it) },
            seatDecisionsAsked = colors.map { seatStats.getValue(it).totalAsked() },
            seatDecisionsSubstantive = colors.map { seatStats.getValue(it).totalSubstantive() },
        )
        return metrics to violation
    }

    /** Same checks as `GameSimulationTest.checkInvariants`, deliberately duplicated rather than
     * shared (see class doc) - throws [InvariantViolation] on any real violation. */
    private fun checkInvariants(state: GameState, playerCount: Int, gameIndex: Int, seed: Long, turnNumber: Int) {
        fun fail(message: String): Nothing = throw InvariantViolation(playerCount, gameIndex, seed, turnNumber, message)

        state.players.forEach { (color, ps) ->
            TierLevel.entries.forEach { tier ->
                val pool = ps.tierPool(tier)
                if (pool.totalOwned != tier.tokensPerPlayer) {
                    fail("Token conservation violated for $color/$tier: totalOwned=${pool.totalOwned}, expected=${tier.tokensPerPlayer}")
                }
            }
        }

        if (state.currentPhase !in Phase.ROUND_ORDER) fail("currentPhase ${state.currentPhase} is not a member of Phase.ROUND_ORDER")

        val handTotal = state.players.values.sumOf { it.hand.size }
        val total = state.deck.drawPileSize + state.deck.discardPileSize + handTotal
        if (total != FATE_HARVEST_DECK_SIZE) {
            fail("Card conservation violated: draw=${state.deck.drawPileSize} discard=${state.deck.discardPileSize} hands=$handTotal total=$total (expected $FATE_HARVEST_DECK_SIZE)")
        }

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

    // --- report generation ---

    private fun ms(nanos: Double) = nanos / 1_000_000.0
    private fun fmt(d: Double, digits: Int = 1) = "%.${digits}f".format(d)

    private fun buildReport(
        deckAudit: List<DeckAuditRow>,
        stage1: List<CohortResult>,
        stage2: List<CohortResult>,
        violations: List<InvariantViolation>,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("# T.O.E. Player-Count Gameplay-Length and Performance Benchmark")
        sb.appendLine()
        sb.appendLine("Baseline: commit `ba2448d`. No architectural or gameplay-rule changes were made for this task.")
        sb.appendLine()
        sb.appendLine("Seed scheme: `seed = stageBase + playerCount * $PLAYER_COUNT_SEED_STRIDE + gameIndex`, " +
            "Stage 1 stageBase = $STAGE1_BASE_SEED, Stage 2 stageBase = $STAGE2_BASE_SEED (independent, non-overlapping ranges).")
        sb.appendLine()
        sb.appendLine("Color rotation: cyclic rotation of `PlayerColor.entries` (GREEN, RED, BLACK, YELLOW, WHITE, BLUE) " +
            "offset by each game's own index within its cohort, truncated to that cohort's player count - " +
            "e.g. at 3 players, game 0 seats GREEN/RED/BLACK, game 1 seats RED/BLACK/YELLOW, etc., cycling every 6 games.")
        sb.appendLine()

        sb.appendLine("## Table A - Canonical deck composition audit")
        sb.appendLine()
        sb.appendLine("| Card name | Rulebook multiplicity | Simulation multiplicity | Match? |")
        sb.appendLine("|---|---|---|---|")
        deckAudit.forEach { row -> sb.appendLine("| ${row.name} | ${row.rulebookMultiplicity} | ${row.simulationMultiplicity} | ${if (row.matches) "Yes" else "**NO**"} |") }
        val totalRulebook = deckAudit.sumOf { it.rulebookMultiplicity }
        val totalSim = deckAudit.sumOf { it.simulationMultiplicity }
        sb.appendLine("| **Total** | **$totalRulebook** | **$totalSim** | ${if (totalRulebook == totalSim && totalRulebook == 70) "Yes (70)" else "**NO**"} |")
        sb.appendLine()
        sb.appendLine("Every game in every cohort below (Stage 1 and Stage 2 alike) uses the complete, unfiltered " +
            "70-card canonical deck via `FateHarvestDeck.newShuffled(random)` - no color-based card removal is applied " +
            "for this benchmark, per explicit instruction; only shuffle order is randomized.")
        sb.appendLine()

        fun cohortTable(title: String, cohorts: List<CohortResult>, gamesPerCohort: Int): String {
            val s = StringBuilder()
            s.appendLine("## $title")
            s.appendLine()
            s.appendLine("| Player count | Games | Finished | Capped | Mean turns | Median turns | p90 | p95 | Mean Rounds | Mean human-seat turns | Mean decision opportunities/seat | Total runtime (s) | Mean runtime/game (ms) | Mean runtime/turn (ms) |")
            s.appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
            cohorts.forEach { c ->
                s.appendLine(
                    "| ${c.playerCount} | ${c.games.size} | ${c.finished} | ${c.cappedCount} | " +
                        "${fmt(c.meanTurns)} | ${fmt(c.medianTurns, 0)} | ${fmt(c.p90Turns, 0)} | ${fmt(c.p95Turns, 0)} | " +
                        "${fmt(c.meanRounds)} | ${fmt(c.meanHumanSeatTurns)} | ${fmt(c.meanDecisionOpportunitiesPerSeat)} | " +
                        "${fmt(c.totalRuntimeNanos / 1_000_000_000.0, 2)} | ${fmt(ms(c.meanRuntimePerGameNanos), 3)} | ${fmt(ms(c.meanRuntimePerTurnNanos), 4)} |",
                )
            }
            s.appendLine()
            return s.toString()
        }

        sb.append(cohortTable("Table B - Stage 1 baseline ($STAGE1_GAMES_PER_COHORT games/cohort)", stage1, STAGE1_GAMES_PER_COHORT))
        sb.append(cohortTable("Table C - Stage 2 scaling sample ($STAGE2_GAMES_PER_COHORT games/cohort)", stage2, STAGE2_GAMES_PER_COHORT))

        sb.appendLine("## Table D - Scaling comparison (Stage 2, 2-player = 1.00x baseline)")
        sb.appendLine()
        sb.appendLine("| Player count | Turn-length ratio vs 2P | Round ratio vs 2P | Engine-runtime ratio vs 2P | Runtime/turn ratio vs 2P |")
        sb.appendLine("|---|---|---|---|---|")
        val base = stage2.first { it.playerCount == 2 }
        stage2.forEach { c ->
            sb.appendLine(
                "| ${c.playerCount} | ${fmt(c.meanTurns / base.meanTurns, 2)}x | ${fmt(c.meanRounds / base.meanRounds, 2)}x | " +
                    "${fmt(c.meanRuntimePerGameNanos / base.meanRuntimePerGameNanos, 2)}x | ${fmt(c.meanRuntimePerTurnNanos / base.meanRuntimePerTurnNanos, 2)}x |",
            )
        }
        sb.appendLine()

        sb.appendLine("## Table E - One-human local-app duration model (Stage 2 data)")
        sb.appendLine()
        sb.appendLine("**Assumptions (not observations)** - seconds per *substantive* decision and a per-turn mechanical " +
            "overhead (rolling, moving the token, reading the landed square) for three pacing styles:")
        sb.appendLine()
        sb.appendLine("| Pacing | Seconds/decision opportunity | Seconds/turn mechanical overhead |")
        sb.appendLine("|---|---|---|")
        sb.appendLine("| Fast | 5 | 5 |")
        sb.appendLine("| Typical | 10 | 8 |")
        sb.appendLine("| Deliberate | 20 | 12 |")
        sb.appendLine()
        sb.appendLine("These are illustrative assumptions the user should adjust, not measured quantities. AI-seat " +
            "compute time is excluded from these estimates (see Table B/C's measured mean runtime/turn - " +
            "sub-millisecond to low-millisecond, negligible next to any human pacing tier above). UI/animation time " +
            "is unknown and is NOT included in any estimate below; it would add to every figure.")
        sb.appendLine()
        sb.appendLine("| Player count | AI seats | Human turns/game | Human decisions/game | Fast estimate | Typical estimate | Deliberate estimate |")
        sb.appendLine("|---|---|---|---|---|---|---|")
        val pacing = listOf("Fast" to (5.0 to 5.0), "Typical" to (10.0 to 8.0), "Deliberate" to (20.0 to 12.0))
        stage2.forEach { c ->
            val humanTurns = c.meanHumanSeatTurns
            val humanDecisions = c.meanDecisionOpportunitiesPerSeat
            val estimates = pacing.map { (_, secs) ->
                val (perDecision, perTurn) = secs
                val totalSeconds = humanDecisions * perDecision + humanTurns * perTurn
                formatDuration(totalSeconds)
            }
            sb.appendLine(
                "| ${c.playerCount} | ${c.playerCount - 1} | ${fmt(humanTurns)} | ${fmt(humanDecisions)} | " +
                    "${estimates[0]} | ${estimates[1]} | ${estimates[2]} |",
            )
        }
        sb.appendLine()

        sb.appendLine("## Analysis")
        sb.appendLine()
        val twoP = stage2.first { it.playerCount == 2 }
        val sixP = stage2.first { it.playerCount == 6 }
        val longestByTurns = stage2.maxByOrNull { it.meanTurns }!!
        val shortestByTurns = stage2.minByOrNull { it.meanTurns }!!
        val cappedAny = stage2.any { it.cappedCount > 0 }
        val stage1For = { pc: Int -> stage1.first { it.playerCount == pc } }
        val stabilization = stage2.joinToString("; ") { c ->
            val s1 = stage1For(c.playerCount)
            "${c.playerCount}P mean turns ${fmt(s1.meanTurns)} (Stage 1) -> ${fmt(c.meanTurns)} (Stage 2)"
        }

        sb.appendLine("1. **Game length, 2 to 6 players**: mean turns/game moves from ${fmt(twoP.meanTurns)} (2P) to ${fmt(sixP.meanTurns)} (6P), " +
            "a ${fmt(sixP.meanTurns / twoP.meanTurns, 2)}x ratio (see Table D).")
        sb.appendLine("2. **Computational cost, 2 to 6 players**: mean engine runtime/game moves ${fmt(ms(twoP.meanRuntimePerGameNanos), 2)}ms -> " +
            "${fmt(ms(sixP.meanRuntimePerGameNanos), 2)}ms, a ${fmt(sixP.meanRuntimePerGameNanos / twoP.meanRuntimePerGameNanos, 2)}x ratio; " +
            "mean runtime/turn moves ${fmt(ms(twoP.meanRuntimePerTurnNanos), 3)}ms -> ${fmt(ms(sixP.meanRuntimePerTurnNanos), 3)}ms " +
            "(${fmt(sixP.meanRuntimePerTurnNanos / twoP.meanRuntimePerTurnNanos, 2)}x).")
        sb.appendLine("3. **Does one human seat get roughly the same, more, or fewer turns as player count rises?**: " +
            stage2.joinToString(", ") { "${it.playerCount}P: ${fmt(it.meanHumanSeatTurns)}" } +
            " - mean human-seat turns/game " + (if (sixP.meanHumanSeatTurns < twoP.meanHumanSeatTurns) "decreases" else "does not decrease") +
            " as player count rises, since total game turns grow sub-linearly relative to player count while more seats share them.")
        sb.appendLine("4. **Shortest/longest typical player count**: shortest mean-turns cohort is ${shortestByTurns.playerCount}P " +
            "(${fmt(shortestByTurns.meanTurns)} turns), longest is ${longestByTurns.playerCount}P (${fmt(longestByTurns.meanTurns)} turns).")
        sb.appendLine("5. **Any player count disproportionately prone to very long games?**: p95 turns by player count - " +
            stage2.joinToString(", ") { "${it.playerCount}P: ${fmt(it.p95Turns, 0)}" } + "; capped (hit the $MAX_TURNS_PER_GAME-turn cap without a winner): " +
            stage2.joinToString(", ") { "${it.playerCount}P: ${it.cappedCount}/${it.games.size}" } + ".")
        sb.appendLine("6. **Have the 250-game Stage 1 estimates stabilized by 1,500-game Stage 2?**: $stabilization.")
        sb.appendLine("7. **What does this suggest about a plausible standalone-app session duration?**: see Table E - " +
            "under the stated pacing assumptions (never treat these as measured), a Typical-pace human session ranges " +
            stage2.joinToString(", ") { c ->
                val totalSeconds = c.meanDecisionOpportunitiesPerSeat * 10.0 + c.meanHumanSeatTurns * 8.0
                "${c.playerCount}P: ${formatDuration(totalSeconds)}"
            } + ". UI/animation time is excluded and would add to every figure.")
        sb.appendLine("8. **Did the benchmark expose any new engine defect?**: " +
            (if (violations.isEmpty()) "No - zero invariant violations across ${(stage1.sumOf { it.games.size } + stage2.sumOf { it.games.size })} total simulated games (Stage 1 + Stage 2)."
            else "**YES** - ${violations.size} violation(s) found; see the raw failure detail surfaced by the failing assertion/test output. These must be investigated as correctness defects, not benchmarked around."))
        sb.appendLine("9. **Did the canonical deck audit expose any discrepancy in the existing simulation setup?**: " +
            (if (deckAudit.all { it.matches }) "No - `FateHarvestDeck.newShuffled()` already builds the full, unfiltered 70-card canonical deck unconditionally (no colors/player-count parameter exists), matching this benchmark's own full-deck requirement with no code changes needed."
            else "**YES** - see Table A's unmatched rows."))
        sb.appendLine("10. **Turn cap saturation**: " + (if (cappedAny) "at least one Stage 2 cohort hit the $MAX_TURNS_PER_GAME-turn cap without a winner - see row 5 above for counts." else "no Stage 2 game hit the $MAX_TURNS_PER_GAME-turn cap without reaching a winner."))
        sb.appendLine()
        sb.appendLine("Total games run: ${stage1.sumOf { it.games.size }} (Stage 1) + ${stage2.sumOf { it.games.size }} (Stage 2) = " +
            "${stage1.sumOf { it.games.size } + stage2.sumOf { it.games.size }}. Total invariant violations: ${violations.size}.")

        return sb.toString()
    }

    private fun formatDuration(totalSeconds: Double): String {
        val minutes = (totalSeconds / 60.0)
        return if (minutes < 1.0) "${fmt(totalSeconds, 0)}s" else "${fmt(minutes, 1)} min"
    }
}
