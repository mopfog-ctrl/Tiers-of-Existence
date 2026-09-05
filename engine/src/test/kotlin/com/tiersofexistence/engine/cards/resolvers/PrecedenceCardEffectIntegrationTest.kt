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
import com.tiersofexistence.engine.rules.TurnEngine
import com.tiersofexistence.engine.rules.TurnOrder
import com.tiersofexistence.engine.rules.precedence.InteractionChain
import com.tiersofexistence.engine.rules.precedence.SuspendedAction
import com.tiersofexistence.engine.state.GameState
import com.tiersofexistence.engine.state.PlayerState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * End-to-end proof that the Precedence interaction engine (Phase C) and the card resolvers
 * (Phase J) actually work together, not just independently: [InteractionChain] sequences the
 * response, [CardEffectDispatcher] applies what survives it to real [GameState] — and, since the
 * stale-target-by-position bug fix, does so by re-locating each [CardTarget.Token] via
 * [com.tiersofexistence.engine.cards.play.TokenLocator] at the moment it actually resolves.
 */
class PrecedenceCardEffectIntegrationTest {

    private fun cardNamed(name: String) = FateHarvestCatalog.all.single { it.name == name }

    private fun plain(index: Int) = Square(index, SquareType.PLAIN)

    private fun gameWith(tier: TierLevel, board: TierBoard, colors: List<PlayerColor> = listOf(RED, GREEN)): GameState {
        val players = colors.associateWith { PlayerState(it) }
        return GameState(players, TurnOrder(colors), boards = BoardLayouts.current() + (tier to board))
    }

    @Test
    fun `rule 23 worked example - a Precedence movement card rescues a token before a pending Marauder move resolves`() {
        // "A player rolls the purple die to move their Marauder token. You play a movement card
        // that has Precedence... you can move a Tier token to safety that would have otherwise
        // been destroyed by the Marauder." (rulebook.txt:477-484)
        val board = TierBoard(TierLevel.FIRST, listOf(Square(0, SquareType.BIRTH_CANAL), plain(1), plain(2)))
        val state = gameWith(TierLevel.FIRST, board)
        state.players.getValue(RED).marauders.placeOnBirthCanal(TierLevel.FIRST)
        val greenId = state.players.getValue(GREEN).tierPool(TierLevel.FIRST).startToken()
        state.players.getValue(GREEN).tierPool(TierLevel.FIRST).moveInPlay(0, 1) // sits in the Marauder's 2-space path (0 -> 2 passes 1)

        // RED has rolled to move their Marauder 2 spaces — that move is now pending.
        val chain = InteractionChain.open(SuspendedAction.PendingMove(RED), eligiblePlayers = listOf(RED, GREEN))

        // GREEN plays Tactical Step (Precedence, +1) on their own endangered token before the move resolves.
        val rescue = CardPlayRequest(GREEN, cardNamed("Tactical Step"), listOf(CardTarget.Token(greenId)), TriggeringEvent.RespondingInChain(chain.id))
        chain.respond(GREEN, rescue)
        chain.pass(RED)
        chain.pass(GREEN)

        val order = chain.resolve()
        CardEffectDispatcher.dispatchAll(state, order)
        chain.finishResolving()

        // GREEN's token moved from 1 to 2 — the Marauder's own landing square, not its path.
        assertEquals(listOf(2), state.players.getValue(GREEN).tierPool(TierLevel.FIRST).inPlayPositions)

        // NOW the originally-pending Marauder move resolves, per the suspended action. Landing on
        // an occupied square never destroys the occupant ("If a Marauder lands on a space
        // occupied by another token, that token is not destroyed" — rulebook.txt:321-322).
        val marauderMove = TurnEngine.moveMarauder(state, RED, TierLevel.FIRST, fromPosition = 0, spaces = 2)

        assertTrue(marauderMove.destroyedTokens.isEmpty()) // GREEN's token was already moved to safety
        assertEquals(1, state.players.getValue(GREEN).tierPool(TierLevel.FIRST).inPlayCount)
    }

    @Test
    fun `Annulment played against a pending card resolution cancels it before the caller ever applies it`() {
        val state = GameState.newGame(listOf(RED, GREEN))
        val greenId = state.players.getValue(GREEN).tierPool(TierLevel.FIRST).idAt(0)!!
        val pendingDivineAssistance = CardPlayRequest(RED, cardNamed("Divine Assistance"), listOf(CardTarget.Token(greenId)), TriggeringEvent.PlayedFromHand)

        val chain = InteractionChain.open(SuspendedAction.PendingCardResolution(pendingDivineAssistance), eligiblePlayers = listOf(GREEN))
        val annulment = CardPlayRequest(GREEN, cardNamed("Annulment (Antimatter)"), triggeringEvent = TriggeringEvent.RespondingInChain(chain.id))
        chain.respond(GREEN, annulment)
        chain.pass(GREEN)

        val order = chain.resolve()
        CardEffectDispatcher.dispatchAll(state, order) // empty — Annulment itself never dispatches an effect
        chain.finishResolving()

        assertTrue(chain.isSuspendedActionCancelled)
        // A real caller checks isSuspendedActionCancelled and skips applying pendingDivineAssistance —
        // proving GREEN's token survives specifically because the caller respects that flag.
        assertEquals(1, state.players.getValue(GREEN).tierPool(TierLevel.FIRST).inPlayCount)
    }

    @Test
    fun `multiple Precedence responses on different tokens each resolve correctly in reverse order`() {
        val board = TierBoard(TierLevel.FIRST, listOf(Square(0, SquareType.BIRTH_CANAL), plain(1), plain(2), plain(3), plain(4)))
        val state = gameWith(TierLevel.FIRST, board, colors = listOf(RED, GREEN))
        val redId = state.players.getValue(RED).tierPool(TierLevel.FIRST).startToken()
        val greenId = state.players.getValue(GREEN).tierPool(TierLevel.FIRST).startToken()
        state.players.getValue(GREEN).tierPool(TierLevel.FIRST).moveInPlay(0, 2)

        val chain = InteractionChain.open(SuspendedAction.PendingRoll(RED), eligiblePlayers = listOf(RED, GREEN))
        val step = CardPlayRequest(RED, cardNamed("Tactical Step"), listOf(CardTarget.Token(redId)), TriggeringEvent.RespondingInChain(chain.id))
        chain.respond(RED, step)
        val motion = CardPlayRequest(GREEN, cardNamed("Tactical Motion"), listOf(CardTarget.Token(greenId)), TriggeringEvent.RespondingInChain(chain.id))
        chain.respond(GREEN, motion) // played second, resolves FIRST (reverse order) — independent token, no conflict
        chain.pass(RED)
        chain.pass(GREEN)

        val order = chain.resolve()
        assertEquals(listOf(motion.card.name, step.card.name), order.map { it.request.card.name })
        val results = CardEffectDispatcher.dispatchAll(state, order)
        chain.finishResolving()

        assertTrue(results.all { it is CardPlayResult.Resolved })
        assertEquals(listOf(1), state.players.getValue(RED).tierPool(TierLevel.FIRST).inPlayPositions) // 0 + 1
        assertEquals(listOf(4), state.players.getValue(GREEN).tierPool(TierLevel.FIRST).inPlayPositions) // 2 + 2
    }

    @Test
    fun `two different players targeting the same token both resolve correctly against its current location`() {
        // This is the exact scenario that used to crash before the identity fix: two different
        // players (the per-Phase card limit only blocks a SAME player from double-responding,
        // since every Precedence card is Held) each play a Precedence movement card recording
        // the same token as their target. Before the fix, CardTarget.Token captured a board
        // POSITION at play time; the earlier-resolving response (played second, reverse order)
        // would move the token, and the later-resolving response (played first) would then
        // crash looking for its now-vacated recorded position. Now CardTarget.Token carries only
        // an identity, re-located via TokenLocator at the moment each response actually resolves
        // — so both moves apply, cumulatively, against wherever the token actually is.
        val board = TierBoard(TierLevel.FIRST, listOf(Square(0, SquareType.BIRTH_CANAL), plain(1), plain(2), plain(3)))
        val state = gameWith(TierLevel.FIRST, board, colors = listOf(RED, GREEN))
        val greenId = state.players.getValue(GREEN).tierPool(TierLevel.FIRST).startToken()

        val chain = InteractionChain.open(SuspendedAction.PendingRoll(RED), eligiblePlayers = listOf(RED, GREEN))
        // RED's movement card can target any player's token (rule 11), including GREEN's.
        val step = CardPlayRequest(RED, cardNamed("Tactical Step"), listOf(CardTarget.Token(greenId)), TriggeringEvent.RespondingInChain(chain.id))
        chain.respond(RED, step) // played first, resolves LAST
        val motion = CardPlayRequest(GREEN, cardNamed("Tactical Motion"), listOf(CardTarget.Token(greenId)), TriggeringEvent.RespondingInChain(chain.id))
        chain.respond(GREEN, motion) // played second, resolves FIRST — same token as step
        chain.pass(RED)
        chain.pass(GREEN)

        val order = chain.resolve()
        val results = CardEffectDispatcher.dispatchAll(state, order)
        chain.finishResolving()

        assertTrue(results.all { it is CardPlayResult.Resolved }) // no crash, no spurious rejection
        // motion resolves first: 0 -> 2. step resolves second against the NEW position: 2 -> 3.
        assertEquals(listOf(3), state.players.getValue(GREEN).tierPool(TierLevel.FIRST).inPlayPositions)
    }

    @Test
    fun `Zone-of-Protection validation uses the token's location at resolution time, not when the target was chosen`() {
        // RED targets GREEN's token while it's still on the open loop (legal to choose). Before
        // RED's response resolves, GREEN's OWN earlier-resolving response moves that same token
        // into a Zone of Protection. RED isn't a named rule-12 exception and doesn't get the
        // own-token carve-out, so RED's response must be rejected once it actually resolves —
        // proving the ZoP check is re-evaluated live, not decided when the target was picked.
        val board = TierBoard(TierLevel.FIRST, listOf(Square(0, SquareType.BIRTH_CANAL), plain(1), Square(2, SquareType.ZONE_OF_PROTECTION, magnitude = 1)))
        val state = gameWith(TierLevel.FIRST, board, colors = listOf(RED, GREEN))
        val greenId = state.players.getValue(GREEN).tierPool(TierLevel.FIRST).startToken()

        val chain = InteractionChain.open(SuspendedAction.PendingRoll(RED), eligiblePlayers = listOf(RED, GREEN))
        val redsStep = CardPlayRequest(RED, cardNamed("Tactical Step"), listOf(CardTarget.Token(greenId)), TriggeringEvent.RespondingInChain(chain.id))
        chain.respond(RED, redsStep) // played first, resolves LAST — token not yet in a Zone when chosen
        val greenMovesSelfIntoZone = CardPlayRequest(GREEN, cardNamed("Tactical Motion"), listOf(CardTarget.Token(greenId)), TriggeringEvent.RespondingInChain(chain.id))
        chain.respond(GREEN, greenMovesSelfIntoZone) // played second, resolves FIRST: 0 -> 2, entering the Zone
        chain.pass(RED)
        chain.pass(GREEN)

        val order = chain.resolve()
        val results = CardEffectDispatcher.dispatchAll(state, order)
        chain.finishResolving()

        assertIs<CardPlayResult.Resolved>(results[0]) // GREEN's own move into the Zone succeeds
        val redResult = results[1]
        assertIs<CardPlayResult.Rejected>(redResult)
        assertIs<TargetValidationError.ZoneOfProtectionBlocksTarget>(redResult.reason)
        assertEquals(listOf(1), state.players.getValue(GREEN).tierPool(TierLevel.FIRST).zoneResidents) // untouched by the rejected response
    }

    @Test
    fun `a later-resolving response whose target was destroyed by an earlier one is rejected gracefully`() {
        val state = GameState.newGame(listOf(RED, GREEN, BLACK))
        val greenId = state.players.getValue(GREEN).tierPool(TierLevel.FIRST).idAt(0)!!

        val chain = InteractionChain.open(SuspendedAction.PendingRoll(RED), eligiblePlayers = listOf(RED, GREEN, BLACK))
        val tacticalStep = CardPlayRequest(RED, cardNamed("Tactical Step"), listOf(CardTarget.Token(greenId)), TriggeringEvent.RespondingInChain(chain.id))
        chain.respond(RED, tacticalStep) // played first, resolves LAST — the token still exists when chosen
        val gravitonRift = CardPlayRequest(BLACK, cardNamed("Graviton Rift"), listOf(CardTarget.Token(greenId)), TriggeringEvent.RespondingInChain(chain.id))
        chain.respond(BLACK, gravitonRift) // played second, resolves FIRST: destroys GREEN's token outright
        chain.pass(RED)
        chain.pass(GREEN)
        chain.pass(BLACK)

        val order = chain.resolve()
        val results = CardEffectDispatcher.dispatchAll(state, order)
        chain.finishResolving()

        assertIs<CardPlayResult.Resolved>(results[0]) // Graviton Rift's destroy
        // 1st Tier auto-replenishes from the Ion Battery back up to the 2-in-play cap — but as a
        // freshly minted TokenId, distinct from greenId, which is what actually matters below:
        // Tactical Step's stale target is still gone, not accidentally re-resolved against the
        // replacement token.
        assertEquals(2, state.players.getValue(GREEN).tierPool(TierLevel.FIRST).inPlayCount)
        val stepResult = results[1]
        assertIs<CardPlayResult.Rejected>(stepResult) // no crash — greenId specifically is simply gone by the time this resolves
        assertIs<TargetValidationError.NoLegalTarget>(stepResult.reason)
    }
}
