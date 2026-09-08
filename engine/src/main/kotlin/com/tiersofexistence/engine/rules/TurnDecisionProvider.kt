package com.tiersofexistence.engine.rules

import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.state.GameState
import com.tiersofexistence.engine.state.TokenId

/**
 * Every real choice a player makes during the mechanical part of an ordinary turn — which token
 * to move, and whether to take each of the game's optional square offers — abstracted behind one
 * interface so [TurnDriver] can be driven by a UI, an AI, or a deterministic test double without
 * rework. Playing a Fate Harvest card (which target for which card) is a separate, later concern
 * this interface deliberately doesn't cover yet — see [TurnDriver]'s class doc and
 * [GameState.pendingImmediateCards] for where a drawn Immediate card lands in the meantime.
 *
 * Every method receives the live [GameState] so an implementation can inspect as much context as
 * it needs (board layout, other players' positions, hand contents) to make its choice.
 */
interface TurnDecisionProvider {
    /**
     * [player]'s current turn has [candidates] eligible tokens to move — every one of their own
     * Tier tokens currently in play or Zone-resident on the active Tier Phase's Tier, or every
     * one of their Marauders currently in play on any Tier for the Marauder Phase. Must return
     * one of [candidates]; [TurnDriver] is only called with this when [candidates] is non-empty.
     */
    fun chooseTokenToMove(state: GameState, player: PlayerColor, candidates: List<TokenId>): TokenId

    /** After landing exactly on a Zone of Protection's numbered entry square
     * ([SquareEffect.MayEnterZone]) — does [player] choose to enter Zone [zoneNumber]? */
    fun chooseEnterZone(state: GameState, player: PlayerColor, tier: TierLevel, zoneNumber: Int): Boolean

    /** After landing on a Marauder Construction Facility ([SquareEffect.MayBuildMarauder]) —
     * does [player] choose to build a Marauder here? */
    fun chooseBuildMarauder(state: GameState, player: PlayerColor, tier: TierLevel): Boolean

    /** After a Marauder lands on a Marauder Transport ([SquareEffect.MayTransport]) — does
     * [player] choose to transport to one of [options] (always 1 or 2 neighboring Tiers), or
     * decline by returning null? [options] is never empty (every Tier has at least one
     * neighbor). */
    fun chooseTransport(state: GameState, player: PlayerColor, fromTier: TierLevel, options: List<TierLevel>): TierLevel?
}

/**
 * The simplest possible [TurnDecisionProvider]: always moves the first candidate offered, always
 * declines every optional offer. Deterministic and dependency-free, so it's useful as a default
 * for tests/simulations that only care about the mechanical roll → move → offer loop running
 * correctly, not about realistic play — a real UI or AI should supply its own implementation.
 */
object FirstCandidateDecisionProvider : TurnDecisionProvider {
    override fun chooseTokenToMove(state: GameState, player: PlayerColor, candidates: List<TokenId>): TokenId = candidates.first()
    override fun chooseEnterZone(state: GameState, player: PlayerColor, tier: TierLevel, zoneNumber: Int): Boolean = false
    override fun chooseBuildMarauder(state: GameState, player: PlayerColor, tier: TierLevel): Boolean = false
    override fun chooseTransport(state: GameState, player: PlayerColor, fromTier: TierLevel, options: List<TierLevel>): TierLevel? = null
}
