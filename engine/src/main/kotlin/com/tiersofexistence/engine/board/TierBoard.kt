package com.tiersofexistence.engine.board

import com.tiersofexistence.engine.model.TierLevel

/**
 * One square on a Tier board's track. Index 0 is always the Birth Canal / Start square.
 *
 * @param magnitude Board-specific number printed on the square, where the type needs one:
 *   spaces to move for [SquareType.WARP]/[SquareType.HYPERTHRUST], or the Zone number
 *   (1st-5th, escalating by Tier) for [SquareType.ZONE_OF_PROTECTION].
 * @param note Verbatim board text for effects not yet modeled as their own [SquareType]
 *   (e.g. which of the several printed Time Wrinkle variants this is). Prefer promoting
 *   a recurring [note] to a real [SquareType]/field once its rule is cross-checked.
 */
data class Square(
    val index: Int,
    val type: SquareType,
    val magnitude: Int? = null,
    val note: String? = null,
)

/**
 * A Zone of Protection: "shaded spaces through which a Marauder may not pass" (rulebook,
 * Game Board Rules) — real board real estate off to the side of the main loop, not just
 * flavor text on the entry square. Entered by landing on the main loop's numbered
 * [SquareType.ZONE_OF_PROTECTION] square (see [Square.magnitude]).
 *
 * [squares] lists the zone's own slots in board order (usually [SquareType.PLAIN], i.e.
 * "empty spaces" with no effect beyond the protection itself) — empty where a photo hasn't
 * confirmed the count/contents yet. **Confirmed by the user (correcting an earlier, wrong
 * assumption baked in here): a Zone IS a dice-driven sub-path.** A resident token can be
 * chosen and moved during an ordinary Tier-Phase turn (or by a card, e.g. Galactic
 * Roundabout) by rolling/adding spaces to its own position *within* [squares] — see
 * [com.tiersofexistence.engine.state.TierTokenPool.zonePositionOf]/
 * [com.tiersofexistence.engine.rules.TurnEngine.moveZoneToken] for the actual movement
 * mechanic: moving past the end of [squares] exits the Zone, continuing the leftover spaces
 * from this Zone's own main-loop entry square. Notably, a Zone can itself contain a
 * [SquareType.WORMHOLE_OF_CONSTRUCTION] slot (confirmed for the 1st Tier's Zone 2) — the
 * rulebook's "square that says to move to the Wormhole of Construction" turned out to live
 * inside a Zone of Protection, not on the main loop.
 */
data class ProtectionZone(val number: Int, val squares: List<SquareType> = emptyList())

/**
 * The full loop of squares for one Tier board, plus its off-loop Zones of Protection. Main
 * loop movement is clockwise and wraps from the last index back to 0.
 */
data class TierBoard(
    val tier: TierLevel,
    val squares: List<Square>,
    val protectionZones: List<ProtectionZone> = emptyList(),
) {
    init {
        require(squares.isNotEmpty()) { "Tier board for $tier must have at least one square" }
        require(squares[0].type == SquareType.BIRTH_CANAL) {
            "Tier board for $tier must start with a Birth Canal square"
        }
    }

    val size: Int get() = squares.size

    fun squareAt(index: Int): Square = squares[((index % size) + size) % size]

    /**
     * Squares strictly between [from] and [to] (exclusive of both), walking clockwise.
     * Used to resolve "destroy anything you pass" effects (Marauders, Hyperthrust, Last Gasp).
     */
    fun squaresPassedBetween(from: Int, to: Int): List<Square> {
        val passed = mutableListOf<Square>()
        var i = (from + 1) % size
        while (i != ((to % size) + size) % size) {
            passed += squareAt(i)
            i = (i + 1) % size
        }
        return passed
    }

    /**
     * The next [SquareType.ZONE_OF_PROTECTION] entry square walking clockwise from
     * [fromPosition] (exclusive of [fromPosition] itself — "next" always means strictly ahead),
     * wrapping the loop if needed. Used by Circulate. Null if this Tier's board has no Zone of
     * Protection entry square at all (none of the confirmed board layouts hit this, but the
     * query stays honest rather than assuming).
     */
    fun nextZoneEntry(fromPosition: Int): Square? {
        for (offset in 1..size) {
            val candidate = squareAt(fromPosition + offset)
            if (candidate.type == SquareType.ZONE_OF_PROTECTION) return candidate
        }
        return null
    }

    /** This Tier's [ProtectionZone] numbered [number]. Throws if this Tier has no such Zone —
     * an internal-consistency check (callers only ever reach here for a Zone a token is
     * actually resident in), not a player-facing legality check. */
    fun protectionZone(number: Int): ProtectionZone =
        protectionZones.firstOrNull { it.number == number }
            ?: error("No Zone of Protection $number on $tier")

    /** The main-loop index of Zone [number]'s own numbered entry square (see
     * [SquareType.ZONE_OF_PROTECTION]/[Square.magnitude]) — where a token re-enters the main
     * loop after moving past the end of that Zone's own [ProtectionZone.squares]. Same
     * throws-if-absent contract as [protectionZone]. */
    fun zoneEntryIndex(number: Int): Int =
        squares.firstOrNull { it.type == SquareType.ZONE_OF_PROTECTION && it.magnitude == number }?.index
            ?: error("No Zone of Protection $number entry square on $tier")
}
