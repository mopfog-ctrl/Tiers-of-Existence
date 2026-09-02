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
 * The full loop of squares for one Tier board. Movement is clockwise and wraps from the
 * last index back to 0.
 */
data class TierBoard(val tier: TierLevel, val squares: List<Square>) {
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
