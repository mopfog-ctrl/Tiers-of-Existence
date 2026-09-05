package com.tiersofexistence.engine.state

import com.tiersofexistence.engine.model.PlayerColor.RED
import com.tiersofexistence.engine.model.TierLevel
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class MarauderPoolTest {

    @Test
    fun `only one Marauder per Tier unless bypassed`() {
        val pool = MarauderPool(RED)
        pool.placeOnBirthCanal(TierLevel.FIRST)

        assertThrows<IllegalArgumentException> { pool.placeOnBirthCanal(TierLevel.FIRST) }

        pool.placeOnBirthCanal(TierLevel.FIRST, bypassCap = true)
        assertEquals(2, pool.inPlayCount(TierLevel.FIRST))
    }

    @Test
    fun `Marauder Transport only moves to an adjacent Tier`() {
        val pool = MarauderPool(RED)
        pool.placeOnBirthCanal(TierLevel.SECOND)

        assertThrows<IllegalArgumentException> {
            pool.moveToNeighboringTier(TierLevel.SECOND, TierLevel.FOURTH, position = 0)
        }

        pool.moveToNeighboringTier(TierLevel.SECOND, TierLevel.FIRST, position = 0)
        assertEquals(0, pool.inPlayCount(TierLevel.SECOND))
        assertEquals(1, pool.inPlayCount(TierLevel.FIRST))
    }

    @Test
    fun `destroying a Marauder returns it to the Ion Battery`() {
        val pool = MarauderPool(RED)
        pool.placeOnBirthCanal(TierLevel.THIRD)
        assertEquals(3, pool.ionBattery)

        pool.destroy(TierLevel.THIRD, position = 0)

        assertEquals(4, pool.ionBattery)
        assertEquals(0, pool.inPlayCount(TierLevel.THIRD))
    }
}
