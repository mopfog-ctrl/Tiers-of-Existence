package com.tiersofexistence.engine.board

import com.tiersofexistence.engine.model.TierLevel

/**
 * TODO(board-art): The rulebook explains what each square *does* (see [SquareType]) but
 * never lists the actual square-by-square sequence of any of the four physical boards —
 * that only exists in the printed board artwork, which we don't have yet.
 *
 * [placeholder] below is NOT a real board. It exists only so square-effect logic (landing
 * on a Nebula, Fate Harvest, Wormhole of Construction, etc.) has something to run against
 * in tests before the real layouts are digitized. Replace this with real per-Tier square
 * sequences (ideally loaded from data, e.g. JSON) once we have photos/measurements of the
 * four game boards.
 */
object BoardLayouts {

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
}
