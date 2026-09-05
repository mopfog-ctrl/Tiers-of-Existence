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

    // --- 1st Tier Ion Battery auto-replenishment ---

    @Test
    fun `destroying the only 1st Tier token in play never leaves the player permanently stranded`() {
        // Historically, TierTokenPool had no equivalent of the Hatchery-refill rule for the 1st
        // Tier, so a player whose sole in-play 1st Tier token was destroyed would sit at
        // inPlayCount == 0 forever — permanently losing all future 1st Tier turns (PlayerState
        // .hasTierTurn checks inPlayCount > 0). Per the rulebook: "On the First Tier... When
        // there are less than two 1st Tier tokens in play..., a new... token is taken from the
        // Ion Battery and placed on Start."
        val pool = TierTokenPool(TierLevel.FIRST, RED)
        pool.startToken()
        assertEquals(1, pool.inPlayCount)

        pool.destroyInPlay(0)

        assertEquals(2, pool.inPlayCount) // refilled from the Ion Battery, not stranded at 0
        assertEquals(8, pool.totalOwned)
    }

    @Test
    fun `repeatedly destroying a 1st Tier token keeps refilling from the Ion Battery indefinitely`() {
        // Each destroy returns its token to the Ion Battery, and the refill immediately draws
        // one back out to fill the resulting gap — so with only one slot freed per destroy, the
        // pool stays topped off at the 2-in-play cap no matter how many times this repeats,
        // exactly like it would for a real 1st Tier player whose token keeps getting destroyed
        // and replaced turn after turn.
        val pool = TierTokenPool(TierLevel.FIRST, RED)
        pool.startToken()
        pool.startToken() // 2 in play, 6 left in the Ion Battery

        repeat(20) {
            pool.destroyInPlay(0)
            assertEquals(2, pool.inPlayCount)
            assertEquals(8, pool.totalOwned)
        }
    }

    @Test
    fun `refilling gracefully settles for less than the cap once the Ion Battery is truly exhausted`() {
        // Unlike destruction (which always returns the same token the refill can immediately
        // reuse), sending tokens to the Staging Pile removes them from circulation until a bulk
        // promotion — so repeating it can genuinely run the Ion Battery dry, at which point the
        // refill must gracefully accept fewer than 2 in play rather than erroring out.
        val pool = TierTokenPool(TierLevel.FIRST, RED)
        pool.startToken()
        pool.startToken() // 2 in play, 6 left in the Ion Battery
        repeat(6) { pool.sendToStagingPile(0) } // each one refills from the Ion Battery in turn
        assertEquals(6, pool.stagingPile)
        assertEquals(0, pool.ionBattery)
        assertEquals(2, pool.inPlayCount) // still fully topped off — just barely

        pool.sendToStagingPile(0) // one more: only 1 in-play slot can be refilled now

        assertEquals(7, pool.stagingPile)
        assertEquals(0, pool.ionBattery)
        assertEquals(1, pool.inPlayCount) // settles at 1, no crash, nothing invented from nothing
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
        // 1st Tier auto-replenishes from the Ion Battery back up to the 2-in-play cap (see
        // TierTokenPool.refillInPlayIfRoom) — it doesn't just sit at 0.
        assertEquals(2, pool.inPlayCount)
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
