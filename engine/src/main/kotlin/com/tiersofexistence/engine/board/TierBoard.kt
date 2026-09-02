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
 * Deliberately NOT modeled as a dice-driven sub-path (no [List] of [Square]): the rulebook
 * never describes moving square-by-square once inside a Zone, only entering and later
 * leaving it (e.g. via specific Fate Harvest cards, rule #12). [capacity] is the number of
 * shaded slots visible on the board photo, where that could be counted with confidence;
 * null where it couldn't — don't invent a number here without confirming it against the
 * physical board.
 */
data class ProtectionZone(val number: Int, val capacity: Int? = null)

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
}
