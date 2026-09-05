package com.tiersofexistence.engine.rules

import com.tiersofexistence.engine.board.Square
import com.tiersofexistence.engine.board.SquareType
import com.tiersofexistence.engine.board.TierBoard
import com.tiersofexistence.engine.cards.CardTiming
import com.tiersofexistence.engine.cards.FateHarvestCard
import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.state.GameState
import com.tiersofexistence.engine.state.TierTokenPool

/** Which kind of token occupies a board position — used when scanning for tokens passed/landed on. */
enum class TokenKind { TIER_TOKEN, MARAUDER }

/** A token found at a specific board position, identified by owner/kind rather than physical identity
 * (tokens are fungible — see [com.tiersofexistence.engine.state.TierTokenPool]). */
data class TokenRef(val color: PlayerColor, val kind: TokenKind, val position: Int)

/** What happened as a result of landing on a square, beyond the plain position update. */
sealed class SquareEffect {
    /** Nothing beyond moving there — includes squares whose effect isn't implemented yet (see
     * [TurnEngine]'s class doc for what's deferred: Warp, Zone of Protection entry, most
     * Time Wrinkle variants). */
    data object None : SquareEffect()
    data class SentToStagingPile(val promotedToNextTier: Boolean) : SquareEffect()
    data object SentToStart : SquareEffect()
    data class Promoted(val toTier: TierLevel) : SquareEffect()
    data object Won : SquareEffect()
    data class DrewCard(val card: FateHarvestCard) : SquareEffect()
    data object Destroyed : SquareEffect()
    /** Entered a Zone of Protection (see [com.tiersofexistence.engine.state.TierTokenPool.enterZone]) —
     * the token is now off the main loop and protected until a card moves it out or destroys it. */
    data class EnteredZone(val zoneNumber: Int) : SquareEffect()
    /** Landed on a Marauder Construction Facility — building is the player's choice; call [TurnEngine.buildMarauder]. */
    data object MayBuildMarauder : SquareEffect()
    /** A Marauder landed on a Marauder Transport — moving is optional; call [TurnEngine.transportMarauder]. */
    data object MayTransport : SquareEffect()
}

/** The result of moving one token: where it ended up, what it destroyed along the way (Marauder/
 * Hyperthrust pass-through), the type of square it landed on, and what that landing triggered. */
data class MoveResult(
    val finalPosition: Int,
    val destroyedTokens: List<TokenRef>,
    val landedSquareType: SquareType,
    val effect: SquareEffect,
)

/**
 * Rolls dice, moves tokens, and resolves landing-square effects — the base mechanics of a turn.
 * Deliberately does NOT interpret Fate Harvest card effects (beyond drawing and, for [CardTiming.HELD]
 * cards, holding them) — that's a separate, much larger layer to build on top of this once the base
 * mechanics are solid. Specific things left unimplemented here, flagged rather than guessed:
 * - Most Time Wrinkle variants — "Go again" is supported structurally via [GameState.endTurn]'s
 *   `grantAnotherTurn` parameter (the caller decides to pass that when a Go Again square/card fires),
 *   but "lose next turn on this Tier" and "take an extra turn, First Tier" need deferred/cross-Phase
 *   state (see `docs/card-mechanics-matrix.md` §3.5) that doesn't exist yet.
 * - Precedence-card interruption mid-roll (rulebook rule #23) — a live multi-player synchronization
 *   concern for whatever orchestrates turns (the eventual UI), not something a stateless engine
 *   function can represent.
 *
 * Now implemented: Zone of Protection as real token state ([SquareEffect.EnteredZone], see
 * [com.tiersofexistence.engine.state.TierTokenPool.enterZone]) and Warp, using each square's own
 * printed [Square.magnitude]/[Square.note] instead of a hardcoded "Warp always means +5" — the 1st
 * Tier's Warp squares move 5, the 2nd Tier's moves 7, and the 1st Tier's compound Birth-Canal-with-
 * Warp-note ("Start. If you land here, Warp 5 spaces.") chains a second Warp move after the Birth
 * Canal's own (no-op) landing resolves, exactly matching what's printed there.
 */
object TurnEngine {

    /**
     * Moves a Tier token [spaces] forward and resolves whatever it lands on. Ordinary Tier token
     * movement does not destroy tokens it passes over (only Marauders and Hyperthrust do).
     */
    fun moveTierToken(state: GameState, color: PlayerColor, tier: TierLevel, fromPosition: Int, spaces: Int): MoveResult {
        val board = state.boards.getValue(tier)
        val pool = state.players.getValue(color).tierPool(tier)
        val landed = board.squareAt(fromPosition + spaces)
        pool.moveInPlay(fromPosition, landed.index)
        return resolveTierLanding(state, color, tier, board, landed)
    }

    /** Builds a Marauder on [tier]'s Birth Canal — the player's choice after landing on a Marauder
     * Construction Facility ([SquareEffect.MayBuildMarauder]). */
    fun buildMarauder(state: GameState, color: PlayerColor, tier: TierLevel) {
        state.players.getValue(color).marauders.placeOnBirthCanal(tier)
    }

    /**
     * Moves a Marauder [spaces] forward, destroying any other player's token or Marauder strictly
     * passed over along the way (not landed on), then resolves whatever it lands on. Per the
     * rulebook, only Marauder Transport/Sensor/Abyss squares affect a Marauder at all.
     */
    fun moveMarauder(state: GameState, color: PlayerColor, tier: TierLevel, fromPosition: Int, spaces: Int): MoveResult {
        val board = state.boards.getValue(tier)
        val toRaw = fromPosition + spaces
        val destroyed = destroyTokensPassed(state, tier, color, board, fromPosition, toRaw)
        val landed = board.squareAt(toRaw)
        state.players.getValue(color).marauders.move(tier, fromPosition, landed.index)
        return resolveMarauderLanding(state, color, tier, board, landed, destroyed)
    }

    /** Moves a Marauder that landed on a Marauder Transport to [toTier]'s Birth Canal — optional,
     * the player's choice after [SquareEffect.MayTransport]. */
    fun transportMarauder(state: GameState, color: PlayerColor, fromTier: TierLevel, toTier: TierLevel, position: Int) {
        state.players.getValue(color).marauders.moveToNeighboringTier(fromTier, toTier, position)
    }

    private fun resolveTierLanding(
        state: GameState,
        color: PlayerColor,
        tier: TierLevel,
        board: TierBoard,
        square: Square,
    ): MoveResult {
        val pool = state.players.getValue(color).tierPool(tier)
        val primary = resolvePrimaryTierLanding(state, color, tier, board, square, pool)
        // Compound square: 1st Tier's Birth Canal/Start also prints its own Warp instruction
        // ("Start. If you land here, Warp 5 spaces.") on top of the Birth Canal's own (no-op)
        // landing — confirmed by the user, only relevant when a token loops all the way back
        // to Start. Deliberately narrow (BIRTH_CANAL only) rather than a generic "any square
        // whose note mentions Warp" rule, since this is the one confirmed compound case.
        if (square.type == SquareType.BIRTH_CANAL && square.note?.contains("Warp", ignoreCase = true) == true && square.magnitude != null) {
            val afterWarp = resolveWarp(state, color, tier, board, board.squareAt(primary.finalPosition))
            return MoveResult(afterWarp.finalPosition, primary.destroyedTokens + afterWarp.destroyedTokens, afterWarp.landedSquareType, afterWarp.effect)
        }
        return primary
    }

    private fun resolvePrimaryTierLanding(
        state: GameState,
        color: PlayerColor,
        tier: TierLevel,
        board: TierBoard,
        square: Square,
        pool: TierTokenPool,
    ): MoveResult {
        return when (square.type) {
            SquareType.NEBULA -> {
                pool.sendToStagingPile(square.index)
                val promoted = pool.tryPromoteFromStagingPile()
                if (promoted) tier.next()?.let { state.players.getValue(color).tierPool(it).startToken() }
                MoveResult(square.index, emptyList(), square.type, SquareEffect.SentToStagingPile(promoted))
            }
            SquareType.INFERNAL_ABYSS -> {
                pool.destroyInPlay(square.index)
                MoveResult(square.index, emptyList(), square.type, SquareEffect.Destroyed)
            }
            SquareType.VORTEX_OF_REGRESSION -> {
                pool.moveInPlay(square.index, 0)
                MoveResult(0, emptyList(), square.type, SquareEffect.SentToStart)
            }
            SquareType.WORMHOLE_OF_CONSTRUCTION -> {
                pool.promoteInPlayToken(square.index)
                val next = tier.next()
                next?.let { state.players.getValue(color).tierPool(it).startToken() }
                MoveResult(square.index, emptyList(), square.type, next?.let { SquareEffect.Promoted(it) } ?: SquareEffect.None)
            }
            SquareType.YOU_WIN -> {
                state.declareWinner(color)
                MoveResult(square.index, emptyList(), square.type, SquareEffect.Won)
            }
            SquareType.FATE_HARVEST -> {
                val card = state.deck.draw()
                if (card.timing == CardTiming.HELD) state.players.getValue(color).hand += card
                MoveResult(square.index, emptyList(), square.type, SquareEffect.DrewCard(card))
            }
            SquareType.MARAUDER_CONSTRUCTION_FACILITY -> {
                MoveResult(square.index, emptyList(), square.type, SquareEffect.MayBuildMarauder)
            }
            SquareType.HYPERTHRUST -> {
                val magnitude = requireNotNull(square.magnitude) { "Hyperthrust square on $tier has no magnitude set" }
                val fromIndex = square.index
                val destroyed = destroyTokensPassed(state, tier, color, board, fromIndex, fromIndex + magnitude)
                val landed = board.squareAt(fromIndex + magnitude)
                pool.moveInPlay(fromIndex, landed.index)
                val after = resolveTierLanding(state, color, tier, board, landed)
                MoveResult(after.finalPosition, destroyed + after.destroyedTokens, after.landedSquareType, after.effect)
            }
            SquareType.ZONE_OF_PROTECTION -> {
                val zoneNumber = requireNotNull(square.magnitude) { "Zone of Protection square on $tier has no zone number set" }
                pool.enterZone(square.index, zoneNumber)
                MoveResult(square.index, emptyList(), square.type, SquareEffect.EnteredZone(zoneNumber))
            }
            SquareType.WARP -> resolveWarp(state, color, tier, board, square)
            else -> MoveResult(square.index, emptyList(), square.type, SquareEffect.None)
        }
    }

    /**
     * Warp: moves the token forward by this square's own printed [Square.magnitude] (5 on the
     * 1st Tier, 7 on the 2nd — never a hardcoded global constant) and resolves whatever it
     * lands on, chaining like Hyperthrust but WITHOUT pass-through destruction — the rulebook's
     * Warp text says only "usually affects movement," with no destroy clause, unlike
     * Hyperthrust's explicit "destroying any opponents' tokens... that you pass."
     */
    private fun resolveWarp(state: GameState, color: PlayerColor, tier: TierLevel, board: TierBoard, square: Square): MoveResult {
        val magnitude = requireNotNull(square.magnitude) { "Warp square on $tier has no magnitude set" }
        val pool = state.players.getValue(color).tierPool(tier)
        val landed = board.squareAt(square.index + magnitude)
        pool.moveInPlay(square.index, landed.index)
        return resolveTierLanding(state, color, tier, board, landed)
    }

    private fun resolveMarauderLanding(
        state: GameState,
        color: PlayerColor,
        tier: TierLevel,
        board: TierBoard,
        square: Square,
        destroyedSoFar: List<TokenRef>,
    ): MoveResult {
        // "Marauders are only affected when they land on Marauder Transports, Marauder Sensors, and
        // Abysses. No other space on the board affects Marauders" (Marauders section).
        return when (square.type) {
            SquareType.ABYSS, SquareType.INFERNAL_ABYSS -> {
                state.players.getValue(color).marauders.destroy(tier, square.index)
                MoveResult(square.index, destroyedSoFar, square.type, SquareEffect.Destroyed)
            }
            SquareType.MARAUDER_SENSOR -> {
                val fromIndex = square.index
                val toRaw = fromIndex + 2
                val moreDestroyed = destroyTokensPassed(state, tier, color, board, fromIndex, toRaw)
                val landed = board.squareAt(toRaw)
                state.players.getValue(color).marauders.move(tier, fromIndex, landed.index)
                resolveMarauderLanding(state, color, tier, board, landed, destroyedSoFar + moreDestroyed)
            }
            SquareType.MARAUDER_TRANSPORT -> MoveResult(square.index, destroyedSoFar, square.type, SquareEffect.MayTransport)
            else -> MoveResult(square.index, destroyedSoFar, square.type, SquareEffect.None)
        }
    }

    /**
     * Destroys any other player's token or Marauder on a square strictly between [fromIndex] and
     * [toIndex] (exclusive of both — landing on a token doesn't destroy it, only passing over one
     * does), per the Marauders section ("Your Marauders destroy the tokens of other players by
     * passing them... If a Marauder lands on a space occupied by another token, that token is not
     * destroyed") and Hyperthrust's identically-worded square text. Never destroys the mover's own
     * tokens. A Tier token on Reprieve is never destroyed this way, regardless of what's passing —
     * "Any normal token on Reprieve cannot be destroyed" (confirmed by the user, applies uniformly
     * to Marauder and Hyperthrust pass-through alike). A Marauder on Reprieve is NOT protected —
     * Reprieve only shields ordinary Tier tokens.
     */
    private fun destroyTokensPassed(
        state: GameState,
        tier: TierLevel,
        moverColor: PlayerColor,
        board: TierBoard,
        fromIndex: Int,
        toIndex: Int,
    ): List<TokenRef> {
        val destroyed = mutableListOf<TokenRef>()
        for (square in board.squaresPassedBetween(fromIndex, toIndex)) {
            for (ref in tokensAt(state, tier, square.index)) {
                if (ref.color == moverColor) continue
                if (square.type == SquareType.REPRIEVE && ref.kind == TokenKind.TIER_TOKEN) continue
                destroyed += ref
                when (ref.kind) {
                    TokenKind.TIER_TOKEN -> state.players.getValue(ref.color).tierPool(tier).destroyInPlay(ref.position)
                    TokenKind.MARAUDER -> state.players.getValue(ref.color).marauders.destroy(tier, ref.position)
                }
            }
        }
        return destroyed
    }

    private fun tokensAt(state: GameState, tier: TierLevel, position: Int): List<TokenRef> =
        state.players.values.flatMap { player ->
            buildList {
                if (position in player.tierPool(tier).inPlayPositions) add(TokenRef(player.color, TokenKind.TIER_TOKEN, position))
                if (position in player.marauders.positions(tier)) add(TokenRef(player.color, TokenKind.MARAUDER, position))
            }
        }
}
