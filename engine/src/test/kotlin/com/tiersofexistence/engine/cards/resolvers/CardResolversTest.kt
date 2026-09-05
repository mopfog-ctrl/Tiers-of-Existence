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
import com.tiersofexistence.engine.model.PlayerColor.RED
import com.tiersofexistence.engine.model.PlayerColor.WHITE
import com.tiersofexistence.engine.model.PlayerColor.GREEN as GreenColor
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.rules.TokenKind
import com.tiersofexistence.engine.rules.TurnOrder
import com.tiersofexistence.engine.state.GameState
import com.tiersofexistence.engine.state.PlayerState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * End-to-end tests proving the shared resolvers (Phase J Group 1) actually drive real board
 * state through the full stack (TargetValidator -> CardLifecycle -> TurnEngine/pools), not just
 * that their catalog metadata exists.
 */
class CardResolversTest {

    private fun cardNamed(name: String) = FateHarvestCatalog.all.single { it.name == name }

    private fun plain(index: Int) = Square(index, SquareType.PLAIN)

    private fun gameWith(tier: TierLevel, board: TierBoard, colors: List<PlayerColor> = listOf(RED, GreenColor, WHITE)): GameState {
        val players = colors.associateWith { PlayerState(it) }
        return GameState(players, TurnOrder(colors), boards = BoardLayouts.current() + (tier to board))
    }

    private fun requestFor(player: PlayerColor, cardName: String) = CardPlayRequest(
        sourcePlayer = player,
        card = cardNamed(cardName),
        triggeringEvent = TriggeringEvent.PlayedFromHand,
    )

    // --- MovementCardResolver ---

    @Test
    fun `Skip Hop and Jump moves the target token 3 spaces and resolves its landing`() {
        val board = TierBoard(TierLevel.FIRST, listOf(Square(0, SquareType.BIRTH_CANAL), plain(1), plain(2), Square(3, SquareType.NEBULA)))
        val state = gameWith(TierLevel.FIRST, board)
        state.players.getValue(RED).tierPool(TierLevel.FIRST).startToken()
        val target = CardTarget.Token(RED, TokenKind.TIER_TOKEN, TierLevel.FIRST, position = 0)

        val result = MovementCardResolver.resolve(state, requestFor(RED, "Skip, Hop, and Jump (Dimensional)"), target, spaces = 3)

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(1, state.players.getValue(RED).tierPool(TierLevel.FIRST).stagingPile) // landed on the Nebula
        assertEquals(1, state.deck.discardPileSize)
    }

    @Test
    fun `a movement card cannot move an opponent's token that's inside a Zone of Protection`() {
        val board = TierBoard(TierLevel.FIRST, listOf(Square(0, SquareType.BIRTH_CANAL), plain(1)))
        val state = gameWith(TierLevel.FIRST, board)
        state.players.getValue(GreenColor).tierPool(TierLevel.FIRST).startToken()
        val target = CardTarget.ZoneResidentToken(owner = GreenColor, tier = TierLevel.FIRST, zoneNumber = 2)

        // ZoP legality is checked before any board mutation is attempted — this exercises the
        // same TargetValidator.validateZoneOfProtection path MovementCardResolver uses, using a
        // ZoneResidentToken directly since MovementCardResolver's own signature only accepts a
        // main-loop CardTarget.Token (see its class doc: moving a token OUT of a Zone isn't
        // supported yet, so there is no code path that could wrongly move a protected token).
        val error = com.tiersofexistence.engine.cards.play.TargetValidator.validateZoneOfProtection(
            cardNamed("Skip, Hop, and Jump (Dimensional)"),
            sourcePlayer = RED,
            target,
            ownTokenMovementAllowed = true,
        )

        assertIs<TargetValidationError.ZoneOfProtectionBlocksTarget>(error)
    }

    @Test
    fun `Evasive Action (Immediate) resolves the same way as a Held movement card`() {
        val board = TierBoard(TierLevel.FOURTH, listOf(Square(0, SquareType.BIRTH_CANAL), plain(1), Square(2, SquareType.YOU_WIN)))
        val state = gameWith(TierLevel.FOURTH, board)
        state.players.getValue(RED).tierPool(TierLevel.FOURTH).startToken()
        val target = CardTarget.Token(RED, TokenKind.TIER_TOKEN, TierLevel.FOURTH, position = 0)

        val result = MovementCardResolver.resolve(state, requestFor(RED, "Evasive Action"), target, spaces = 2)

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(RED, state.winner) // exact landing on You Win via a card-driven move
    }

    @Test
    fun `a movement card moving a Marauder destroys tokens it passes but not one it lands on`() {
        val board = TierBoard(TierLevel.FIRST, listOf(Square(0, SquareType.BIRTH_CANAL), plain(1), plain(2)))
        val state = gameWith(TierLevel.FIRST, board)
        state.players.getValue(GreenColor).marauders.placeOnBirthCanal(TierLevel.FIRST)
        state.players.getValue(WHITE).tierPool(TierLevel.FIRST).startToken()
        state.players.getValue(WHITE).tierPool(TierLevel.FIRST).moveInPlay(0, 1) // strictly between 0 and 2: passed, not landed on
        val target = CardTarget.Token(GreenColor, TokenKind.MARAUDER, TierLevel.FIRST, position = 0)

        val result = MovementCardResolver.resolve(state, requestFor(RED, "Tactical Motion"), target, spaces = 2)

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(0, state.players.getValue(WHITE).tierPool(TierLevel.FIRST).inPlayCount) // destroyed by the pass
        assertEquals(listOf(2), state.players.getValue(GreenColor).marauders.positions(TierLevel.FIRST))
    }

    @Test
    fun `landing a moved Marauder directly on a token does not destroy it`() {
        val board = TierBoard(TierLevel.FIRST, listOf(Square(0, SquareType.BIRTH_CANAL), plain(1)))
        val state = gameWith(TierLevel.FIRST, board)
        state.players.getValue(GreenColor).marauders.placeOnBirthCanal(TierLevel.FIRST)
        state.players.getValue(WHITE).tierPool(TierLevel.FIRST).startToken()
        state.players.getValue(WHITE).tierPool(TierLevel.FIRST).moveInPlay(0, 1) // exactly where the Marauder will land
        val target = CardTarget.Token(GreenColor, TokenKind.MARAUDER, TierLevel.FIRST, position = 0)

        val result = MovementCardResolver.resolve(state, requestFor(RED, "Sidestep (Extinction Avoidance)"), target, spaces = 1)

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(1, state.players.getValue(WHITE).tierPool(TierLevel.FIRST).inPlayCount) // landed on, not destroyed
    }

    // --- MarauderConstructionCardResolver ---

    @Test
    fun `Dwarf Star places a White Marauder on the 4th Tier, bypassing the per-Tier cap`() {
        val state = GameState.newGame(listOf(WHITE, RED))
        state.players.getValue(WHITE).marauders.placeOnBirthCanal(TierLevel.FOURTH) // already at the normal cap of 1

        val result = MarauderConstructionCardResolver.resolve(state, requestFor(WHITE, "Dwarf Star"), WHITE, TierLevel.FOURTH)

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(2, state.players.getValue(WHITE).marauders.inPlayCount(TierLevel.FOURTH))
    }

    @Test
    fun `Dwarf Star played by a non-White player is rejected and never places a Marauder`() {
        val state = GameState.newGame(listOf(WHITE, RED))

        val result = MarauderConstructionCardResolver.resolve(state, requestFor(RED, "Dwarf Star"), RED, TierLevel.FOURTH)

        assertIs<CardPlayResult.Rejected>(result)
        assertIs<TargetValidationError.WrongColor>((result as CardPlayResult.Rejected).reason)
        assertEquals(0, state.players.getValue(RED).marauders.inPlayCount(TierLevel.FOURTH))
        assertEquals(0, state.deck.discardPileSize)
    }

    @Test
    fun `Materialize Help places a Marauder specifically on the 3rd Tier`() {
        val state = GameState.newGame(listOf(RED))

        val result = MarauderConstructionCardResolver.resolve(state, requestFor(RED, "Materialize Help"), RED, TierLevel.THIRD)

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(1, state.players.getValue(RED).marauders.inPlayCount(TierLevel.THIRD))
    }

    // --- BirthCanalConstructionCardResolver ---

    @Test
    fun `Verdant Growth starts a new token on the 1st, 2nd, and 3rd Tiers at once`() {
        val state = GameState.newGame(listOf(GreenColor))
        // newGame already started a 1st Tier token; capture the baseline before playing the card.
        val before1st = state.players.getValue(GreenColor).tierPool(TierLevel.FIRST).inPlayCount

        val result = BirthCanalConstructionCardResolver.resolve(
            state,
            requestFor(GreenColor, "Verdant Growth"),
            GreenColor,
            listOf(TierLevel.FIRST, TierLevel.SECOND, TierLevel.THIRD),
        )

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(before1st + 1, state.players.getValue(GreenColor).tierPool(TierLevel.FIRST).inPlayCount)
        assertEquals(1, state.players.getValue(GreenColor).tierPool(TierLevel.SECOND).inPlayCount)
        assertEquals(1, state.players.getValue(GreenColor).tierPool(TierLevel.THIRD).inPlayCount)
    }

    @Test
    fun `Verdant Growth played by a non-Green player is rejected and starts no tokens`() {
        val state = GameState.newGame(listOf(RED))

        val result = BirthCanalConstructionCardResolver.resolve(
            state,
            requestFor(RED, "Verdant Growth"),
            RED,
            listOf(TierLevel.FIRST, TierLevel.SECOND, TierLevel.THIRD),
        )

        assertIs<CardPlayResult.Rejected>(result)
        assertEquals(0, state.players.getValue(RED).tierPool(TierLevel.SECOND).inPlayCount)
    }

    // --- StagingPileConstructionCardResolver ---

    @Test
    fun `Lucky Nebula adds directly to the 1st Tier Staging Pile without a Nebula landing`() {
        val state = GameState.newGame(listOf(RED))

        val result = StagingPileConstructionCardResolver.resolve(state, requestFor(RED, "Lucky Nebula"), RED, TierLevel.FIRST)

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(1, state.players.getValue(RED).tierPool(TierLevel.FIRST).stagingPile)
    }

    @Test
    fun `Luckier Nebula's direct Staging Pile add promotes once it reaches the 2nd Tier's threshold`() {
        val state = GameState.newGame(listOf(RED))
        val pool = state.players.getValue(RED).tierPool(TierLevel.SECOND)
        pool.startToken()
        pool.sendToStagingPile(0) // 1 of 3
        pool.startToken()
        pool.sendToStagingPile(0) // 2 of 3

        val result = StagingPileConstructionCardResolver.resolve(state, requestFor(RED, "Luckier Nebula"), RED, TierLevel.SECOND)

        assertIs<CardPlayResult.Resolved>(result)
        assertEquals(0, pool.stagingPile) // promoted
        assertEquals(1, state.players.getValue(RED).tierPool(TierLevel.THIRD).inPlayCount)
    }
}
