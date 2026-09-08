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
import com.tiersofexistence.engine.model.PlayerColor.BLACK
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
 * Plasma Burst: "Red player chooses a Tier, then removes all tokens (of any type) from 3
 * neighboring squares on that Tier. This card can also destroy Tier tokens on Zone of Protection
 * squares." Confirmed with the user: the 3 squares are 3 consecutive main-loop positions, and
 * Plasma Burst does affect Zone-of-Protection residents (a named rule-12 exception) by reaching
 * in through the Zone's own entry square when it's one of the 3 chosen. See
 * [PlasmaBurstResolver]'s class doc.
 */
class PlasmaBurstResolverTest {

    private fun cardNamed(name: String) = FateHarvestCatalog.all.single { it.name == name }

    private fun plain(index: Int) = Square(index, SquareType.PLAIN)

    private fun boardOf(vararg squares: Square) = TierBoard(TierLevel.FIRST, squares.toList())

    private fun gameWith(tier: TierLevel, board: TierBoard, colors: List<PlayerColor> = listOf(RED, GREEN)): GameState {
        val players = colors.associateWith { PlayerState(it) }
        return GameState(players, TurnOrder(colors), boards = BoardLayouts.current() + (tier to board))
    }

    private fun gameWith(board: TierBoard, colors: List<PlayerColor> = listOf(RED, GREEN)): GameState =
        gameWith(TierLevel.FIRST, board, colors)

    private fun requestFor(player: PlayerColor, position: Int, tier: TierLevel = TierLevel.FIRST) = CardPlayRequest(
        sourcePlayer = player,
        card = cardNamed("Plasma Burst"),
        targets = listOf(CardTarget.BoardPosition(tier, position)),
        triggeringEvent = TriggeringEvent.PlayedFromHand,
    )

    @Test
    fun `removes all tokens of any owner from the 3 chosen consecutive squares`() {
        val board = boardOf(Square(0, SquareType.BIRTH_CANAL), plain(1), plain(2), plain(3), plain(4))
        val state = gameWith(board)
        val redPool = state.players.getValue(RED).tierPool(TierLevel.FIRST)
        val greenPool = state.players.getValue(GREEN).tierPool(TierLevel.FIRST)
        redPool.startToken()
        redPool.moveInPlay(0, 1)
        greenPool.startToken()
        greenPool.moveInPlay(0, 3)

        val result = PlasmaBurstResolver.resolve(state, requestFor(RED, 1), CardTarget.BoardPosition(TierLevel.FIRST, 1))

        assertIs<CardPlayResult.Resolved>(result)
        assertTrue(1 !in redPool.inPlayPositions)
        assertTrue(3 !in greenPool.inPlayPositions) // Red's own card, but still destroys Green's token too
    }

    @Test
    fun `leaves tokens outside the 3-square window untouched`() {
        val board = boardOf(Square(0, SquareType.BIRTH_CANAL), plain(1), plain(2), plain(3), plain(4), plain(5))
        val state = gameWith(board)
        val redPool = state.players.getValue(RED).tierPool(TierLevel.FIRST)
        redPool.startToken()
        redPool.moveInPlay(0, 5) // outside [1, 2, 3]

        val result = PlasmaBurstResolver.resolve(state, requestFor(RED, 1), CardTarget.BoardPosition(TierLevel.FIRST, 1))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(listOf(5), redPool.inPlayPositions)
    }

    @Test
    fun `destroys Marauders on the chosen squares too, since the card names no token type`() {
        val board = boardOf(Square(0, SquareType.BIRTH_CANAL), plain(1), plain(2), plain(3))
        val state = gameWith(board)
        val marauders = state.players.getValue(GREEN).marauders
        marauders.placeOnBirthCanal(TierLevel.FIRST)
        marauders.move(TierLevel.FIRST, 0, 2)

        val result = PlasmaBurstResolver.resolve(state, requestFor(RED, 1), CardTarget.BoardPosition(TierLevel.FIRST, 1))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(0, marauders.inPlayCount(TierLevel.FIRST))
    }

    @Test
    fun `destroys every stacked token sharing one of the chosen squares, not just one`() {
        val board = boardOf(Square(0, SquareType.BIRTH_CANAL), plain(1), plain(2))
        val state = gameWith(board, colors = listOf(RED, GREEN, BLACK))
        val redPool = state.players.getValue(RED).tierPool(TierLevel.FIRST)
        val greenPool = state.players.getValue(GREEN).tierPool(TierLevel.FIRST)
        redPool.startToken()
        redPool.moveInPlay(0, 1)
        greenPool.startToken()
        greenPool.moveInPlay(0, 1) // stacked with Red's on the same square

        val result = PlasmaBurstResolver.resolve(state, requestFor(RED, 0), CardTarget.BoardPosition(TierLevel.FIRST, 0))

        assertIs<CardPlayResult.Resolved>(result)
        assertTrue(1 !in redPool.inPlayPositions)
        assertTrue(1 !in greenPool.inPlayPositions)
    }

    @Test
    fun `reaches into a Zone of Protection when its entry square is one of the 3 chosen`() {
        val board = boardOf(
            Square(0, SquareType.BIRTH_CANAL),
            plain(1),
            Square(2, SquareType.ZONE_OF_PROTECTION, magnitude = 1),
            plain(3),
        )
        val state = gameWith(board)
        val greenPool = state.players.getValue(GREEN).tierPool(TierLevel.FIRST)
        greenPool.startToken()
        greenPool.moveInPlay(0, 2)
        greenPool.enterZone(fromPosition = 2, zoneNumber = 1)

        val result = PlasmaBurstResolver.resolve(state, requestFor(RED, 1), CardTarget.BoardPosition(TierLevel.FIRST, 1))

        assertIs<CardPlayResult.Resolved>(result)
        assertTrue(greenPool.zoneResidents.isEmpty())
    }

    @Test
    fun `a token that only landed on the entry square without entering the Zone is destroyed by the plain sweep`() {
        val board = boardOf(
            Square(0, SquareType.BIRTH_CANAL),
            plain(1),
            Square(2, SquareType.ZONE_OF_PROTECTION, magnitude = 1),
            plain(3),
        )
        val state = gameWith(board)
        val greenPool = state.players.getValue(GREEN).tierPool(TierLevel.FIRST)
        greenPool.startToken()
        greenPool.moveInPlay(0, 2) // sitting on the entry square, never actually entered

        val result = PlasmaBurstResolver.resolve(state, requestFor(RED, 1), CardTarget.BoardPosition(TierLevel.FIRST, 1))

        assertIs<CardPlayResult.Resolved>(result)
        assertTrue(2 !in greenPool.inPlayPositions)
        assertTrue(greenPool.zoneResidents.isEmpty()) // never was a resident to begin with
    }

    @Test
    fun `a Zone resident not reached by the chosen 3 squares survives`() {
        val board = boardOf(
            Square(0, SquareType.BIRTH_CANAL),
            plain(1),
            plain(2),
            plain(3),
            plain(4),
            Square(5, SquareType.ZONE_OF_PROTECTION, magnitude = 1),
        )
        val state = gameWith(board)
        val greenPool = state.players.getValue(GREEN).tierPool(TierLevel.FIRST)
        greenPool.startToken()
        greenPool.moveInPlay(0, 5)
        greenPool.enterZone(fromPosition = 5, zoneNumber = 1)

        val result = PlasmaBurstResolver.resolve(state, requestFor(RED, 1), CardTarget.BoardPosition(TierLevel.FIRST, 1)) // squares [1,2,3]

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(listOf(1), greenPool.zoneResidents)
    }

    @Test
    fun `the 3-square window wraps around the board`() {
        // 3rd Tier, deliberately: it has no 1st-Tier-style Ion Battery auto-replenish, so
        // destroying the token at Birth Canal (position 0) can't spawn a fresh replacement there
        // that would confuse this test about what the wrap actually swept.
        val board = TierBoard(TierLevel.THIRD, listOf(Square(0, SquareType.BIRTH_CANAL), plain(1), plain(2), plain(3)))
        val state = gameWith(TierLevel.THIRD, board)
        val redPool = state.players.getValue(RED).tierPool(TierLevel.THIRD)
        redPool.startToken()
        redPool.moveInPlay(0, 3)
        redPool.startToken() // second Red token, stays on Birth Canal (index 0)

        // window starting at 3: squares [3, 0, 1] (wraps past the end of a 4-square board)
        val result = PlasmaBurstResolver.resolve(state, requestFor(RED, 3, TierLevel.THIRD), CardTarget.BoardPosition(TierLevel.THIRD, 3))

        assertIs<CardPlayResult.Resolved>(result)
        assertTrue(redPool.inPlayPositions.isEmpty())
    }

    @Test
    fun `played by a non-Red player is rejected and destroys nothing`() {
        val board = boardOf(Square(0, SquareType.BIRTH_CANAL), plain(1), plain(2), plain(3))
        val state = gameWith(board)
        val greenPool = state.players.getValue(GREEN).tierPool(TierLevel.FIRST)
        greenPool.startToken()
        greenPool.moveInPlay(0, 1)

        val result = PlasmaBurstResolver.resolve(state, requestFor(GREEN, 1), CardTarget.BoardPosition(TierLevel.FIRST, 1))

        assertIs<CardPlayResult.Rejected>(result)
        assertIs<TargetValidationError.WrongColor>(result.reason)
        assertEquals(listOf(1), greenPool.inPlayPositions) // untouched
        assertEquals(0, state.deck.discardPileSize)
    }

    @Test
    fun `dispatches through CardEffectDispatcher given a board position target`() {
        val board = boardOf(Square(0, SquareType.BIRTH_CANAL), plain(1), plain(2), plain(3))
        val state = gameWith(board)
        val greenPool = state.players.getValue(GREEN).tierPool(TierLevel.FIRST)
        greenPool.startToken()
        greenPool.moveInPlay(0, 1)

        val result = CardEffectDispatcher.dispatch(state, requestFor(RED, 1))

        assertIs<CardPlayResult.Resolved>(result)
        assertTrue(1 !in greenPool.inPlayPositions)
    }

    @Test
    fun `CardEffectDispatcher rejects Plasma Burst given the wrong target type`() {
        val board = boardOf(Square(0, SquareType.BIRTH_CANAL), plain(1))
        val state = gameWith(board)
        val request = CardPlayRequest(RED, cardNamed("Plasma Burst"), listOf(CardTarget.TierChoice(TierLevel.FIRST)), TriggeringEvent.PlayedFromHand)

        val result = CardEffectDispatcher.dispatch(state, request)

        assertIs<CardPlayResult.Rejected>(result)
        assertIs<TargetValidationError.NoLegalTarget>(result.reason)
    }
}
