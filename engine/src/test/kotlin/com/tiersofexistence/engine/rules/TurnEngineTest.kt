package com.tiersofexistence.engine.rules

import com.tiersofexistence.engine.board.BoardLayouts
import com.tiersofexistence.engine.board.Square
import com.tiersofexistence.engine.board.SquareType
import com.tiersofexistence.engine.board.TierBoard
import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.PlayerColor.GREEN
import com.tiersofexistence.engine.model.PlayerColor.RED
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.state.GameState
import com.tiersofexistence.engine.state.PlayerState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TurnEngineTest {

    private fun boardOf(tier: TierLevel, vararg squares: Square): TierBoard = TierBoard(tier, squares.toList())

    private fun plain(index: Int) = Square(index, SquareType.PLAIN)

    private fun gameWith(tier: TierLevel, board: TierBoard, colors: List<PlayerColor> = listOf(RED, GREEN)): GameState {
        val players = colors.associateWith { PlayerState(it) }
        return GameState(players, TurnOrder(colors), boards = BoardLayouts.current() + (tier to board))
    }

    // --- Tier token movement ---

    @Test
    fun `plain movement wraps around the board and has no effect`() {
        val board = boardOf(TierLevel.FIRST, Square(0, SquareType.BIRTH_CANAL), plain(1), plain(2))
        val game = gameWith(TierLevel.FIRST, board)
        game.players.getValue(RED).tierPool(TierLevel.FIRST).startToken()

        val result = TurnEngine.moveTierToken(game, RED, TierLevel.FIRST, fromPosition = 0, spaces = 5)

        assertEquals(2, result.finalPosition) // (0 + 5) % 3
        assertEquals(SquareType.PLAIN, result.landedSquareType)
        assertIs<SquareEffect.None>(result.effect)
        assertEquals(listOf(2), game.players.getValue(RED).tierPool(TierLevel.FIRST).inPlayPositions)
    }

    @Test
    fun `landing on Nebula fills the staging pile and promotes once it hits threshold`() {
        // 3rd Tier's staging pile threshold is 2.
        val board = boardOf(TierLevel.THIRD, Square(0, SquareType.BIRTH_CANAL), Square(1, SquareType.NEBULA))
        val game = gameWith(TierLevel.THIRD, board)
        val red = game.players.getValue(RED)
        red.tierPool(TierLevel.THIRD).startToken()

        val first = TurnEngine.moveTierToken(game, RED, TierLevel.THIRD, fromPosition = 0, spaces = 1)
        assertEquals(SquareEffect.SentToStagingPile(promotedToNextTier = false), first.effect)
        assertEquals(1, red.tierPool(TierLevel.THIRD).stagingPile)
        assertEquals(0, red.tierPool(TierLevel.FOURTH).inPlayCount)

        red.tierPool(TierLevel.THIRD).startToken()
        val second = TurnEngine.moveTierToken(game, RED, TierLevel.THIRD, fromPosition = 0, spaces = 1)
        assertEquals(SquareEffect.SentToStagingPile(promotedToNextTier = true), second.effect)
        assertEquals(0, red.tierPool(TierLevel.THIRD).stagingPile)
        assertEquals(1, red.tierPool(TierLevel.FOURTH).inPlayCount)
    }

    @Test
    fun `landing on Vortex of Regression sends the token back to Start`() {
        val board = boardOf(TierLevel.FIRST, Square(0, SquareType.BIRTH_CANAL), plain(1), Square(2, SquareType.VORTEX_OF_REGRESSION))
        val game = gameWith(TierLevel.FIRST, board)
        game.players.getValue(RED).tierPool(TierLevel.FIRST).startToken()

        val result = TurnEngine.moveTierToken(game, RED, TierLevel.FIRST, fromPosition = 0, spaces = 2)

        assertEquals(0, result.finalPosition)
        assertIs<SquareEffect.SentToStart>(result.effect)
        assertEquals(listOf(0), game.players.getValue(RED).tierPool(TierLevel.FIRST).inPlayPositions)
    }

    @Test
    fun `landing on Wormhole of Construction promotes the token immediately`() {
        val board = boardOf(TierLevel.SECOND, Square(0, SquareType.BIRTH_CANAL), Square(1, SquareType.WORMHOLE_OF_CONSTRUCTION))
        val game = gameWith(TierLevel.SECOND, board)
        val red = game.players.getValue(RED)
        red.tierPool(TierLevel.SECOND).startToken()

        val result = TurnEngine.moveTierToken(game, RED, TierLevel.SECOND, fromPosition = 0, spaces = 1)

        assertEquals(SquareEffect.Promoted(TierLevel.THIRD), result.effect)
        assertEquals(0, red.tierPool(TierLevel.SECOND).inPlayCount)
        assertEquals(1, red.tierPool(TierLevel.THIRD).inPlayCount)
    }

    @Test
    fun `landing exactly on You Win declares the winner`() {
        val board = boardOf(TierLevel.FOURTH, Square(0, SquareType.BIRTH_CANAL), plain(1), Square(2, SquareType.YOU_WIN))
        val game = gameWith(TierLevel.FOURTH, board)
        game.players.getValue(RED).tierPool(TierLevel.FOURTH).startToken()

        val result = TurnEngine.moveTierToken(game, RED, TierLevel.FOURTH, fromPosition = 0, spaces = 2)

        assertIs<SquareEffect.Won>(result.effect)
        assertEquals(RED, game.winner)
    }

    @Test
    fun `overshooting You Win just continues around the loop, no win`() {
        val board = boardOf(TierLevel.FOURTH, Square(0, SquareType.BIRTH_CANAL), plain(1), Square(2, SquareType.YOU_WIN))
        val game = gameWith(TierLevel.FOURTH, board)
        game.players.getValue(RED).tierPool(TierLevel.FOURTH).startToken()

        val result = TurnEngine.moveTierToken(game, RED, TierLevel.FOURTH, fromPosition = 0, spaces = 4) // lands on 1, not 2

        assertEquals(1, result.finalPosition)
        assertIs<SquareEffect.None>(result.effect)
        assertEquals(null, game.winner)
    }

    @Test
    fun `landing on Infernal Abyss destroys the token`() {
        val board = boardOf(TierLevel.FOURTH, Square(0, SquareType.BIRTH_CANAL), Square(1, SquareType.INFERNAL_ABYSS))
        val game = gameWith(TierLevel.FOURTH, board)
        val red = game.players.getValue(RED)
        red.tierPool(TierLevel.FOURTH).startToken()

        val result = TurnEngine.moveTierToken(game, RED, TierLevel.FOURTH, fromPosition = 0, spaces = 1)

        assertIs<SquareEffect.Destroyed>(result.effect)
        assertEquals(0, red.tierPool(TierLevel.FOURTH).inPlayCount)
    }

    @Test
    fun `landing on Fate Harvest draws a card, held cards go to hand`() {
        val board = boardOf(TierLevel.FIRST, Square(0, SquareType.BIRTH_CANAL), Square(1, SquareType.FATE_HARVEST))
        val game = gameWith(TierLevel.FIRST, board)
        val red = game.players.getValue(RED)
        red.tierPool(TierLevel.FIRST).startToken()

        val result = TurnEngine.moveTierToken(game, RED, TierLevel.FIRST, fromPosition = 0, spaces = 1)

        val effect = assertIs<SquareEffect.DrewCard>(result.effect)
        if (effect.card.timing == com.tiersofexistence.engine.cards.CardTiming.HELD) {
            assertEquals(listOf(effect.card), red.hand)
        } else {
            assertTrue(red.hand.isEmpty())
        }
    }

    @Test
    fun `landing on Marauder Construction Facility offers building, doesn't build automatically`() {
        val board = boardOf(TierLevel.FIRST, Square(0, SquareType.BIRTH_CANAL), Square(1, SquareType.MARAUDER_CONSTRUCTION_FACILITY))
        val game = gameWith(TierLevel.FIRST, board)
        val red = game.players.getValue(RED)
        red.tierPool(TierLevel.FIRST).startToken()

        val result = TurnEngine.moveTierToken(game, RED, TierLevel.FIRST, fromPosition = 0, spaces = 1)
        assertIs<SquareEffect.MayBuildMarauder>(result.effect)
        assertEquals(0, red.marauders.inPlayCount(TierLevel.FIRST))

        TurnEngine.buildMarauder(game, RED, TierLevel.FIRST)
        assertEquals(1, red.marauders.inPlayCount(TierLevel.FIRST))
    }

    @Test
    fun `Hyperthrust destroys opponents passed and resolves the final landing square`() {
        val board = boardOf(
            TierLevel.FIRST,
            Square(0, SquareType.BIRTH_CANAL),
            Square(1, SquareType.HYPERTHRUST, magnitude = 3),
            plain(2),
            plain(3),
            Square(4, SquareType.VORTEX_OF_REGRESSION),
        )
        val game = gameWith(TierLevel.FIRST, board)
        val red = game.players.getValue(RED)
        val green = game.players.getValue(GREEN)
        red.tierPool(TierLevel.FIRST).startToken()
        green.tierPool(TierLevel.FIRST).startToken()
        green.tierPool(TierLevel.FIRST).moveInPlay(0, 3) // sits in Hyperthrust's path

        val result = TurnEngine.moveTierToken(game, RED, TierLevel.FIRST, fromPosition = 0, spaces = 1)

        assertEquals(listOf(TokenRef(GREEN, TokenKind.TIER_TOKEN, 3)), result.destroyedTokens)
        assertEquals(0, green.tierPool(TierLevel.FIRST).inPlayCount)
        assertIs<SquareEffect.SentToStart>(result.effect) // chained into the Vortex of Regression at index 4
        assertEquals(0, result.finalPosition)
    }

    @Test
    fun `Reprieve protects a tier token from Hyperthrust too`() {
        val board = boardOf(
            TierLevel.FIRST,
            Square(0, SquareType.BIRTH_CANAL),
            Square(1, SquareType.HYPERTHRUST, magnitude = 3),
            Square(2, SquareType.REPRIEVE),
            plain(3),
        )
        val game = gameWith(TierLevel.FIRST, board)
        val red = game.players.getValue(RED)
        val green = game.players.getValue(GREEN)
        red.tierPool(TierLevel.FIRST).startToken()
        green.tierPool(TierLevel.FIRST).startToken()
        green.tierPool(TierLevel.FIRST).moveInPlay(0, 2) // sits on Reprieve, in Hyperthrust's path

        val result = TurnEngine.moveTierToken(game, RED, TierLevel.FIRST, fromPosition = 0, spaces = 1)

        assertTrue(result.destroyedTokens.isEmpty())
        assertEquals(1, green.tierPool(TierLevel.FIRST).inPlayCount)
    }

    // --- Victory timing (Phase I) ---

    @Test
    fun `a movement-card-style move (not just dice) that lands exactly on You Win still wins`() {
        // TurnEngine.moveTierToken doesn't know or care whether `spaces` came from dice or a
        // Fate Harvest movement card — this proves the exact-landing check holds regardless of
        // the move's source, which is the guarantee movement cards will rely on once wired up.
        val board = boardOf(TierLevel.FOURTH, Square(0, SquareType.BIRTH_CANAL), plain(1), plain(2), Square(3, SquareType.YOU_WIN))
        val game = gameWith(TierLevel.FOURTH, board)
        game.players.getValue(RED).tierPool(TierLevel.FOURTH).startToken()

        val result = TurnEngine.moveTierToken(game, RED, TierLevel.FOURTH, fromPosition = 0, spaces = 3)

        assertIs<SquareEffect.Won>(result.effect)
        assertEquals(RED, game.winner)
    }

    @Test
    fun `chained movement through Hyperthrust landing exactly on You Win still wins`() {
        val board = boardOf(
            TierLevel.FOURTH,
            Square(0, SquareType.BIRTH_CANAL),
            Square(1, SquareType.HYPERTHRUST, magnitude = 2),
            plain(2),
            Square(3, SquareType.YOU_WIN),
        )
        val game = gameWith(TierLevel.FOURTH, board)
        game.players.getValue(RED).tierPool(TierLevel.FOURTH).startToken()

        val result = TurnEngine.moveTierToken(game, RED, TierLevel.FOURTH, fromPosition = 0, spaces = 1)

        assertIs<SquareEffect.Won>(result.effect)
        assertEquals(3, result.finalPosition)
        assertEquals(RED, game.winner)
    }

    @Test
    fun `chained movement through Warp landing exactly on You Win still wins`() {
        val board = boardOf(
            TierLevel.FOURTH,
            Square(0, SquareType.BIRTH_CANAL),
            Square(1, SquareType.WARP, magnitude = 2, note = "Warp 2 spaces"),
            plain(2),
            Square(3, SquareType.YOU_WIN),
        )
        val game = gameWith(TierLevel.FOURTH, board)
        game.players.getValue(RED).tierPool(TierLevel.FOURTH).startToken()

        val result = TurnEngine.moveTierToken(game, RED, TierLevel.FOURTH, fromPosition = 0, spaces = 1)

        assertIs<SquareEffect.Won>(result.effect)
        assertEquals(RED, game.winner)
    }

    @Test
    fun `chained movement through Warp overshooting You Win does not win`() {
        val board = boardOf(
            TierLevel.FOURTH,
            Square(0, SquareType.BIRTH_CANAL),
            Square(1, SquareType.WARP, magnitude = 3, note = "Warp 3 spaces"),
            plain(2),
            Square(3, SquareType.YOU_WIN),
            plain(4),
        )
        val game = gameWith(TierLevel.FOURTH, board)
        game.players.getValue(RED).tierPool(TierLevel.FOURTH).startToken()

        val result = TurnEngine.moveTierToken(game, RED, TierLevel.FOURTH, fromPosition = 0, spaces = 1)

        assertEquals(4, result.finalPosition) // 1 + 3, past You Win at 3
        assertIs<SquareEffect.None>(result.effect)
        assertEquals(null, game.winner)
    }

    @Test
    fun `a movement distance corrected by a card interaction before resolving still checks exact landing correctly`() {
        // Simulates a Precedence/movement card interaction resolving BEFORE the move is applied
        // (e.g. Delayed Motion's +2 pre-move roll modifier, or a Tactical Step nudge) — by the
        // time TurnEngine.moveTierToken is called, the distance already reflects whatever the
        // interaction chain decided; this proves victory is only checked against that final,
        // fully-resolved distance, not some earlier uncorrected one.
        val board = boardOf(TierLevel.FOURTH, Square(0, SquareType.BIRTH_CANAL), plain(1), Square(2, SquareType.YOU_WIN))
        val game = gameWith(TierLevel.FOURTH, board)
        game.players.getValue(RED).tierPool(TierLevel.FOURTH).startToken()
        val uncorrectedRoll = 1 // would only reach square 1, short of You Win
        val correctedDistance = uncorrectedRoll + 1 // e.g. a +1 movement card applied first

        val result = TurnEngine.moveTierToken(game, RED, TierLevel.FOURTH, fromPosition = 0, spaces = correctedDistance)

        assertIs<SquareEffect.Won>(result.effect)
        assertEquals(RED, game.winner)
    }

    @Test
    fun `once a winner is declared, a second token's exact landing never overwrites it`() {
        // Guards the Galactic-Roundabout-style case: a card that moves multiple tokens in one
        // resolution shouldn't let a later-processed token's own exact landing steal the win
        // from whichever token was processed (and won) first.
        val board = boardOf(TierLevel.FOURTH, Square(0, SquareType.BIRTH_CANAL), plain(1), Square(2, SquareType.YOU_WIN))
        val game = gameWith(TierLevel.FOURTH, board)
        game.players.getValue(RED).tierPool(TierLevel.FOURTH).startToken()
        game.players.getValue(GREEN).tierPool(TierLevel.FOURTH).startToken()

        val redResult = TurnEngine.moveTierToken(game, RED, TierLevel.FOURTH, fromPosition = 0, spaces = 2)
        assertIs<SquareEffect.Won>(redResult.effect)
        assertEquals(RED, game.winner)

        val greenResult = TurnEngine.moveTierToken(game, GREEN, TierLevel.FOURTH, fromPosition = 0, spaces = 2)
        assertIs<SquareEffect.Won>(greenResult.effect) // GREEN's own landing is still reported...
        assertEquals(RED, game.winner) // ...but doesn't overwrite the already-declared winner
    }

    // --- Zone of Protection entry ---

    @Test
    fun `landing on a Zone of Protection entry square moves the token into the Zone`() {
        val board = boardOf(TierLevel.FIRST, Square(0, SquareType.BIRTH_CANAL), Square(1, SquareType.ZONE_OF_PROTECTION, magnitude = 2))
        val game = gameWith(TierLevel.FIRST, board)
        val red = game.players.getValue(RED)
        red.tierPool(TierLevel.FIRST).startToken()

        val result = TurnEngine.moveTierToken(game, RED, TierLevel.FIRST, fromPosition = 0, spaces = 1)

        assertEquals(SquareEffect.EnteredZone(2), result.effect)
        assertTrue(red.tierPool(TierLevel.FIRST).inPlayPositions.isEmpty())
        assertEquals(listOf(2), red.tierPool(TierLevel.FIRST).zoneResidents)
    }

    @Test
    fun `a token inside a Zone of Protection is invisible to a Marauder's pass-through`() {
        val board = boardOf(
            TierLevel.FIRST,
            Square(0, SquareType.BIRTH_CANAL),
            Square(1, SquareType.ZONE_OF_PROTECTION, magnitude = 2),
            plain(2),
            plain(3),
        )
        val game = gameWith(TierLevel.FIRST, board)
        val red = game.players.getValue(RED)
        val green = game.players.getValue(GREEN)
        red.marauders.placeOnBirthCanal(TierLevel.FIRST)
        green.tierPool(TierLevel.FIRST).startToken()
        green.tierPool(TierLevel.FIRST).moveInPlay(0, 1)
        green.tierPool(TierLevel.FIRST).enterZone(fromPosition = 1, zoneNumber = 2) // now off the main loop entirely

        val result = TurnEngine.moveMarauder(game, RED, TierLevel.FIRST, fromPosition = 0, spaces = 3)

        assertTrue(result.destroyedTokens.isEmpty())
        assertEquals(listOf(2), green.tierPool(TierLevel.FIRST).zoneResidents)
    }

    // --- Warp (Phase H: each square's own printed magnitude, not a hardcoded global) ---

    @Test
    fun `Warp moves the token forward by this square's own printed magnitude`() {
        val board = boardOf(
            TierLevel.SECOND,
            Square(0, SquareType.BIRTH_CANAL),
            Square(1, SquareType.WARP, magnitude = 7, note = "Warp 7 spaces"),
            *Array(6) { plain(it + 2) },
            Square(8, SquareType.NEBULA),
        )
        val game = gameWith(TierLevel.SECOND, board)
        val red = game.players.getValue(RED)
        red.tierPool(TierLevel.SECOND).startToken()

        val result = TurnEngine.moveTierToken(game, RED, TierLevel.SECOND, fromPosition = 0, spaces = 1)

        assertEquals(8, result.finalPosition) // 1 + 7
        assertEquals(SquareEffect.SentToStagingPile(promotedToNextTier = false), result.effect) // chained into the Nebula
    }

    @Test
    fun `Warp does not destroy tokens it passes, unlike Hyperthrust`() {
        val board = boardOf(
            TierLevel.FIRST,
            Square(0, SquareType.BIRTH_CANAL),
            Square(1, SquareType.WARP, magnitude = 5, note = "Warp 5 spaces"),
            *Array(4) { plain(it + 2) },
            plain(6),
        )
        val game = gameWith(TierLevel.FIRST, board)
        val red = game.players.getValue(RED)
        val green = game.players.getValue(GREEN)
        red.tierPool(TierLevel.FIRST).startToken()
        green.tierPool(TierLevel.FIRST).startToken()
        green.tierPool(TierLevel.FIRST).moveInPlay(0, 3) // sits in the Warp's path

        val result = TurnEngine.moveTierToken(game, RED, TierLevel.FIRST, fromPosition = 0, spaces = 1)

        assertEquals(6, result.finalPosition)
        assertTrue(result.destroyedTokens.isEmpty())
        assertEquals(1, green.tierPool(TierLevel.FIRST).inPlayCount)
    }

    @Test
    fun `looping all the way back to 1st Tier Start chains its own compound Warp instruction`() {
        val board = boardOf(
            TierLevel.FIRST,
            Square(0, SquareType.BIRTH_CANAL, magnitude = 5, note = "Start. If you land here, Warp 5 spaces."),
            plain(1),
            plain(2),
            plain(3),
            plain(4),
            Square(5, SquareType.NEBULA),
        )
        val game = gameWith(TierLevel.FIRST, board)
        val red = game.players.getValue(RED)
        red.tierPool(TierLevel.FIRST).startToken()
        red.tierPool(TierLevel.FIRST).moveInPlay(0, 5) // wrap distance in this tiny test board is 6, so...

        // Move from square 5 by 1 space wraps to square 0 (Start) on this 6-square board, which
        // should then chain its own "Warp 5 spaces" straight onto the Nebula at index 5.
        val result = TurnEngine.moveTierToken(game, RED, TierLevel.FIRST, fromPosition = 5, spaces = 1)

        assertEquals(5, result.finalPosition) // (0 + 5) % 6 == 5, the Nebula
        assertEquals(SquareEffect.SentToStagingPile(promotedToNextTier = false), result.effect)
    }

    @Test
    fun `an ordinary plain Birth Canal square (no Warp note) is not affected by the compound-Warp rule`() {
        val board = boardOf(TierLevel.SECOND, Square(0, SquareType.BIRTH_CANAL), plain(1), plain(2))
        val game = gameWith(TierLevel.SECOND, board)
        val red = game.players.getValue(RED)
        red.tierPool(TierLevel.SECOND).startToken()
        red.tierPool(TierLevel.SECOND).moveInPlay(0, 2)

        val result = TurnEngine.moveTierToken(game, RED, TierLevel.SECOND, fromPosition = 2, spaces = 1) // wraps to 0

        assertEquals(0, result.finalPosition)
        assertIs<SquareEffect.None>(result.effect)
    }

    // --- Marauder movement ---

    @Test
    fun `Marauder destroys an opponent token it passes but not one it lands on`() {
        val board = boardOf(TierLevel.FIRST, Square(0, SquareType.BIRTH_CANAL), plain(1), plain(2), plain(3), plain(4))
        val game = gameWith(TierLevel.FIRST, board)
        val red = game.players.getValue(RED)
        val green = game.players.getValue(GREEN)
        red.marauders.placeOnBirthCanal(TierLevel.FIRST)
        green.tierPool(TierLevel.FIRST).startToken()
        green.tierPool(TierLevel.FIRST).moveInPlay(0, 2) // passed over
        green.tierPool(TierLevel.FIRST).startToken()
        green.tierPool(TierLevel.FIRST).moveInPlay(0, 4) // landed on

        val result = TurnEngine.moveMarauder(game, RED, TierLevel.FIRST, fromPosition = 0, spaces = 4)

        assertEquals(listOf(TokenRef(GREEN, TokenKind.TIER_TOKEN, 2)), result.destroyedTokens)
        assertEquals(1, green.tierPool(TierLevel.FIRST).inPlayCount)
        assertEquals(listOf(4), green.tierPool(TierLevel.FIRST).inPlayPositions)
    }

    @Test
    fun `Marauder never destroys its own owner's tokens`() {
        val board = boardOf(TierLevel.FIRST, Square(0, SquareType.BIRTH_CANAL), plain(1), plain(2))
        val game = gameWith(TierLevel.FIRST, board)
        val red = game.players.getValue(RED)
        red.marauders.placeOnBirthCanal(TierLevel.FIRST)
        red.tierPool(TierLevel.FIRST).startToken()
        red.tierPool(TierLevel.FIRST).moveInPlay(0, 1)

        val result = TurnEngine.moveMarauder(game, RED, TierLevel.FIRST, fromPosition = 0, spaces = 2)

        assertTrue(result.destroyedTokens.isEmpty())
        assertEquals(1, red.tierPool(TierLevel.FIRST).inPlayCount)
    }

    @Test
    fun `Reprieve protects a passed token from a Marauder`() {
        val board = boardOf(TierLevel.FIRST, Square(0, SquareType.BIRTH_CANAL), Square(1, SquareType.REPRIEVE), plain(2))
        val game = gameWith(TierLevel.FIRST, board)
        val red = game.players.getValue(RED)
        val green = game.players.getValue(GREEN)
        red.marauders.placeOnBirthCanal(TierLevel.FIRST)
        green.tierPool(TierLevel.FIRST).startToken()
        green.tierPool(TierLevel.FIRST).moveInPlay(0, 1)

        val result = TurnEngine.moveMarauder(game, RED, TierLevel.FIRST, fromPosition = 0, spaces = 2)

        assertTrue(result.destroyedTokens.isEmpty())
        assertEquals(1, green.tierPool(TierLevel.FIRST).inPlayCount)
    }

    @Test
    fun `Reprieve does not protect a passed Marauder`() {
        val board = boardOf(TierLevel.FIRST, Square(0, SquareType.BIRTH_CANAL), Square(1, SquareType.REPRIEVE), plain(2))
        val game = gameWith(TierLevel.FIRST, board)
        val red = game.players.getValue(RED)
        val green = game.players.getValue(GREEN)
        red.marauders.placeOnBirthCanal(TierLevel.FIRST)
        green.marauders.placeOnBirthCanal(TierLevel.FIRST)
        green.marauders.move(TierLevel.FIRST, 0, 1) // Marauder sitting on Reprieve

        val result = TurnEngine.moveMarauder(game, RED, TierLevel.FIRST, fromPosition = 0, spaces = 2)

        assertEquals(listOf(TokenRef(GREEN, TokenKind.MARAUDER, 1)), result.destroyedTokens)
        assertEquals(0, green.marauders.inPlayCount(TierLevel.FIRST))
    }

    @Test
    fun `Marauder landing on Abyss is destroyed`() {
        val board = boardOf(TierLevel.FIRST, Square(0, SquareType.BIRTH_CANAL), Square(1, SquareType.ABYSS))
        val game = gameWith(TierLevel.FIRST, board)
        val red = game.players.getValue(RED)
        red.marauders.placeOnBirthCanal(TierLevel.FIRST)

        val result = TurnEngine.moveMarauder(game, RED, TierLevel.FIRST, fromPosition = 0, spaces = 1)

        assertIs<SquareEffect.Destroyed>(result.effect)
        assertEquals(0, red.marauders.inPlayCount(TierLevel.FIRST))
    }

    @Test
    fun `Marauder landing on Marauder Sensor moves 2 more squares`() {
        val board = boardOf(
            TierLevel.FIRST,
            Square(0, SquareType.BIRTH_CANAL),
            Square(1, SquareType.MARAUDER_SENSOR),
            plain(2),
            plain(3),
        )
        val game = gameWith(TierLevel.FIRST, board)
        val red = game.players.getValue(RED)
        red.marauders.placeOnBirthCanal(TierLevel.FIRST)

        val result = TurnEngine.moveMarauder(game, RED, TierLevel.FIRST, fromPosition = 0, spaces = 1)

        assertEquals(3, result.finalPosition)
        assertEquals(listOf(3), red.marauders.positions(TierLevel.FIRST))
    }

    @Test
    fun `Marauder landing on Marauder Transport offers transport, doesn't move automatically`() {
        val board = boardOf(TierLevel.SECOND, Square(0, SquareType.BIRTH_CANAL), Square(1, SquareType.MARAUDER_TRANSPORT))
        val game = gameWith(TierLevel.SECOND, board)
        val red = game.players.getValue(RED)
        red.marauders.placeOnBirthCanal(TierLevel.SECOND)

        val result = TurnEngine.moveMarauder(game, RED, TierLevel.SECOND, fromPosition = 0, spaces = 1)
        assertIs<SquareEffect.MayTransport>(result.effect)
        assertEquals(1, red.marauders.inPlayCount(TierLevel.SECOND))

        TurnEngine.transportMarauder(game, RED, TierLevel.SECOND, TierLevel.FIRST, position = 1)
        assertEquals(0, red.marauders.inPlayCount(TierLevel.SECOND))
        assertEquals(1, red.marauders.inPlayCount(TierLevel.FIRST))
    }

    @Test
    fun `no other square affects a Marauder`() {
        val board = boardOf(TierLevel.FIRST, Square(0, SquareType.BIRTH_CANAL), Square(1, SquareType.NEBULA))
        val game = gameWith(TierLevel.FIRST, board)
        val red = game.players.getValue(RED)
        red.marauders.placeOnBirthCanal(TierLevel.FIRST)

        val result = TurnEngine.moveMarauder(game, RED, TierLevel.FIRST, fromPosition = 0, spaces = 1)

        assertIs<SquareEffect.None>(result.effect)
        assertEquals(1, red.marauders.inPlayCount(TierLevel.FIRST))
    }
}
