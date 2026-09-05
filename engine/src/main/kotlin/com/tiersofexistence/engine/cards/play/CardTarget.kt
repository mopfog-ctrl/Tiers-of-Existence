package com.tiersofexistence.engine.cards.play

import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.rules.TokenKind

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
    /** A specific in-play token (Tier token or Marauder) belonging to [owner], found on the board. */
    data class Token(val owner: PlayerColor, val kind: TokenKind, val tier: TierLevel, val position: Int) : CardTarget()

    /** A token waiting in a Staging Pile — no board position, since pool contents are fungible
     * (see [com.tiersofexistence.engine.state.TierTokenPool]'s class doc). */
    data class StagingPileToken(val owner: PlayerColor, val tier: TierLevel) : CardTarget()

    /** A Tier token currently inside a Zone of Protection (see
     * [com.tiersofexistence.engine.state.TierTokenPool.zoneResidents]) — never a Marauder,
     * which can never be in a Zone. Kept distinct from [Token] since a Zone resident has no
     * main-loop position. */
    data class ZoneResidentToken(val owner: PlayerColor, val tier: TierLevel, val zoneNumber: Int) : CardTarget()

    /** A whole Tier, chosen without picking a specific token on it (e.g. which Tier to build a
     * Marauder on, which Tier Plasma Burst/Phase Control acts on). */
    data class TierChoice(val tier: TierLevel) : CardTarget()

    /** Another player, not a token (Cleansing's "choose an opponent"). */
    data class PlayerChoice(val color: PlayerColor) : CardTarget()
}
