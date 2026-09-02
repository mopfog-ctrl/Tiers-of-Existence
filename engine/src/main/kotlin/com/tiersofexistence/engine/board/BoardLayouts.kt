package com.tiersofexistence.engine.board

import com.tiersofexistence.engine.model.TierLevel

/**
 * Real per-Tier square sequences, digitized from photos of the four physical boards.
 *
 * Status per Tier — see the doc comment on each function below for details. All four
 * Tiers now have real digitized boards:
 * - [firstTier], [fourthTier], [thirdTier]: read off board photos (with corrections from
 *   the user after the fact for the first two), medium-high confidence.
 * - [secondTier]: dictated square-by-square by the user rather than read off the photo —
 *   high confidence.
 * Some Zone of Protection contents are still unconfirmed guesses — see each function's doc.
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
 *    as a dice-driven sub-path.
 *
 * 3. **Wormhole of Construction lives inside a Zone of Protection, not on the main loop.**
 *    Initially assumed to be a plain loop square per the rulebook's "the square that says
 *    to move to the Wormhole of Construction," but the user confirmed (1st Tier) it's
 *    actually one of Zone 2's own 7 slots — 3 plain spaces, the Wormhole in the middle
 *    (4th), then 3 more plain spaces. Zone 1 has no Wormhole: just 7 plain spaces. See
 *    [firstTier]'s `protectionZones`.
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
            add(SquareType.NEBULA to null)
            add(SquareType.FATE_HARVEST to null)
            add(SquareType.TIME_WRINKLE to "Go again")
            add(SquareType.ABYSS to null)
            add(SquareType.MARAUDER_TRANSPORT to null)
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
            protectionZones = listOf(
                ProtectionZone(number = 1, squares = List(7) { SquareType.PLAIN }),
                ProtectionZone(
                    number = 2,
                    squares = listOf(
                        SquareType.PLAIN,
                        SquareType.PLAIN,
                        SquareType.PLAIN,
                        SquareType.WORMHOLE_OF_CONSTRUCTION,
                        SquareType.PLAIN,
                        SquareType.PLAIN,
                        SquareType.PLAIN,
                    ),
                ),
            ),
        )
    }

    /**
     * 4th Tier, digitized from the board photo. Only 8 squares — this Tier's board is a
     * small standalone loop, not a full-perimeter board like the others. Confidence:
     * medium-high, same caveat as [firstTier]. Corrected per the user: the star icons
     * behind the Zone 5 entry are decorative border art, not one square each (same mistake
     * as the purple background pattern elsewhere) — Zone 5 only has room for one real
     * square, and it's a Marauder Transport, still protected while inside the Zone.
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
        return TierBoard(
            TierLevel.FOURTH,
            squares,
            // Confirmed by the user: a Marauder Transport inside a Zone of Protection is a
            // specific exception to "Marauders cannot enter the Zone of Protection"
            // (rulebook, Marauders section) — a Marauder may land on this square, but still
            // can't affect any other token still sitting in the Zone.
            protectionZones = listOf(ProtectionZone(number = 5, squares = listOf(SquareType.MARAUDER_TRANSPORT))),
        )
    }

    /**
     * 2nd Tier, dictated square-by-square by the user (not read off the photo, unlike
     * [firstTier]/[fourthTier]) — high confidence. 22 squares, outer perimeter only.
     * Unlike the 1st Tier, the Wormhole of Construction here is a plain main-loop square
     * (index 13), not tucked inside a Zone of Protection.
     */
    fun secondTier(): TierBoard {
        val squares = buildList {
            add(SquareType.BIRTH_CANAL to null)
            add(SquareType.WARP to null) // "Warp 7 spaces"
            add(SquareType.ABYSS to null)
            add(SquareType.ZONE_OF_PROTECTION to null) // "enter Third Zone of Protection"
            add(SquareType.MARAUDER_CONSTRUCTION_FACILITY to null)
            add(SquareType.FATE_HARVEST to null)
            add(SquareType.MARAUDER_TRANSPORT to null)
            add(SquareType.TIME_WRINKLE to "Take an extra turn, First Tier")
            add(SquareType.REPRIEVE to null)
            add(SquareType.FATE_HARVEST to null)
            add(SquareType.NEBULA to null)
            add(SquareType.MARAUDER_SENSOR to null)
            add(SquareType.HYPERTHRUST to null) // "Move 7 spaces, any tokens you pass are destroyed"
            add(SquareType.WORMHOLE_OF_CONSTRUCTION to null)
            add(SquareType.MARAUDER_TRANSPORT to null)
            add(SquareType.ABYSS to null)
            add(SquareType.FATE_HARVEST to null)
            add(SquareType.NEBULA to null)
            add(SquareType.TIME_WRINKLE to "Go again")
            add(SquareType.MARAUDER_CONSTRUCTION_FACILITY to null)
            add(SquareType.FATE_HARVEST to null)
            add(SquareType.NEBULA to null)
        }
        val magnitudes = mapOf(1 to 7, 3 to 3, 12 to 7)
        return TierBoard(
            TierLevel.SECOND,
            squares.mapIndexed { index, (type, note) ->
                Square(index, type, magnitude = magnitudes[index], note = note)
            },
            // Entered at square 4 (confirmed, above) — the 4th of the 7 squares from Birth
            // Canal to the bottom-right corner. The user also described the zone's far end
            // as exiting "in the middle on the other side" (roughly opposite square, on the
            // left side's run) — noted here, not yet modeled as a distinct square since
            // ProtectionZone deliberately isn't a dice-driven sub-path (see class doc).
            protectionZones = listOf(
                ProtectionZone(
                    number = 3,
                    squares = listOf(
                        SquareType.PLAIN,
                        SquareType.NEBULA,
                        SquareType.PROTECTION_REJECTED, // "Protection Rejected! Move two spaces"
                        SquareType.PLAIN,
                    ),
                ),
            ),
        )
    }

    /**
     * 3rd Tier, read directly off the board photo (unlike [secondTier]'s dictation) — the
     * user says this photo should be easy to parse, and its layout is a clean scaled-down
     * version of [firstTier]/[secondTier]'s pattern (Birth Canal top-left this time,
     * Hatchery also top-left; clockwise: right along the top row, down the right column,
     * left along the mirrored bottom row, up the left column). Confidence: medium-high on
     * the 14-square main loop.
     *
     * Zone 4's own contents are a guess, NOT confirmed by the user yet: the photo shows a
     * "Fourth Zone of Protection" icon directly below the entry square, then a Fate Harvest
     * square below that, before the interior column rejoins the main loop at square 10
     * (Marauder Transport, already on the main loop — not zone content). Guessed as 2 slots:
     * a plain protection square (the icon itself) and Fate Harvest. Needs confirmation.
     */
    fun thirdTier(): TierBoard {
        val squares = buildList {
            add(SquareType.BIRTH_CANAL to null)
            add(SquareType.HYPERTHRUST to null) // "Move 9 spaces, any tokens you pass are destroyed"
            add(SquareType.ZONE_OF_PROTECTION to null) // "if you land here, enter Fourth Zone of Protection"
            add(SquareType.REPRIEVE to null)
            add(SquareType.MARAUDER_TRANSPORT to null)
            add(SquareType.ABYSS to null)
            add(SquareType.NEBULA to null)
            add(SquareType.MARAUDER_CONSTRUCTION_FACILITY to null)
            add(SquareType.MARAUDER_SENSOR to null)
            add(SquareType.MARAUDER_TRANSPORT to null)
            add(SquareType.ABYSS to null)
            add(SquareType.VORTEX_OF_REGRESSION to null)
            add(SquareType.MARAUDER_CONSTRUCTION_FACILITY to null)
            add(SquareType.NEBULA to null)
        }
        val magnitudes = mapOf(1 to 9, 2 to 4)
        return TierBoard(
            TierLevel.THIRD,
            squares.mapIndexed { index, (type, note) ->
                Square(index, type, magnitude = magnitudes[index], note = note)
            },
            protectionZones = listOf(
                ProtectionZone(number = 4, squares = listOf(SquareType.PLAIN, SquareType.FATE_HARVEST)),
            ),
        )
    }

    /**
     * All four Tiers now have real digitized boards ([firstTier], [secondTier],
     * [thirdTier], [fourthTier]) — this generic, NOT-a-real-board layout is kept only as a
     * lightweight fallback/test fixture where a test wants a board without depending on the
     * real per-Tier data (e.g. exercising square-effect logic generically).
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
     * The real digitized board for each Tier. This is what
     * [com.tiersofexistence.engine.state.GameState] defaults to.
     */
    fun current(): Map<TierLevel, TierBoard> = mapOf(
        TierLevel.FIRST to firstTier(),
        TierLevel.SECOND to secondTier(),
        TierLevel.THIRD to thirdTier(),
        TierLevel.FOURTH to fourthTier(),
    )
}
