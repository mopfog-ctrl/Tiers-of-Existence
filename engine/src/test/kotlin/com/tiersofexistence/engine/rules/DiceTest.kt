package com.tiersofexistence.engine.rules

import com.tiersofexistence.engine.model.TierLevel
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiceTest {

    @Test
    fun `rollBlack and rollPurple are always 1 to 6`() {
        val random = Random(1)
        repeat(200) {
            assertTrue(Dice.rollBlack(random) in 1..6)
            assertTrue(Dice.rollPurple(random) in 1..6)
        }
    }

    @Test
    fun `Marauder Phase rolls purple only`() {
        val random = Random(42)
        val expected = Random(42).let { Dice.rollPurple(it) }
        val actual = Dice.rollForPhase(Phase.Marauder, Random(42))
        assertEquals(expected, actual)
        assertTrue(actual in 1..6)
    }

    @Test
    fun `Tier Phase rolls the pair, summed`() {
        val random = Random(7)
        val expected = Random(7).let { Dice.rollBlack(it) + Dice.rollPurple(it) }
        val actual = Dice.rollForPhase(Phase.Tier(TierLevel.FIRST), Random(7))
        assertEquals(expected, actual)
        assertTrue(actual in 2..12)
    }
}
