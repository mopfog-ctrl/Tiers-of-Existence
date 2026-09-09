package com.tiersofexistence.engine.simulation

import com.tiersofexistence.engine.board.SquareType
import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.rules.Dice
import com.tiersofexistence.engine.rules.Phase
import com.tiersofexistence.engine.rules.TurnDriver
import com.tiersofexistence.engine.rules.TurnOrder
import com.tiersofexistence.engine.cards.FateHarvestDeck
import com.tiersofexistence.engine.state.GameStalledException
import com.tiersofexistence.engine.state.GameState
import com.tiersofexistence.engine.state.PlayerState
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Total physical Fate Harvest cards — see `FateHarvestCatalogTest`: 10x1 + 10x2 + 8x3 + 4x4 = 70. */
private const val FATE_HARVEST_DECK_SIZE = 70

/** One invariant violation caught mid-simulation, with enough context (seed, turn number) to
 * reproduce it deterministically outside this run. */
private class InvariantViolation(val gameIndex: Int, val seed: Long, val turnNumber: Int, message: String, cause: Throwable? = null) :
    AssertionError("[game #$gameIndex, seed=$seed, turn=$turnNumber] $message", cause)

private data class GameOutcome(
    val gameIndex: Int,
    val seed: Long,
    val playerCount: Int,
    val turnsTaken: Int,
    val roundsTaken: Int,
    val completed: Boolean,
    val winners: Set<PlayerColor>,
)

/**
 * Runs many full games end to end through [TurnDriver], every decision made by
 * [RandomLegalDecisionProvider], re-checking a fixed set of engine invariants after every single
 * completed turn (not just at the end) — a broad, unscripted stress test complementing the
 * targeted unit/integration tests elsewhere in this suite, which each exercise one specific rule
 * or interaction in isolation.
 *
 * Invariants checked every turn (see [checkInvariants]):
 * - **Token conservation**: for every player/Tier, `TierTokenPool.totalOwned` (Ion Battery +
 *   Hatchery + Staging Pile + in-play + Zone-resident) always equals that Tier's fixed
 *   `tokensPerPlayer` — nothing is ever created or lost. Marauders have no fixed total by design
 *   (see `MarauderPool`'s own class doc — no reserve to conserve), so covered instead by the
 *   "no crash" property every other invariant + the harness's own exception handling implies.
 * - **No illegal turn ownership**: nobody ever gets to actually move a token/Marauder without
 *   currently being eligible to. `state.currentTurn` naming a player who's since become
 *   ineligible (per `PlayerState.hasTierTurn`/`hasMarauderTurn`) is not itself a violation —
 *   a Fate Harvest card can legitimately destroy another queued player's last token on this Tier
 *   before that player's own queue slot is reached this Phase (Divine Assistance, Graviton Rift,
 *   Plasma Burst, Galactic Roundabout, ...), and `TurnDriver.driveOneTurn` is required to detect
 *   that and end the turn as a no-op rather than crash or let them move something they don't
 *   have — see `driveOneTurn`'s own empty-candidates handling. This is tracked (see
 *   `staleQueueSlotsObserved`) and reported in the summary rather than silently ignored, but
 *   only an actual crash (caught by this harness's own exception handling) or a move genuinely
 *   applied for an ineligible player would be the real violation.
 * - **No orphaned cards / deck+hand+discard conservation**: `drawPileSize + discardPileSize +
 *   sum(every hand.size)` always equals the fixed 70-card total — a card can move between the
 *   deck, a hand, and the discard pile, but the total across all three never changes.
 * - **No unresolved pending decisions after a completed turn**: `GameState.pendingRoll` is
 *   always null once `driveOneTurn` returns (Delayed Motion's own checkpoint is fully consumed
 *   within the same turn it's offered, never left dangling into the next).
 * - **No Precedence chain leakage**: structurally guaranteed rather than separately probed —
 *   `InteractionChain` is a local variable inside `TurnDriver.resolvePrecedenceWindow`, never
 *   stored on `GameState`, so there is no field a chain could leak into; the practical symptom a
 *   leaked/mishandled chain would actually produce (a card lost or duplicated) is exactly what
 *   the card-conservation invariant above would catch.
 * - **No impossible Phase states**: `state.currentPhase` is always one of `Phase.ROUND_ORDER`'s 5
 *   entries (by construction of `phaseIndex`'s wraparound in `GameState.advancePhase`) and, for a
 *   Tier Phase, names a real `TierLevel`.
 * - **No vanished tokens**: covered by the token-conservation invariant for Tier tokens; for
 *   both token kinds, any resolution reaching an already-gone `TokenId` is required to reject
 *   gracefully via `TokenLocation.NoLongerExists`, not crash — which the harness's own uncaught-
 *   exception handling (any crash fails the affected game loudly, with full repro context) already
 *   verifies across thousands of real plays rather than one hand-built scenario.
 * - **Eventual continued progress**: each game is capped at [MAX_TURNS_PER_GAME] `driveOneTurn`
 *   calls; a game that neither reaches a winner nor throws within that budget is reported as a
 *   non-terminating run worth investigating, not silently ignored. `GameState.skipEmptyPhases`'s
 *   own `GameStalledException` fail-safe (guarding a genuinely impossible/corrupted state — no
 *   player eligible anywhere, no winner) is caught and reported as a critical finding in its own
 *   right if it ever fires, since ordinary randomized-but-legal play should never reach it.
 * - **Winner-state correctness**: once `state.winners` is non-empty, every declared winner
 *   actually has a 4th-Tier token in play on a `SquareType.YOU_WIN` square (exact landing, not
 *   "close enough") — and the harness stops driving that game the instant a winner is set, so a
 *   winner is never followed by further mutation of the "finished" game.
 */
class GameSimulationTest {

    companion object {
        private const val GAME_COUNT = 100
        private const val MAX_TURNS_PER_GAME = 8000
        private const val BASE_SEED = 20260909L
    }

    @Test
    fun `simulate many full games with randomized-but-legal decisions, checking invariants after every turn`() {
        val violations = mutableListOf<InvariantViolation>()
        val outcomes = mutableListOf<GameOutcome>()
        var staleQueueSlotsObserved = 0

        repeat(GAME_COUNT) { gameIndex ->
            val seed = BASE_SEED + gameIndex
            val random = Random(seed)
            val colors = PlayerColor.entries.shuffled(random).take(random.nextInt(2, 7))
            val turnOrder = TurnOrder(colors)
            val players = colors.associateWith { PlayerState(it) }
            players.values.forEach { it.tierPool(TierLevel.FIRST).startToken() }
            val state = GameState(players = players, turnOrder = turnOrder, deck = FateHarvestDeck.newShuffled(random))
            val decisionsByPlayer = colors.associateWith { RandomLegalDecisionProvider(Random(random.nextLong())) }
            val driver = TurnDriver(decisionsByPlayer, rollForPhase = { phase -> Dice.rollForPhase(phase, random) })

            var turnsTaken = 0
            var completed = false
            try {
                state.skipEmptyPhases()
                staleQueueSlotsObserved += checkInvariants(state, gameIndex, seed, turnsTaken)
                while (turnsTaken < MAX_TURNS_PER_GAME && state.winners.isEmpty() && state.currentTurn != null) {
                    driver.driveOneTurn(state)
                    turnsTaken += 1
                    staleQueueSlotsObserved += checkInvariants(state, gameIndex, seed, turnsTaken)
                }
                completed = state.winners.isNotEmpty()
            } catch (e: InvariantViolation) {
                violations += e
            } catch (e: GameStalledException) {
                violations += InvariantViolation(gameIndex, seed, turnsTaken, "GameStalledException fired during ordinary randomized-but-legal play: ${e.message}", e)
            } catch (e: Throwable) {
                violations += InvariantViolation(gameIndex, seed, turnsTaken, "Uncaught ${e::class.simpleName}: ${e.message}", e)
            }

            outcomes += GameOutcome(gameIndex, seed, colors.size, turnsTaken, state.roundNumber, completed, state.winners)
        }

        val completedCount = outcomes.count { it.completed }
        val avgTurns = outcomes.filter { it.completed }.map { it.turnsTaken }.average()
        val maxTurns = outcomes.maxOf { it.turnsTaken }
        println(
            "GameSimulationTest: ${outcomes.size} games, $completedCount reached a winner within $MAX_TURNS_PER_GAME turns " +
                "(avg ${if (avgTurns.isNaN()) "n/a" else "%.1f".format(avgTurns)} turns among those, max $maxTurns turns seen), " +
                "${violations.size} invariant violations, $staleQueueSlotsObserved stale-queue-slot no-ops gracefully handled.",
        )
        val neverFinished = outcomes.filter { !it.completed && violations.none { v -> v.gameIndex == it.gameIndex } }
        if (neverFinished.isNotEmpty()) {
            println("Games that hit the $MAX_TURNS_PER_GAME-turn cap without a winner (seeds): ${neverFinished.map { it.seed }}")
        }

        if (violations.isNotEmpty()) {
            val summary = violations.joinToString("\n---\n") { v ->
                "${v.message}\n" + (v.cause?.stackTraceToString()?.lineSequence()?.take(8)?.joinToString("\n") ?: "")
            }
            throw AssertionError("${violations.size} invariant violation(s) across $GAME_COUNT simulated games:\n$summary")
        }
    }

    /** Returns 1 if this check observed (and tolerated) a stale queue slot, 0 otherwise — see the
     * class doc's "No illegal turn ownership" entry for why that's tracked, not failed on. */
    private fun checkInvariants(state: GameState, gameIndex: Int, seed: Long, turnNumber: Int): Int {
        fun fail(message: String): Nothing = throw InvariantViolation(gameIndex, seed, turnNumber, message)

        // Token conservation: every player's every Tier pool always accounts for exactly
        // tier.tokensPerPlayer tokens across Ion Battery + Hatchery + Staging Pile + in-play +
        // Zone-resident — nothing created, nothing lost, regardless of what mutated it.
        state.players.forEach { (color, ps) ->
            TierLevel.entries.forEach { tier ->
                val pool = ps.tierPool(tier)
                if (pool.totalOwned != tier.tokensPerPlayer) {
                    fail("Token conservation violated for $color/$tier: totalOwned=${pool.totalOwned}, expected=${tier.tokensPerPlayer}")
                }
            }
        }

        // No impossible Phase state.
        if (state.currentPhase !in Phase.ROUND_ORDER) fail("currentPhase ${state.currentPhase} is not a member of Phase.ROUND_ORDER")

        // A queued turn's eligibility can legitimately go stale between queue-build time and its
        // own slot (see class doc) — driveOneTurn is required to handle that as a no-op, not a
        // violation, so this only counts the occurrence rather than failing on it.
        var staleQueueSlot = 0
        state.currentTurn?.let { turn ->
            val ps = state.players.getValue(turn)
            val eligible = when (val phase = state.currentPhase) {
                Phase.Marauder -> ps.hasMarauderTurn()
                is Phase.Tier -> ps.hasTierTurn(phase.tier)
            }
            if (!eligible) staleQueueSlot = 1
        }

        // No orphaned cards: draw pile + discard pile + every hand together always total exactly
        // the fixed 70-card deck, no matter how many cards have moved between them.
        val handTotal = state.players.values.sumOf { it.hand.size }
        val total = state.deck.drawPileSize + state.deck.discardPileSize + handTotal
        if (total != FATE_HARVEST_DECK_SIZE) {
            fail("Card conservation violated: draw=${state.deck.drawPileSize} discard=${state.deck.discardPileSize} hands=$handTotal total=$total (expected $FATE_HARVEST_DECK_SIZE)")
        }

        // No unresolved pending decisions after a completed turn.
        if (state.pendingRoll != null) fail("GameState.pendingRoll is still set after driveOneTurn returned: ${state.pendingRoll}")

        // Winner-state correctness: every declared winner actually has a 4th-Tier token in play
        // exactly on a YOU_WIN square.
        if (state.winners.isNotEmpty()) {
            val board = state.boards.getValue(TierLevel.FOURTH)
            state.winners.forEach { color ->
                val ps = state.players.getValue(color)
                val onYouWin = ps.tierPool(TierLevel.FOURTH).inPlayPositions.any { board.squareAt(it).type == SquareType.YOU_WIN }
                if (!onYouWin) fail("Winner $color declared but has no 4th-Tier in-play token on a YOU_WIN square (positions: ${ps.tierPool(TierLevel.FOURTH).inPlayPositions})")
            }
        }

        return staleQueueSlot
    }
}
