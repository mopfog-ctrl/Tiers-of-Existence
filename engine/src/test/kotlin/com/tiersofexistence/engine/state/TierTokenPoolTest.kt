package com.tiersofexistence.engine.state

import com.tiersofexistence.engine.model.TierLevel
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TierTokenPoolTest {

    @Test
    fun `starting a token moves it from Ion Battery to Birth Canal`() {
        val pool = TierTokenPool(TierLevel.FIRST)
        assertEquals(8, pool.ionBattery)

        pool.startToken()

        assertEquals(7, pool.ionBattery)
        assertEquals(listOf(0), pool.inPlayPositions)
        assertEquals(8, pool.totalOwned)
    }

    @Test
    fun `extra tokens beyond max in play wait in the Hatchery`() {
        val pool = TierTokenPool(TierLevel.FIRST)
        pool.startToken()
        pool.startToken()
        assertEquals(2, pool.inPlayCount)

        pool.startToken()

        assertEquals(2, pool.inPlayCount)
        assertEquals(1, pool.hatchery)
        assertEquals(8, pool.totalOwned)
    }

    @Test
    fun `destroying an in-play token promotes a Hatchery token into its place`() {
        val pool = TierTokenPool(TierLevel.FIRST)
        repeat(3) { pool.startToken() } // 2 in play, 1 in hatchery
        assertEquals(1, pool.hatchery)

        pool.destroyInPlay(0)

        assertEquals(2, pool.inPlayCount)
        assertEquals(0, pool.hatchery)
        assertEquals(8, pool.totalOwned)
    }

    @Test
    fun `1st Tier staging pile promotes after 4 tokens`() {
        val pool = TierTokenPool(TierLevel.FIRST)
        pool.startToken()

        repeat(3) {
            pool.sendToStagingPile(0)
            assertFalse(pool.tryPromoteFromStagingPile())
            pool.startToken()
        }
        pool.sendToStagingPile(0)

        assertEquals(4, pool.stagingPile)
        assertTrue(pool.tryPromoteFromStagingPile())
        assertEquals(0, pool.stagingPile)
        assertEquals(8, pool.totalOwned)
    }

    @Test
    fun `4th Tier has no staging pile promotion`() {
        val pool = TierTokenPool(TierLevel.FOURTH)
        assertEquals(null, TierLevel.FOURTH.stagingPileThreshold)
        assertFalse(pool.tryPromoteFromStagingPile())
    }

    @Test
    fun `total owned stays constant across zone transfers`() {
        val pool = TierTokenPool(TierLevel.SECOND)
        val total = pool.totalOwned
        pool.startToken()
        pool.startToken()
        pool.startToken() // overflow to hatchery
        pool.sendToStagingPile(0)
        pool.destroyInPlay(0)

        assertEquals(total, pool.totalOwned)
    }
}
