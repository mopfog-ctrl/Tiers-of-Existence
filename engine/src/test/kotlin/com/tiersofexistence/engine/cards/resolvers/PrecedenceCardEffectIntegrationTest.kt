package com.tiersofexistence.engine.cards.resolvers

import com.tiersofexistence.engine.board.BoardLayouts
import com.tiersofexistence.engine.board.Square
import com.tiersofexistence.engine.board.SquareType
import com.tiersofexistence.engine.board.TierBoard
import com.tiersofexistence.engine.cards.FateHarvestCatalog
import com.tiersofexistence.engine.cards.play.CardPlayRequest
import com.tiersofexistence.engine.cards.play.CardTarget
import com.tiersofexistence.engine.cards.play.TriggeringEvent
import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.PlayerColor.GREEN
import com.tiersofexistence.engine.model.PlayerColor.RED
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.rules.TokenKind
import com.tiersofexistence.engine.rules.TurnEngine
import com.tiersofexistence.engine.rules.TurnOrder
import com.tiersofexistence.engine.rules.precedence.InteractionChain
import com.tiersofexistence.engine.rules.precedence.SuspendedAction
import com.tiersofexistence.engine.state.GameState
import com.tiersofexistence.engine.state.PlayerState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * End-to-end proof that the Precedence interaction engine (Phase C) and the card resolvers
 * (Phase J) actually work together, not just independently: [InteractionChain] sequences the
 * response, [CardEffectDispatcher] applies what survives it to real [GameState].
 */
class PrecedenceCardEffectIntegrationTest {

    private fun cardNamed(name: String) = FateHarvestCatalog.all.single { it.name == name }

    private fun plain(index: Int) = Square(index, SquareType.PLAIN)

    private fun gameWith(tier: TierLevel, board: TierBoard, colors: List<PlayerColor> = listOf(RED, GREEN)): GameState {
        val players = colors.associateWith { PlayerState(it) }
        return GameState(players, TurnOrder(colors), boards = BoardLayouts.current() + (tier to board))
    }

    @Test
    fun `rule 23 worked example - a Precedence movement card rescues a token before a pending Marauder move resolves`() {
        // "A player rolls the purple die to move their Marauder token. You play a movement card
        // that has Precedence... you can move a Tier token to safety that would have otherwise
        // been destroyed by the Marauder." (rulebook.txt:477-484)
        val board = TierBoard(TierLevel.FIRST, listOf(Square(0, SquareType.BIRTH_CANAL), plain(1), plain(2)))
        val state = gameWith(TierLevel.FIRST, board)
        state.players.getValue(RED).marauders.placeOnBirthCanal(TierLevel.FIRST)
        state.players.getValue(GREEN).tierPool(TierLevel.FIRST).startToken()
        state.players.getValue(GREEN).tierPool(TierLevel.FIRST).moveInPlay(0, 1) // sits in the Marauder's 2-space path (0 -> 2 passes 1)

        // RED has rolled to move their Marauder 2 spaces — that move is now pending.
        val chain = InteractionChain.open(SuspendedAction.PendingMove(RED), eligiblePlayers = listOf(RED, GREEN))

        // GREEN plays Tactical Step (Precedence, +1) on their own endangered token before the move resolves.
        val rescueTarget = CardTarget.Token(GREEN, TokenKind.TIER_TOKEN, TierLevel.FIRST, position = 1)
        val rescue = CardPlayRequest(GREEN, cardNamed("Tactical Step"), listOf(rescueTarget), TriggeringEvent.RespondingInChain(chain.id))
        chain.respond(GREEN, rescue)
        chain.pass(RED)
        chain.pass(GREEN)

        val order = chain.resolve()
        CardEffectDispatcher.dispatchAll(state, order)
        chain.finishResolving()

        // GREEN's token moved from 1 to 2 — the Marauder's own landing square, not its path.
        assertEquals(listOf(2), state.players.getValue(GREEN).tierPool(TierLevel.FIRST).inPlayPositions)

        // NOW the originally-pending Marauder move resolves, per the suspended action. Landing on
        // an occupied square never destroys the occupant ("If a Marauder lands on a space
        // occupied by another token, that token is not destroyed" — rulebook.txt:321-322).
        val marauderMove = TurnEngine.moveMarauder(state, RED, TierLevel.FIRST, fromPosition = 0, spaces = 2)

        assertTrue(marauderMove.destroyedTokens.isEmpty()) // GREEN's token was already moved to safety
        assertEquals(1, state.players.getValue(GREEN).tierPool(TierLevel.FIRST).inPlayCount)
    }

    @Test
    fun `Annulment played against a pending card resolution cancels it before the caller ever applies it`() {
        val state = GameState.newGame(listOf(RED, GREEN))
        val target = CardTarget.Token(GREEN, TokenKind.TIER_TOKEN, TierLevel.FIRST, position = 0)
        val pendingDivineAssistance = CardPlayRequest(RED, cardNamed("Divine Assistance"), listOf(target), TriggeringEvent.PlayedFromHand)

        val chain = InteractionChain.open(SuspendedAction.PendingCardResolution(pendingDivineAssistance), eligiblePlayers = listOf(GREEN))
        val annulment = CardPlayRequest(GREEN, cardNamed("Annulment (Antimatter)"), triggeringEvent = TriggeringEvent.RespondingInChain(chain.id))
        chain.respond(GREEN, annulment)
        chain.pass(GREEN)

        val order = chain.resolve()
        CardEffectDispatcher.dispatchAll(state, order) // empty — Annulment itself never dispatches an effect
        chain.finishResolving()

        assertTrue(chain.isSuspendedActionCancelled)
        // A real caller checks isSuspendedActionCancelled and skips applying pendingDivineAssistance —
        // proving GREEN's token survives specifically because the caller respects that flag.
        assertEquals(1, state.players.getValue(GREEN).tierPool(TierLevel.FIRST).inPlayCount)
    }

    @Test
    fun `multiple Precedence responses on different tokens each resolve correctly in reverse order`() {
        val board = TierBoard(TierLevel.FIRST, listOf(Square(0, SquareType.BIRTH_CANAL), plain(1), plain(2), plain(3), plain(4)))
        val state = gameWith(TierLevel.FIRST, board, colors = listOf(RED, GREEN))
        state.players.getValue(RED).tierPool(TierLevel.FIRST).startToken()
        state.players.getValue(GREEN).tierPool(TierLevel.FIRST).startToken()
        state.players.getValue(GREEN).tierPool(TierLevel.FIRST).moveInPlay(0, 2)

        val chain = InteractionChain.open(SuspendedAction.PendingRoll(RED), eligiblePlayers = listOf(RED, GREEN))
        val step = CardPlayRequest(
            RED, cardNamed("Tactical Step"),
            listOf(CardTarget.Token(RED, TokenKind.TIER_TOKEN, TierLevel.FIRST, position = 0)),
            TriggeringEvent.RespondingInChain(chain.id),
        )
        chain.respond(RED, step)
        val motion = CardPlayRequest(
            GREEN, cardNamed("Tactical Motion"),
            listOf(CardTarget.Token(GREEN, TokenKind.TIER_TOKEN, TierLevel.FIRST, position = 2)),
            TriggeringEvent.RespondingInChain(chain.id),
        )
        chain.respond(GREEN, motion) // played second, resolves FIRST (reverse order) — independent token, no conflict
        chain.pass(RED)
        chain.pass(GREEN)

        val order = chain.resolve()
        assertEquals(listOf(motion.card.name, step.card.name), order.map { it.request.card.name })
        val results = CardEffectDispatcher.dispatchAll(state, order)
        chain.finishResolving()

        assertTrue(results.all { it is com.tiersofexistence.engine.cards.play.CardPlayResult.Resolved })
        assertEquals(listOf(1), state.players.getValue(RED).tierPool(TierLevel.FIRST).inPlayPositions) // 0 + 1
        assertEquals(listOf(4), state.players.getValue(GREEN).tierPool(TierLevel.FIRST).inPlayPositions) // 2 + 2
    }

    @Test
    fun `KNOWN GAP - two different players targeting the same token resolve against a stale recorded position`() {
        // CardTarget.Token records a board position at the moment the card is PLAYED (chosen by
        // the player), not "wherever this token currently is" at RESOLUTION time. Reverse-order
        // resolution means an earlier-played, later-resolving card can find its recorded position
        // already vacated by a later-played, earlier-resolving card that moved the same token —
        // TierTokenPool.moveInPlay's `require` then throws instead of failing gracefully.
        //
        // Note this can only happen across DIFFERENT players in one chain: every Precedence card
        // in the catalog is Held (Graviton Rift, Fluidic Wave, Tactical Motion, Annulment,
        // Tactical Step, Last Gasp all have timing = HELD), and the per-Phase play limit
        // (rule 4) blocks the SAME player from playing a second Held card in the same Phase — so
        // a same-player double-response to the same token is already prevented one layer up, by
        // TargetValidator.validatePhaseCardLimit, before it would ever reach this bug. The
        // cross-player case below is not caught by anything.
        //
        // This is a genuine, unresolved architectural gap in the current CardTarget design (the
        // rulebook doesn't address two movement cards targeting the same token in one Precedence
        // exchange either) — documented here via assertFailsWith rather than silently asserting
        // incorrect success. Needs a design fix (e.g. re-resolving a token target by identity
        // rather than recorded position) before Group 5 cards are used this way in practice.
        val board = TierBoard(TierLevel.FIRST, listOf(Square(0, SquareType.BIRTH_CANAL), plain(1), plain(2), plain(3)))
        val state = gameWith(TierLevel.FIRST, board, colors = listOf(RED, GREEN))
        state.players.getValue(GREEN).tierPool(TierLevel.FIRST).startToken()

        val chain = InteractionChain.open(SuspendedAction.PendingRoll(RED), eligiblePlayers = listOf(RED, GREEN))
        chain.respond(
            RED,
            CardPlayRequest(
                RED, cardNamed("Tactical Step"),
                listOf(CardTarget.Token(GREEN, TokenKind.TIER_TOKEN, TierLevel.FIRST, position = 0)),
                TriggeringEvent.RespondingInChain(chain.id),
            ),
        )
        chain.respond(
            GREEN,
            CardPlayRequest(
                GREEN, cardNamed("Tactical Motion"),
                listOf(CardTarget.Token(GREEN, TokenKind.TIER_TOKEN, TierLevel.FIRST, position = 0)), // same token, same recorded position
                TriggeringEvent.RespondingInChain(chain.id),
            ),
        )
        chain.pass(RED)
        chain.pass(GREEN)
        val order = chain.resolve()

        assertFailsWith<IllegalArgumentException> { CardEffectDispatcher.dispatchAll(state, order) }
    }
}
