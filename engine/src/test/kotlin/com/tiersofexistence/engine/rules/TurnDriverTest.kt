package com.tiersofexistence.engine.rules

import com.tiersofexistence.engine.board.BoardLayouts
import com.tiersofexistence.engine.board.ProtectionZone
import com.tiersofexistence.engine.board.Square
import com.tiersofexistence.engine.board.SquareType
import com.tiersofexistence.engine.board.TierBoard
import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.PlayerColor.GREEN
import com.tiersofexistence.engine.model.PlayerColor.RED
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.state.GameState
import com.tiersofexistence.engine.state.PlayerState
import com.tiersofexistence.engine.state.TokenId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [TurnDriver] drives the mechanical roll → choose-a-token → move → resolve-the-landing part of
 * one turn, delegating every real choice to a [TurnDecisionProvider]. Fate Harvest card play is
 * deliberately out of scope for this pass — see [TurnDriver]'s class doc.
 */
class TurnDriverTest {

    private fun plain(index: Int) = Square(index, SquareType.PLAIN)

    private fun boardOf(tier: TierLevel, vararg squares: Square): TierBoard = TierBoard(tier, squares.toList())

    private fun boardWithZone(tier: TierLevel, mainLoop: List<Square>, zone: ProtectionZone): TierBoard =
        TierBoard(tier, mainLoop, protectionZones = listOf(zone))

    private fun gameWith(tier: TierLevel, board: TierBoard, colors: List<PlayerColor> = listOf(RED, GREEN)): GameState {
        val players = colors.associateWith { PlayerState(it) }
        return GameState(players, TurnOrder(colors), boards = BoardLayouts.current() + (tier to board))
    }

    private class ScriptedDecisions(
        val tokenChoice: (List<TokenId>) -> TokenId = { it.first() },
        val enterZone: Boolean = false,
        val buildMarauder: Boolean = false,
        val transport: (List<TierLevel>) -> TierLevel? = { null },
    ) : TurnDecisionProvider {
        override fun chooseTokenToMove(state: GameState, player: PlayerColor, candidates: List<TokenId>) = tokenChoice(candidates)
        override fun chooseEnterZone(state: GameState, player: PlayerColor, tier: TierLevel, zoneNumber: Int) = enterZone
        override fun chooseBuildMarauder(state: GameState, player: PlayerColor, tier: TierLevel) = buildMarauder
        override fun chooseTransport(state: GameState, player: PlayerColor, fromTier: TierLevel, options: List<TierLevel>) = transport(options)
    }

    @Test
    fun `moves the chosen candidate by the rolled amount and ends the turn`() {
        val board = boardOf(TierLevel.FIRST, Square(0, SquareType.BIRTH_CANAL), plain(1), plain(2), plain(3), plain(4), plain(5))
        val state = gameWith(TierLevel.FIRST, board)
        state.players.getValue(RED).tierPool(TierLevel.FIRST).startToken()
        state.players.getValue(GREEN).tierPool(TierLevel.FIRST).startToken()
        state.skipEmptyPhases()
        assertEquals(RED, state.currentTurn)
        val driver = TurnDriver(ScriptedDecisions(), rollForPhase = { 3 })

        val drove = driver.driveOneTurn(state)

        assertTrue(drove)
        assertEquals(listOf(3), state.players.getValue(RED).tierPool(TierLevel.FIRST).inPlayPositions)
        assertEquals(null, state.pendingRoll)
        assertEquals(GREEN, state.currentTurn)
    }

    @Test
    fun `returns false when there is no current turn to drive`() {
        val state = GameState.newGame(listOf(RED)) // Marauder Phase is empty; skipEmptyPhases never called

        val drove = TurnDriver(ScriptedDecisions()).driveOneTurn(state)

        assertFalse(drove)
    }

    @Test
    fun `asks the decision provider which of several candidate tokens to move`() {
        val board = boardOf(TierLevel.FIRST, Square(0, SquareType.BIRTH_CANAL), plain(1), plain(2), plain(3))
        val state = gameWith(TierLevel.FIRST, board, colors = listOf(RED))
        val pool = state.players.getValue(RED).tierPool(TierLevel.FIRST)
        val first = pool.startToken()
        val second = pool.startToken() // 1st Tier allows 2 in play
        state.skipEmptyPhases()
        val driver = TurnDriver(ScriptedDecisions(tokenChoice = { it.single { id -> id == second } }), rollForPhase = { 2 })

        driver.driveOneTurn(state)

        assertEquals(2, pool.positionOf(second))
        assertEquals(0, pool.positionOf(first)) // untouched
    }

    @Test
    fun `Marauder Phase offers every Marauder across all Tiers as a candidate`() {
        val state = GameState(mapOf(RED to PlayerState(RED)), TurnOrder(listOf(RED)))
        val marauders = state.players.getValue(RED).marauders
        marauders.placeOnBirthCanal(TierLevel.FIRST)
        val secondTierId = marauders.placeOnBirthCanal(TierLevel.SECOND)
        state.skipEmptyPhases()
        assertEquals(Phase.Marauder, state.currentPhase)
        val driver = TurnDriver(ScriptedDecisions(tokenChoice = { it.single { id -> id == secondTierId } }), rollForPhase = { 1 })

        driver.driveOneTurn(state)

        assertEquals(1, marauders.positionOf(secondTierId))
        assertEquals(0, marauders.positionOf(marauders.idAt(TierLevel.FIRST, 0)!!)) // untouched
    }

    @Test
    fun `accepting the Zone entry offer moves the token into the Zone`() {
        val board = boardWithZone(
            TierLevel.FIRST,
            listOf(Square(0, SquareType.BIRTH_CANAL), Square(1, SquareType.ZONE_OF_PROTECTION, magnitude = 9)),
            ProtectionZone(9, squares = List(3) { SquareType.PLAIN }),
        )
        val state = gameWith(TierLevel.FIRST, board, colors = listOf(RED))
        state.players.getValue(RED).tierPool(TierLevel.FIRST).startToken()
        state.skipEmptyPhases()
        val driver = TurnDriver(ScriptedDecisions(enterZone = true), rollForPhase = { 1 })

        driver.driveOneTurn(state)

        val pool = state.players.getValue(RED).tierPool(TierLevel.FIRST)
        assertEquals(listOf(9), pool.zoneResidents)
        assertTrue(pool.inPlayPositions.isEmpty())
    }

    @Test
    fun `declining the Zone entry offer leaves the token an ordinary in-play token`() {
        val board = boardWithZone(
            TierLevel.FIRST,
            listOf(Square(0, SquareType.BIRTH_CANAL), Square(1, SquareType.ZONE_OF_PROTECTION, magnitude = 9)),
            ProtectionZone(9, squares = List(3) { SquareType.PLAIN }),
        )
        val state = gameWith(TierLevel.FIRST, board, colors = listOf(RED))
        state.players.getValue(RED).tierPool(TierLevel.FIRST).startToken()
        state.skipEmptyPhases()
        val driver = TurnDriver(ScriptedDecisions(enterZone = false), rollForPhase = { 1 })

        driver.driveOneTurn(state)

        val pool = state.players.getValue(RED).tierPool(TierLevel.FIRST)
        assertTrue(pool.zoneResidents.isEmpty())
        assertEquals(listOf(1), pool.inPlayPositions)
    }

    @Test
    fun `each player is driven by their own TurnDecisionProvider, not one shared across the whole driver`() {
        // Two AI players with genuinely different behavior — RED always enters a Zone offered
        // to it, GREEN never does — is exactly the "several AI players, different behavior
        // types" shape the per-player constructor exists for.
        val board = boardWithZone(
            TierLevel.FIRST,
            listOf(Square(0, SquareType.BIRTH_CANAL), Square(1, SquareType.ZONE_OF_PROTECTION, magnitude = 9)),
            ProtectionZone(9, squares = List(3) { SquareType.PLAIN }),
        )
        val state = gameWith(TierLevel.FIRST, board)
        state.players.getValue(RED).tierPool(TierLevel.FIRST).startToken()
        state.players.getValue(GREEN).tierPool(TierLevel.FIRST).startToken()
        state.skipEmptyPhases()
        val driver = TurnDriver(
            mapOf(RED to ScriptedDecisions(enterZone = true), GREEN to ScriptedDecisions(enterZone = false)),
            rollForPhase = { 1 },
        )

        driver.driveOneTurn(state) // RED's turn
        driver.driveOneTurn(state) // GREEN's turn

        val redPool = state.players.getValue(RED).tierPool(TierLevel.FIRST)
        val greenPool = state.players.getValue(GREEN).tierPool(TierLevel.FIRST)
        assertEquals(listOf(9), redPool.zoneResidents) // RED's provider accepted
        assertTrue(greenPool.zoneResidents.isEmpty()) // GREEN's provider declined
        assertEquals(listOf(1), greenPool.inPlayPositions)
    }

    @Test
    fun `accepting the Marauder Construction offer builds a Marauder`() {
        val board = boardOf(TierLevel.FIRST, Square(0, SquareType.BIRTH_CANAL), Square(1, SquareType.MARAUDER_CONSTRUCTION_FACILITY))
        val state = gameWith(TierLevel.FIRST, board, colors = listOf(RED))
        state.players.getValue(RED).tierPool(TierLevel.FIRST).startToken()
        state.skipEmptyPhases()
        val driver = TurnDriver(ScriptedDecisions(buildMarauder = true), rollForPhase = { 1 })

        driver.driveOneTurn(state)

        assertEquals(1, state.players.getValue(RED).marauders.inPlayCount(TierLevel.FIRST))
    }

    @Test
    fun `declining the Marauder Construction offer builds nothing`() {
        val board = boardOf(TierLevel.FIRST, Square(0, SquareType.BIRTH_CANAL), Square(1, SquareType.MARAUDER_CONSTRUCTION_FACILITY))
        val state = gameWith(TierLevel.FIRST, board, colors = listOf(RED))
        state.players.getValue(RED).tierPool(TierLevel.FIRST).startToken()
        state.skipEmptyPhases()
        val driver = TurnDriver(ScriptedDecisions(buildMarauder = false), rollForPhase = { 1 })

        driver.driveOneTurn(state)

        assertEquals(0, state.players.getValue(RED).marauders.inPlayCount(TierLevel.FIRST))
    }

    @Test
    fun `accepting the Marauder Transport offer moves it to the chosen neighboring Tier`() {
        val board = boardOf(TierLevel.SECOND, Square(0, SquareType.BIRTH_CANAL), Square(1, SquareType.MARAUDER_TRANSPORT))
        val state = gameWith(TierLevel.SECOND, board, colors = listOf(RED))
        state.players.getValue(RED).marauders.placeOnBirthCanal(TierLevel.SECOND)
        state.skipEmptyPhases()
        val driver = TurnDriver(
            ScriptedDecisions(transport = { options ->
                assertEquals(setOf(TierLevel.THIRD, TierLevel.FIRST), options.toSet())
                TierLevel.FIRST
            }),
            rollForPhase = { 1 },
        )

        driver.driveOneTurn(state)

        val marauders = state.players.getValue(RED).marauders
        assertEquals(0, marauders.inPlayCount(TierLevel.SECOND))
        assertEquals(1, marauders.inPlayCount(TierLevel.FIRST))
    }

    @Test
    fun `declining the Marauder Transport offer leaves it on the Transport square`() {
        val board = boardOf(TierLevel.SECOND, Square(0, SquareType.BIRTH_CANAL), Square(1, SquareType.MARAUDER_TRANSPORT))
        val state = gameWith(TierLevel.SECOND, board, colors = listOf(RED))
        state.players.getValue(RED).marauders.placeOnBirthCanal(TierLevel.SECOND)
        state.skipEmptyPhases()
        val driver = TurnDriver(ScriptedDecisions(transport = { null }), rollForPhase = { 1 })

        driver.driveOneTurn(state)

        val marauders = state.players.getValue(RED).marauders
        assertEquals(1, marauders.inPlayCount(TierLevel.SECOND))
        assertEquals(listOf(1), marauders.positions(TierLevel.SECOND))
    }

    @Test
    fun `Go again grants another turn to the same player instead of advancing`() {
        val board = boardOf(TierLevel.FIRST, Square(0, SquareType.BIRTH_CANAL), Square(1, SquareType.TIME_WRINKLE, note = "Go again"))
        val state = gameWith(TierLevel.FIRST, board)
        state.players.getValue(RED).tierPool(TierLevel.FIRST).startToken()
        state.players.getValue(GREEN).tierPool(TierLevel.FIRST).startToken()
        state.skipEmptyPhases()
        assertEquals(RED, state.currentTurn)
        val driver = TurnDriver(ScriptedDecisions(), rollForPhase = { 1 })

        driver.driveOneTurn(state)

        assertEquals(RED, state.currentTurn) // still RED's turn, not advanced to GREEN
    }

    @Test
    fun `a Fate Harvest draw is recorded, never silently lost, regardless of its timing`() {
        val board = boardOf(TierLevel.FIRST, Square(0, SquareType.BIRTH_CANAL), Square(1, SquareType.FATE_HARVEST))
        val state = gameWith(TierLevel.FIRST, board, colors = listOf(RED))
        state.players.getValue(RED).tierPool(TierLevel.FIRST).startToken()
        state.skipEmptyPhases()
        val handBefore = state.players.getValue(RED).hand.size
        val pendingBefore = state.pendingImmediateCards.size
        val driver = TurnDriver(ScriptedDecisions(), rollForPhase = { 1 })

        driver.driveOneTurn(state)

        val handAfter = state.players.getValue(RED).hand.size
        val pendingAfter = state.pendingImmediateCards.size
        assertEquals(1, (handAfter - handBefore) + (pendingAfter - pendingBefore))
    }

    @Test
    fun `FirstCandidateDecisionProvider picks the first candidate and declines every offer`() {
        val board = boardOf(TierLevel.FIRST, Square(0, SquareType.BIRTH_CANAL), Square(1, SquareType.MARAUDER_CONSTRUCTION_FACILITY))
        val state = gameWith(TierLevel.FIRST, board, colors = listOf(RED))
        state.players.getValue(RED).tierPool(TierLevel.FIRST).startToken()
        state.skipEmptyPhases()
        val driver = TurnDriver(FirstCandidateDecisionProvider, rollForPhase = { 1 })

        driver.driveOneTurn(state)

        assertEquals(listOf(1), state.players.getValue(RED).tierPool(TierLevel.FIRST).inPlayPositions)
        assertEquals(0, state.players.getValue(RED).marauders.inPlayCount(TierLevel.FIRST)) // offer declined
    }
}
