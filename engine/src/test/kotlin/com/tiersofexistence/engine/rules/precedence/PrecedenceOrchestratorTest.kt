package com.tiersofexistence.engine.rules.precedence

import com.tiersofexistence.engine.board.BoardLayouts
import com.tiersofexistence.engine.board.Square
import com.tiersofexistence.engine.board.SquareType
import com.tiersofexistence.engine.board.TierBoard
import com.tiersofexistence.engine.cards.FateHarvestCard
import com.tiersofexistence.engine.cards.FateHarvestCatalog
import com.tiersofexistence.engine.cards.play.CardPlayRequest
import com.tiersofexistence.engine.cards.play.CardTarget
import com.tiersofexistence.engine.cards.play.TokenLocation
import com.tiersofexistence.engine.cards.play.TokenLocator
import com.tiersofexistence.engine.cards.play.TriggeringEvent
import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.PlayerColor.BLACK
import com.tiersofexistence.engine.model.PlayerColor.GREEN
import com.tiersofexistence.engine.model.PlayerColor.RED
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.rules.CardChoice
import com.tiersofexistence.engine.rules.TurnDecisionProvider
import com.tiersofexistence.engine.rules.TurnOrder
import com.tiersofexistence.engine.state.GameState
import com.tiersofexistence.engine.state.PlayerState
import com.tiersofexistence.engine.state.TokenId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Direct, isolated coverage of [PrecedenceOrchestrator.openWindow] — the exact hand/discard
 * bookkeeping this class was extracted from [com.tiersofexistence.engine.rules.TurnDriver] with
 * (`resolvePrecedenceWindow`/`offerResponseRounds`, verbatim logic, moved rather than rewritten).
 * Before this extraction, that bookkeeping could only be exercised by driving a full turn through
 * `TurnDriver.driveOneTurn` (see `TurnDriverCardIntegrationTest`'s Precedence-canon tests, which
 * still pass unchanged after this refactor and remain the end-to-end proof); this file is the new
 * seam the extraction enables — calling [PrecedenceOrchestrator.openWindow] directly, with real
 * hands, without needing a whole turn around it.
 */
class PrecedenceOrchestratorTest {

    private fun cardNamed(name: String) = FateHarvestCatalog.all.single { it.name == name }

    private fun plain(index: Int) = Square(index, SquareType.PLAIN)

    private fun gameWith(colors: List<PlayerColor>, tier: TierLevel = TierLevel.FIRST, board: TierBoard? = null): GameState {
        val players = colors.associateWith { PlayerState(it) }
        val boards = if (board != null) BoardLayouts.current() + (tier to board) else BoardLayouts.current()
        return GameState(players, TurnOrder(colors), boards = boards)
    }

    /** Every abstract [TurnDecisionProvider] member throws (never expected to be called by
     * [PrecedenceOrchestrator], which only ever calls [choosePrecedenceResponse]); [respond]
     * drives that one method's scripted answers. */
    private class RespondOnly(private val respond: (GameState, PlayerColor, InteractionChain) -> CardChoice?) : TurnDecisionProvider {
        override fun chooseTokenToMove(state: GameState, player: PlayerColor, candidates: List<TokenId>) =
            error("not expected to be called by PrecedenceOrchestrator")
        override fun chooseEnterZone(state: GameState, player: PlayerColor, tier: TierLevel, zoneNumber: Int) =
            error("not expected to be called by PrecedenceOrchestrator")
        override fun chooseBuildMarauder(state: GameState, player: PlayerColor, tier: TierLevel) =
            error("not expected to be called by PrecedenceOrchestrator")
        override fun chooseTransport(state: GameState, player: PlayerColor, fromTier: TierLevel, options: List<TierLevel>) =
            error("not expected to be called by PrecedenceOrchestrator")
        override fun choosePrecedenceResponse(state: GameState, player: PlayerColor, chain: InteractionChain) = respond(state, player, chain)
    }

    private fun decline(): (GameState, PlayerColor, InteractionChain) -> CardChoice? = { _, _, _ -> null }

    /** Responds with [card]/[targets] exactly once (tracked via a local flag), then declines every
     * further round — matching how a real player only plays one card per response opportunity. */
    private fun respondOnce(card: FateHarvestCard, targets: List<CardTarget> = emptyList()): (GameState, PlayerColor, InteractionChain) -> CardChoice? {
        var responded = false
        return { _, _, _ -> if (responded) null else { responded = true; CardChoice(card, targets) } }
    }

    @Test
    fun `nobody responds - the window closes cleanly with no discards and no cancellation`() {
        val state = gameWith(listOf(RED, GREEN))
        val orchestrator = PrecedenceOrchestrator { RespondOnly(decline()) }

        val chain = orchestrator.openWindow(state, SuspendedAction.PendingMove(RED))

        assertTrue(chain.entriesSnapshot().isEmpty())
        assertFalse(chain.isSuspendedActionCancelled)
        assertEquals(0, state.deck.discardPileSize)
    }

    @Test
    fun `a response is removed from hand the moment it's played, resolved, and discarded - never left in hand or lost`() {
        val board = TierBoard(TierLevel.FIRST, listOf(Square(0, SquareType.BIRTH_CANAL), plain(1), plain(2)))
        val state = gameWith(listOf(RED, GREEN), board = board)
        val greenId = state.players.getValue(GREEN).tierPool(TierLevel.FIRST).startToken()!!
        val tacticalStep = cardNamed("Tactical Step") // Precedence, +1, own token
        state.players.getValue(GREEN).hand += tacticalStep

        val decisions = mapOf<PlayerColor, TurnDecisionProvider>(
            RED to RespondOnly(decline()),
            GREEN to RespondOnly(respondOnce(tacticalStep, listOf(CardTarget.Token(greenId)))),
        )
        val orchestrator = PrecedenceOrchestrator { decisions.getValue(it) }

        val chain = orchestrator.openWindow(state, SuspendedAction.PendingMove(RED))

        assertEquals(1, chain.entriesSnapshot().size)
        assertTrue(state.players.getValue(GREEN).hand.isEmpty()) // removed on response, not returned
        assertEquals(1, state.deck.discardPileSize) // Resolved entries discard via CardLifecycle.attemptPlay
        assertEquals(listOf(1), state.players.getValue(GREEN).tierPool(TierLevel.FIRST).inPlayPositions) // 0 -> 1
    }

    @Test
    fun `a response that rejects at resolution time (stale target) is discarded, not returned to the responder's hand`() {
        val board = TierBoard(TierLevel.FIRST, listOf(Square(0, SquareType.BIRTH_CANAL), plain(1), plain(2)))
        val state = gameWith(listOf(RED, GREEN, BLACK), board = board)
        val greenId = state.players.getValue(GREEN).tierPool(TierLevel.FIRST).startToken()!!
        val tacticalStep = cardNamed("Tactical Step") // Precedence, +1, GREEN's own token
        state.players.getValue(GREEN).hand += tacticalStep
        val gravitonRift = cardNamed("Graviton Rift") // Precedence, destroys GREEN's token
        state.players.getValue(BLACK).hand += gravitonRift

        // GREEN responds first (declared first -> resolves LAST, after BLACK's Graviton Rift
        // already destroyed the target); BLACK responds second (resolves FIRST).
        val decisions = mapOf<PlayerColor, TurnDecisionProvider>(
            RED to RespondOnly(decline()),
            GREEN to RespondOnly(respondOnce(tacticalStep, listOf(CardTarget.Token(greenId)))),
            BLACK to RespondOnly(respondOnce(gravitonRift, listOf(CardTarget.Token(greenId)))),
        )
        val orchestrator = PrecedenceOrchestrator { decisions.getValue(it) }

        orchestrator.openWindow(state, SuspendedAction.PendingMove(RED))

        // Confirmed canon: a Precedence response is spent once played, regardless of how it
        // resolves — GREEN's stale-target rejection does NOT return Tactical Step to hand.
        assertTrue(state.players.getValue(GREEN).hand.isEmpty())
        assertTrue(state.players.getValue(BLACK).hand.isEmpty())
        assertIs<TokenLocation.NoLongerExists>(TokenLocator.locate(state, greenId))
        assertEquals(2, state.deck.discardPileSize) // both cards accounted for
    }

    @Test
    fun `Annulment played as the chain's first entry against a pending card resolution cancels it, and is itself discarded`() {
        val state = gameWith(listOf(RED, GREEN))
        val annulment = cardNamed("Annulment (Antimatter)")
        state.players.getValue(GREEN).hand += annulment
        val tacticalMotion = cardNamed("Tactical Motion") // the (not-actually-played-from-hand-here) suspended card
        val suspendedRequest = CardPlayRequest(RED, tacticalMotion, emptyList(), TriggeringEvent.PlayedFromHand)

        val decisions = mapOf<PlayerColor, TurnDecisionProvider>(
            RED to RespondOnly(decline()),
            GREEN to RespondOnly(respondOnce(annulment)),
        )
        val orchestrator = PrecedenceOrchestrator { decisions.getValue(it) }

        val chain = orchestrator.openWindow(state, SuspendedAction.PendingCardResolution(suspendedRequest))

        assertTrue(chain.isSuspendedActionCancelled)
        assertTrue(state.players.getValue(GREEN).hand.isEmpty()) // Annulment itself removed and discarded
        assertEquals(1, state.deck.discardPileSize)
    }
}
