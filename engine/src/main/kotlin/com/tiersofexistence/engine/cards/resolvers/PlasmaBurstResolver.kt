package com.tiersofexistence.engine.cards.resolvers

import com.tiersofexistence.engine.board.SquareType
import com.tiersofexistence.engine.cards.play.CardLifecycle
import com.tiersofexistence.engine.cards.play.CardPlayRequest
import com.tiersofexistence.engine.cards.play.CardPlayResult
import com.tiersofexistence.engine.cards.play.CardTarget
import com.tiersofexistence.engine.state.GameState

/**
 * Plasma Burst: "Red player chooses a Tier, then removes all tokens (of any type) from 3
 * neighboring squares on that Tier. This card can also destroy Tier tokens on Zone of Protection
 * squares." [target] identifies the first of the 3 consecutive squares (per confirmed ruling,
 * "neighboring" means 3 consecutive main-loop positions); the other two follow via the board's
 * own wraparound indexing ([com.tiersofexistence.engine.board.TierBoard.squareAt]), same as any
 * other movement. Owner-unrestricted, like Fluidic Wave — the card names no player, so every
 * owner's tokens on the 3 squares are removed, not just an opponent's.
 *
 * Confirmed with the user: Plasma Burst is one of the 5 named rule-12 exceptions (matching
 * [com.tiersofexistence.engine.cards.play.TargetValidator.ZONE_OF_PROTECTION_EXCEPTIONS], which
 * already listed it), so if one of the 3 chosen squares is a Zone of Protection's own entry
 * square, every token currently resident in that Zone is destroyed too — the entry square is
 * how Plasma Burst reaches into the Zone, not a separate target of its own. A token that merely
 * landed on the entry square without choosing to enter (see [com.tiersofexistence.engine.rules.TurnEngine.enterZoneOfProtection])
 * isn't a Zone resident at all — it's an ordinary in-play token on that square, already covered
 * by the plain per-square sweep below.
 */
object PlasmaBurstResolver {
    private const val SQUARE_COUNT = 3

    fun resolve(state: GameState, request: CardPlayRequest, target: CardTarget.BoardPosition): CardPlayResult {
        val playResult = CardLifecycle.attemptPlay(state, request)
        if (playResult !is CardPlayResult.Resolved) return playResult

        val board = state.boards.getValue(target.tier)
        repeat(SQUARE_COUNT) { offset ->
            val square = board.squareAt(target.position + offset)
            state.players.values.forEach { player ->
                player.tierPool(target.tier).destroyAllAt(square.index)
                player.marauders.destroyAllAt(target.tier, square.index)
                if (square.type == SquareType.ZONE_OF_PROTECTION) {
                    val zoneNumber = requireNotNull(square.magnitude) {
                        "Zone of Protection square on ${target.tier} has no zone number set"
                    }
                    player.tierPool(target.tier).destroyAllInZone(zoneNumber)
                }
            }
        }
        return playResult
    }
}
