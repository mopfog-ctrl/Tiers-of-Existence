package com.tiersofexistence.engine.state

import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.model.PlayerColor.RED
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TierTokenPoolTest {

    @Test
    fun `starting a token moves it from Ion Battery to Birth Canal`() {
        val pool = TierTokenPool(TierLevel.FIRST, RED)
        assertEquals(8, pool.ionBattery)

        pool.startToken()

        assertEquals(7, pool.ionBattery)
        assertEquals(listOf(0), pool.inPlayPositions)
        assertEquals(8, pool.totalOwned)
    }

    @Test
    fun `extra tokens beyond max in play wait in the Hatchery`() {
        val pool = TierTokenPool(TierLevel.FIRST, RED)
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
        val pool = TierTokenPool(TierLevel.FIRST, RED)
        repeat(3) { pool.startToken() } // 2 in play, 1 in hatchery
        assertEquals(1, pool.hatchery)

        pool.destroyInPlay(0)

        assertEquals(2, pool.inPlayCount)
        assertEquals(0, pool.hatchery)
        assertEquals(8, pool.totalOwned)
    }

    @Test
    fun `1st Tier staging pile promotes after 4 tokens`() {
        val pool = TierTokenPool(TierLevel.FIRST, RED)
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
        val pool = TierTokenPool(TierLevel.FOURTH, RED)
        assertEquals(null, TierLevel.FOURTH.stagingPileThreshold)
        assertFalse(pool.tryPromoteFromStagingPile())
    }

    @Test
    fun `total owned stays constant across zone transfers`() {
        val pool = TierTokenPool(TierLevel.SECOND, RED)
        val total = pool.totalOwned
        pool.startToken()
        pool.startToken()
        pool.startToken() // overflow to hatchery
        pool.sendToStagingPile(0)
        pool.destroyInPlay(0)

        assertEquals(total, pool.totalOwned)
    }

    // --- Zone of Protection residence (Phase G) ---

    @Test
    fun `entering a Zone removes the token from the main loop but keeps it in play`() {
        val pool = TierTokenPool(TierLevel.FIRST, RED)
        pool.startToken()
        pool.moveInPlay(0, 10)

        pool.enterZone(fromPosition = 10, zoneNumber = 2)

        assertTrue(pool.inPlayPositions.isEmpty())
        assertEquals(listOf(2), pool.zoneResidents)
        assertEquals(1, pool.inPlayCount) // still "in play" — on the board, just off the main loop
        assertEquals(8, pool.totalOwned)
    }

    @Test
    fun `a token inside a Zone still counts toward the max-in-play cap`() {
        val pool = TierTokenPool(TierLevel.FIRST, RED)
        pool.startToken()
        pool.moveInPlay(0, 10)
        pool.enterZone(fromPosition = 10, zoneNumber = 2)
        pool.startToken() // 1 in play (main loop) + 1 in Zone = 2, at the 1st Tier cap

        pool.startToken() // should overflow to Hatchery, not exceed the cap

        assertEquals(2, pool.inPlayCount)
        assertEquals(1, pool.hatchery)
    }

    @Test
    fun `leaving a Zone returns the token to the main loop`() {
        val pool = TierTokenPool(TierLevel.FIRST, RED)
        pool.startToken()
        pool.moveInPlay(0, 10)
        pool.enterZone(fromPosition = 10, zoneNumber = 2)

        pool.leaveZone(zoneNumber = 2, toPosition = 14)

        assertTrue(pool.zoneResidents.isEmpty())
        assertEquals(listOf(14), pool.inPlayPositions)
    }

    @Test
    fun `destroying a token inside a Zone returns it to the Ion Battery`() {
        val pool = TierTokenPool(TierLevel.FIRST, RED)
        pool.startToken()
        pool.moveInPlay(0, 10)
        pool.enterZone(fromPosition = 10, zoneNumber = 2)

        pool.destroyInZone(zoneNumber = 2)

        assertTrue(pool.zoneResidents.isEmpty())
        assertEquals(0, pool.inPlayCount)
        assertEquals(8, pool.totalOwned)
    }

    // --- Staging Pile direct mutation (Fate Harvest cards, not just Nebula landings) ---

    @Test
    fun `adding to the staging pile directly runs the same promotion check as a Nebula landing`() {
        val pool = TierTokenPool(TierLevel.THIRD, RED) // threshold 2
        pool.startToken()
        assertFalse(pool.addToStagingPileDirectly())

        pool.startToken()
        assertTrue(pool.addToStagingPileDirectly())
        assertEquals(0, pool.stagingPile)
    }

    @Test
    fun `destroying from the staging pile does not trigger promotion`() {
        val pool = TierTokenPool(TierLevel.THIRD, RED)
        pool.startToken()
        pool.sendToStagingPile(0)

        pool.destroyFromStagingPile()

        assertEquals(0, pool.stagingPile)
        assertEquals(4, pool.ionBattery) // back to the starting count
    }
}
