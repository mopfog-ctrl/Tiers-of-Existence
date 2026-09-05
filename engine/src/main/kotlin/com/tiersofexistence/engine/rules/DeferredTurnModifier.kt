package com.tiersofexistence.engine.rules

import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.TierLevel

/**
 * A queued, bounded turn-state change that doesn't take effect the instant it's granted —
 * consumed the next time a turn queue is built for the matching (player, Tier) — see
 * [com.tiersofexistence.engine.state.GameState.queueSkipNextTierTurn]/`queueExtraTierTurn`.
 *
 * Both Phase Loss (card) and the 1st Tier's "Lose next turn on this Tier" Time Wrinkle square
 * need [SkipNextTierTurn]; both Phase Control (card, its same-Round case) and the 2nd Tier's
 * "Take an extra turn, First Tier" Time Wrinkle square need [ExtraTierTurn]. See
 * `docs/card-mechanics-matrix.md` §3.5 — bounded per confirmed canon ("no turn may be repeated
 * more than once from a single triggering effect"): each instance here is consumed exactly
 * once, so a single trigger can never queue more than one repeat/skip.
 */
sealed class DeferredTurnModifier {
    abstract val player: PlayerColor
    abstract val tier: TierLevel

    data class SkipNextTierTurn(override val player: PlayerColor, override val tier: TierLevel) : DeferredTurnModifier()
    data class ExtraTierTurn(override val player: PlayerColor, override val tier: TierLevel) : DeferredTurnModifier()
}
