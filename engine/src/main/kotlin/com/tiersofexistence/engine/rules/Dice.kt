package com.tiersofexistence.engine.rules

import kotlin.random.Random

/** The two dice (rulebook: "one black die and one purple die", both 16mm/6-sided per Parts List). */
object Dice {
    /**
     * One of the two dice rolled together as "the pair" for a Tier Phase turn (Rounds/
     * Phases/Turns: "rolling the pair of dice... moving that token the number of spaces
     * indicated by the dice"). Not black-die-only for Tier tokens — see [rollPurple].
     */
    fun rollBlack(random: Random = Random): Int = random.nextInt(1, 7)

    /**
     * The other half of "the pair" for a Tier Phase turn. During the Marauder Phase, this is
     * the *only* die rolled — "players who have Marauders will only roll the purple die.
     * Marauders do not get to roll the black die" (Rounds/Phases/Turns).
     */
    fun rollPurple(random: Random = Random): Int = random.nextInt(1, 7)
}
