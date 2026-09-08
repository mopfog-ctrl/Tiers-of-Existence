package com.tiersofexistence.engine.rules

import com.tiersofexistence.engine.model.PlayerColor

/**
 * A die roll that has happened but hasn't yet been used to move a token — the narrow window
 * Delayed Motion needs to hook into ("must be played after your die roll, but before you move
 * the token"). [Dice.rollForPhase] already returns a plain distance rather than folding roll and
 * movement into one step specifically so a card can adjust it in between — see that function's
 * doc. This is the missing engine-tracked checkpoint that lets a card actually do so.
 *
 * Deliberately owned by [com.tiersofexistence.engine.state.GameState] (via `beginPendingRoll`/
 * `clearPendingRoll`) rather than passed around as a bare parameter every resolver would need to
 * thread through, matching [DeferredTurnModifier]'s precedent for other turn-scoped transient
 * state. [total] starts at the roll's raw value and can be bumped by a card
 * ([com.tiersofexistence.engine.cards.resolvers.DelayedMotionResolver]) any number of times
 * before whoever's driving the turn reads it back and passes it into [TurnEngine.moveTierToken]/
 * [TurnEngine.moveMarauder]'s own `spaces` parameter — those functions are unchanged by any of
 * this, and never read [com.tiersofexistence.engine.state.GameState.pendingRoll] themselves,
 * keeping movement's already-established API untouched.
 */
class PendingRoll(val player: PlayerColor, initialValue: Int) {
    var total: Int = initialValue
        private set

    /** Adds [amount] to the roll total — Delayed Motion's own "+2". */
    fun addBonus(amount: Int) {
        total += amount
    }
}
