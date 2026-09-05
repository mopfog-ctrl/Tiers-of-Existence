package com.tiersofexistence.engine.cards.play

import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.state.TokenId

/**
 * One concrete thing a card's effect can point at. A single [CardPlayRequest] carries a list of
 * these — most cards need exactly one, some need zero (Dwarf Star: fixed effect, no choice),
 * some need more than one (Parallel Phasing: your token + an opponent's token; Graviton Rift: up
 * to four, one per Tier). See `docs/card-mechanics-matrix.md` for which shape each card expects —
 * that shape is enforced per-card as cards are implemented (Phase E/J), not by this type itself.
 *
 * Deliberately separate from [com.tiersofexistence.engine.cards.FateHarvestCard] (the catalog
 * entry describing a card in the abstract) — this describes one live choice made while resolving
 * a specific play.
 */
sealed class CardTarget {
    /**
     * A specific game piece, referenced by its persistent [id] rather than a board position —
     * covers a Tier token OR a Marauder ([TokenId.kind]), whether it's currently on the main
     * loop or inside a Zone of Protection. Resolvers look up where [id] actually is at
     * RESOLUTION time via [TokenLocator], not at the moment the target was chosen — this is what
     * lets a card played earlier in a Precedence chain still find the right piece even after an
     * earlier-resolving response has already moved it (see [TokenLocator]'s class doc).
     *
     * Previously this and a separate `ZoneResidentToken` case both recorded a snapshot (board
     * position, or Zone number) instead of identity, which is exactly the stale-target bug this
     * type was redesigned to fix — there is deliberately only one "a specific token" case now,
     * not two incompatible ones.
     */
    data class Token(val id: TokenId) : CardTarget()

    /** A token waiting in a Staging Pile — no identity, since Staging Pile contents are
     * genuinely fungible (no card ever needs "this specific one" over another — see
     * [com.tiersofexistence.engine.state.TierTokenPool]'s class doc). */
    data class StagingPileToken(val owner: PlayerColor, val tier: TierLevel) : CardTarget()

    /** A whole Tier, chosen without picking a specific token on it (e.g. which Tier to build a
     * Marauder on, which Tier Plasma Burst/Phase Control acts on). */
    data class TierChoice(val tier: TierLevel) : CardTarget()

    /** Another player, not a token (Cleansing's "choose an opponent"). */
    data class PlayerChoice(val color: PlayerColor) : CardTarget()
}

/** The Tier a target refers to, where it has one — null for [CardTarget.PlayerChoice]. */
val CardTarget.tierOrNull: TierLevel?
    get() = when (this) {
        is CardTarget.Token -> id.tier
        is CardTarget.StagingPileToken -> tier
        is CardTarget.TierChoice -> tier
        is CardTarget.PlayerChoice -> null
    }

/** The player who owns a target, where it has an owner distinct from the acting player — null
 * for [CardTarget.TierChoice] and [CardTarget.PlayerChoice] (that IS the owner-like value there,
 * accessed via [CardTarget.PlayerChoice.color] instead). */
val CardTarget.ownerOrNull: PlayerColor?
    get() = when (this) {
        is CardTarget.Token -> id.owner
        is CardTarget.StagingPileToken -> owner
        is CardTarget.TierChoice -> null
        is CardTarget.PlayerChoice -> null
    }
