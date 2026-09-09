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
import com.tiersofexistence.engine.model.PlayerColor.BLACK
import com.tiersofexistence.engine.model.PlayerColor.GREEN
import com.tiersofexistence.engine.model.PlayerColor.RED
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.state.GameState
import com.tiersofexistence.engine.state.PlayerState
import com.tiersofexistence.engine.state.TokenId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        val cleansingDiscard: (GameState, PlayerColor, PlayerColor, List<com.tiersofexistence.engine.cards.FateHarvestCard>) -> com.tiersofexistence.engine.cards.FateHarvestCard =
            { _, _, _, eligible -> eligible.first() },
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
        override fun chooseCleansingDiscard(
            state: GameState,
            decidingPlayer: PlayerColor,
            sourcePlayer: PlayerColor,
            eligibleCards: List<com.tiersofexistence.engine.cards.FateHarvestCard>,
        ) = cleansingDiscard(state, decidingPlayer, sourcePlayer, eligibleCards)
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

    @Test
    fun `Cleansing lets the targeted opponent, not the source player, choose which of their own cards to discard`() {
        // Covers: source player legally plays Cleansing; an opponent with cards can be targeted;
        // the targeted opponent (not the source player) receives the discard choice; the
        // selected card is discarded; the non-selected card remains; the source player's own
        // provider is never consulted for the victim's private choice.
        val board = boardOf(TierLevel.FIRST, Square(0, SquareType.BIRTH_CANAL), plain(1))
        val state = gameWith(TierLevel.FIRST, board, colors = listOf(RED, GREEN))
        state.players.getValue(RED).tierPool(TierLevel.FIRST).startToken()
        val cleansing = cardNamed("Cleansing (Atmospheric)")
        state.players.getValue(RED).hand += cleansing
        val keep = cardNamed("Tactical Step")
        val discard = cardNamed("Tactical Motion")
        state.players.getValue(GREEN).hand += listOf(keep, discard)
        state.skipEmptyPhases()

        val redDecisions = ScriptedDecisions(
            heldCardBeforeRoll = { _, _ -> CardChoice(cleansing, listOf(CardTarget.PlayerChoice(GREEN))) },
            // RED must never be asked to choose GREEN's discard — the victim's own provider
            // handles that. If TurnDriver ever routed the decision to the wrong player, this
            // would throw instead of silently passing.
            cleansingDiscard = { _, _, _, _ -> error("the source player's provider must never be asked to choose the victim's discard") },
        )
        val greenDecisions = ScriptedDecisions(
            cleansingDiscard = { _, decidingPlayer, sourcePlayer, eligibleCards ->
                assertEquals(GREEN, decidingPlayer) // the targeted opponent decides...
                assertEquals(RED, sourcePlayer) // ...about the play RED made
                assertEquals(listOf(keep, discard), eligibleCards) // exactly GREEN's own hand
                discard
            },
        )
        val driver = TurnDriver(mapOf(RED to redDecisions, GREEN to greenDecisions), rollForPhase = { 1 })

        driver.driveOneTurn(state)

        assertEquals(listOf(keep), state.players.getValue(GREEN).hand) // discard is gone, keep remains
        assertTrue(state.players.getValue(RED).hand.isEmpty()) // Cleansing itself was played and discarded
        assertEquals(2, state.deck.discardPileSize) // Cleansing + the discarded card
    }

    @Test
    fun `Cleansing against an empty-handed opponent is illegal and never consumes the card or the Phase allowance`() {
        val board = boardOf(TierLevel.FIRST, Square(0, SquareType.BIRTH_CANAL), plain(1))
        val state = gameWith(TierLevel.FIRST, board, colors = listOf(RED, GREEN))
        state.players.getValue(RED).tierPool(TierLevel.FIRST).startToken()
        val cleansing = cardNamed("Cleansing (Atmospheric)")
        state.players.getValue(RED).hand += cleansing
        assertTrue(state.players.getValue(GREEN).hand.isEmpty())
        state.skipEmptyPhases()
        val decisions = ScriptedDecisions(
            heldCardBeforeRoll = { _, _ -> CardChoice(cleansing, listOf(CardTarget.PlayerChoice(GREEN))) },
        )
        val driver = TurnDriver(decisions, rollForPhase = { 1 })

        driver.driveOneTurn(state)

        assertEquals(listOf(cleansing), state.players.getValue(RED).hand) // rejected, not consumed
        assertEquals(0, state.deck.discardPileSize) // never discarded
        assertEquals(false, state.players.getValue(RED).hasPlayedCardThisPhase) // Phase allowance untouched
    }

    // --- Confirmed canon (per the user): a played card is expended once played — Precedence
    // response cards are never returned to hand regardless of how they resolve, and a card
    // Annulment cancels is discarded, not returned, since Annulment nullifies the effect, not
    // the historical fact that the card was played. ---

    @Test
    fun `a Precedence response that rejects at resolution time (stale target) is discarded, not returned to the responder's hand`() {
        val board = boardOf(TierLevel.FIRST, Square(0, SquareType.BIRTH_CANAL), plain(1), plain(2))
        val state = gameWith(TierLevel.FIRST, board, colors = listOf(RED, GREEN, BLACK))
        val redId = state.players.getValue(RED).tierPool(TierLevel.FIRST).startToken()!!
        val greenId = state.players.getValue(GREEN).tierPool(TierLevel.FIRST).startToken()!!
        val tacticalMotion = cardNamed("Tactical Motion") // Held, not Precedence — RED's own play
        state.players.getValue(RED).hand += tacticalMotion
        val tacticalStep = cardNamed("Tactical Step") // Precedence — GREEN's response, targets GREEN's own token
        state.players.getValue(GREEN).hand += tacticalStep
        val gravitonRift = cardNamed("Graviton Rift") // Precedence — BLACK's response, destroys GREEN's token
        state.players.getValue(BLACK).hand += gravitonRift
        state.skipEmptyPhases()

        var greenResponded = false
        var blackResponded = false
        val redDecisions = ScriptedDecisions(
            heldCardBeforeRoll = { _, _ -> CardChoice(tacticalMotion, listOf(CardTarget.Token(redId))) },
        )
        val greenDecisions = ScriptedDecisions(
            precedenceResponse = { _, _, _ ->
                if (greenResponded) null else { greenResponded = true; CardChoice(tacticalStep, listOf(CardTarget.Token(greenId))) }
            },
        )
        val blackDecisions = ScriptedDecisions(
            precedenceResponse = { _, _, _ ->
                if (blackResponded) null else { blackResponded = true; CardChoice(gravitonRift, listOf(CardTarget.Token(greenId))) }
            },
        )
        val driver = TurnDriver(mapOf(RED to redDecisions, GREEN to greenDecisions, BLACK to blackDecisions), rollForPhase = { 1 })

        driver.driveOneTurn(state)

        // GREEN responded first (resolves LAST, after BLACK's Graviton Rift already destroyed
        // the target) — GREEN's Tactical Step rejects at resolution time with a stale target,
        // per TokenLocator. Confirmed canon: it does NOT return to GREEN's hand even though the
        // play was rejected — a played response is spent regardless of how it resolves. (The
        // original greenId is gone for good even though the 1st Tier auto-replenishes a fresh,
        // unrelated token afterward — see TokenLocator, not a raw inPlayCount check.)
        assertTrue(state.players.getValue(GREEN).hand.isEmpty())
        assertTrue(state.players.getValue(BLACK).hand.isEmpty())
        assertIs<TokenLocation.NoLongerExists>(TokenLocator.locate(state, greenId))
    }

    @Test
    fun `Annulment cancelling a top-level Held-card play discards that card rather than returning it to hand`() {
        val board = boardOf(TierLevel.FIRST, Square(0, SquareType.BIRTH_CANAL), plain(1))
        val state = gameWith(TierLevel.FIRST, board, colors = listOf(RED, GREEN))
        val redId = state.players.getValue(RED).tierPool(TierLevel.FIRST).startToken()!!
        val tacticalMotion = cardNamed("Tactical Motion") // Held, not Precedence — RED's own play
        state.players.getValue(RED).hand += tacticalMotion
        val annulment = cardNamed("Annulment (Antimatter)") // Precedence — GREEN cancels RED's play outright
        state.players.getValue(GREEN).hand += annulment
        state.skipEmptyPhases()

        var greenResponded = false
        val redDecisions = ScriptedDecisions(
            heldCardBeforeRoll = { _, _ -> CardChoice(tacticalMotion, listOf(CardTarget.Token(redId))) },
        )
        val greenDecisions = ScriptedDecisions(
            precedenceResponse = { _, _, _ ->
                if (greenResponded) null else { greenResponded = true; CardChoice(annulment) }
            },
        )
        val driver = TurnDriver(mapOf(RED to redDecisions, GREEN to greenDecisions), rollForPhase = { 1 })

        driver.driveOneTurn(state)

        // RED's Tactical Motion was legitimately played (removed from hand, targeting/legality
        // already passed) and then Annulled before its own +2-space effect applied — driveOneTurn
        // still performs RED's ordinary roll-based move afterward regardless (roll = 1), so the
        // token ends at 1, not at the 3 it would reach if Tactical Motion's own move had also
        // applied (0 -> 2 from the card, then -> 3 from the roll). Per confirmed canon, RED's
        // card does NOT return to hand even though it never took effect: Annulment nullifies the
        // effect, not the historical fact that RED played it.
        assertEquals(listOf(1), state.players.getValue(RED).tierPool(TierLevel.FIRST).inPlayPositions)
        assertTrue(state.players.getValue(RED).hand.isEmpty()) // not returned
        assertTrue(state.players.getValue(GREEN).hand.isEmpty()) // Annulment itself also spent
    }

    // --- Confirmed by construction (per the user's own request to verify/check): a skipped
    // player gets no turn on that Tier occurrence at all, so never gets a card-play window there
    // either; a player granted a genuine extra turn on that Tier gets a fully independent
    // card-play window on it, same as any other turn. ---

    @Test
    fun `a player skipped via pendingSkips is never offered a card-play decision for that occurrence`() {
        val board = boardOf(TierLevel.FIRST, Square(0, SquareType.BIRTH_CANAL), plain(1), plain(2), plain(3))
        val state = gameWith(TierLevel.FIRST, board, colors = listOf(RED, GREEN))
        state.players.getValue(RED).tierPool(TierLevel.FIRST).startToken()
        state.players.getValue(GREEN).tierPool(TierLevel.FIRST).startToken()
        state.skipEmptyPhases()
        assertEquals(RED, state.currentTurn) // Round 1: nobody's skipped yet

        // Queue RED's skip now — too late to affect the in-progress Round 1 queue (already
        // built), so it applies starting Round 2's 1st Tier Phase, exactly like
        // GameState.queueSkipNextTierTurn's own contract.
        state.queueSkipNextTierTurn(RED, TierLevel.FIRST)

        var redAsked = false
        val redDecisions = ScriptedDecisions(heldCardBeforeRoll = { _, _ -> redAsked = true; null })
        val greenDecisions = ScriptedDecisions()
        val driver = TurnDriver(mapOf(RED to redDecisions, GREEN to greenDecisions), rollForPhase = { 1 })

        driver.driveOneTurn(state) // RED's ordinary Round 1 turn — the flag mechanism itself works
        assertTrue(redAsked) // positive control: RED WAS asked on a turn RED actually got
        redAsked = false

        driver.driveOneTurn(state) // GREEN's Round 1 turn — ends Round 1, builds Round 2's queue,
        // where RED's skip debt is consumed by buildTurnQueue filtering RED out entirely.
        assertEquals(GREEN, state.currentTurn) // RED was filtered out of Round 2's 1st Tier queue
        assertFalse(redAsked) // RED's decision provider was never invoked for the skipped occurrence

        driver.driveOneTurn(state) // GREEN's Round 2 turn
        assertFalse(redAsked) // still never asked
        assertEquals(RED, state.currentTurn) // Round 3: RED is eligible again, debt fully consumed
    }

    @Test
    fun `a player granted an extra turn on the same Tier gets a fully independent card-play window on it`() {
        val board = boardOf(TierLevel.FIRST, Square(0, SquareType.BIRTH_CANAL), plain(1), plain(2), plain(3), plain(4), plain(5))
        val state = gameWith(TierLevel.FIRST, board, colors = listOf(RED))
        val redId = state.players.getValue(RED).tierPool(TierLevel.FIRST).startToken()!!
        val tacticalMotion = cardNamed("Tactical Motion") // Held, moves any token 2 spaces
        state.players.getValue(RED).hand += tacticalMotion
        state.skipEmptyPhases()
        assertEquals(Phase.Tier(TierLevel.FIRST), state.currentPhase)

        // Splice a genuine extra turn for RED onto this same Tier's live queue — RED's normal
        // turn hasn't happened yet this Round, so this attaches right after it, per
        // GameState.queueExtraTierTurn's own contract.
        state.queueExtraTierTurn(RED, TierLevel.FIRST)

        var askedCount = 0
        val decisions = ScriptedDecisions(
            heldCardBeforeRoll = { _, _ ->
                askedCount += 1
                // Only actually playable the 2nd time (the extra turn) — proves it's a fresh,
                // independent offer, not the same one somehow re-fired or skipped.
                if (askedCount == 2) CardChoice(tacticalMotion, listOf(CardTarget.Token(redId))) else null
            },
        )
        val driver = TurnDriver(decisions, rollForPhase = { 1 })

        driver.driveOneTurn(state) // RED's normal turn — declines, rolls 1: 0 -> 1
        assertEquals(1, askedCount)
        assertEquals(RED, state.currentTurn) // the queued extra turn keeps RED active

        driver.driveOneTurn(state) // RED's genuinely separate extra turn on the same Tier
        assertEquals(2, askedCount) // a second, independent card-play window was actually offered
        // Tactical Motion (+2) applied before this turn's own roll (+1): 1 -> 3 -> 4.
        assertEquals(listOf(4), state.players.getValue(RED).tierPool(TierLevel.FIRST).inPlayPositions)
        assertTrue(state.players.getValue(RED).hand.isEmpty())
    }
}
