package com.tiersofexistence.engine.cards.play

import com.tiersofexistence.engine.model.PlayerColor

/**
 * Where a specific play currently stands. Chain entries in
 * [com.tiersofexistence.engine.rules.precedence.InteractionChain] reuse this same vocabulary
 * instead of inventing a parallel one.
 */
enum class CardResolutionState { PENDING, RESOLVING, RESOLVED, CANCELLED, REJECTED }

/**
 * Why a [CardPlayRequest] was rejected — a meaningful, inspectable result instead of a silent
 * no-op or a thrown exception the UI has to guess the cause of (Phase D's explicit requirement).
 * Each case's [detail] carries the card/target-specific specifics (which color, which Tier, etc.)
 * since a fixed enum can't spell out every card's own restriction text.
 */
sealed class TargetValidationError(val detail: String) {
    class WrongColor(detail: String) : TargetValidationError(detail)
    class WrongScope(detail: String) : TargetValidationError(detail)
    class PhaseCardLimitReached(detail: String) : TargetValidationError(detail)
    class ZoneOfProtectionBlocksTarget(detail: String) : TargetValidationError(detail)
    class WrongTokenType(detail: String) : TargetValidationError(detail)
    class WrongTargetCount(detail: String) : TargetValidationError(detail)
    class NoLegalTarget(detail: String) : TargetValidationError(detail)
    class CardSpecificRestriction(detail: String) : TargetValidationError(detail)
}

/**
 * A decision the engine needs from some player before a play can finish resolving. Generalizes
 * both Cleansing's "the targeted opponent picks which of their own cards to discard" and a
 * Precedence response window opening — see
 * [com.tiersofexistence.engine.rules.precedence.InteractionChain], which is built on this same
 * "a play can be pending on someone else's decision" idea rather than a parallel mechanism.
 */
sealed class PendingDecision {
    /** Cleansing: [decidingPlayer] (the targeted opponent) must choose which held card to discard. */
    data class OpponentDiscardChoice(val decidingPlayer: PlayerColor) : PendingDecision()

    /** A Precedence interaction window is open; [chainId] identifies it for follow-up decisions
     * (respond / pass) via the interaction engine. */
    data class PrecedenceWindowOpen(val chainId: Long) : PendingDecision()
}

/** The outcome of attempting to resolve a [CardPlayRequest]. */
sealed class CardPlayResult {
    /** A Held card was drawn and placed in the player's hand — not played yet, no validation
     * performed (see [com.tiersofexistence.engine.cards.play.CardLifecycle.onDrawnFromSquare]). */
    data class EnteredHand(val request: CardPlayRequest) : CardPlayResult()
    data class Resolved(val request: CardPlayRequest) : CardPlayResult()
    data class Rejected(val request: CardPlayRequest, val reason: TargetValidationError) : CardPlayResult()
    data class AwaitingDecision(val request: CardPlayRequest, val pending: PendingDecision) : CardPlayResult()
}
