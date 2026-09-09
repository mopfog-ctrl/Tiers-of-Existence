package com.tiersofexistence.engine.cards.resolvers

import com.tiersofexistence.engine.cards.play.CardLifecycle
import com.tiersofexistence.engine.cards.play.CardPlayRequest
import com.tiersofexistence.engine.cards.play.CardPlayResult
import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.rules.SquareEffect
import com.tiersofexistence.engine.rules.TurnEngine
import com.tiersofexistence.engine.rules.ZoneMoveResult
import com.tiersofexistence.engine.state.GameState

/**
 * Galactic Roundabout: "Move every token (all tokens including Marauders and Tier tokens in the
 * Zone of Protection) forward two spaces. This card must be played immediately." Unconditional,
 * whole-board, no target to choose — every Tier token, Marauder, and Zone-resident Tier token,
 * every player, every Tier.
 *
 * Every id this sweep will move is snapshotted *before* any of them move — [TierTokenPool
 * .inPlayIds]/[TierTokenPool.zoneResidentIds]/[MarauderPool.inPlayIds], not a live re-scan as the
 * sweep progresses — so a token created as a side effect of an earlier move in this same sweep
 * (e.g. a Nebula promotion starting a fresh token on the next Tier) is never also swept up by the
 * very same resolution; only tokens that existed the moment this card resolved move. Each
 * snapshotted id is re-checked for existence immediately before it's actually moved (skipped,
 * not crashed, if already gone) since an earlier move in this same sweep can destroy a bystander
 * at the same position (Infernal Abyss/Vortex-of-Regression-style landings) — same
 * arbitrary-but-accepted position tie-break every other bulk sweep in this engine already has.
 *
 * Two rulings confirmed by the user, resolving `docs/card-mechanics-matrix.md` §4 Q5:
 * - **Marauder movement here does NOT trigger the normal pass-through-destroy rule.** This
 *   uniform "shift everyone" isn't a targeted move in the usual sense, so every Marauder here
 *   moves via [TurnEngine.moveMarauderById] with `destroysPassedTokens = false` — the one caller
 *   of that function that opts out.
 * - **Two (or more) players' tokens landing exactly on their own 4th Tier You Win square in this
 *   one resolution is a genuine tie, not "whichever token got processed first."** Every color
 *   whose token's [com.tiersofexistence.engine.rules.MoveResult.effect] came back
 *   [SquareEffect.Won] during this sweep is collected and declared together via
 *   [GameState.declareSimultaneousWinners] — overriding whatever single winner the ordinary
 *   per-token [GameState.declareWinner] call (inside `TurnEngine`'s own landing resolution)
 *   already locked in for the first one processed. Only applies if the game had no winner before
 *   this sweep began; if it already did, nothing about the winner(s) changes (the moves still
 *   happen for board effect, but [GameState.declareWinner]'s own no-op guard already keeps the
 *   existing winner(s) untouched, so this resolver doesn't declare anything further).
 *
 * Every token includes Zone-resident Tier tokens (rule 12's named exception, per the card's own
 * text) — each moves via [TurnEngine.moveZoneToken], the same dice-driven-sub-path mechanic any
 * other caller uses (§4 Q17).
 */
object GalacticRoundaboutResolver {
    private const val SPACES = 2

    fun resolve(state: GameState, request: CardPlayRequest): CardPlayResult {
        val playResult = CardLifecycle.attemptPlay(state, request)
        if (playResult !is CardPlayResult.Resolved) return playResult

        val hadWinnerAlready = state.winners.isNotEmpty()
        val tierTokenIds = TierLevel.entries.flatMap { tier -> state.players.values.flatMap { it.tierPool(tier).inPlayIds } }
        val zoneResidentIds = TierLevel.entries.flatMap { tier -> state.players.values.flatMap { it.tierPool(tier).zoneResidentIds } }
        val marauderIds = TierLevel.entries.flatMap { tier -> state.players.values.flatMap { it.marauders.inPlayIds(tier) } }

        val winners = mutableSetOf<PlayerColor>()

        for (id in tierTokenIds) {
            val pool = state.players.getValue(id.owner).tierPool(id.tier)
            if (pool.positionOf(id) == null) continue
            val result = TurnEngine.moveTierTokenById(state, id, SPACES)
            if (result.effect is SquareEffect.Won) winners += id.owner
            discardStrandedImmediateCard(state, result.effect)
        }

        for (id in zoneResidentIds) {
            val pool = state.players.getValue(id.owner).tierPool(id.tier)
            if (pool.zoneOf(id) == null) continue
            when (val result = TurnEngine.moveZoneToken(state, id, SPACES)) {
                is ZoneMoveResult.ExitedZone -> {
                    if (result.moveResult.effect is SquareEffect.Won) winners += id.owner
                    discardStrandedImmediateCard(state, result.moveResult.effect)
                }
                is ZoneMoveResult.StillInZone -> discardStrandedImmediateCard(state, result.effect)
            }
        }

        for (id in marauderIds) {
            val marauders = state.players.getValue(id.owner).marauders
            if (marauders.positionOf(id) == null) continue
            TurnEngine.moveMarauderById(state, id, SPACES, destroysPassedTokens = false)
        }

        if (!hadWinnerAlready) {
            state.declareSimultaneousWinners(winners)
        }
        return playResult
    }
}
