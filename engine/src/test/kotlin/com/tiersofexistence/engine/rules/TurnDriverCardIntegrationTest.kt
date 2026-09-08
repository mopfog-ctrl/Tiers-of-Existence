package com.tiersofexistence.engine.rules

import com.tiersofexistence.engine.board.BoardLayouts
import com.tiersofexistence.engine.board.Square
import com.tiersofexistence.engine.board.SquareType
import com.tiersofexistence.engine.board.TierBoard
import com.tiersofexistence.engine.cards.FateHarvestCatalog
import com.tiersofexistence.engine.cards.FateHarvestDeck
import com.tiersofexistence.engine.cards.play.CardTarget
import com.tiersofexistence.engine.cards.play.TokenLocation
import com.tiersofexistence.engine.cards.play.TokenLocator
import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.PlayerColor.GREEN
import com.tiersofexistence.engine.model.PlayerColor.RED
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.state.GameState
import com.tiersofexistence.engine.state.PlayerState
import com.tiersofexistence.engine.state.TokenId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * End-to-end proof that Fate Harvest card play (Immediate, Held, Delayed Motion, Precedence) is
 * actually wired into [TurnDriver.driveOneTurn] — not just independently reachable via direct
 * resolver/[com.tiersofexistence.engine.cards.resolvers.CardEffectDispatcher] calls, the way
 * `PrecedenceCardEffectIntegrationTest` and the various `*ResolverTest`s already prove the
 * underlying pieces work. Uses [FateHarvestDeck.forTesting] for a deterministic next draw instead
 * of the default shuffled 70-card deck.
 */
class TurnDriverCardIntegrationTest {

    private fun cardNamed(name: String) = FateHarvestCatalog.all.single { it.name == name }

    private fun plain(index: Int) = Square(index, SquareType.PLAIN)

    private fun boardOf(tier: TierLevel, vararg squares: Square): TierBoard = TierBoard(tier, squares.toList())

    private fun gameWith(
        tier: TierLevel,
        board: TierBoard,
        colors: List<PlayerColor> = listOf(RED, GREEN),
        deck: FateHarvestDeck = FateHarvestDeck.forTesting(emptyList()),
    ): GameState {
        val players = colors.associateWith { PlayerState(it) }
        return GameState(players, TurnOrder(colors), boards = BoardLayouts.current() + (tier to board), deck = deck)
    }

    /** Overrides only what each test needs; every other decision declines, via the interface's
     * own defaults. */
    private class ScriptedDecisions(
        val tokenChoice: (List<TokenId>) -> TokenId = { it.first() },
        val heldCardBeforeRoll: (GameState, PlayerColor) -> CardChoice? = { _, _ -> null },
        val cardAfterRollBeforeMove: (GameState, PlayerColor, Int) -> CardChoice? = { _, _, _ -> null },
        val immediateTargets: (GameState, PlayerColor, com.tiersofexistence.engine.cards.FateHarvestCard) -> List<CardTarget> = { _, _, _ -> emptyList() },
        val precedenceResponse: (GameState, PlayerColor, com.tiersofexistence.engine.rules.precedence.InteractionChain) -> CardChoice? = { _, _, _ -> null },
    ) : TurnDecisionProvider {
        override fun chooseTokenToMove(state: GameState, player: PlayerColor, candidates: List<TokenId>) = tokenChoice(candidates)
        override fun chooseEnterZone(state: GameState, player: PlayerColor, tier: TierLevel, zoneNumber: Int) = false
        override fun chooseBuildMarauder(state: GameState, player: PlayerColor, tier: TierLevel) = false
        override fun chooseTransport(state: GameState, player: PlayerColor, fromTier: TierLevel, options: List<TierLevel>): TierLevel? = null
        override fun chooseHeldCardBeforeRoll(state: GameState, player: PlayerColor) = heldCardBeforeRoll(state, player)
        override fun chooseCardAfterRollBeforeMove(state: GameState, player: PlayerColor, roll: Int) = cardAfterRollBeforeMove(state, player, roll)
        override fun chooseImmediateCardTargets(state: GameState, player: PlayerColor, card: com.tiersofexistence.engine.cards.FateHarvestCard) =
            immediateTargets(state, player, card)
        override fun choosePrecedenceResponse(state: GameState, player: PlayerColor, chain: com.tiersofexistence.engine.rules.precedence.InteractionChain) =
            precedenceResponse(state, player, chain)
    }

    @Test
    fun `an Immediate card drawn mid-turn is resolved right then through the real turn-driving path`() {
        val board = boardOf(TierLevel.FIRST, Square(0, SquareType.BIRTH_CANAL), Square(1, SquareType.FATE_HARVEST))
        val divineAssistance = cardNamed("Divine Assistance") // Immediate, needs one token target
        val state = gameWith(TierLevel.FIRST, board, deck = FateHarvestDeck.forTesting(listOf(divineAssistance)))
        state.players.getValue(RED).tierPool(TierLevel.FIRST).startToken()
        val greenId = state.players.getValue(GREEN).tierPool(TierLevel.FIRST).startToken()!!
        state.skipEmptyPhases()
        val driver = TurnDriver(ScriptedDecisions(immediateTargets = { _, _, _ -> listOf(CardTarget.Token(greenId)) }), rollForPhase = { 1 })

        driver.driveOneTurn(state)

        // Divine Assistance destroyed GREEN's targeted token — applied immediately, not queued.
        assertIs<TokenLocation.NoLongerExists>(TokenLocator.locate(state, greenId))
        assertEquals(0, state.deck.drawPileSize) // the card was drawn
        assertEquals(1, state.deck.discardPileSize) // and resolved+discarded, never held
        assertTrue(state.players.getValue(RED).hand.isEmpty()) // Immediate cards never enter a hand
    }

    @Test
    fun `an Immediate card with no legal target is discarded, not lost and not crashing`() {
        val board = boardOf(TierLevel.FIRST, Square(0, SquareType.BIRTH_CANAL), Square(1, SquareType.FATE_HARVEST))
        val divineAssistance = cardNamed("Divine Assistance")
        val state = gameWith(TierLevel.FIRST, board, colors = listOf(RED), deck = FateHarvestDeck.forTesting(listOf(divineAssistance)))
        state.players.getValue(RED).tierPool(TierLevel.FIRST).startToken()
        state.skipEmptyPhases()
        // No target supplied (the default) — the mandatory play has nowhere to land.
        val driver = TurnDriver(ScriptedDecisions(), rollForPhase = { 1 })

        driver.driveOneTurn(state) // must not throw

        assertEquals(0, state.deck.drawPileSize)
        assertEquals(1, state.deck.discardPileSize) // discarded rather than vanishing
    }

    @Test
    fun `a Held card played from hand before rolling actually applies through the real turn-driving path`() {
        val board = boardOf(TierLevel.FIRST, Square(0, SquareType.BIRTH_CANAL), plain(1), plain(2), plain(3), plain(4), plain(5))
        val state = gameWith(TierLevel.FIRST, board, colors = listOf(RED))
        val redId = state.players.getValue(RED).tierPool(TierLevel.FIRST).startToken()!!
        val tacticalMotion = cardNamed("Tactical Motion") // Held, moves any token 2 spaces
        state.players.getValue(RED).hand += tacticalMotion
        state.skipEmptyPhases()
        val decisions = ScriptedDecisions(
            heldCardBeforeRoll = { _, _ -> CardChoice(tacticalMotion, listOf(CardTarget.Token(redId))) },
        )
        val driver = TurnDriver(decisions, rollForPhase = { 3 })

        driver.driveOneTurn(state)

        // Tactical Motion moves 0 -> 2 before the roll; the roll then moves 2 -> 5.
        assertEquals(listOf(5), state.players.getValue(RED).tierPool(TierLevel.FIRST).inPlayPositions)
        assertTrue(state.players.getValue(RED).hand.isEmpty()) // played and discarded, not stuck in hand
    }

    @Test
    fun `an illegal Held card choice is rejected and returned to hand, never silently lost`() {
        val board = boardOf(TierLevel.FIRST, Square(0, SquareType.BIRTH_CANAL), plain(1))
        val state = gameWith(TierLevel.FIRST, board, colors = listOf(RED, GREEN))
        state.players.getValue(RED).tierPool(TierLevel.FIRST).startToken()
        val greenZoneToken = state.players.getValue(GREEN).tierPool(TierLevel.FIRST).startToken()!!
        // Move GREEN's token into a Zone of Protection so Tactical Motion (no ZoP exception,
        // and no own-token carve-out since it isn't RED's token) can never legally target it.
        state.players.getValue(GREEN).tierPool(TierLevel.FIRST).moveInPlay(0, 10)
        state.players.getValue(GREEN).tierPool(TierLevel.FIRST).enterZone(fromPosition = 10, zoneNumber = 2)
        val tacticalMotion = cardNamed("Tactical Motion")
        state.players.getValue(RED).hand += tacticalMotion
        state.skipEmptyPhases()
        val decisions = ScriptedDecisions(
            heldCardBeforeRoll = { _, _ -> CardChoice(tacticalMotion, listOf(CardTarget.Token(greenZoneToken))) },
        )
        val driver = TurnDriver(decisions, rollForPhase = { 1 })

        driver.driveOneTurn(state)

        // The illegal play is rejected before ever being discarded — the card stays in RED's hand.
        assertEquals(listOf(tacticalMotion), state.players.getValue(RED).hand)
        assertEquals(listOf(2), state.players.getValue(GREEN).tierPool(TierLevel.FIRST).zoneResidents) // untouched
    }

    @Test
    fun `Delayed Motion adds its bonus to the roll actually used to move the token`() {
        val board = boardOf(TierLevel.FIRST, Square(0, SquareType.BIRTH_CANAL), plain(1), plain(2), plain(3), plain(4), plain(5))
        val state = gameWith(TierLevel.FIRST, board, colors = listOf(RED))
        state.players.getValue(RED).tierPool(TierLevel.FIRST).startToken()
        val delayedMotion = cardNamed("Delayed Motion") // Held, self-only, "+2 to your die roll"
        state.players.getValue(RED).hand += delayedMotion
        state.skipEmptyPhases()
        val decisions = ScriptedDecisions(cardAfterRollBeforeMove = { _, _, _ -> CardChoice(delayedMotion) })
        val driver = TurnDriver(decisions, rollForPhase = { 2 })

        driver.driveOneTurn(state)

        // Rolled 2, +2 from Delayed Motion = 4.
        assertEquals(listOf(4), state.players.getValue(RED).tierPool(TierLevel.FIRST).inPlayPositions)
        assertTrue(state.players.getValue(RED).hand.isEmpty())
    }

    @Test
    fun `rule 23 worked example through the real turn-driving path - a Precedence card rescues a token before a pending move resolves`() {
        // Mirrors PrecedenceCardEffectIntegrationTest's manually-wired version of this exact
        // scenario, but drives it through TurnDriver.driveOneTurn end to end instead.
        //
        // RED's Marauder must exist BEFORE GameState is constructed — GameState.init builds the
        // turn queue once from whatever's already in each PlayerState's pools, and skipEmptyPhases
        // only ever advances past an already-built empty queue, it doesn't retroactively rebuild
        // the current Phase's queue if eligibility changes afterward.
        val board = TierBoard(TierLevel.FIRST, listOf(Square(0, SquareType.BIRTH_CANAL), plain(1), plain(2)))
        val redState = PlayerState(RED)
        redState.marauders.placeOnBirthCanal(TierLevel.FIRST)
        val greenState = PlayerState(GREEN)
        val greenId = greenState.tierPool(TierLevel.FIRST).startToken()!!
        greenState.tierPool(TierLevel.FIRST).moveInPlay(0, 1) // in the Marauder's 2-space path
        val tacticalStep = cardNamed("Tactical Step") // Precedence, Held, +1
        greenState.hand += tacticalStep
        val state = GameState(mapOf(RED to redState, GREEN to greenState), TurnOrder(listOf(RED, GREEN)), boards = BoardLayouts.current() + (TierLevel.FIRST to board))
        assertEquals(Phase.Marauder, state.currentPhase)
        assertEquals(RED, state.currentTurn)

        var greenResponded = false
        val redDecisions = ScriptedDecisions()
        val greenDecisions = ScriptedDecisions(
            precedenceResponse = { _, _, _ ->
                if (greenResponded) {
                    null
                } else {
                    greenResponded = true
                    CardChoice(tacticalStep, listOf(CardTarget.Token(greenId)))
                }
            },
        )
        val driver = TurnDriver(mapOf(RED to redDecisions, GREEN to greenDecisions), rollForPhase = { 2 })

        driver.driveOneTurn(state)

        // GREEN's token was moved to safety (1 -> 2, the Marauder's own landing square, not its
        // path) before the Marauder's move resolved, exactly like the manually-wired version of
        // this test proves — landing on an occupied square never destroys the occupant.
        assertEquals(listOf(2), state.players.getValue(GREEN).tierPool(TierLevel.FIRST).inPlayPositions)
        assertTrue(state.players.getValue(GREEN).hand.isEmpty()) // Tactical Step was played, not lost
        assertEquals(2, state.players.getValue(RED).marauders.positionOf(state.players.getValue(RED).marauders.inPlayIds(TierLevel.FIRST).single()))
    }
}
