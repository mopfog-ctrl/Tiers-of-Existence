package com.tiersofexistence.engine.benchmark

import com.tiersofexistence.engine.cards.FateHarvestCard
import kotlin.random.Random

/**
 * The experimental "dynamic Fate Harvest reincarnation" reshuffle rule (Phase 1B) — **not
 * canonical**, benchmarked only to characterize its effect before any decision to adopt it (see
 * `PlayerCountDynamicReincarnationBenchmarkTest`). Plugs into `FateHarvestDeck.ReshuffleStrategy`
 * — Baseline A (`FateHarvestDeck.ReshuffleStrategy.PlainShuffle`, the only strategy any canonical
 * game ever uses) is completely untouched by this file's existence.
 *
 * **Two design decisions this file resolves, surfaced explicitly here since the user's own spec
 * left them implicit (everything the user *did* flag as unresolved — the 1-copy transition
 * probabilities, overflow/underflow handling, zero-copy refill eligibility — was answered
 * directly and is encoded below without further interpretation):**
 * 1. **What "current multiplicity" means at a regeneration event.** Taken as the literal count of
 *    each card type physically present in *this specific discard pile* at the moment it's about
 *    to be regenerated — not a separately-tracked, persistent per-game ecology counter. The
 *    "generation-to-generation evolution" the benchmark's own analysis reports on emerges
 *    naturally from this: Generation N's regenerated pile becomes what gets drawn from and
 *    (eventually) discarded, which is exactly what Generation N+1's transition step then reads.
 *    No separate state needs to be threaded through `FateHarvestDeck` for this to work, and it
 *    matches "at deck recycle" framing precisely — the recycling batch itself IS what's evolving.
 * 2. **What the regenerated pile's target size is.** Exactly the discard pile's own size at that
 *    moment (`discardPile.size`), never the game's global deck size (`64 + playerCount`). This is
 *    not a free choice — cards currently sitting in a player's hand are untouched by a
 *    regeneration event (only the discard pile is being recycled), so if the regenerated pile's
 *    target were the *global* total instead, the game's overall card count (draw + discard +
 *    hands) would inflate past `64 + playerCount` in dynamic-experimental-invariant-violating
 *    fashion. Targeting `discardPile.size` is what keeps the whole-game total exactly conserved
 *    through every regeneration, letting only *composition* evolve — `FateHarvestDeck.draw()`
 *    itself now enforces this as a hard invariant (`ReshuffleStrategy` must return the same size
 *    it was given), so a bug here fails loudly rather than silently drifting the total.
 */
object DynamicReincarnationRules {

    /**
     * Samples this experimental deck's transition outcome for one card type currently at
     * [currentCount] physical copies in the discard pile about to regenerate — defined for any
     * `currentCount >= 1` (every card starts the game at its canonical rarity, 1/2/3/4, so the
     * user's own 1/2/3/4 buckets cover ordinary play, but [regenerate]'s own refill step - a
     * uniform, unrestricted, with-replacement pick across every eligible type, per the user's
     * explicit instruction - can independently push a single type's count above 4 in one
     * regeneration even though transition itself never does; every count `>= 4` shares the
     * "4 copies" bucket's own rule, generalized, rather than needing a new bucket per count ever
     * reached this way - see that `when` branch's own comment for the full reasoning, found by
     * this file's own determinism test crashing on exactly this case. A 0-count type has nothing
     * to transition *from* and is only reachable again via [regenerate]'s refill step, per the
     * user's explicit "0 copies is just another eligible state" ruling.
     */
    fun transition(currentCount: Int, random: Random): Int {
        val roll = random.nextDouble()
        return when (currentCount) {
            // >=4, not ==4: the user's own rule only ever named a "4 copies" bucket, but the
            // refill step (uniform-with-replacement across types, per the user's own explicit
            // instruction) can independently push a single type's count above 4 in one
            // regeneration - e.g. a type already at 3 post-transition gets picked twice more
            // during refill, landing at 5. That's not a total-size violation (refill's target-size
            // correction still holds exactly), just one type temporarily exceeding its "canonical
            // max" - found by this file's own determinism test crashing on exactly this case.
            // Generalizing the 4-copies bucket's own rule (50% drop by one, 50% hold) to every
            // count >=4 is the smallest, most conservative fix: it keeps pulling an
            // over-abundant type back toward the defined 1-4 range every regeneration rather than
            // ever needing a new rule for 5, 6, 7... and never lets refill's own specified
            // uniformity be restricted to prevent this in the first place.
            in 4..Int.MAX_VALUE -> if (roll < 0.50) currentCount - 1 else currentCount
            3 -> when {
                roll < 0.33 -> 4
                roll < 0.66 -> 2
                else -> 3
            }
            2 -> when {
                roll < 0.25 -> 3
                roll < 0.50 -> 1
                roll < 0.57 -> 4
                else -> 2
            }
            // User's clarified rule (the originally-stated version had a duplicated outcome):
            // 1->2: 33%, 1->0: 33%, remain at 1: 34%.
            1 -> when {
                roll < 0.33 -> 2
                roll < 0.66 -> 0
                else -> 1
            }
            else -> error("DynamicReincarnationRules.transition is only defined for currentCount >= 1, got $currentCount")
        }
    }

    /**
     * Regenerates [discardPile] into a new pool of exactly the same size, per the user's own
     * specified sequence:
     * 1. Every card type *currently present* in [discardPile] (count > 0) independently samples
     *    [transition] once, using [random] — the shared, game-seeded generator, so this is fully
     *    reproducible from a seed like everything else in this codebase.
     * 2. The provisional pool is built from the post-transition counts.
     * 3. If over target size: cull uniformly at random *without replacement* across individual
     *    provisional cards (not card types) — a type with 4 provisional copies has 4 removal
     *    opportunities, a singleton has 1, so abundant types get proportionately more correction
     *    exposure, never artificially protected. Implemented as "shuffle, then drop the excess
     *    from the end" — equivalent to repeated random removal without replacement, cheaper.
     * 4. If under target size: refill one slot at a time, each independently a uniform random
     *    pick *with replacement* from [eligibleTypes] (every color-legal card type for this game,
     *    exactly one entry per type regardless of that type's current or canonical multiplicity —
     *    so refill never favors a canonically-common card over a canonically-rare one, and a
     *    type currently at 0 copies is exactly as eligible as any other).
     * 5. The result is shuffled once more (drawing order should still be random even though
     *    composition was decided by the steps above).
     *
     * [eligibleTypes] must contain exactly one [FateHarvestCard] per legal type name for this
     * game (i.e. already filtered to the seated colors' own canonical + unrestricted cards, the
     * same filtering `PlayerCountBenchmarkTest.buildDeckForColors` already does for Baseline A) —
     * this is what "current multiplicity" (via [FateHarvestCard.name] lookup) and refill both
     * draw from.
     */
    fun regenerate(discardPile: List<FateHarvestCard>, eligibleTypes: List<FateHarvestCard>, random: Random): RegenerationResult {
        val cardByName = eligibleTypes.associateBy { it.name }
        val preMultiplicity = discardPile.groupingBy { it.name }.eachCount()

        val postTransition = preMultiplicity.mapValues { (_, count) -> transition(count, random) }

        var provisional = postTransition.flatMap { (name, count) -> List(count) { cardByName.getValue(name) } }
        val target = discardPile.size
        var refillCount = 0
        var cullCount = 0

        when {
            provisional.size > target -> {
                cullCount = provisional.size - target
                provisional = provisional.shuffled(random).dropLast(cullCount)
            }
            provisional.size < target -> {
                refillCount = target - provisional.size
                val filled = provisional.toMutableList()
                repeat(refillCount) { filled += eligibleTypes.random(random) }
                provisional = filled
            }
        }

        val finalPile = provisional.shuffled(random)
        return RegenerationResult(
            regeneratedPile = finalPile,
            targetSize = target,
            preMultiplicity = preMultiplicity,
            postTransitionMultiplicity = postTransition.filterValues { it > 0 },
            finalMultiplicity = finalPile.groupingBy { it.name }.eachCount(),
            refillCount = refillCount,
            cullCount = cullCount,
        )
    }
}

/** One regeneration event's full outcome, for both driving [FateHarvestDeck.ReshuffleStrategy]
 * and instrumentation. [preMultiplicity]/[postTransitionMultiplicity]/[finalMultiplicity] are all
 * name -> count maps that omit a type entirely when its count is 0 (rather than keying it to 0),
 * matching `Map.groupingBy.eachCount()`'s own natural shape - callers needing an explicit 0 for
 * an absent type should default missing keys to 0 themselves. */
data class RegenerationResult(
    val regeneratedPile: List<FateHarvestCard>,
    val targetSize: Int,
    val preMultiplicity: Map<String, Int>,
    val postTransitionMultiplicity: Map<String, Int>,
    val finalMultiplicity: Map<String, Int>,
    val refillCount: Int,
    val cullCount: Int,
)
