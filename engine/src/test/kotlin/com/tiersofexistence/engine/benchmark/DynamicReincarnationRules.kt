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
 *    it was given), so a bug here fails loudly rather than silently drifting the total. This is a
 *    *semantic invariant*, never exposed via [ReincarnationConfig] — see that class's own doc for
 *    why it's structurally different from the config's genuinely swappable numeric knobs.
 *
 * **Parameterization** (standing policy from Phase 1B on — see CLAUDE.md's own "Standing
 * requirement" section): every probability and selection strategy below is a field on
 * [ReincarnationConfig], defaulting to exactly this experimental mode's validated values (the ones
 * the 5,000-game `PlayerCountDynamicReincarnationBenchmarkTest` run measured) — not inlined into
 * this object's own control flow. [transition]/[regenerate] both accept a config and are otherwise
 * unaware of what specific numbers it holds.
 */
object DynamicReincarnationRules {

    /**
     * Samples this experimental deck's transition outcome for one card type currently at
     * [currentCount] physical copies in the discard pile about to regenerate, per [config] —
     * defined for any `currentCount >= 1` (every card starts the game at its canonical rarity,
     * 1/2/3/4, so [config]'s own 1/2/3 buckets plus its "high count" bucket cover ordinary play,
     * but [regenerate]'s own refill step — a uniform, unrestricted, with-replacement pick across
     * every eligible type by default — can independently push a single type's count above 4 in
     * one regeneration even though transition itself never does on its own; every count at or
     * above [ReincarnationConfig.highCountBucketMin] shares that one generalized rule rather than
     * needing a distinct bucket per count ever reached this way — found by this file's own
     * determinism test crashing on exactly this case before this generalization existed). A
     * 0-count type has nothing to transition *from* and is only reachable again via [regenerate]'s
     * refill step, per the user's explicit "0 copies is just another eligible state" ruling.
     *
     * Always consumes exactly one [Random.nextDouble] call, regardless of [config]'s own content
     * or which branch fires — this is what keeps "same seed -> same game" true for *any* valid
     * configuration, not just the default one (see CLAUDE.md's own "Standing requirement" note).
     */
    fun transition(currentCount: Int, random: Random, config: ReincarnationConfig = ReincarnationConfig.DEFAULT): Int {
        require(currentCount >= 1) { "transition() expects currentCount >= 1 (a type with 0 copies has nothing to transition from), got $currentCount" }
        val roll = random.nextDouble()
        if (currentCount >= config.highCountBucketMin) {
            return if (roll < config.highCountDropProbability) currentCount - 1 else currentCount
        }
        val outcomes = when (currentCount) {
            1 -> config.transitionAtOne
            2 -> config.transitionAtTwo
            3 -> config.transitionAtThree
            // A config that sets highCountBucketMin above 4 leaves some count(s) with no bucket
            // defined at all - rather than crash on an otherwise-valid configuration, such a count
            // simply doesn't transition this regeneration (stays put, same as "the remainder of
            // the probability mass" already means for a defined bucket).
            else -> emptyList()
        }
        var cumulative = 0.0
        for (outcome in outcomes) {
            cumulative += outcome.probability
            if (roll < cumulative) return outcome.targetCount
        }
        return currentCount // remaining probability mass (not consumed by any listed outcome) = stay unchanged
    }

    /**
     * Regenerates [discardPile] into a new pool of exactly the same size, per [config] and the
     * user's own specified sequence:
     * 1. Every card type *currently present* in [discardPile] (count > 0) independently samples
     *    [transition] once, using [random] — the shared, game-seeded generator, so this is fully
     *    reproducible from a seed like everything else in this codebase.
     * 2. The provisional pool is built from the post-transition counts.
     * 3. If over target size: [ReincarnationConfig.cullSelector] removes the excess — by default,
     *    uniformly at random *without replacement* across individual provisional cards (not card
     *    types), so a type with more provisional copies gets proportionately more removal
     *    exposure, never artificially protected.
     * 4. If under target size: [ReincarnationConfig.refillSelector] is called once per empty slot
     *    — by default, an independent uniform random pick *with replacement* from [eligibleTypes]
     *    (every color-legal card type for this game, exactly one entry per type regardless of that
     *    type's current or canonical multiplicity — so refill never favors a canonically-common
     *    card over a canonically-rare one by default, and a type currently at 0 copies is exactly
     *    as eligible as any other).
     * 5. The result is shuffled once more (drawing order should still be random even though
     *    composition was decided by the steps above).
     *
     * [eligibleTypes] must contain exactly one [FateHarvestCard] per legal type name for this
     * game (i.e. already filtered to the seated colors' own canonical + unrestricted cards, the
     * same filtering `PlayerCountBenchmarkTest.buildDeckForColors` already does for Baseline A) —
     * this is what "current multiplicity" (via [FateHarvestCard.name] lookup) and the default
     * refill selector both draw from.
     */
    fun regenerate(
        discardPile: List<FateHarvestCard>,
        eligibleTypes: List<FateHarvestCard>,
        random: Random,
        config: ReincarnationConfig = ReincarnationConfig.DEFAULT,
    ): RegenerationResult {
        val cardByName = eligibleTypes.associateBy { it.name }
        val preMultiplicity = discardPile.groupingBy { it.name }.eachCount()

        val postTransition = preMultiplicity.mapValues { (_, count) -> transition(count, random, config) }

        var provisional = postTransition.flatMap { (name, count) -> List(count) { cardByName.getValue(name) } }
        val target = discardPile.size
        var refillCount = 0
        var cullCount = 0

        when {
            provisional.size > target -> {
                cullCount = provisional.size - target
                provisional = config.cullSelector(provisional, cullCount, random)
            }
            provisional.size < target -> {
                refillCount = target - provisional.size
                val filled = provisional.toMutableList()
                repeat(refillCount) { filled += config.refillSelector(eligibleTypes, random) }
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

/** One transition bucket's possible outcome: a [probability] (0.0-1.0) of landing at exactly
 * [targetCount]. A bucket's own list of outcomes need not sum its probabilities to 1.0 - any
 * remaining mass means "stay at the current count," matching how the user's own rule was
 * originally phrased ("remain at N: X%") rather than requiring every bucket to spell out its own
 * "stay" outcome explicitly. */
data class WeightedOutcome(val targetCount: Int, val probability: Double)

/**
 * Explicit, swappable configuration for [DynamicReincarnationRules] — the specific numbers and
 * selection strategies below are this experimental mode's *default* parameter set (exactly what
 * the 5,000-game `PlayerCountDynamicReincarnationBenchmarkTest` run validated), not immutable
 * engine constants. See CLAUDE.md's own "Standing requirement" section for why this exists and
 * the semantic-invariant/configurable-value distinction it's built around.
 *
 * **Deliberately NOT exposed here** (semantic invariants, not configurable values — see
 * [DynamicReincarnationRules]'s own class doc point 2 for the full reasoning on the first one):
 * - The regenerated pile's target size, always exactly the discard pile's own size — enforced by
 *   `FateHarvestDeck.draw()` itself, not a parameter of this algorithm at all.
 * - That culling never removes the same physical card twice, and refill can always repeat a type
 *   — these follow from what culling/refilling *mean*, not a choice between equally-valid
 *   alternatives (a [cullSelector] or [refillSelector] a caller supplies could still behave
 *   differently, but the *shape* of "remove some, add some" is fixed).
 * - Which card types are even eligible to appear at all (color-legal for the seated players) —
 *   controlled entirely by the caller's own `eligibleTypes` argument to
 *   [DynamicReincarnationRules.regenerate], already a parameter there, not duplicated into this
 *   config.
 */
data class ReincarnationConfig(
    /** Outcomes for a type currently at exactly 1 copy. Default: 1->2: 33%, 1->0: 33%, remain at
     * 1: 34% (the user's own corrected rule — the originally-stated version had a duplicated
     * outcome). */
    val transitionAtOne: List<WeightedOutcome> = listOf(WeightedOutcome(2, 0.33), WeightedOutcome(0, 0.33)),
    /** Outcomes for a type currently at exactly 2 copies. Default: 2->3: 25%, 2->1: 25%, 2->4: 7%,
     * remain at 2: 43%. */
    val transitionAtTwo: List<WeightedOutcome> = listOf(WeightedOutcome(3, 0.25), WeightedOutcome(1, 0.25), WeightedOutcome(4, 0.07)),
    /** Outcomes for a type currently at exactly 3 copies. Default: 3->4: 33%, 3->2: 33%, remain at
     * 3: 34%. */
    val transitionAtThree: List<WeightedOutcome> = listOf(WeightedOutcome(4, 0.33), WeightedOutcome(2, 0.33)),
    /** Any count at or above this is treated as the "high count" bucket (relative, not absolute,
     * outcomes — see [highCountDropProbability]) rather than needing its own bucket per count.
     * Default 4, matching the user's own "4 copies" bucket — generalized because [regenerate]'s
     * own refill step can independently push a single type above 4 in one regeneration even
     * though transition alone never does (see [DynamicReincarnationRules.transition]'s own doc). */
    val highCountBucketMin: Int = 4,
    /** Chance, for any count in the high-count bucket, of dropping by exactly one relative to the
     * current count (otherwise stays unchanged). Default 0.50, matching the user's own "4 copies"
     * bucket's rule (50% -> 3, 50% remain at 4) generalized to every count >= [highCountBucketMin]. */
    val highCountDropProbability: Double = 0.50,
    /** Picks one card to add per empty refill slot. Default: uniform random *with replacement*
     * across every entry in the caller-supplied eligible-types list, regardless of canonical
     * rarity or current count (the user's own confirmed ruling for this experimental mode's
     * default) — never weighted toward a canonically-common or currently-abundant type. */
    val refillSelector: (eligibleTypes: List<FateHarvestCard>, random: Random) -> FateHarvestCard =
        { types, random -> types.random(random) },
    /** Picks which cards survive when the provisional pool exceeds the target size (called with
     * the full provisional pool and how many cards must be removed) — returns the pool with
     * exactly that many cards removed. Default: uniform at random *without replacement* across
     * individual physical cards (never card types), implemented as "shuffle, then drop the excess
     * from the end" (equivalent to repeated random removal without replacement, cheaper) — so a
     * type with more provisional copies gets proportionately more removal exposure, never
     * artificially protected. */
    val cullSelector: (pool: List<FateHarvestCard>, cullCount: Int, random: Random) -> List<FateHarvestCard> =
        { pool, cullCount, random -> pool.shuffled(random).dropLast(cullCount) },
) {
    companion object {
        /** This experimental mode's validated default parameter set — what every existing test
         * and the 5,000-game benchmark run actually used. Not an immutable engine constant: a
         * future server-authoritative configuration surface may construct a different
         * [ReincarnationConfig] entirely without touching [DynamicReincarnationRules]'s own logic. */
        val DEFAULT = ReincarnationConfig()
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
