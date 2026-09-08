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
 * Circulate: "Move any Tier token to the next Zone of Protection on that Tier." Confirmed with
 * the user: any player's Tier token currently in play, teleported directly into the next Zone
 * of Protection walking clockwise from its position; a token already in a Zone can't be
 * targeted at all. See [CirculateResolver]'s class doc and `docs/card-mechanics-matrix.md` §31.
 */
class CirculateResolverTest {

    private fun cardNamed(name: String) = FateHarvestCatalog.all.single { it.name == name }

    private fun plain(index: Int) = Square(index, SquareType.PLAIN)

    private fun gameWith(board: TierBoard, colors: List<PlayerColor> = listOf(RED, GREEN)): GameState {
        val players = colors.associateWith { PlayerState(it) }
        return GameState(players, TurnOrder(colors), boards = BoardLayouts.current() + (TierLevel.FIRST to board))
    }

    private fun requestFor(player: PlayerColor, target: CardTarget.Token) = CardPlayRequest(
        sourcePlayer = player,
        card = cardNamed("Circulate (Elemental)"),
        targets = listOf(target),
        triggeringEvent = TriggeringEvent.PlayedFromHand,
    )

    @Test
    fun `moves the target token from the main loop into the next Zone of Protection`() {
        val board = TierBoard(
            TierLevel.FIRST,
            listOf(Square(0, SquareType.BIRTH_CANAL), plain(1), plain(2), Square(3, SquareType.ZONE_OF_PROTECTION, magnitude = 1), plain(4)),
        )
        val state = gameWith(board)
        val pool = state.players.getValue(RED).tierPool(TierLevel.FIRST)
        val id = pool.startToken()
        pool.moveInPlay(0, 1)

        val result = CirculateResolver.resolve(state, requestFor(RED, CardTarget.Token(id)), CardTarget.Token(id))

        assertIs<CardPlayResult.Resolved>(result)
        assertTrue(1 !in pool.inPlayPositions)
        assertEquals(listOf(1), pool.zoneResidents)
    }

    @Test
    fun `works on any player's token, not just the caster's own`() {
        val board = TierBoard(
            TierLevel.FIRST,
            listOf(Square(0, SquareType.BIRTH_CANAL), plain(1), Square(2, SquareType.ZONE_OF_PROTECTION, magnitude = 1)),
        )
        val state = gameWith(board)
        val greenPool = state.players.getValue(GREEN).tierPool(TierLevel.FIRST)
        val greenId = greenPool.startToken()

        val result = CirculateResolver.resolve(state, requestFor(RED, CardTarget.Token(greenId)), CardTarget.Token(greenId))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(listOf(1), greenPool.zoneResidents)
    }

    @Test
    fun `finds the nearest Zone ahead, skipping one already passed`() {
        val board = TierBoard(
            TierLevel.FIRST,
            listOf(
                Square(0, SquareType.BIRTH_CANAL),
                Square(1, SquareType.ZONE_OF_PROTECTION, magnitude = 1), // behind the token, must be skipped
                plain(2),
                Square(3, SquareType.ZONE_OF_PROTECTION, magnitude = 2), // ahead, this is "next"
                plain(4),
            ),
        )
        val state = gameWith(board)
        val pool = state.players.getValue(RED).tierPool(TierLevel.FIRST)
        val id = pool.startToken()
        pool.moveInPlay(0, 2)

        val result = CirculateResolver.resolve(state, requestFor(RED, CardTarget.Token(id)), CardTarget.Token(id))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(listOf(2), pool.zoneResidents) // Zone 2, not Zone 1
    }

    @Test
    fun `wraps around the board to find the next Zone when none is ahead before the end`() {
        val board = TierBoard(
            TierLevel.FIRST,
            listOf(
                Square(0, SquareType.BIRTH_CANAL),
                Square(1, SquareType.ZONE_OF_PROTECTION, magnitude = 1),
                plain(2),
                plain(3),
            ),
        )
        val state = gameWith(board)
        val pool = state.players.getValue(RED).tierPool(TierLevel.FIRST)
        val id = pool.startToken()
        pool.moveInPlay(0, 3) // nothing ahead of 3 except wrapping back to 0, then 1

        val result = CirculateResolver.resolve(state, requestFor(RED, CardTarget.Token(id)), CardTarget.Token(id))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(listOf(1), pool.zoneResidents)
    }

    @Test
    fun `a token already inside a Zone of Protection cannot be targeted, even the player's own`() {
        val board = TierBoard(
            TierLevel.FIRST,
            listOf(Square(0, SquareType.BIRTH_CANAL), plain(1), Square(2, SquareType.ZONE_OF_PROTECTION, magnitude = 1)),
        )
        val state = gameWith(board)
        val pool = state.players.getValue(RED).tierPool(TierLevel.FIRST)
        val id = pool.startToken()
        pool.moveInPlay(0, 2)
        pool.enterZone(fromPosition = 2, zoneNumber = 1)

        val result = CirculateResolver.resolve(state, requestFor(RED, CardTarget.Token(id)), CardTarget.Token(id))

        assertIs<CardPlayResult.Rejected>(result)
        assertIs<TargetValidationError.ZoneOfProtectionBlocksTarget>(result.reason)
        assertEquals(listOf(1), pool.zoneResidents) // untouched
    }

    @Test
    fun `rejects a Marauder target`() {
        val board = TierBoard(
            TierLevel.FIRST,
            listOf(Square(0, SquareType.BIRTH_CANAL), plain(1), Square(2, SquareType.ZONE_OF_PROTECTION, magnitude = 1)),
        )
        val state = gameWith(board)
        val marauderId = state.players.getValue(RED).marauders.placeOnBirthCanal(TierLevel.FIRST)

        val result = CirculateResolver.resolve(state, requestFor(RED, CardTarget.Token(marauderId)), CardTarget.Token(marauderId))

        assertIs<CardPlayResult.Rejected>(result)
        assertIs<TargetValidationError.WrongTokenType>(result.reason)
        assertEquals(0, state.deck.discardPileSize)
    }

    @Test
    fun `a target that no longer exists is rejected gracefully, not a crash`() {
        val board = TierBoard(
            TierLevel.FIRST,
            listOf(Square(0, SquareType.BIRTH_CANAL), plain(1), Square(2, SquareType.ZONE_OF_PROTECTION, magnitude = 1)),
        )
        val state = gameWith(board)
        val pool = state.players.getValue(RED).tierPool(TierLevel.FIRST)
        val id = pool.startToken()
        pool.destroyInPlay(0) // gone before this resolver ever runs

        val result = CirculateResolver.resolve(state, requestFor(RED, CardTarget.Token(id)), CardTarget.Token(id))

        assertIs<CardPlayResult.Rejected>(result)
        assertIs<TargetValidationError.NoLegalTarget>(result.reason)
    }

    @Test
    fun `dispatches through CardEffectDispatcher given a token target`() {
        val board = TierBoard(
            TierLevel.FIRST,
            listOf(Square(0, SquareType.BIRTH_CANAL), plain(1), Square(2, SquareType.ZONE_OF_PROTECTION, magnitude = 1)),
        )
        val state = gameWith(board)
        val pool = state.players.getValue(RED).tierPool(TierLevel.FIRST)
        val id = pool.startToken()

        val result = CardEffectDispatcher.dispatch(state, requestFor(RED, CardTarget.Token(id)))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(listOf(1), pool.zoneResidents)
    }
}
