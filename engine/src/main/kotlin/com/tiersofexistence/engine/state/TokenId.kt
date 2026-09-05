package com.tiersofexistence.engine.state

import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.model.TokenKind

/**
 * A stable identity for one currently-existing game piece — assigned once when a token enters
 * play ([TierTokenPool.startToken]/[MarauderPool.placeOnBirthCanal]) and retained across every
 * move, Zone entry/exit, and Staging Pile transition until the piece is destroyed, promoted, or
 * otherwise leaves play, at which point the id is retired and never reassigned (see
 * [TokenIdGenerator]).
 *
 * Position is deliberately NOT part of identity — a card targets "this token" once, and the
 * engine looks up wherever it currently is at resolution time (see
 * [com.tiersofexistence.engine.cards.play.TokenLocator]) rather than replaying a stale recorded
 * position. [owner]/[kind]/[tier] are included directly (not just an opaque ordinal) so a
 * [TokenId] alone is enough to route a lookup to the right player's pool without a separate
 * search — and because, per the fungible-pool model, "which Tier/kind/owner" is itself part of
 * what makes two tokens distinguishable, not just an incidental detail.
 *
 * Does NOT model identity across destruction and later reconstruction: a destroyed token's
 * matter returns to the Ion Battery per "matter is neither destroyed nor created," but a new
 * token later started from that Ion Battery gets a fresh, unrelated [TokenId]. The rulebook has
 * no concept of a specific physical piece's continuity beyond "currently in play," so this
 * engine doesn't invent one — identity here means "this currently-existing game piece," nothing
 * more.
 */
data class TokenId(
    val owner: PlayerColor,
    val kind: TokenKind,
    val tier: TierLevel,
    val ordinal: Long,
)

/**
 * Assigns fresh, never-reused [TokenId]s. A single process-wide counter is simplest and
 * sufficient — uniqueness only needs to hold within one running game, and a monotonically
 * increasing `Long` never wraps in practice. Tests should compare [TokenId]s by identity/equality
 * (e.g. "the same id survived a move") rather than asserting specific ordinal values, since the
 * exact numbers depend on how many tokens earlier tests in the same process have already created.
 */
object TokenIdGenerator {
    private var nextOrdinal: Long = 0

    fun next(owner: PlayerColor, kind: TokenKind, tier: TierLevel): TokenId =
        TokenId(owner, kind, tier, nextOrdinal++)
}
