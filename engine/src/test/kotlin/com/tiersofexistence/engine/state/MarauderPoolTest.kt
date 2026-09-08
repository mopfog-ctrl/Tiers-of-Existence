package com.tiersofexistence.engine.state

import com.tiersofexistence.engine.model.PlayerColor.RED
import com.tiersofexistence.engine.model.TierLevel
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

/**
 * Marauders have no Ion Battery / finite spawn reserve — confirmed by the user, correcting an
 * earlier implementation that (wrongly) modeled the Parts List's "4x Marauder tokens per color"
 * as an engine resource pool. The only real limits are the per-Tier cap ("only one Marauder
 * token is allowed per Tier per player") and its Fate Harvest bypass (rule #9) — see
 * `MarauderPool`'s class doc.
 */
class MarauderPoolTest {

    @Test
    fun `ordinary legal Marauder spawning works`() {
        val pool = MarauderPool(RED)

        val id = pool.placeOnBirthCanal(TierLevel.FIRST)

        assertEquals(1, pool.inPlayCount(TierLevel.FIRST))
        assertEquals(id, pool.idAt(TierLevel.FIRST, position = 0))
    }

    @Test
    fun `only one Marauder per Tier unless bypassed`() {
        val pool = MarauderPool(RED)
        pool.placeOnBirthCanal(TierLevel.FIRST)

        assertThrows<IllegalArgumentException> { pool.placeOnBirthCanal(TierLevel.FIRST) }

        pool.placeOnBirthCanal(TierLevel.FIRST, bypassCap = true)
        assertEquals(2, pool.inPlayCount(TierLevel.FIRST))
    }

    @Test
    fun `Fate Harvest cards can bypass the per-Tier cap`() {
        val pool = MarauderPool(RED)
        pool.placeOnBirthCanal(TierLevel.FIRST)

        // A second, third... Marauder on the same Tier via bypassCap = true is legal every time
        // — the ordinary per-Tier cap simply doesn't apply to these (rule #9).
        pool.placeOnBirthCanal(TierLevel.FIRST, bypassCap = true)
        pool.placeOnBirthCanal(TierLevel.FIRST, bypassCap = true)

        assertEquals(3, pool.inPlayCount(TierLevel.FIRST))
    }

    @Test
    fun `more than 4 logical Marauders may exist across Tiers if legal effects produce that state`() {
        val pool = MarauderPool(RED)
        // One per Tier, ordinary play — 4 total, the size of the old (incorrect) "reserve."
        TierLevel.entries.forEach { pool.placeOnBirthCanal(it) }
        assertEquals(4, TierLevel.entries.sumOf { pool.inPlayCount(it) })

        // A 5th and 6th, via the Fate Harvest bypass, must not be blocked by any global count —
        // there is no such count to exhaust.
        pool.placeOnBirthCanal(TierLevel.FIRST, bypassCap = true)
        pool.placeOnBirthCanal(TierLevel.FIRST, bypassCap = true)

        assertEquals(6, TierLevel.entries.sumOf { pool.inPlayCount(it) })
    }

    @Test
    fun `destroying a Marauder removes it rather than replenishing a fictitious reserve`() {
        val pool = MarauderPool(RED)
        pool.placeOnBirthCanal(TierLevel.THIRD)

        pool.destroy(TierLevel.THIRD, position = 0)

        assertEquals(0, pool.inPlayCount(TierLevel.THIRD))
    }

    @Test
    fun `repeated spawn-destroy cycles do not depend on any component inventory`() {
        val pool = MarauderPool(RED)

        // Spawn and destroy on the same Tier far more times than 4 physical pieces could ever
        // cover, proving nothing here is tracked as a finite, exhaustible supply.
        repeat(20) {
            pool.placeOnBirthCanal(TierLevel.SECOND)
            pool.destroy(TierLevel.SECOND, position = 0)
        }

        assertEquals(0, pool.inPlayCount(TierLevel.SECOND))
        pool.placeOnBirthCanal(TierLevel.SECOND) // still legally spawnable afterward
        assertEquals(1, pool.inPlayCount(TierLevel.SECOND))
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
    fun `destroying all in-play Marauders on a Tier only affects that Tier`() {
        val pool = MarauderPool(RED)
        pool.placeOnBirthCanal(TierLevel.FIRST, bypassCap = true)
        pool.placeOnBirthCanal(TierLevel.FIRST, bypassCap = true)
        pool.placeOnBirthCanal(TierLevel.SECOND)

        pool.destroyAllInPlay(TierLevel.FIRST)

        assertEquals(0, pool.inPlayCount(TierLevel.FIRST))
        assertEquals(1, pool.inPlayCount(TierLevel.SECOND)) // untouched
    }
}
