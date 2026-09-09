package com.tiersofexistence.engine.state

import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.PlayerColor.GREEN
import com.tiersofexistence.engine.model.PlayerColor.RED
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.rules.Phase
import com.tiersofexistence.engine.rules.TurnOrder
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GameStateTest {

    @Test
    fun `new game gives every player one 1st Tier token in play and nothing else`() {
        val game = GameState.newGame(listOf(PlayerColor.RED, PlayerColor.GREEN))

        for (player in game.players.values) {
            assertEquals(1, player.tierPool(TierLevel.FIRST).inPlayCount)
            assertEquals(0, player.tierPool(TierLevel.SECOND).inPlayCount)
            assertEquals(0, player.tierPool(TierLevel.THIRD).inPlayCount)
            assertEquals(0, player.tierPool(TierLevel.FOURTH).inPlayCount)
            assertTrue(TierLevel.entries.all { player.marauders.inPlayCount(it) == 0 })
        }
    }

    @Test
    fun `round 1 only has turns in the 1st Tier Phase`() {
        val game = GameState.newGame(listOf(PlayerColor.RED, PlayerColor.GREEN))

        val turnsPerPhase = Phase.ROUND_ORDER.associateWith { game.turnOrder.turnsFor(it, game.players) }

        assertTrue(turnsPerPhase.getValue(Phase.Marauder).isEmpty())
        assertTrue(turnsPerPhase.getValue(Phase.Tier(TierLevel.FOURTH)).isEmpty())
        assertTrue(turnsPerPhase.getValue(Phase.Tier(TierLevel.THIRD)).isEmpty())
        assertTrue(turnsPerPhase.getValue(Phase.Tier(TierLevel.SECOND)).isEmpty())
        assertEquals(listOf(PlayerColor.RED, PlayerColor.GREEN), turnsPerPhase.getValue(Phase.Tier(TierLevel.FIRST)))
    }

    @Test
    fun `advancing through all 5 phases starts a new round`() {
        val game = GameState.newGame(listOf(PlayerColor.RED))
        assertEquals(1, game.roundNumber)

        repeat(Phase.ROUND_ORDER.size) { game.advancePhase() }

        assertEquals(2, game.roundNumber)
        assertEquals(Phase.Marauder, game.currentPhase)
    }

    @Test
    fun `a player with a token on a higher Tier goes before one who only has a 1st Tier token`() {
        val game = GameState.newGame(listOf(PlayerColor.RED, PlayerColor.GREEN))
        game.players.getValue(PlayerColor.GREEN).tierPool(TierLevel.SECOND).startToken()

        val secondTierTurns = game.turnOrder.turnsFor(Phase.Tier(TierLevel.SECOND), game.players)

        assertEquals(listOf(PlayerColor.GREEN), secondTierTurns)
    }

    @Test
    fun `a fresh game has no current turn until skipEmptyPhases is called`() {
        val game = GameState.newGame(listOf(PlayerColor.RED, PlayerColor.GREEN))
        assertEquals(Phase.Marauder, game.currentPhase)
        assertEquals(null, game.currentTurn)
    }

    @Test
    fun `skipEmptyPhases lands Round 1 directly on the 1st Tier Phase`() {
        val game = GameState.newGame(listOf(PlayerColor.RED, PlayerColor.GREEN))

        game.skipEmptyPhases()

        assertEquals(1, game.roundNumber)
        assertEquals(Phase.Tier(TierLevel.FIRST), game.currentPhase)
        assertEquals(PlayerColor.RED, game.currentTurn)
    }

    @Test
    fun `endTurn advances color order, then Phase, and wraps to a new Round`() {
        val game = GameState.newGame(listOf(PlayerColor.RED, PlayerColor.GREEN))
        game.skipEmptyPhases()
        assertEquals(PlayerColor.RED, game.currentTurn)

        game.endTurn()
        assertEquals(PlayerColor.GREEN, game.currentTurn)

        game.endTurn()
        // Both players are done with the only eligible Phase (1st Tier) in Round 1 — wraps
        // straight to Round 2's 1st Tier Phase too, since Marauder/4th/3rd/2nd are still empty.
        assertEquals(2, game.roundNumber)
        assertEquals(Phase.Tier(TierLevel.FIRST), game.currentPhase)
        assertEquals(PlayerColor.RED, game.currentTurn)
    }

    @Test
    fun `endTurn with grantAnotherTurn keeps the same player active`() {
        val game = GameState.newGame(listOf(PlayerColor.RED, PlayerColor.GREEN))
        game.skipEmptyPhases()

        game.endTurn(grantAnotherTurn = true)

        assertEquals(PlayerColor.RED, game.currentTurn)
    }

    // --- Deferred turn modifiers (Phase Loss / Phase Control / matching Time Wrinkle squares) ---

    @Test
    fun `queueSkipNextTierTurn never affects the turn in progress, only the Tier's next occurrence`() {
        val game = GameState.newGame(listOf(PlayerColor.RED, PlayerColor.GREEN))
        game.skipEmptyPhases()
        assertEquals(PlayerColor.RED, game.currentTurn)

        game.queueSkipNextTierTurn(PlayerColor.RED, TierLevel.FIRST) // drawn as part of RED's current turn
        game.endTurn() // RED's current turn still happens normally
        assertEquals(PlayerColor.GREEN, game.currentTurn)

        game.endTurn() // Round 2's 1st Tier Phase: RED is skipped, only GREEN plays
        assertEquals(2, game.roundNumber)
        assertEquals(Phase.Tier(TierLevel.FIRST), game.currentPhase)
        assertEquals(PlayerColor.GREEN, game.currentTurn)

        game.endTurn() // Round 3: the skip was consumed, RED is back to normal
        assertEquals(3, game.roundNumber)
        assertEquals(PlayerColor.RED, game.currentTurn)
    }

    @Test
    fun `queueExtraTierTurn splices into the live queue when that Tier's Phase is already active`() {
        val game = GameState.newGame(listOf(PlayerColor.RED, PlayerColor.GREEN))
        game.skipEmptyPhases()
        assertEquals(PlayerColor.RED, game.currentTurn) // RED hasn't taken this Phase's turn yet

        game.queueExtraTierTurn(PlayerColor.RED, TierLevel.FIRST)

        assertEquals(PlayerColor.RED, game.currentTurn) // still RED's normal turn first
        game.endTurn()
        assertEquals(PlayerColor.RED, game.currentTurn) // the extra turn, taken right after
        game.endTurn()
        assertEquals(PlayerColor.GREEN, game.currentTurn) // then GREEN, as normal
    }

    @Test
    fun `queueExtraTierTurn for a Phase not currently active is applied and consumed at its next occurrence`() {
        val game = GameState.newGame(listOf(PlayerColor.RED, PlayerColor.GREEN))
        assertEquals(Phase.Marauder, game.currentPhase) // not the 1st Tier Phase yet

        game.queueExtraTierTurn(PlayerColor.RED, TierLevel.FIRST)
        game.skipEmptyPhases()

        assertEquals(Phase.Tier(TierLevel.FIRST), game.currentPhase)
        assertEquals(PlayerColor.RED, game.currentTurn)
        game.endTurn()
        assertEquals(PlayerColor.RED, game.currentTurn) // the granted extra turn
        game.endTurn()
        assertEquals(PlayerColor.GREEN, game.currentTurn)

        game.endTurn() // Round 2: the extra turn was consumed, doesn't repeat
        assertEquals(2, game.roundNumber)
        assertEquals(PlayerColor.RED, game.currentTurn)
        game.endTurn()
        assertEquals(PlayerColor.GREEN, game.currentTurn) // no second RED turn this time
    }

    @Test
    fun `two independent ExtraTierTurn triggers each grant their own turn, never more than one repeat each`() {
        val game = GameState.newGame(listOf(PlayerColor.RED, PlayerColor.GREEN))
        game.skipEmptyPhases()

        game.queueExtraTierTurn(PlayerColor.RED, TierLevel.FIRST)
        game.queueExtraTierTurn(PlayerColor.RED, TierLevel.FIRST)

        assertEquals(PlayerColor.RED, game.currentTurn)
        game.endTurn()
        assertEquals(PlayerColor.RED, game.currentTurn) // extra #1
        game.endTurn()
        assertEquals(PlayerColor.RED, game.currentTurn) // extra #2
        game.endTurn()
        assertEquals(PlayerColor.GREEN, game.currentTurn) // no third repeat
    }

    // --- Pending roll (Delayed Motion's checkpoint) ---

    @Test
    fun `beginPendingRoll tracks the raw value until cleared`() {
        val game = GameState.newGame(listOf(PlayerColor.RED))

        val roll = game.beginPendingRoll(PlayerColor.RED, 5)

        assertEquals(5, roll.total)
        assertEquals(roll, game.pendingRoll)
    }

    @Test
    fun `addBonus on the tracked roll is visible via GameState-pendingRoll`() {
        val game = GameState.newGame(listOf(PlayerColor.RED))
        game.beginPendingRoll(PlayerColor.RED, 5)

        game.pendingRoll!!.addBonus(2)

        assertEquals(7, game.pendingRoll!!.total)
    }

    @Test
    fun `clearPendingRoll removes it`() {
        val game = GameState.newGame(listOf(PlayerColor.RED))
        game.beginPendingRoll(PlayerColor.RED, 5)

        game.clearPendingRoll()

        assertEquals(null, game.pendingRoll)
    }

    @Test
    fun `beginning a new pending roll overwrites the previous one`() {
        val game = GameState.newGame(listOf(PlayerColor.RED, PlayerColor.GREEN))
        game.beginPendingRoll(PlayerColor.RED, 5)

        val second = game.beginPendingRoll(PlayerColor.GREEN, 3)

        assertEquals(second, game.pendingRoll)
        assertEquals(PlayerColor.GREEN, game.pendingRoll!!.player)
    }

    // --- skipEmptyPhases: canonical skipping, and the fail-safe stalled-state guard ---

    @Test
    fun `one empty upper-Tier Phase skips normally to the next playable Phase`() {
        val game = GameState.newGame(listOf(RED))
        game.players.getValue(RED).tierPool(TierLevel.THIRD).startToken()
        game.advancePhase() // Marauder -> Tier(FOURTH), still empty
        assertEquals(Phase.Tier(TierLevel.FOURTH), game.currentPhase)
        assertEquals(null, game.currentTurn)

        game.skipEmptyPhases()

        assertEquals(Phase.Tier(TierLevel.THIRD), game.currentPhase)
        assertEquals(RED, game.currentTurn)
    }

    @Test
    fun `several consecutive empty Tier Phases skip normally to the next playable Phase`() {
        val game = GameState.newGame(listOf(RED))
        game.players.getValue(RED).tierPool(TierLevel.SECOND).startToken()

        game.skipEmptyPhases() // skips Marauder, Tier(FOURTH), Tier(THIRD) — 3 consecutive empty Phases

        assertEquals(Phase.Tier(TierLevel.SECOND), game.currentPhase)
        assertEquals(RED, game.currentTurn)
    }

    @Test
    fun `skipEmptyPhases reaches the correct next playable Phase with the correct player queued`() {
        val game = GameState.newGame(listOf(RED, GREEN)) // both start with a 1st Tier token only
        game.players.getValue(GREEN).tierPool(TierLevel.THIRD).startToken()

        game.skipEmptyPhases()

        assertEquals(Phase.Tier(TierLevel.THIRD), game.currentPhase)
        assertEquals(GREEN, game.currentTurn) // only GREEN is eligible on the 3rd Tier
    }

    @Test
    fun `1st Tier auto-replenishment keeps that Phase eligible after a player's only token is destroyed, preventing the ordinary soft-lock`() {
        val game = GameState.newGame(listOf(RED, GREEN))
        game.skipEmptyPhases()
        assertEquals(Phase.Tier(TierLevel.FIRST), game.currentPhase)
        val redPool = game.players.getValue(RED).tierPool(TierLevel.FIRST)
        assertEquals(1, redPool.inPlayCount)

        redPool.destroyInPlay(0) // RED's only 1st Tier token, destroyed by some ordinary effect

        // Auto-replenished from the Ion Battery (TierTokenPool.refillInPlayIfRoom) — RED still
        // has a legal 1st Tier turn, so the 1st Tier Phase never permanently loses them.
        assertTrue(redPool.inPlayCount > 0)
        assertTrue(RED in game.turnOrder.turnsFor(Phase.Tier(TierLevel.FIRST), game.players))
    }

    @Test
    fun `a queued Phase Loss on a single sparse player's only Tier resumes normally, not as a stalled state`() {
        // This is the exact scenario that caught the original 1-cycle threshold being too tight
        // (found via TurnDriver's card integration tests randomly drawing Phase Loss): a single
        // player whose only Tier turn is deferred-skipped needs to traverse a full empty Phase
        // cycle (Marauder/4th/3rd/2nd of the Round the skip consumes) PLUS another full cycle
        // (the same four Phases of the NEXT Round too) before their 1st Tier turn becomes
        // eligible again — 10 Phase-advances total, more than one cycle (5) but nowhere near a
        // real stall.
        val game = GameState.newGame(listOf(RED)) // single player, sparsest possible active game
        game.skipEmptyPhases()
        assertEquals(Phase.Tier(TierLevel.FIRST), game.currentPhase)
        assertEquals(RED, game.currentTurn)

        game.queueSkipNextTierTurn(RED, TierLevel.FIRST) // simulates Phase Loss resolving mid-turn
        game.endTurn(grantAnotherTurn = false) // must not throw GameStalledException

        assertEquals(Phase.Tier(TierLevel.FIRST), game.currentPhase)
        assertEquals(RED, game.currentTurn) // eligible again — the skip only deferred one occurrence
        assertEquals(3, game.roundNumber) // Round 2's 1st Tier turn was the one skipped
    }

    // --- Stacked SkipNextTierTurn debt: confirmed canon — independent triggers against the
    // same (player, Tier) stack, each removing exactly one distinct future eligible turn, never
    // collapsed together. Tracked as an explicit per-(player, Tier) counter (GameState
    // .pendingSkips), not a list, specifically to make the earlier collapse bug structurally
    // impossible — see GameState.queueSkipNextTierTurn's own doc. ---

    @Test
    fun `two independent SkipNextTierTurn triggers against the same player and Tier stack, each removing a separate future turn`() {
        // Uses 2 players specifically so each skipped occurrence is independently observable —
        // GREEN's own eligible turn keeps skipEmptyPhases() from searching straight past both
        // skipped occurrences in one call the way it would in a single-player game (its loop
        // only pauses once it finds SOMEONE eligible, not at every individual filtered-out
        // player).
        val game = GameState.newGame(listOf(RED, GREEN))
        game.skipEmptyPhases()
        assertEquals(RED, game.currentTurn)

        game.queueSkipNextTierTurn(RED, TierLevel.FIRST)
        game.queueSkipNextTierTurn(RED, TierLevel.FIRST) // a second, independent trigger, same (player, Tier)
        game.endTurn() // RED's current (unaffected) turn
        assertEquals(GREEN, game.currentTurn)
        game.endTurn() // GREEN's turn ends -> Round 2's 1st Tier Phase

        // Round 2: debt 2 -> 1, RED is skipped, only GREEN plays.
        assertEquals(2, game.roundNumber)
        assertEquals(GREEN, game.currentTurn)
        game.endTurn()

        // Round 3: RED is STILL skipped (debt 1 -> 0) — this is exactly what distinguishes
        // stacking from the old collapse bug, where RED would already be back by Round 3.
        assertEquals(3, game.roundNumber)
        assertEquals(GREEN, game.currentTurn)
        game.endTurn()

        // Round 4: debt is fully spent, RED is eligible again.
        assertEquals(4, game.roundNumber)
        assertEquals(RED, game.currentTurn)
    }

    @Test
    fun `a skip queued for one Tier never affects another Tier, even for the same player`() {
        val game = GameState.newGame(listOf(RED, GREEN)) // both start with a 1st Tier token
        game.skipEmptyPhases()
        assertEquals(Phase.Tier(TierLevel.FIRST), game.currentPhase)
        assertEquals(RED, game.currentTurn)

        game.queueSkipNextTierTurn(RED, TierLevel.FIRST)
        game.players.getValue(RED).tierPool(TierLevel.SECOND).startToken() // RED also gains a 2nd Tier token
        game.endTurn() // RED's current 1st Tier turn already happened before the skip takes effect
        assertEquals(GREEN, game.currentTurn)
        game.endTurn() // GREEN's 1st Tier turn ends -> Round 2 begins

        // Round 2's 2nd Tier Phase (reached before the 1st Tier Phase re-occurs, since 2nd comes
        // before 1st in Phase.ROUND_ORDER) is completely unaffected by the 1st-Tier-only debt —
        // RED plays normally here.
        assertEquals(2, game.roundNumber)
        assertEquals(Phase.Tier(TierLevel.SECOND), game.currentPhase)
        assertEquals(RED, game.currentTurn)
        game.endTurn()

        // Round 2's 1st Tier Phase: the 1st-Tier-specific skip IS honored here — only GREEN plays.
        assertEquals(Phase.Tier(TierLevel.FIRST), game.currentPhase)
        assertEquals(GREEN, game.currentTurn)
    }

    @Test
    fun `different players have independent skip debt on the same Tier`() {
        val game = GameState.newGame(listOf(RED, GREEN))
        game.skipEmptyPhases()
        assertEquals(RED, game.currentTurn)

        game.queueSkipNextTierTurn(RED, TierLevel.FIRST) // only RED is skipped
        game.endTurn() // RED's current turn
        assertEquals(GREEN, game.currentTurn)
        game.endTurn() // GREEN's current turn — Round 2's 1st Tier Phase begins

        // Round 2: RED is skipped (debt consumed), GREEN plays completely normally — one
        // player's skip debt never bleeds into another player's eligibility.
        assertEquals(2, game.roundNumber)
        assertEquals(GREEN, game.currentTurn)
        game.endTurn()

        // Round 3: RED's debt is spent, back to normal for both.
        assertEquals(3, game.roundNumber)
        assertEquals(RED, game.currentTurn)
    }

    @Test
    fun `skip debt stays pending while a player has no eligible turn on that Tier, and is honored once they gain one`() {
        // Queue a skip for a Tier RED doesn't even have a token on yet — the debt must not be
        // silently dropped or consumed just because that Tier's Phase occurs with RED
        // ineligible; it stays pending until RED is actually eligible there.
        val game = GameState.newGame(listOf(RED, GREEN)) // RED: 1st Tier only, no 2nd Tier token yet
        game.players.getValue(GREEN).tierPool(TierLevel.SECOND).startToken()
        game.queueSkipNextTierTurn(RED, TierLevel.SECOND)
        game.skipEmptyPhases()
        assertEquals(Phase.Tier(TierLevel.SECOND), game.currentPhase)
        assertEquals(listOf(GREEN), game.turnOrder.turnsFor(Phase.Tier(TierLevel.SECOND), game.players)) // RED has no 2nd Tier turn at all yet — nothing to skip
        game.endTurn() // GREEN's 2nd Tier turn; RED's debt untouched, still pending
        assertEquals(1, game.roundNumber) // still Round 1 (1st Tier hasn't been reached yet)
        assertEquals(Phase.Tier(TierLevel.FIRST), game.currentPhase)
        assertEquals(RED, game.currentTurn)

        // Now RED gains a 2nd Tier token mid-game (e.g. a Wormhole promotion), during RED's own
        // 1st Tier turn this Round.
        game.players.getValue(RED).tierPool(TierLevel.SECOND).startToken()
        game.endTurn() // RED's 1st Tier turn ends
        assertEquals(GREEN, game.currentTurn) // GREEN still has their own 1st Tier turn this Round
        game.endTurn() // GREEN's 1st Tier turn ends — only now does Round 2 begin

        assertEquals(2, game.roundNumber)
        // Round 2's 2nd Tier Phase: RED is NOW eligible per raw turnsFor, so the long-pending
        // skip is finally consumed here — the first occurrence where it could have been.
        assertEquals(Phase.Tier(TierLevel.SECOND), game.currentPhase)
        assertEquals(listOf(RED, GREEN), game.turnOrder.turnsFor(Phase.Tier(TierLevel.SECOND), game.players)) // RED would be eligible per raw turnsFor...
        assertEquals(GREEN, game.currentTurn) // ...but is skipped, so only GREEN actually plays
    }

    @Test
    fun `a skip and a queued ExtraTierTurn for the same player and Tier don't lose either`() {
        val game = GameState.newGame(listOf(RED, GREEN))
        assertEquals(Phase.Marauder, game.currentPhase) // neither queued while Tier(FIRST) is active,
        // so queueExtraTierTurn defers into deferredModifiers rather than splicing live

        game.queueSkipNextTierTurn(RED, TierLevel.FIRST)
        game.queueExtraTierTurn(RED, TierLevel.FIRST)
        game.skipEmptyPhases()

        // Round 1's 1st Tier Phase: RED's normal turn is skipped (debt consumed) — RED never
        // appears in this occurrence's base turn list at all, so the queued extra turn has
        // nothing to attach after and is NOT granted this Round either; it stays queued rather
        // than being lost.
        assertEquals(1, game.roundNumber)
        assertEquals(Phase.Tier(TierLevel.FIRST), game.currentPhase)
        assertEquals(GREEN, game.currentTurn)
        game.endTurn()

        // Round 2: the skip is fully spent, RED is back to normal AND the still-pending extra
        // turn is finally granted right after RED's normal turn.
        assertEquals(2, game.roundNumber)
        assertEquals(RED, game.currentTurn)
        game.endTurn()
        assertEquals(RED, game.currentTurn) // the extra turn, granted immediately after
        game.endTurn()
        assertEquals(GREEN, game.currentTurn)
    }

    @Test
    fun `stacked skip debt on the 1st Tier is independent of 1st Tier auto-replenishment`() {
        // 1st Tier auto-replenishment (TierTokenPool.refillInPlayIfRoom) keeps a player's
        // inPlayCount topped off after a destroy — completely orthogonal to skip debt, which is
        // tracked per (player, Tier) regardless of how many tokens are currently in play there.
        val game = GameState.newGame(listOf(RED, GREEN))
        game.skipEmptyPhases()
        game.queueSkipNextTierTurn(RED, TierLevel.FIRST)
        game.queueSkipNextTierTurn(RED, TierLevel.FIRST)
        val redPool = game.players.getValue(RED).tierPool(TierLevel.FIRST)
        redPool.destroyInPlay(0) // auto-replenishes back to inPlayCount > 0, per existing behavior
        assertTrue(redPool.inPlayCount > 0)

        game.endTurn() // RED's current (unaffected) turn
        game.endTurn() // GREEN's turn ends -> Round 2's 1st Tier Phase

        assertEquals(2, game.roundNumber)
        assertEquals(GREEN, game.currentTurn) // debt #1 consumed, RED skipped
        game.endTurn()

        assertEquals(3, game.roundNumber)
        assertEquals(GREEN, game.currentTurn) // debt #2 consumed, RED still skipped
        game.endTurn()

        assertEquals(4, game.roundNumber)
        assertEquals(RED, game.currentTurn) // debt fully spent — the replenished pool never affected it
    }

    @Test
    fun `50 stacked skips against a single sparse player's only Tier does not falsely trigger the stalled-state fail-safe`() {
        // Confirms STALLED_STATE_PHASE_THRESHOLD (500) still comfortably holds after the
        // collapse-to-stacking correction, per the user's explicit request to re-verify rather
        // than assume — N=50 stacked skips need N+1=51 occurrences (5*51 = 255 Phases) before
        // RED is eligible again, well under the 500-Phase threshold. Single-player specifically
        // because that's the sparsest legitimate shape the fail-safe itself has to tell apart
        // from a genuinely stalled/corrupted state — the same debt in a 2+ player game would
        // never come remotely close to mattering, since skipEmptyPhases's search pauses at any
        // other player's own eligible turn long before it would need to count this high.
        val game = GameState.newGame(listOf(RED))
        game.skipEmptyPhases()
        assertEquals(Phase.Tier(TierLevel.FIRST), game.currentPhase)
        assertEquals(RED, game.currentTurn)

        repeat(50) { game.queueSkipNextTierTurn(RED, TierLevel.FIRST) }
        game.endTurn(grantAnotherTurn = false) // must not throw GameStalledException

        assertEquals(Phase.Tier(TierLevel.FIRST), game.currentPhase)
        assertEquals(RED, game.currentTurn)
        assertEquals(52, game.roundNumber) // 1 (initial) + 51 fully-skipped occurrences
    }

    @Test
    fun `a deliberately impossible all-empty state throws GameStalledException instead of looping forever`() {
        // No player has any Tier token or Marauder anywhere, and no winner is declared — every
        // Phase is permanently empty. Constructed directly (bypassing GameState.newGame, which
        // always starts a 1st Tier token) specifically to reach this otherwise-unreachable state.
        val players = mapOf(RED to PlayerState(RED), GREEN to PlayerState(GREEN))
        val game = GameState(players, TurnOrder(listOf(RED, GREEN)))

        assertFailsWith<GameStalledException> { game.skipEmptyPhases() }
    }
}
