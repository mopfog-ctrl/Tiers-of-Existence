package com.tiersofexistence.engine.board

/**
 * Every distinct square meaning described in the rulebook's "Game Board Rules" section.
 * Not every type appears on every Tier board (e.g. [YOU_WIN] and [INFERNAL_ABYSS] are
 * 4th-Tier-only; [WORMHOLE_OF_CONSTRUCTION] only appears on the 1st and 2nd Tiers).
 */
enum class SquareType {
    /** Plain space with no special rule text. */
    PLAIN,

    /** Where a Tier token or Marauder is started on this Tier. On the 1st Tier this square is also "Start". */
    BIRTH_CANAL,

    /** Destroys Marauders that land here. Does not affect Tier tokens. */
    ABYSS,

    /** 4th-Tier-only Abyss variant: destroys ALL tokens (Tier tokens and Marauders alike). */
    INFERNAL_ABYSS,

    /** Any token here is safe from Marauders passing it ("Breath of Reprieve"). */
    REPRIEVE,

    /** Draw a Fate Harvest card. */
    FATE_HARVEST,

    /**
     * Moves the token a set number of spaces, destroying any opponent tokens (including
     * Marauders) passed along the way. A token landed on exactly is not destroyed.
     */
    HYPERTHRUST,

    /** Build a Marauder here (only if you don't already have one in play on this Tier). */
    MARAUDER_CONSTRUCTION_FACILITY,

    /** A Marauder landing here moves two more squares. */
    MARAUDER_SENSOR,

    /** A Marauder landing here may move to the Birth Canal of a neighboring Tier. */
    MARAUDER_TRANSPORT,

    /** A Tier token landing here is placed in that Tier's Nebula Staging Pile. */
    NEBULA,

    /** Usually affects turns (exact effect is board-specific; see rulebook Gameboard Rules). */
    TIME_WRINKLE,

    /** A Tier token landing here is sent back to the Birth Canal / Start square. */
    VORTEX_OF_REGRESSION,

    /** Usually affects movement (exact effect is board-specific; see rulebook Gameboard Rules). */
    WARP,

    /** 1st/2nd-Tier-only: landing here promotes the token to the next Tier immediately. */
    WORMHOLE_OF_CONSTRUCTION,

    /** Shaded spaces a Marauder may not enter; tokens here are protected from most effects. */
    ZONE_OF_PROTECTION,

    /**
     * 4th-Tier-only win square: must be landed on exactly to win the game. A roll that
     * would move a token past it (not landing exactly) does not win — that token simply
     * continues past You Win and around the loop again, same as any other square.
     */
    YOU_WIN,

    /**
     * Seen on the 2nd Tier board ("PROTECTION REJECTED! MOVE TWO MORE SPACES"). Exact rule
     * text/trigger not yet cross-checked against the rulebook body — flagged as a gap in
     * [com.tiersofexistence.engine.board.BoardLayouts].
     */
    PROTECTION_REJECTED,
}
