package com.tiersofexistence.engine.board

import com.tiersofexistence.engine.model.TierLevel

/**
 * Real per-Tier square sequences, digitized from photos of the four physical boards.
 *
 * Status per Tier — see the doc comment on each function below for details:
 * - [firstTier] and [fourthTier]: digitized from board photos, medium-high confidence.
 * - 2nd and 3rd Tier: still [placeholder] — the provided photos were too dense/cluttered
 *   (nested interior bands, staging piles overlapping the loop) to transcribe with
 *   confidence. Needs clearer/cropped photos of those two boards, or in-person
 *   verification, before digitizing for real.
 *
 * Two structural decisions made while digitizing, both worth the user confirming against
 * the physical boards rather than trusting blindly:
 *
 * 1. **Loop = outer perimeter only.** Each board's interior (Nebula Staging Piles, the
 *    Phase Clock/Turn Indicator, the Wormhole of Construction icon) is drawn overlapping
 *    the middle of the board, not on the ring of squares tokens actually step through.
 *    Landing on a [SquareType.NEBULA] square is what moves a token into the (off-board)
 *    staging pile pool — the pile itself isn't a square on the loop. Same reasoning
 *    applies to the Phase Clock/Turn Indicator (round-tracking props, not squares).
 *
 * 2. **Wormhole of Construction placement is an open gap, not guessed.** The rulebook and
 *    [SquareType.WORMHOLE_OF_CONSTRUCTION] say landing on it promotes a token to the next
 *    Tier, and the 1st Tier photo shows a "IF YOU LAND HERE ENTER THE WORMHOLE OF
 *    CONSTRUCTION" card — but that card, and the Wormhole circle it points to, sit in the
 *    board's interior near the staging piles, not touching the outer loop at any point we
 *    could confidently identify. Rather than invent a guessed position on the loop, both
 *    Tier boards below omit it entirely; promotion via Wormhole of Construction is still an
 *    unimplemented gap. Don't add it to a board layout without confirming where on the
 *    loop it's actually reached from.
 *
 * Zone of Protection squares below use [Square.magnitude] for the zone number; the
 * numbering escalates by Tier per the photos (1st Tier has Zones 1 and 2, 2nd Tier has
 * Zone 3, 3rd Tier has Zone 4, 4th Tier has Zone 5) rather than restarting each board.
 * Each photo shows a small "shaded" run of extra squares behind the zone's entry square
 * (e.g. a strip of star icons) that we're treating as flavor art for "this area is
 * protected," not extra squares a token individually steps through — same open-to-being-
 * wrong caveat as point 2 above.
 */
object BoardLayouts {

    /**
     * 1st Tier, digitized from the board photo. 28 squares, outer perimeter only (see
     * class doc). Confidence: medium-high — read directly off the photo, but not verified
     * against the physical board, so treat as a first draft to spot-check rather than
     * ground truth.
     */
    fun firstTier(): TierBoard {
        val squares = buildList {
            add(SquareType.BIRTH_CANAL to null) // also "Start" on the 1st Tier
            add(SquareType.TIME_WRINKLE to "Go again")
            add(SquareType.FATE_HARVEST to null)
            add(SquareType.ABYSS to null)
            add(SquareType.MARAUDER_TRANSPORT to null)
            add(SquareType.TIME_WRINKLE to "Lose next turn on this Tier")
            add(SquareType.FATE_HARVEST to null)
            add(SquareType.NEBULA to null)
            add(SquareType.MARAUDER_SENSOR to null)
            add(SquareType.FATE_HARVEST to null)
            add(SquareType.ZONE_OF_PROTECTION to null) // "if you land here, enter Second Zone of Protection"
            add(SquareType.ABYSS to null)
            add(SquareType.ZONE_OF_PROTECTION to null) // "if you land here, enter First Zone of Protection"
            add(SquareType.FATE_HARVEST to null)
            add(SquareType.WARP to null) // "Warp 5 spaces"
            add(SquareType.FATE_HARVEST to null)
            add(SquareType.TIME_WRINKLE to "Go again")
            add(SquareType.ABYSS to null)
            add(SquareType.MARAUDER_TRANSPORT to null)
            add(SquareType.FATE_HARVEST to null)
            add(SquareType.HYPERTHRUST to null) // "Hyperthrust! Move 6 spaces, any tokens you pass are destroyed"
            add(SquareType.REPRIEVE to null)
            add(SquareType.NEBULA to null)
            add(SquareType.MARAUDER_SENSOR to null)
            add(SquareType.FATE_HARVEST to null)
            add(SquareType.ABYSS to null)
            add(SquareType.MARAUDER_TRANSPORT to null)
            add(SquareType.NEBULA to null)
        }
        val magnitudes = mapOf(10 to 2, 12 to 1, 14 to 5, 20 to 6)
        return TierBoard(
            TierLevel.FIRST,
            squares.mapIndexed { index, (type, note) ->
                Square(index, type, magnitude = magnitudes[index], note = note)
            },
        )
    }

    /**
     * 4th Tier, digitized from the board photo. Only 8 squares — this Tier's board is a
     * small standalone loop, not a full-perimeter board like the others. Confidence:
     * medium-high, same caveat as [firstTier]. The 5 star-icon squares behind the Zone 5
     * entry are treated as the zone's flavor art, not extra loop squares (see class doc).
     */
    fun fourthTier(): TierBoard {
        val squares = listOf(
            Square(0, SquareType.BIRTH_CANAL),
            Square(1, SquareType.MARAUDER_SENSOR),
            Square(2, SquareType.ZONE_OF_PROTECTION, magnitude = 5), // "enter Fifth Zone of Protection"
            Square(3, SquareType.MARAUDER_TRANSPORT),
            Square(4, SquareType.INFERNAL_ABYSS),
            Square(5, SquareType.MARAUDER_SENSOR),
            Square(6, SquareType.VORTEX_OF_REGRESSION),
            Square(7, SquareType.YOU_WIN),
        )
        return TierBoard(TierLevel.FOURTH, squares)
    }

    /**
     * TODO(board-art): still not digitized — see class doc. [placeholder] below is NOT a
     * real board; it exists only so square-effect logic has something to run against in
     * tests until real 2nd/3rd Tier photos (clear enough to transcribe) are available.
     */
    fun placeholder(tier: TierLevel): TierBoard {
        val squares = buildList {
            add(SquareType.BIRTH_CANAL)
            add(SquareType.PLAIN)
            add(SquareType.FATE_HARVEST)
            add(SquareType.PLAIN)
            add(SquareType.NEBULA)
            add(SquareType.PLAIN)
            add(SquareType.ZONE_OF_PROTECTION)
            add(SquareType.PLAIN)
            if (tier == TierLevel.FIRST || tier == TierLevel.SECOND) {
                add(SquareType.WORMHOLE_OF_CONSTRUCTION)
            } else {
                add(SquareType.PLAIN)
            }
            add(SquareType.MARAUDER_TRANSPORT)
            add(SquareType.PLAIN)
            add(SquareType.MARAUDER_SENSOR)
            add(SquareType.PLAIN)
            add(SquareType.MARAUDER_CONSTRUCTION_FACILITY)
            add(SquareType.PLAIN)
            if (tier == TierLevel.FOURTH) {
                add(SquareType.INFERNAL_ABYSS)
                add(SquareType.YOU_WIN)
            } else {
                add(SquareType.ABYSS)
            }
            add(SquareType.PLAIN)
            add(SquareType.REPRIEVE)
            add(SquareType.PLAIN)
            add(SquareType.VORTEX_OF_REGRESSION)
            add(SquareType.HYPERTHRUST)
            add(SquareType.WARP)
            add(SquareType.TIME_WRINKLE)
        }
        return TierBoard(tier, squares.mapIndexed { index, type -> Square(index, type) })
    }

    fun allPlaceholders(): Map<TierLevel, TierBoard> =
        TierLevel.entries.associateWith { placeholder(it) }

    /**
     * The best board data currently available for each Tier: real digitized boards for the
     * 1st and 4th, [placeholder] for the 2nd and 3rd until those are digitized too. This is
     * what [com.tiersofexistence.engine.state.GameState] defaults to.
     */
    fun current(): Map<TierLevel, TierBoard> = mapOf(
        TierLevel.FIRST to firstTier(),
        TierLevel.SECOND to placeholder(TierLevel.SECOND),
        TierLevel.THIRD to placeholder(TierLevel.THIRD),
        TierLevel.FOURTH to fourthTier(),
    )
}
