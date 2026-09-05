package com.tiersofexistence.engine.cards.play

import com.tiersofexistence.engine.model.TokenKind
import com.tiersofexistence.engine.state.GameState
import com.tiersofexistence.engine.state.TokenId

/** Where a [TokenId] currently is, as of the moment it's looked up — never a stale snapshot. */
sealed class TokenLocation {
    data class InPlay(val position: Int) : TokenLocation()
    data class InZone(val zoneNumber: Int) : TokenLocation()

    /** [TokenId] no longer refers to anything — destroyed, promoted, staged, or otherwise no
     * longer in play since the target was chosen. Not an error: see [TokenLocator]'s class doc
     * and [com.tiersofexistence.engine.cards.play.TargetValidationError.NoLegalTarget] — a
     * resolver finding this must reject the play gracefully, never crash. */
    data object NoLongerExists : TokenLocation()
}

/**
 * Resolves a [TokenId] to its CURRENT location by asking [GameState] directly, rather than
 * trusting any position recorded when the target was originally chosen. This is the fix for the
 * stale-target bug: a [CardTarget.Token] carries only an identity now, never a position, so every
 * resolver is forced through this lookup at the moment it actually applies its effect — including
 * inside a Precedence chain, where an earlier-resolving response may have already moved or
 * destroyed the same token a later response also targets.
 */
object TokenLocator {
    fun locate(state: GameState, id: TokenId): TokenLocation {
        val player = state.players.getValue(id.owner)
        return when (id.kind) {
            TokenKind.MARAUDER -> {
                val position = player.marauders.positionOf(id)
                if (position != null) TokenLocation.InPlay(position) else TokenLocation.NoLongerExists
            }
            TokenKind.TIER_TOKEN -> {
                val pool = player.tierPool(id.tier)
                val position = pool.positionOf(id)
                if (position != null) return TokenLocation.InPlay(position)
                val zoneNumber = pool.zoneOf(id)
                if (zoneNumber != null) TokenLocation.InZone(zoneNumber) else TokenLocation.NoLongerExists
            }
        }
    }
}
