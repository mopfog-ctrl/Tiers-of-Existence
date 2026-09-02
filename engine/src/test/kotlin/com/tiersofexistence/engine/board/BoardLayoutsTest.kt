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
    fun `fourth tier Zone of Protection is numbered Fifth and has a single Marauder Transport slot`() {
        val board = BoardLayouts.fourthTier()
        val zone = board.squares.single { it.type == SquareType.ZONE_OF_PROTECTION }
        assertEquals(5, zone.magnitude)

        val protectionZone = board.protectionZones.single { it.number == 5 }
        assertEquals(listOf(SquareType.MARAUDER_TRANSPORT), protectionZone.squares)
    }

    @Test
    fun `second tier loop starts at Birth Canal and has 22 squares`() {
        val board = BoardLayouts.secondTier()
        assertEquals(TierLevel.SECOND, board.tier)
        assertEquals(22, board.size)
        assertEquals(SquareType.BIRTH_CANAL, board.squareAt(0).type)
    }

    @Test
    fun `second tier has Warp, Zone 3 entry, and Hyperthrust magnitudes`() {
        val board = BoardLayouts.secondTier()
        val warp = board.squares.single { it.type == SquareType.WARP }
        assertEquals(7, warp.magnitude)

        val zone = board.squares.single { it.type == SquareType.ZONE_OF_PROTECTION }
        assertEquals(3, zone.magnitude)
        assertEquals(listOf(3), board.protectionZones.map { it.number })

        val hyperthrust = board.squares.single { it.type == SquareType.HYPERTHRUST }
        assertEquals(7, hyperthrust.magnitude)
    }

    @Test
    fun `second tier has a Wormhole of Construction directly on the main loop`() {
        val board = BoardLayouts.secondTier()
        assertEquals(1, board.squares.count { it.type == SquareType.WORMHOLE_OF_CONSTRUCTION })
    }

    @Test
    fun `second tier Zone 3 is plain, nebula, protection rejected, plain`() {
        val board = BoardLayouts.secondTier()
        val zone3 = board.protectionZones.single { it.number == 3 }
        assertEquals(
            listOf(SquareType.PLAIN, SquareType.NEBULA, SquareType.PROTECTION_REJECTED, SquareType.PLAIN),
            zone3.squares,
        )
    }

    @Test
    fun `third tier loop starts at Birth Canal and has 14 squares`() {
        val board = BoardLayouts.thirdTier()
        assertEquals(TierLevel.THIRD, board.tier)
        assertEquals(14, board.size)
        assertEquals(SquareType.BIRTH_CANAL, board.squareAt(0).type)
    }

    @Test
    fun `third tier has Hyperthrust and Zone 4 entry magnitudes`() {
        val board = BoardLayouts.thirdTier()
        val hyperthrust = board.squares.single { it.type == SquareType.HYPERTHRUST }
        assertEquals(9, hyperthrust.magnitude)

        val zone = board.squares.single { it.type == SquareType.ZONE_OF_PROTECTION }
        assertEquals(4, zone.magnitude)
        assertEquals(listOf(4), board.protectionZones.map { it.number })
    }

    @Test
    fun `third tier Zone 4 guess is plain then Fate Harvest, not yet user-confirmed`() {
        val board = BoardLayouts.thirdTier()
        val zone4 = board.protectionZones.single { it.number == 4 }
        assertEquals(listOf(SquareType.PLAIN, SquareType.FATE_HARVEST), zone4.squares)
    }

    @Test
    fun `current uses the real digitized board for every Tier`() {
        val boards = BoardLayouts.current()
        assertEquals(BoardLayouts.firstTier(), boards[TierLevel.FIRST])
        assertEquals(BoardLayouts.secondTier(), boards[TierLevel.SECOND])
        assertEquals(BoardLayouts.thirdTier(), boards[TierLevel.THIRD])
        assertEquals(BoardLayouts.fourthTier(), boards[TierLevel.FOURTH])
    }

    @Test
    fun `every Tier board still starts with Birth Canal`() {
        TierLevel.entries.forEach { tier ->
            assertEquals(SquareType.BIRTH_CANAL, BoardLayouts.current().getValue(tier).squareAt(0).type)
        }
    }
}
