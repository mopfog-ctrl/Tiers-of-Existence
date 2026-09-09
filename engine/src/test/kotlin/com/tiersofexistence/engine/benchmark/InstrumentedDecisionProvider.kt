package com.tiersofexistence.engine.benchmark

import com.tiersofexistence.engine.cards.FateHarvestCard
import com.tiersofexistence.engine.cards.play.CardTarget
import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.rules.CardChoice
import com.tiersofexistence.engine.rules.TurnDecisionProvider
import com.tiersofexistence.engine.rules.precedence.InteractionChain
import com.tiersofexistence.engine.simulation.RandomLegalDecisionProvider
import com.tiersofexistence.engine.state.GameState
import com.tiersofexistence.engine.state.TokenId

/**
 * Wraps a [RandomLegalDecisionProvider] (unmodified — this benchmark reuses the exact same
 * randomized-but-plausible decision logic `GameSimulationTest` already validated across 6000+
 * games, rather than a new/different policy) and records one [DecisionTally] entry per call into
 * [stats], split into "asked" (every call) vs "substantive" (see each `override`'s own comment
 * for what that means per callback) — then delegates to the wrapped provider for the actual
 * answer, so gameplay behavior is byte-for-byte identical to plain `RandomLegalDecisionProvider`;
 * this class only observes, never changes, what gets decided.
 *
 * "Substantive" is a best-effort, cheaply-computed proxy — checking candidate-list size or hand
 * contents already visible in the callback's own parameters — not a full re-derivation of
 * [com.tiersofexistence.engine.cards.play.TargetValidator] legality. Two callbacks
 * ([chooseImmediateCardTargets], [choosePrecedenceResponse]'s target-shape aspect) can't cheaply
 * distinguish "one legal target existed" from "several did" without duplicating that legality
 * logic, so they're reported as asked-only where noted, rather than a fabricated substantive
 * count — see the benchmark report's own note on this gap.
 */
class InstrumentedDecisionProvider(
    private val delegate: RandomLegalDecisionProvider,
    private val stats: SeatDecisionStats,
) : TurnDecisionProvider {

    override fun chooseTokenToMove(state: GameState, player: PlayerColor, candidates: List<TokenId>): TokenId {
        // Substantive: more than one token/Marauder could legally have been chosen instead.
        stats.record(DecisionType.TOKEN_TO_MOVE, candidates.size > 1)
        return delegate.chooseTokenToMove(state, player, candidates)
    }

    override fun chooseEnterZone(state: GameState, player: PlayerColor, tier: TierLevel, zoneNumber: Int): Boolean {
        // Both "enter" and "decline" are always genuinely legal here (SquareEffect.MayEnterZone
        // is only ever offered when both are real options) — always substantive when asked.
        stats.record(DecisionType.ENTER_ZONE, true)
        return delegate.chooseEnterZone(state, player, tier, zoneNumber)
    }

    override fun chooseBuildMarauder(state: GameState, player: PlayerColor, tier: TierLevel): Boolean {
        // Same reasoning as chooseEnterZone: MayBuildMarauder is only offered when building is
        // actually legal, and declining is always legal — a genuine binary choice whenever asked.
        stats.record(DecisionType.BUILD_MARAUDER, true)
        return delegate.chooseBuildMarauder(state, player, tier)
    }

    override fun chooseTransport(state: GameState, player: PlayerColor, fromTier: TierLevel, options: List<TierLevel>): TierLevel? {
        // options is never empty per the interface's own contract (every Tier has a neighbor) —
        // "transport or decline" is always a real choice, with a further sub-choice of which
        // neighboring Tier when options.size > 1 (not separately broken out here).
        stats.record(DecisionType.TRANSPORT, true)
        return delegate.chooseTransport(state, player, fromTier, options)
    }

    override fun chooseHeldCardBeforeRoll(state: GameState, player: PlayerColor): CardChoice? {
        // Substantive: hand is non-empty, so playing something vs. declining is a real choice
        // (not literally "nothing to play at all").
        stats.record(DecisionType.HELD_CARD_BEFORE_ROLL, state.players.getValue(player).hand.isNotEmpty())
        return delegate.chooseHeldCardBeforeRoll(state, player)
    }

    override fun chooseCardAfterRollBeforeMove(state: GameState, player: PlayerColor, roll: Int): CardChoice? {
        stats.record(DecisionType.CARD_AFTER_ROLL_BEFORE_MOVE, state.players.getValue(player).hand.isNotEmpty())
        return delegate.chooseCardAfterRollBeforeMove(state, player, roll)
    }

    override fun chooseImmediateCardTargets(state: GameState, player: PlayerColor, card: FateHarvestCard): List<CardTarget> {
        // Mandatory play (rule 14) — every call is "asked." Whether more than one legal target
        // existed isn't cheaply knowable without re-running TargetValidator, so this is reported
        // asked-only (substantive left false rather than guessed) — see class doc.
        stats.record(DecisionType.IMMEDIATE_CARD_TARGETS, false)
        return delegate.chooseImmediateCardTargets(state, player, card)
    }

    override fun choosePrecedenceResponse(state: GameState, player: PlayerColor, chain: InteractionChain): CardChoice? {
        // Substantive: at least one Precedence-flagged card is actually in hand to respond with —
        // a real "respond or pass" choice, not a forced pass with nothing eligible to play.
        stats.record(DecisionType.PRECEDENCE_RESPONSE, state.players.getValue(player).hand.any { it.hasPrecedence })
        return delegate.choosePrecedenceResponse(state, player, chain)
    }

    override fun chooseCleansingDiscard(
        state: GameState,
        decidingPlayer: PlayerColor,
        sourcePlayer: PlayerColor,
        eligibleCards: List<FateHarvestCard>,
    ): FateHarvestCard {
        // Mandatory once reached; substantive only if there's genuinely more than one card to
        // pick among (eligibleCards is never empty per Cleansing's own confirmed canon).
        stats.record(DecisionType.CLEANSING_DISCARD, eligibleCards.size > 1)
        return delegate.chooseCleansingDiscard(state, decidingPlayer, sourcePlayer, eligibleCards)
    }
}
