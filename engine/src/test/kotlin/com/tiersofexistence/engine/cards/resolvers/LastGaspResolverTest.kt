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
 * Last Gasp: "Move any one of your tokens (of any type) 8 spaces. Any tokens you pass are
 * destroyed, as well as the moved token, except tokens in the Zone of Protection." Confirmed
 * with the user: the mover's own other tokens caught in the path are destroyed too (no owner
 * exemption). Uses 3rd Tier boards throughout to avoid the 1st Tier's own auto-replenishment
 * complicating assertions about exactly which tokens survive. See [LastGaspResolver]'s class doc
 * and `docs/card-mechanics-matrix.md` §29.
 */
class LastGaspResolverTest {

    private fun cardNamed(name: String) = FateHarvestCatalog.all.single { it.name == name }

    private fun plain(index: Int) = Square(index, SquareType.PLAIN)

    private fun gameWith(board: TierBoard, colors: List<PlayerColor> = listOf(RED, GREEN)): GameState {
        val players = colors.associateWith { PlayerState(it) }
        return GameState(players, TurnOrder(colors), boards = BoardLayouts.current() + (TierLevel.THIRD to board))
    }

    private fun tenPlainSquares() = TierBoard(
        TierLevel.THIRD,
        listOf(Square(0, SquareType.BIRTH_CANAL)) + (1..9).map { plain(it) },
    )

    private fun requestFor(player: PlayerColor, target: CardTarget.Token) = CardPlayRequest(
        sourcePlayer = player,
        card = cardNamed("Last Gasp"),
        targets = listOf(target),
        triggeringEvent = TriggeringEvent.PlayedFromHand,
    )

    @Test
    fun `moves 8 spaces, destroys everything passed including the mover's own other tokens, and self-destructs on arrival`() {
        val state = gameWith(tenPlainSquares())
        val redPool = state.players.getValue(RED).tierPool(TierLevel.THIRD)
        val greenPool = state.players.getValue(GREEN).tierPool(TierLevel.THIRD)
        val secondId = redPool.startToken()
        redPool.moveInPlay(0, 3) // RED's own second token, in the path
        val moverId = redPool.startToken() // stays at 0, the mover
        greenPool.startToken()
        greenPool.moveInPlay(0, 5) // GREEN's token, in the path

        val result = LastGaspResolver.resolve(state, requestFor(RED, CardTarget.Token(moverId)), CardTarget.Token(moverId))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(0, redPool.inPlayCount) // secondId destroyed by the pass, moverId self-destructed
        assertEquals(0, greenPool.inPlayCount) // GREEN's token destroyed by the pass too
    }

    @Test
    fun `Zone of Protection residents passed over are immune`() {
        val board = TierBoard(
            TierLevel.THIRD,
            listOf(Square(0, SquareType.BIRTH_CANAL), plain(1), plain(2), plain(3), Square(4, SquareType.ZONE_OF_PROTECTION, magnitude = 1), plain(5), plain(6), plain(7), plain(8)),
        )
        val state = gameWith(board)
        val moverId = state.players.getValue(RED).tierPool(TierLevel.THIRD).startToken()
        val greenPool = state.players.getValue(GREEN).tierPool(TierLevel.THIRD)
        greenPool.startToken()
        greenPool.moveInPlay(0, 4)
        greenPool.enterZone(fromPosition = 4, zoneNumber = 1)

        val result = LastGaspResolver.resolve(state, requestFor(RED, CardTarget.Token(moverId)), CardTarget.Token(moverId))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(listOf(1), greenPool.zoneResidents) // untouched
    }

    @Test
    fun `Reprieve protects a Tier token passed over, whether it's the mover's own or an opponent's`() {
        val board = TierBoard(
            TierLevel.THIRD,
            listOf(Square(0, SquareType.BIRTH_CANAL), plain(1), plain(2), Square(3, SquareType.REPRIEVE), plain(4), Square(5, SquareType.REPRIEVE), plain(6), plain(7), plain(8)),
        )
        val state = gameWith(board)
        val redPool = state.players.getValue(RED).tierPool(TierLevel.THIRD)
        val greenPool = state.players.getValue(GREEN).tierPool(TierLevel.THIRD)
        redPool.startToken()
        redPool.moveInPlay(0, 3) // RED's own second token, on Reprieve
        val moverId = redPool.startToken()
        greenPool.startToken()
        greenPool.moveInPlay(0, 5) // GREEN's token, on Reprieve

        val result = LastGaspResolver.resolve(state, requestFor(RED, CardTarget.Token(moverId)), CardTarget.Token(moverId))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(listOf(3), redPool.inPlayPositions) // the Reprieve-protected token survives; only the mover is gone
        assertEquals(listOf(5), greenPool.inPlayPositions)
    }

    @Test
    fun `Reprieve does not protect a Marauder passed over`() {
        val board = TierBoard(
            TierLevel.THIRD,
            listOf(Square(0, SquareType.BIRTH_CANAL), plain(1), plain(2), Square(3, SquareType.REPRIEVE), plain(4), plain(5), plain(6), plain(7), plain(8)),
        )
        val state = gameWith(board)
        val moverId = state.players.getValue(RED).tierPool(TierLevel.THIRD).startToken()
        val greenMarauders = state.players.getValue(GREEN).marauders
        greenMarauders.placeOnBirthCanal(TierLevel.THIRD)
        greenMarauders.move(TierLevel.THIRD, 0, 3) // sitting on Reprieve

        val result = LastGaspResolver.resolve(state, requestFor(RED, CardTarget.Token(moverId)), CardTarget.Token(moverId))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(0, greenMarauders.inPlayCount(TierLevel.THIRD)) // not protected
    }

    @Test
    fun `destroys an opponent's Marauder via a Tier-token mover, the special power rule 10 grants`() {
        val state = gameWith(tenPlainSquares())
        val moverId = state.players.getValue(RED).tierPool(TierLevel.THIRD).startToken()
        val greenMarauders = state.players.getValue(GREEN).marauders
        greenMarauders.placeOnBirthCanal(TierLevel.THIRD)
        greenMarauders.move(TierLevel.THIRD, 0, 4)

        val result = LastGaspResolver.resolve(state, requestFor(RED, CardTarget.Token(moverId)), CardTarget.Token(moverId))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(0, greenMarauders.inPlayCount(TierLevel.THIRD))
    }

    @Test
    fun `a Marauder can be the mover too, and is destroyed on arrival like any other token`() {
        val board = tenPlainSquares()
        val state = gameWith(board)
        val redMarauders = state.players.getValue(RED).marauders
        val moverId = redMarauders.placeOnBirthCanal(TierLevel.THIRD)
        val greenPool = state.players.getValue(GREEN).tierPool(TierLevel.THIRD)
        greenPool.startToken()
        greenPool.moveInPlay(0, 4) // in the path, should be destroyed despite being a Tier token

        val result = LastGaspResolver.resolve(state, requestFor(RED, CardTarget.Token(moverId)), CardTarget.Token(moverId))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(0, redMarauders.inPlayCount(TierLevel.THIRD)) // mover self-destructed
        assertEquals(0, greenPool.inPlayCount) // passed-over token destroyed
    }

    @Test
    fun `if the landing square already destroys the mover (Abyss), the self-destruct step is a graceful no-op`() {
        val board = TierBoard(TierLevel.THIRD, listOf(Square(0, SquareType.BIRTH_CANAL)) + (1..7).map { plain(it) } + Square(8, SquareType.INFERNAL_ABYSS))
        val state = gameWith(board)
        val moverId = state.players.getValue(RED).tierPool(TierLevel.THIRD).startToken()

        val result = LastGaspResolver.resolve(state, requestFor(RED, CardTarget.Token(moverId)), CardTarget.Token(moverId))

        assertIs<CardPlayResult.Resolved>(result) // no crash from a redundant destroy
    }

    @Test
    fun `landing on a Nebula stages the mover normally instead of destroying it, since it's no longer individually tracked`() {
        val board = TierBoard(TierLevel.THIRD, listOf(Square(0, SquareType.BIRTH_CANAL)) + (1..7).map { plain(it) } + Square(8, SquareType.NEBULA))
        val state = gameWith(board)
        val pool = state.players.getValue(RED).tierPool(TierLevel.THIRD)
        val moverId = pool.startToken()

        val result = LastGaspResolver.resolve(state, requestFor(RED, CardTarget.Token(moverId)), CardTarget.Token(moverId))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(1, pool.stagingPile) // staged normally, not destroyed-and-returned-to-Ion-Battery
    }

    @Test
    fun `rejects a target that isn't the source player's own token`() {
        val state = gameWith(tenPlainSquares())
        val greenId = state.players.getValue(GREEN).tierPool(TierLevel.THIRD).startToken()

        val result = LastGaspResolver.resolve(state, requestFor(RED, CardTarget.Token(greenId)), CardTarget.Token(greenId))

        assertIs<CardPlayResult.Rejected>(result)
        assertIs<TargetValidationError.WrongTokenType>(result.reason)
        assertEquals(0, state.deck.discardPileSize)
    }

    @Test
    fun `a target already inside a Zone of Protection is rejected as not yet implemented, not moved out`() {
        val board = TierBoard(TierLevel.THIRD, listOf(Square(0, SquareType.BIRTH_CANAL), plain(1), Square(2, SquareType.ZONE_OF_PROTECTION, magnitude = 1)))
        val state = gameWith(board)
        val pool = state.players.getValue(RED).tierPool(TierLevel.THIRD)
        val id = pool.startToken()
        pool.moveInPlay(0, 2)
        pool.enterZone(fromPosition = 2, zoneNumber = 1)

        val result = LastGaspResolver.resolve(state, requestFor(RED, CardTarget.Token(id)), CardTarget.Token(id))

        assertIs<CardPlayResult.Rejected>(result)
        assertIs<TargetValidationError.CardSpecificRestriction>(result.reason)
        assertEquals(listOf(1), pool.zoneResidents) // untouched
    }

    @Test
    fun `a target that no longer exists is rejected gracefully, not a crash`() {
        val state = gameWith(tenPlainSquares())
        val pool = state.players.getValue(RED).tierPool(TierLevel.THIRD)
        val id = pool.startToken()
        pool.destroyInPlay(0)

        val result = LastGaspResolver.resolve(state, requestFor(RED, CardTarget.Token(id)), CardTarget.Token(id))

        assertIs<CardPlayResult.Rejected>(result)
        assertIs<TargetValidationError.NoLegalTarget>(result.reason)
    }

    @Test
    fun `dispatches through CardEffectDispatcher given a token target`() {
        val state = gameWith(tenPlainSquares())
        val pool = state.players.getValue(RED).tierPool(TierLevel.THIRD)
        val moverId = pool.startToken()

        val result = CardEffectDispatcher.dispatch(state, requestFor(RED, CardTarget.Token(moverId)))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(0, pool.inPlayCount)
    }
}
