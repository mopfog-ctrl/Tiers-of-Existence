package com.tiersofexistence.engine.rules

import kotlin.random.Random

/** The two dice (rulebook: "one black die and one purple die", both 16mm/6-sided per Parts List). */
object Dice {
    /** Tier tokens move using the black die. */
    fun rollBlack(random: Random = Random): Int = random.nextInt(1, 7)

    /** Marauders only use the purple die for movement (Marauder rules, p.7). */
    fun rollPurple(random: Random = Random): Int = random.nextInt(1, 7)
}
