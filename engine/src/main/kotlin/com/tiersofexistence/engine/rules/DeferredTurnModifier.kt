package com.tiersofexistence.engine.rules

import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.TierLevel

/**
 * A queued, bounded turn-state change that doesn't take effect the instant it's granted —
 * consumed the next time a turn queue is built for the matching (player, Tier) — see
 * [com.tiersofexistence.engine.state.GameState.queueExtraTierTurn].
 *
 * Both Phase Control (card, its same-Round case) and the 2nd Tier's "Take an extra turn, First
 * Tier" Time Wrinkle square need [ExtraTierTurn]. See `docs/card-mechanics-matrix.md` §3.5 —
 * bounded per confirmed canon ("no turn may be repeated more than once from a single triggering
 * effect"): each instance here is consumed exactly once, so a single trigger can never queue
 * more than one repeat; independent triggers still stack (2 separate `ExtraTierTurn` entries
 * grant 2 separate extra turns), which is unrelated to and unaffected by the bound above.
 *
 * Phase Loss and the 1st Tier's "Lose next turn on this Tier" Time Wrinkle square need the
 * *other* deferred effect, "skip a future turn" — that one is NOT modeled as an instance of
 * this sealed class. It's tracked as an explicit per-(player, Tier) counter,
 * [com.tiersofexistence.engine.state.GameState.pendingSkips] (queued via
 * [com.tiersofexistence.engine.state.GameState.queueSkipNextTierTurn]), specifically because
 * independent skip triggers against the same (player, Tier) are confirmed canon to stack —
 * each one must remove exactly one distinct future turn, and a list-of-instances
 * representation (matching this class's own shape) had a real collapse bug where multiple
 * entries for the same (player, Tier) got consumed together in one `buildTurnQueue()` call
 * instead of one at a time. An explicit counter makes that class of bug structurally
 * impossible rather than something a future reader could reintroduce by "simplifying" this
 * back into one list.
 */
sealed class DeferredTurnModifier {
    abstract val player: PlayerColor
    abstract val tier: TierLevel

    data class ExtraTierTurn(override val player: PlayerColor, override val tier: TierLevel) : DeferredTurnModifier()
}
