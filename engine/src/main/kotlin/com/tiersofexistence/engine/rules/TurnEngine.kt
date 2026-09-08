package com.tiersofexistence.engine.rules

import com.tiersofexistence.engine.board.Square
import com.tiersofexistence.engine.board.SquareType
import com.tiersofexistence.engine.board.TierBoard
import com.tiersofexistence.engine.cards.CardTiming
import com.tiersofexistence.engine.cards.FateHarvestCard
import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.model.TokenKind
import com.tiersofexistence.engine.state.GameState
import com.tiersofexistence.engine.state.TierTokenPool
import com.tiersofexistence.engine.state.TokenId

/** A token found at a specific board position, identified by owner/kind/position for pass-through
 * scanning purposes — deliberately NOT the same as [com.tiersofexistence.engine.state.TokenId]'s
 * persistent identity; this is a transient snapshot used only within one movement resolution. */
data class TokenRef(val color: PlayerColor, val kind: TokenKind, val position: Int)

/** What happened as a result of landing on a square, beyond the plain position update. */
sealed class SquareEffect {
    /** Nothing beyond moving there — includes squares whose effect isn't implemented yet (see
     * [TurnEngine]'s class doc for what's deferred: most Time Wrinkle variants). */
    data object None : SquareEffect()
    data class SentToStagingPile(val promotedToNextTier: Boolean) : SquareEffect()
    data object SentToStart : SquareEffect()
    data class Promoted(val toTier: TierLevel) : SquareEffect()
    data object Won : SquareEffect()
    data class DrewCard(val card: FateHarvestCard) : SquareEffect()
    data object Destroyed : SquareEffect()
    /** Landed on a Zone of Protection's entry square — entering is the player's choice, and only
     * available for the rest of this turn; call [TurnEngine.enterZoneOfProtection] before the
     * turn ends. If the choice isn't taken, the token simply remains on the entry square as an
     * ordinary, unprotected in-play position from then on — nothing else marks that square as
     * special once the turn passes. */
    data class MayEnterZone(val zoneNumber: Int) : SquareEffect()
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

/** The result of [TurnEngine.moveZoneToken] — either the token is still inside its Zone (now at
 * a new [ProtectionZone][com.tiersofexistence.engine.board.ProtectionZone]-relative position), or
 * it moved past the end of the Zone's own square sequence and re-entered the main loop, in which
 * case this wraps the ordinary [MoveResult] for whatever it landed on there. */
sealed class ZoneMoveResult {
    data class StillInZone(val zoneNumber: Int, val zonePosition: Int, val effect: SquareEffect) : ZoneMoveResult()
    data class ExitedZone(val moveResult: MoveResult) : ZoneMoveResult()
}

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
 * Now implemented: Zone of Protection as real token state ([com.tiersofexistence.engine.state.TierTokenPool.enterZone]),
 * though entering one is the player's own choice rather than automatic — landing on a Zone's entry
 * square only offers it ([SquareEffect.MayEnterZone]; see that effect's doc for what happens if the
 * choice isn't taken before the turn ends) — and Warp, using each square's own printed
 * [Square.magnitude]/[Square.note] instead of a hardcoded "Warp always means +5" — the 1st Tier's
 * Warp squares move 5, the 2nd Tier's moves 7, and the 1st Tier's compound Birth-Canal-with-Warp-
 * note ("Start. If you land here, Warp 5 spaces.") chains a second Warp move after the Birth
 * Canal's own (no-op) landing resolves, exactly matching what's printed there.
 *
 * A Zone of Protection is itself a real dice-driven sub-path (confirmed by the user, correcting
 * an earlier wrong assumption) — [moveZoneToken] moves a Zone-resident token within its own
 * [com.tiersofexistence.engine.board.ProtectionZone.squares], exiting back onto the main loop
 * (via the ordinary [moveTierToken] path) once the move overflows past the Zone's last slot. This
 * is a sibling entry point to [moveTierToken], not a variant of it — same as ordinary main-loop
 * movement, choosing *which* of a player's tokens to move (a main-loop one vs. a Zone-resident
 * one) is left to whatever's driving the turn.
 */
object TurnEngine {

    /**
     * Moves a Tier token [spaces] forward and resolves whatever it lands on. Ordinary Tier token
     * movement does not destroy tokens it passes over (only Marauders and Hyperthrust do) —
     * [destroysPassedTokens] defaults to false to preserve that for every existing caller.
     * Last Gasp is the one card that gives a Tier token this power (Fate Harvest Card Rule #10);
     * pass `destroysPassedTokens = true` (and, per that card's own wording, `exemptMoverOwnTokens
     * = false`) only from [com.tiersofexistence.engine.cards.resolvers.LastGaspResolver].
     */
    fun moveTierToken(
        state: GameState,
        color: PlayerColor,
        tier: TierLevel,
        fromPosition: Int,
        spaces: Int,
        destroysPassedTokens: Boolean = false,
        exemptMoverOwnTokens: Boolean = true,
    ): MoveResult {
        val board = state.boards.getValue(tier)
        val pool = state.players.getValue(color).tierPool(tier)
        val toRaw = fromPosition + spaces
        val destroyed = if (destroysPassedTokens) {
            destroyTokensPassed(state, tier, color, board, fromPosition, toRaw, exemptMoverOwnTokens)
        } else {
            emptyList()
        }
        val landed = board.squareAt(toRaw)
        pool.moveInPlay(fromPosition, landed.index)
        val after = resolveTierLanding(state, color, tier, board, landed)
        return MoveResult(after.finalPosition, destroyed + after.destroyedTokens, after.landedSquareType, after.effect)
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
     * [exemptMoverOwnTokens] defaults to true (the general Marauder-pass-through rule); Last
     * Gasp passes false when its target is a Marauder, per that card's own wording — see
     * [com.tiersofexistence.engine.cards.resolvers.LastGaspResolver].
     */
    fun moveMarauder(
        state: GameState,
        color: PlayerColor,
        tier: TierLevel,
        fromPosition: Int,
        spaces: Int,
        exemptMoverOwnTokens: Boolean = true,
    ): MoveResult {
        val board = state.boards.getValue(tier)
        val toRaw = fromPosition + spaces
        val destroyed = destroyTokensPassed(state, tier, color, board, fromPosition, toRaw, exemptMoverOwnTokens)
        val landed = board.squareAt(toRaw)
        state.players.getValue(color).marauders.move(tier, fromPosition, landed.index)
        return resolveMarauderLanding(state, color, tier, board, landed, destroyed)
    }

    /** Moves a Marauder that landed on a Marauder Transport to [toTier]'s Birth Canal — optional,
     * the player's choice after [SquareEffect.MayTransport]. */
    fun transportMarauder(state: GameState, color: PlayerColor, fromTier: TierLevel, toTier: TierLevel, position: Int) {
        state.players.getValue(color).marauders.moveToNeighboringTier(fromTier, toTier, position)
    }

    /** Moves a token that landed on a Zone of Protection's entry square into the Zone — optional,
     * the player's choice after [SquareEffect.MayEnterZone]; expected to be called before the
     * turn ends (see that effect's doc for what happens if it isn't). */
    fun enterZoneOfProtection(state: GameState, color: PlayerColor, tier: TierLevel, position: Int, zoneNumber: Int) {
        state.players.getValue(color).tierPool(tier).enterZone(position, zoneNumber)
    }

    /**
     * Moves the Tier token [color] has resident in Zone [zoneNumber] forward [spaces] within
     * that Zone's own square sequence — confirmed by the user: a Zone of Protection is a real
     * dice-driven sub-path, not just an undifferentiated "protected" flag, so a resident token
     * can be chosen and moved during an ordinary Tier-Phase turn (rolling dice for it same as
     * any other token) or by a card (Galactic Roundabout's confirmed "+2, advancing back into
     * the normal board as necessary").
     *
     * If the new zone-relative position still fits within the Zone's own
     * [com.tiersofexistence.engine.board.ProtectionZone.squares], the token stays a Zone
     * resident at that new position, and that zone-internal square's own effect resolves (see
     * [resolveZoneLanding]) — [ZoneMoveResult.StillInZone]. Otherwise it exits the Zone entirely:
     * the overflow (spaces beyond the Zone's last slot) continues from the Zone's own main-loop
     * entry square via the ordinary [moveTierToken] path, so any further chaining (a Warp square
     * right at the entry point, Hyperthrust, etc.) resolves exactly as it would for any other
     * main-loop move — [ZoneMoveResult.ExitedZone].
     *
     * [destroysPassedTokens]/[exemptMoverOwnTokens] mirror [moveTierToken]'s own parameters of
     * the same name, applied only to the main-loop portion of an exit (via that same
     * [moveTierToken] call) — never to other tokens resident in this same Zone. That's not an
     * oversight: the rulebook's pass-through-destroy cards all exempt "tokens in the Zone of
     * Protection" by name (Last Gasp included), so a Zone is never a place pass-through
     * destruction reaches into, even when the mover itself started there.
     */
    fun moveZoneToken(
        state: GameState,
        color: PlayerColor,
        tier: TierLevel,
        zoneNumber: Int,
        spaces: Int,
        destroysPassedTokens: Boolean = false,
        exemptMoverOwnTokens: Boolean = true,
    ): ZoneMoveResult {
        val board = state.boards.getValue(tier)
        val pool = state.players.getValue(color).tierPool(tier)
        val id = requireNotNull(pool.idInZone(zoneNumber)) { "No $color token in Zone $zoneNumber on $tier" }
        val currentZonePosition = requireNotNull(pool.zonePositionOf(id)) { "Token $id has no zone position on $tier" }
        val zone = board.protectionZone(zoneNumber)
        val newZonePosition = currentZonePosition + spaces

        if (newZonePosition <= zone.squares.size) {
            return resolveZoneLanding(state, color, tier, pool, id, zoneNumber, newZonePosition, zone.squares[newZonePosition - 1])
        }

        val entryIndex = board.zoneEntryIndex(zoneNumber)
        val overflow = newZonePosition - zone.squares.size
        pool.leaveZone(id, entryIndex)
        return ZoneMoveResult.ExitedZone(
            moveTierToken(state, color, tier, entryIndex, overflow, destroysPassedTokens, exemptMoverOwnTokens),
        )
    }

    /** Resolves landing on zone-internal square [squareType] at 1-indexed [newZonePosition] within
     * Zone [zoneNumber] — the "still inside the Zone" half of [moveZoneToken]. Nebula and Wormhole
     * of Construction remove the token from the Zone entirely (same as their main-loop
     * counterparts remove a token from [com.tiersofexistence.engine.state.TierTokenPool
     * .inPlayPositions]), so those report as [ZoneMoveResult.ExitedZone] even though the token
     * never touched the main loop — everything else leaves it a Zone resident at its new position. */
    private fun resolveZoneLanding(
        state: GameState,
        color: PlayerColor,
        tier: TierLevel,
        pool: TierTokenPool,
        id: TokenId,
        zoneNumber: Int,
        newZonePosition: Int,
        squareType: SquareType,
    ): ZoneMoveResult {
        val entryIndex = state.boards.getValue(tier).zoneEntryIndex(zoneNumber)
        return when (squareType) {
            SquareType.NEBULA -> {
                pool.sendZoneResidentToStagingPile(id)
                val promoted = pool.tryPromoteFromStagingPile()
                if (promoted) tier.next()?.let { state.players.getValue(color).tierPool(it).startToken() }
                ZoneMoveResult.ExitedZone(MoveResult(entryIndex, emptyList(), squareType, SquareEffect.SentToStagingPile(promoted)))
            }
            SquareType.WORMHOLE_OF_CONSTRUCTION -> {
                pool.promoteZoneResident(id)
                val next = tier.next()
                next?.let { state.players.getValue(color).tierPool(it).startToken() }
                ZoneMoveResult.ExitedZone(MoveResult(entryIndex, emptyList(), squareType, next?.let { SquareEffect.Promoted(it) } ?: SquareEffect.None))
            }
            SquareType.FATE_HARVEST -> {
                pool.advanceInZone(id, newZonePosition)
                val card = state.deck.draw()
                if (card.timing == CardTiming.HELD) state.players.getValue(color).hand += card
                ZoneMoveResult.StillInZone(zoneNumber, newZonePosition, SquareEffect.DrewCard(card))
            }
            else -> {
                // PLAIN, MARAUDER_TRANSPORT (Tier tokens aren't affected by Transport squares —
                // only Marauders are, per the rulebook's Marauders section), and
                // PROTECTION_REJECTED (its own printed "move two more spaces" chain is a
                // pre-existing, separately flagged gap — see SquareType.PROTECTION_REJECTED —
                // not something this change resolves).
                pool.advanceInZone(id, newZonePosition)
                ZoneMoveResult.StillInZone(zoneNumber, newZonePosition, SquareEffect.None)
            }
        }
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
                MoveResult(square.index, emptyList(), square.type, SquareEffect.MayEnterZone(zoneNumber))
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
     * Destroys tokens on a square strictly between [fromIndex] and [toIndex] (exclusive of both —
     * landing on a token doesn't destroy it, only passing over one does), per the Marauders
     * section ("Your Marauders destroy the tokens of other players by passing them... If a
     * Marauder lands on a space occupied by another token, that token is not destroyed") and
     * Hyperthrust's identically-worded square text. [exemptMoverOwnTokens] defaults to true,
     * matching that general rule ("of other players" — never the mover's own); Last Gasp is the
     * one card whose own wording omits that exemption entirely (confirmed by the user), so its
     * resolver passes false. A Tier token on Reprieve is never destroyed this way, regardless of
     * what's passing — "Any normal token on Reprieve cannot be destroyed" (confirmed by the user,
     * applies uniformly to Marauder, Hyperthrust, and Last Gasp pass-through alike, since all
     * three share this same category of effect). A Marauder on Reprieve is NOT protected —
     * Reprieve only shields ordinary Tier tokens.
     */
    private fun destroyTokensPassed(
        state: GameState,
        tier: TierLevel,
        moverColor: PlayerColor,
        board: TierBoard,
        fromIndex: Int,
        toIndex: Int,
        exemptMoverOwnTokens: Boolean = true,
    ): List<TokenRef> {
        val destroyed = mutableListOf<TokenRef>()
        for (square in board.squaresPassedBetween(fromIndex, toIndex)) {
            for (ref in tokensAt(state, tier, square.index)) {
                if (exemptMoverOwnTokens && ref.color == moverColor) continue
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
