package com.tiersofexistence.engine.board

import com.tiersofexistence.engine.model.TierLevel

/**
 * Real per-Tier square sequences, digitized from photos of the four physical boards.
 *
 * Status per Tier — see the doc comment on each function below for details:
 * - [firstTier] and [fourthTier]: digitized from board photos, medium-high confidence on
 *   the main loop's square-by-square order.
 * - 2nd and 3rd Tier: still [placeholder]. The user says these two photos should be
 *   perfectly legible, so this is on us to get right, not a photo-quality problem — but a
 *   flat, non-zoomable read of the photos wasn't reliable enough to transcribe with
 *   confidence, especially since (per the user) the interior isn't purely decorative the
 *   way we first assumed (see point 2 below), so getting the loop's shape wrong here is a
 *   real risk, not just a square-content nit. Fastest unblock: have the user dictate the
 *   square order directly (they're reading the physical board, we're reading a photo).
 *
 * Structural decisions made while digitizing, worth the user confirming against the
 * physical boards:
 *
 * 1. **Loop = outer perimeter only, for the main loop.** Nebula Staging Piles and the
 *    Phase Clock/Turn Indicator are drawn overlapping the middle of the board, not on the
 *    ring of squares tokens actually step through. Landing on a [SquareType.NEBULA] square
 *    is what moves a token into the (off-board) staging pile pool — the pile itself isn't
 *    a square on the loop.
 *
 * 2. **Zone of Protection is real off-loop squares, not flavor text.** Corrected after
 *    the user pointed out the "shaded strip" in the photos is the actual Zone of
 *    Protection, sitting outside the main loop — matching the rulebook's "shaded spaces
 *    through which a Marauder may not pass" (plural). See [ProtectionZone]: modeled as a
 *    pocket off the main loop's numbered [SquareType.ZONE_OF_PROTECTION] entry square, not
 *    as a dice-driven sub-path. [ProtectionZone.capacity] is left `null` below — we could
 *    see the strip existed but not confidently count its squares from the photo.
 *
 * 3. **Wormhole of Construction placement is still an open gap.** The rulebook says
 *    landing on "the square that says to move to the Wormhole of Construction" promotes a
 *    token immediately (1st/2nd Tier only) — a single square directly on the loop, not a
 *    separate pocket like the Zone of Protection. The user confirmed there's an arrow for
 *    it on the 1st Tier photo, but we couldn't confidently map that arrow to a specific
 *    outer-loop square/index from the photo — several of the [firstTier] squares below are
 *    a generic [SquareType.FATE_HARVEST] guess and one of those may actually be this
 *    square. Needs the user to point to which loop square it is (e.g. "the one between
 *    Hyperthrust and Reprieve") rather than us guessing further.
 *
 * Zone of Protection numbering escalates by Tier per the photos (1st Tier has Zones 1 and
 * 2, 2nd Tier has Zone 3, 3rd Tier has Zone 4, 4th Tier has Zone 5) rather than restarting
 * each board; [Square.magnitude] on a `ZONE_OF_PROTECTION` square holds the zone number.
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
            add(SquareType.FATE_HARVEST to null)
            add(SquareType.ZONE_OF_PROTECTION to null) // "if you land here, enter First Zone of Protection"
            add(SquareType.WARP to null) // "Warp 5 spaces"
            add(SquareType.MARAUDER_SENSOR to null)
            add(SquareType.NEBULA to null)
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
        val magnitudes = mapOf(10 to 2, 13 to 1, 14 to 5, 20 to 6)
        return TierBoard(
            TierLevel.FIRST,
            squares.mapIndexed { index, (type, note) ->
                Square(index, type, magnitude = magnitudes[index], note = note)
            },
            protectionZones = listOf(ProtectionZone(number = 1), ProtectionZone(number = 2)),
        )
    }

    /**
     * 4th Tier, digitized from the board photo. Only 8 squares — this Tier's board is a
     * small standalone loop, not a full-perimeter board like the others. Confidence:
     * medium-high, same caveat as [firstTier]. The 5 star-icon squares behind the Zone 5
     * entry are its [ProtectionZone] (see class doc point 2) — capacity not confidently
     * countable from the photo, left `null`.
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
        return TierBoard(TierLevel.FOURTH, squares, protectionZones = listOf(ProtectionZone(number = 5)))
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
