package com.tiersofexistence.engine.board

import com.tiersofexistence.engine.model.TierLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoardLayoutsTest {

    @Test
    fun `first tier loop starts at Birth Canal and has 28 squares`() {
        val board = BoardLayouts.firstTier()
        assertEquals(TierLevel.FIRST, board.tier)
        assertEquals(28, board.size)
        assertEquals(SquareType.BIRTH_CANAL, board.squareAt(0).type)
    }

    @Test
    fun `first tier has both Zone of Protection entries, Warp and Hyperthrust magnitudes`() {
        val board = BoardLayouts.firstTier()
        val zones = board.squares.filter { it.type == SquareType.ZONE_OF_PROTECTION }.map { it.magnitude }
        assertEquals(listOf(2, 1), zones)

        val warp = board.squares.single { it.type == SquareType.WARP }
        assertEquals(5, warp.magnitude)

        val hyperthrust = board.squares.single { it.type == SquareType.HYPERTHRUST }
        assertEquals(6, hyperthrust.magnitude)
    }

    @Test
    fun `first tier has no Wormhole of Construction on the main loop`() {
        val board = BoardLayouts.firstTier()
        assertTrue(board.squares.none { it.type == SquareType.WORMHOLE_OF_CONSTRUCTION })
    }

    @Test
    fun `first tier Zone 2 has the Wormhole of Construction as its middle slot, Zone 1 is all plain`() {
        val board = BoardLayouts.firstTier()
        val zone1 = board.protectionZones.single { it.number == 1 }
        val zone2 = board.protectionZones.single { it.number == 2 }

        assertEquals(List(7) { SquareType.PLAIN }, zone1.squares)
        assertEquals(7, zone2.squares.size)
        assertEquals(SquareType.WORMHOLE_OF_CONSTRUCTION, zone2.squares[3])
        assertTrue(zone2.squares.filterIndexed { i, _ -> i != 3 }.all { it == SquareType.PLAIN })
    }

    @Test
    fun `fourth tier loop starts at Birth Canal, ends at You Win, and has 8 squares`() {
        val board = BoardLayouts.fourthTier()
        assertEquals(TierLevel.FOURTH, board.tier)
        assertEquals(8, board.size)
        assertEquals(SquareType.BIRTH_CANAL, board.squareAt(0).type)
        assertEquals(SquareType.YOU_WIN, board.squareAt(7).type)
        assertEquals(SquareType.INFERNAL_ABYSS, board.squares.single { it.type == SquareType.INFERNAL_ABYSS }.type)
    }

    @Test
    fun `fourth tier Zone of Protection is numbered Fifth, has 5 plain slots, no Wormhole`() {
        val board = BoardLayouts.fourthTier()
        val zone = board.squares.single { it.type == SquareType.ZONE_OF_PROTECTION }
        assertEquals(5, zone.magnitude)

        val protectionZone = board.protectionZones.single { it.number == 5 }
        assertEquals(List(5) { SquareType.PLAIN }, protectionZone.squares)
    }

    @Test
    fun `current uses real boards for first and fourth Tier, placeholders for second and third`() {
        val boards = BoardLayouts.current()
        assertEquals(BoardLayouts.firstTier(), boards[TierLevel.FIRST])
        assertEquals(BoardLayouts.fourthTier(), boards[TierLevel.FOURTH])
        assertEquals(BoardLayouts.placeholder(TierLevel.SECOND), boards[TierLevel.SECOND])
        assertEquals(BoardLayouts.placeholder(TierLevel.THIRD), boards[TierLevel.THIRD])
    }

    @Test
    fun `every Tier board still starts with Birth Canal`() {
        TierLevel.entries.forEach { tier ->
            assertEquals(SquareType.BIRTH_CANAL, BoardLayouts.current().getValue(tier).squareAt(0).type)
        }
    }
}
