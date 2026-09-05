package com.tiersofexistence.engine.state

import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.rules.Phase
import com.tiersofexistence.engine.rules.TurnOrder
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Targeted stress coverage for the intermittent `NullPointerException` historically seen inside
 * `TurnOrder.turnsFor` during `GameState.<init> -> buildTurnQueue() ->
 * turnOrder.turnsFor(currentPhase, players)` (see CLAUDE.md's "Known flaky test" section and the
 * investigation notes in the stabilization completion report).
 *
 * This couldn't be reproduced despite extensive manual retries across this and prior sessions —
 * these tests exist so that IF it's a genuine (if rare) construction-order bug rather than purely
 * environmental JVM/JIT nondeterminism, a large number of repeated constructions within a single
 * JVM run gives it many more chances to surface than a handful of manual `./gradlew` invocations
 * ever could, and so the investigation leaves behind a permanent regression guard either way.
 */
class GameStateInitializationStressTest {

    @Test
    fun `constructing many GameStates in a tight loop never throws building the initial turn queue`() {
        val colors = listOf(PlayerColor.RED, PlayerColor.GREEN, PlayerColor.BLACK, PlayerColor.YELLOW, PlayerColor.WHITE, PlayerColor.BLUE)
        repeat(5_000) { iteration ->
            val state = GameState.newGame(colors)
            // Exercises the exact code path the historical NPE occurred in: GameState.<init>
            // (which already ran) followed immediately by reading currentPhase/currentTurn,
            // which is backed by Phase.ROUND_ORDER — first real access on iteration 0, cached
            // class thereafter, so this also covers "first ever access" many times over across
            // fresh GameState instances (each with its own turnQueue state, sharing the same
            // already-loaded Phase/TurnOrder classes).
            assertEquals(Phase.Marauder, state.currentPhase, "iteration $iteration")
            state.skipEmptyPhases()
            assertEquals(Phase.Tier(TierLevel.FIRST), state.currentPhase, "iteration $iteration")
            assertEquals(PlayerColor.RED, state.currentTurn, "iteration $iteration")
        }
    }

    @Test
    fun `repeatedly advancing through every Phase never throws on Phase-ROUND_ORDER indexing`() {
        val colors = listOf(PlayerColor.RED, PlayerColor.GREEN)
        val state = GameState.newGame(colors)
        repeat(2_000) {
            state.advancePhase()
        }
        // No assertion beyond "didn't throw" — advancePhase indexes Phase.ROUND_ORDER on every
        // call, which is exactly where the historical NPE was reported to originate.
    }

    @Test
    fun `constructing GameState directly (not via newGame) with a fresh TurnOrder each time never throws`() {
        // newGame funnels through a default TurnOrder(colors) parameter — this constructs both
        // fresh, independent objects each iteration instead, in case default-parameter
        // evaluation order matters (it shouldn't, but the checklist calls for ruling out
        // "initialization order inside GameState" specifically).
        repeat(2_000) { iteration ->
            val colors = listOf(PlayerColor.RED, PlayerColor.GREEN, PlayerColor.BLACK)
            val players = colors.associateWith { PlayerState(it) }
            players.values.forEach { it.tierPool(TierLevel.FIRST).startToken() }
            val turnOrder = TurnOrder(colors)
            val state = GameState(players = players, turnOrder = turnOrder)
            assertEquals(Phase.Marauder, state.currentPhase, "iteration $iteration")
        }
    }
}
