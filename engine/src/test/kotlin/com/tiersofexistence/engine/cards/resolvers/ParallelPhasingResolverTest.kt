package com.tiersofexistence.engine.cards.resolvers

import com.tiersofexistence.engine.board.BoardLayouts
import com.tiersofexistence.engine.board.Square
import com.tiersofexistence.engine.board.SquareType
import com.tiersofexistence.engine.board.TierBoard
import com.tiersofexistence.engine.cards.FateHarvestCatalog
import com.tiersofexistence.engine.cards.play.CardPlayRequest
import com.tiersofexistence.engine.cards.play.CardPlayResult
import com.tiersofexistence.engine.cards.play.CardTarget
import com.tiersofexistence.engine.cards.play.TargetValidationError
import com.tiersofexistence.engine.cards.play.TriggeringEvent
import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.PlayerColor.GREEN
import com.tiersofexistence.engine.model.PlayerColor.RED
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.rules.TurnOrder
import com.tiersofexistence.engine.state.GameState
import com.tiersofexistence.engine.state.PlayerState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Parallel Phasing: "Move any one of your tokens (of any type) forward four spaces and move any
 * other player's token forward four spaces. You may not move an opponent's token if it's in the
 * Zone of Protection." See `docs/card-mechanics-matrix.md` §8 and [ParallelPhasingResolver]'s
 * class doc.
 */
class ParallelPhasingResolverTest {

    private fun cardNamed(name: String) = FateHarvestCatalog.all.single { it.name == name }

    private fun plain(index: Int) = Square(index, SquareType.PLAIN)

    private fun boardOf6() = TierBoard(TierLevel.FIRST, listOf(Square(0, SquareType.BIRTH_CANAL)) + (1..5).map { plain(it) })

    private fun gameWith(board: TierBoard, colors: List<PlayerColor> = listOf(RED, GREEN)): GameState {
        val players = colors.associateWith { PlayerState(it) }
        return GameState(players, TurnOrder(colors), boards = BoardLayouts.current() + (TierLevel.FIRST to board))
    }

    private fun requestFor(player: PlayerColor, targets: List<CardTarget>) = CardPlayRequest(
        sourcePlayer = player,
        card = cardNamed("Parallel Phasing"),
        targets = targets,
        triggeringEvent = TriggeringEvent.PlayedFromHand,
    )

    @Test
    fun `moves the player's own token and an opponent's token 4 spaces each`() {
        val state = gameWith(boardOf6())
        val ownId = state.players.getValue(RED).tierPool(TierLevel.FIRST).startToken()
        val opponentId = state.players.getValue(GREEN).tierPool(TierLevel.FIRST).startToken()

        val result = ParallelPhasingResolver.resolve(
            state,
            requestFor(RED, listOf(CardTarget.Token(ownId), CardTarget.Token(opponentId))),
            CardTarget.Token(ownId),
            CardTarget.Token(opponentId),
        )

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(listOf(4), state.players.getValue(RED).tierPool(TierLevel.FIRST).inPlayPositions)
        assertEquals(listOf(4), state.players.getValue(GREEN).tierPool(TierLevel.FIRST).inPlayPositions)
        assertEquals(1, state.deck.discardPileSize)
    }

    @Test
    fun `moving a Marauder as one of the two targets works too, since the card affects any token type`() {
        val state = gameWith(boardOf6())
        val ownMarauderId = state.players.getValue(RED).marauders.placeOnBirthCanal(TierLevel.FIRST)
        val opponentId = state.players.getValue(GREEN).tierPool(TierLevel.FIRST).startToken()

        val result = ParallelPhasingResolver.resolve(
            state,
            requestFor(RED, listOf(CardTarget.Token(ownMarauderId), CardTarget.Token(opponentId))),
            CardTarget.Token(ownMarauderId),
            CardTarget.Token(opponentId),
        )

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(listOf(4), state.players.getValue(RED).marauders.positions(TierLevel.FIRST))
        assertEquals(listOf(4), state.players.getValue(GREEN).tierPool(TierLevel.FIRST).inPlayPositions)
    }

    @Test
    fun `rejects when the first target does not actually belong to the source player`() {
        val state = gameWith(boardOf6())
        val greenPool = state.players.getValue(GREEN).tierPool(TierLevel.FIRST)
        val notOwnId = greenPool.startToken()
        val otherId = greenPool.startToken()

        val result = ParallelPhasingResolver.resolve(
            state,
            requestFor(RED, listOf(CardTarget.Token(notOwnId), CardTarget.Token(otherId))),
            CardTarget.Token(notOwnId),
            CardTarget.Token(otherId),
        )

        assertIs<CardPlayResult.Rejected>(result)
        assertIs<TargetValidationError.WrongTokenType>(result.reason)
        assertEquals(0, state.deck.discardPileSize) // never even attempted
    }

    @Test
    fun `rejects when the second target belongs to the source player instead of another player`() {
        val state = gameWith(boardOf6())
        val pool = state.players.getValue(RED).tierPool(TierLevel.FIRST)
        val ownId = pool.startToken()
        val alsoOwnId = pool.startToken()

        val result = ParallelPhasingResolver.resolve(
            state,
            requestFor(RED, listOf(CardTarget.Token(ownId), CardTarget.Token(alsoOwnId))),
            CardTarget.Token(ownId),
            CardTarget.Token(alsoOwnId),
        )

        assertIs<CardPlayResult.Rejected>(result)
        assertIs<TargetValidationError.WrongTokenType>(result.reason)
    }

    @Test
    fun `cannot move the opponent's token if it is inside a Zone of Protection, unlike the player's own token`() {
        val state = gameWith(boardOf6())
        val ownId = state.players.getValue(RED).tierPool(TierLevel.FIRST).startToken()
        val opponentPool = state.players.getValue(GREEN).tierPool(TierLevel.FIRST)
        val opponentId = opponentPool.startToken()
        opponentPool.enterZone(fromPosition = 0, zoneNumber = 2)

        val result = ParallelPhasingResolver.resolve(
            state,
            requestFor(RED, listOf(CardTarget.Token(ownId), CardTarget.Token(opponentId))),
            CardTarget.Token(ownId),
            CardTarget.Token(opponentId),
        )

        assertIs<CardPlayResult.Rejected>(result)
        assertIs<TargetValidationError.ZoneOfProtectionBlocksTarget>(result.reason)
        // atomic: RED's own token wasn't moved either, since the whole play was rejected
        assertEquals(listOf(0), state.players.getValue(RED).tierPool(TierLevel.FIRST).inPlayPositions)
        assertTrue(opponentPool.zoneResidents.isNotEmpty())
    }

    @Test
    fun `the player's own token in their own Zone of Protection is a legal target but moving it out is not yet implemented`() {
        val state = gameWith(boardOf6())
        val ownPool = state.players.getValue(RED).tierPool(TierLevel.FIRST)
        val ownId = ownPool.startToken()
        ownPool.enterZone(fromPosition = 0, zoneNumber = 2)
        val opponentId = state.players.getValue(GREEN).tierPool(TierLevel.FIRST).startToken()

        val result = ParallelPhasingResolver.resolve(
            state,
            requestFor(RED, listOf(CardTarget.Token(ownId), CardTarget.Token(opponentId))),
            CardTarget.Token(ownId),
            CardTarget.Token(opponentId),
        )

        assertIs<CardPlayResult.Rejected>(result)
        assertIs<TargetValidationError.CardSpecificRestriction>(result.reason) // legal per rule 12, just not implemented
        assertEquals(0, state.deck.discardPileSize)
    }

    @Test
    fun `a target that no longer exists is rejected gracefully, not a crash`() {
        val state = gameWith(boardOf6())
        val ownPool = state.players.getValue(RED).tierPool(TierLevel.FIRST)
        val ownId = ownPool.startToken()
        ownPool.destroyInPlay(0) // gone before this resolver ever runs
        val opponentId = state.players.getValue(GREEN).tierPool(TierLevel.FIRST).startToken()

        val result = ParallelPhasingResolver.resolve(
            state,
            requestFor(RED, listOf(CardTarget.Token(ownId), CardTarget.Token(opponentId))),
            CardTarget.Token(ownId),
            CardTarget.Token(opponentId),
        )

        assertIs<CardPlayResult.Rejected>(result)
        assertIs<TargetValidationError.NoLegalTarget>(result.reason)
    }

    @Test
    fun `dispatches through CardEffectDispatcher given exactly two token targets`() {
        val state = gameWith(boardOf6())
        val ownId = state.players.getValue(RED).tierPool(TierLevel.FIRST).startToken()
        val opponentId = state.players.getValue(GREEN).tierPool(TierLevel.FIRST).startToken()
        val request = requestFor(RED, listOf(CardTarget.Token(ownId), CardTarget.Token(opponentId)))

        val result = CardEffectDispatcher.dispatch(state, request)

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(listOf(4), state.players.getValue(RED).tierPool(TierLevel.FIRST).inPlayPositions)
        assertEquals(listOf(4), state.players.getValue(GREEN).tierPool(TierLevel.FIRST).inPlayPositions)
    }

    @Test
    fun `CardEffectDispatcher rejects Parallel Phasing given the wrong number of targets`() {
        val state = gameWith(boardOf6())
        val ownId = state.players.getValue(RED).tierPool(TierLevel.FIRST).startToken()
        val request = requestFor(RED, listOf(CardTarget.Token(ownId)))

        val result = CardEffectDispatcher.dispatch(state, request)

        assertIs<CardPlayResult.Rejected>(result)
        assertIs<TargetValidationError.WrongTargetCount>(result.reason)
        assertEquals(0, state.deck.discardPileSize)
    }
}
