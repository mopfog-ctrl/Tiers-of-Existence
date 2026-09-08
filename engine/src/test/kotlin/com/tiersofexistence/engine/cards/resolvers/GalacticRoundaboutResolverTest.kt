package com.tiersofexistence.engine.cards.resolvers

import com.tiersofexistence.engine.board.BoardLayouts
import com.tiersofexistence.engine.board.ProtectionZone
import com.tiersofexistence.engine.board.Square
import com.tiersofexistence.engine.board.SquareType
import com.tiersofexistence.engine.board.TierBoard
import com.tiersofexistence.engine.cards.FateHarvestCatalog
import com.tiersofexistence.engine.cards.play.CardPlayRequest
import com.tiersofexistence.engine.cards.play.CardPlayResult
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
 * Galactic Roundabout: "Move every token (all tokens including Marauders and Tier tokens in the
 * Zone of Protection) forward two spaces. This card must be played immediately." Two rulings
 * confirmed by the user, resolving `docs/card-mechanics-matrix.md` §4 Q5: (a) this uniform shift
 * does NOT trigger the normal Marauder pass-through-destroy rule; (b) two players' tokens landing
 * exactly on their own 4th Tier You Win square in the same resolution is a genuine tie. See
 * [GalacticRoundaboutResolver]'s class doc.
 */
class GalacticRoundaboutResolverTest {

    private fun cardNamed(name: String) = FateHarvestCatalog.all.single { it.name == name }

    private fun plain(index: Int) = Square(index, SquareType.PLAIN)

    private fun requestFor(player: PlayerColor) = CardPlayRequest(
        sourcePlayer = player,
        card = cardNamed("Galactic Roundabout"),
        triggeringEvent = TriggeringEvent.PlayedFromHand,
    )

    private fun gameWith(tier: TierLevel, board: TierBoard, colors: List<PlayerColor> = listOf(RED, GREEN)): GameState {
        val players = colors.associateWith { PlayerState(it) }
        return GameState(players, TurnOrder(colors), boards = BoardLayouts.current() + (tier to board))
    }

    @Test
    fun `moves every player's Tier token 2 spaces`() {
        val board = TierBoard(TierLevel.FIRST, listOf(Square(0, SquareType.BIRTH_CANAL)) + (1..5).map { plain(it) })
        val state = gameWith(TierLevel.FIRST, board)
        state.players.getValue(RED).tierPool(TierLevel.FIRST).startToken()
        state.players.getValue(GREEN).tierPool(TierLevel.FIRST).startToken()

        val result = GalacticRoundaboutResolver.resolve(state, requestFor(RED))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(listOf(2), state.players.getValue(RED).tierPool(TierLevel.FIRST).inPlayPositions)
        assertEquals(listOf(2), state.players.getValue(GREEN).tierPool(TierLevel.FIRST).inPlayPositions)
    }

    @Test
    fun `Marauder movement here does not trigger pass-through destruction, unlike ordinary Marauder movement`() {
        val board = TierBoard(TierLevel.FIRST, listOf(Square(0, SquareType.BIRTH_CANAL)) + (1..5).map { plain(it) })
        val state = gameWith(TierLevel.FIRST, board)
        val redMarauders = state.players.getValue(RED).marauders
        val greenMarauders = state.players.getValue(GREEN).marauders
        redMarauders.placeOnBirthCanal(TierLevel.FIRST) // position 0
        val greenId = greenMarauders.placeOnBirthCanal(TierLevel.FIRST)
        greenMarauders.move(TierLevel.FIRST, 0, 1) // sits directly in RED's Marauder's path

        val result = GalacticRoundaboutResolver.resolve(state, requestFor(RED))

        assertIs<CardPlayResult.Resolved>(result)
        // Ordinary Marauder movement would destroy GREEN's Marauder as RED's passes over
        // position 1 — this card's own ruling means it survives, and still gets its own 2-space
        // move from this same sweep.
        assertEquals(2, redMarauders.positionOf(redMarauders.idAt(TierLevel.FIRST, 2)!!))
        assertEquals(3, greenMarauders.positionOf(greenId))
    }

    @Test
    fun `a Zone-resident Tier token that stays within its Zone moves via moveZoneToken`() {
        val board = TierBoard(
            TierLevel.FIRST,
            listOf(Square(0, SquareType.BIRTH_CANAL), Square(1, SquareType.ZONE_OF_PROTECTION, magnitude = 9)),
            protectionZones = listOf(ProtectionZone(9, squares = List(4) { SquareType.PLAIN })),
        )
        val state = gameWith(TierLevel.FIRST, board)
        val pool = state.players.getValue(RED).tierPool(TierLevel.FIRST)
        val id = pool.startToken()
        pool.moveInPlay(0, 1)
        pool.enterZone(fromPosition = 1, zoneNumber = 9) // zone position 1

        val result = GalacticRoundaboutResolver.resolve(state, requestFor(RED))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(listOf(9), pool.zoneResidents) // still a resident
        assertEquals(3, pool.zonePositionOf(id)) // 1 + 2
    }

    @Test
    fun `a Zone-resident Tier token that overflows its Zone exits back onto the main loop`() {
        val board = TierBoard(
            TierLevel.FIRST,
            listOf(Square(0, SquareType.BIRTH_CANAL), Square(1, SquareType.ZONE_OF_PROTECTION, magnitude = 9), plain(2), plain(3)),
            protectionZones = listOf(ProtectionZone(9, squares = listOf(SquareType.PLAIN))),
        )
        val state = gameWith(TierLevel.FIRST, board)
        val pool = state.players.getValue(RED).tierPool(TierLevel.FIRST)
        val id = pool.startToken()
        pool.moveInPlay(0, 1)
        pool.enterZone(fromPosition = 1, zoneNumber = 9) // zone position 1, only slot in this Zone

        val result = GalacticRoundaboutResolver.resolve(state, requestFor(RED))

        assertIs<CardPlayResult.Resolved>(result)
        assertTrue(pool.zoneResidents.isEmpty())
        // 1 + 2 = 3, overflows the 1-slot Zone by 2 — exits at the entry square (1), continues 2
        // more spaces to square 3.
        assertEquals(listOf(3), pool.inPlayPositions)
        assertEquals(3, pool.positionOf(id))
    }

    @Test
    fun `two players' tokens landing exactly on their own You Win square in the same resolution is a tie`() {
        val board = TierBoard(TierLevel.FOURTH, listOf(Square(0, SquareType.BIRTH_CANAL), plain(1), Square(2, SquareType.YOU_WIN)))
        val state = gameWith(TierLevel.FOURTH, board)
        state.players.getValue(RED).tierPool(TierLevel.FOURTH).startToken()
        state.players.getValue(GREEN).tierPool(TierLevel.FOURTH).startToken()

        val result = GalacticRoundaboutResolver.resolve(state, requestFor(RED))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(setOf(RED, GREEN), state.winners)
        assertTrue(state.winner == RED || state.winner == GREEN)
    }

    @Test
    fun `a single exact You Win landing declares just that one winner, same as ordinary play`() {
        val board = TierBoard(TierLevel.FOURTH, listOf(Square(0, SquareType.BIRTH_CANAL), plain(1), Square(2, SquareType.YOU_WIN)))
        val state = gameWith(TierLevel.FOURTH, board)
        state.players.getValue(RED).tierPool(TierLevel.FOURTH).startToken()
        state.players.getValue(GREEN).tierPool(TierLevel.FOURTH).startToken()
        state.players.getValue(GREEN).tierPool(TierLevel.FOURTH).moveInPlay(0, 1) // won't land exactly on You Win

        val result = GalacticRoundaboutResolver.resolve(state, requestFor(RED))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(setOf(RED), state.winners)
        assertEquals(RED, state.winner)
    }

    @Test
    fun `a winner already declared before this resolution is left untouched`() {
        val board = TierBoard(TierLevel.FOURTH, listOf(Square(0, SquareType.BIRTH_CANAL), plain(1), Square(2, SquareType.YOU_WIN)))
        val state = gameWith(TierLevel.FOURTH, board)
        state.players.getValue(RED).tierPool(TierLevel.FOURTH).startToken()
        state.players.getValue(GREEN).tierPool(TierLevel.FOURTH).startToken()
        state.declareWinner(RED) // an earlier, separate resolution already decided the game

        val result = GalacticRoundaboutResolver.resolve(state, requestFor(RED))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(setOf(RED), state.winners) // GREEN's own exact landing in this sweep doesn't get added
    }

    @Test
    fun `dispatches through CardEffectDispatcher with no target needed`() {
        val board = TierBoard(TierLevel.FIRST, listOf(Square(0, SquareType.BIRTH_CANAL)) + (1..5).map { plain(it) })
        val state = gameWith(TierLevel.FIRST, board)
        state.players.getValue(RED).tierPool(TierLevel.FIRST).startToken()

        val result = CardEffectDispatcher.dispatch(state, requestFor(RED))

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(listOf(2), state.players.getValue(RED).tierPool(TierLevel.FIRST).inPlayPositions)
    }
}
