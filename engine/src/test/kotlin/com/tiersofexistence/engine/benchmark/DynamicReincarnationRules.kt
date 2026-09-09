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
 * **CORRECTED (rarity-ceiling model) — supersedes an earlier, uncapped interpretation.** A card
 * type's original deck rarity (`FateHarvestCard.rarity.copies` — 1/2/3/4) is a hard maximum on how
 * many copies of it may exist *anywhere in the live game* — draw pile + discard pile + every
 * player's hand — at any point, for the whole game, never just within the discard pile being
 * regenerated. The earlier version of this file allowed a type's copy count to grow past its own
 * rarity (a generic ">=4 copies" bucket, reachable via refill picking an already-abundant type
 * with no ceiling check at all) — that was a mistaken interpretation, corrected here: refill now
 * only ever selects among types with remaining whole-game capacity (see [regenerate]'s own doc),
 * and [transition] itself can never *propose* a count exceeding a type's own ceiling. **The
 * original, uncapped 5,000-game Phase 1B benchmark run
 * (`docs/benchmarks/dynamic-reincarnation-benchmark.md`) remains valid evidence about that earlier
 * model, kept as historical/superseded record — it is explicitly NOT reused as evidence for this
 * corrected model**, per the user's own instruction; a new benchmark run under this corrected
 * model establishes its own baseline before any resolution-pressure weighting is derived from it.
 *
 * **Design decisions this file resolves, surfaced explicitly here since the user's own spec left
 * them implicit (everything the user *did* flag as unresolved — the 1-copy transition
 * probabilities, overflow/underflow handling, zero-copy refill eligibility, and now the
 * whole-game rarity ceiling — was answered directly and is encoded below without further
 * interpretation):**
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
 * 3. **What "whole-game rarity ceiling" means at a regeneration event.** A card type's own
 *    canonical rarity minus however many copies of it currently sit *outside* the discard pile
 *    (in hands — never the draw pile, which [regenerate] is only ever called once empty, per
 *    `FateHarvestDeck.draw`) is the most that type may occupy *within* the regenerated pile. This
 *    is a *semantic invariant*, not a configurable number — see [ReincarnationConfig]'s own doc.
 *
 * **Parameterization** (standing policy from Phase 1B on — see CLAUDE.md's own "Standing
 * requirement" section): every probability and selection strategy below is a field on
 * [ReincarnationConfig] — not inlined into this object's own control flow. [transition]/
 * [regenerate] both accept a config and are otherwise unaware of what specific numbers it holds.
 */
object DynamicReincarnationRules {

    /**
     * Samples this experimental deck's transition outcome for one card type currently at
     * [currentCount] physical copies in the discard pile about to regenerate, per [config] —
     * defined for any `currentCount >= 1` (every card starts the game at its canonical rarity,
     * 1/2/3/4, so [config]'s own 1/2/3/4 buckets cover every reachable count). A 0-count type has
     * nothing to transition *from* and is only reachable again via [regenerate]'s refill step, per
     * the user's explicit "0 copies is just another eligible state" ruling.
     *
     * [ceiling] is the most this specific call may ever return — the corrected rarity-ceiling
     * model's hard boundary (see this object's own class doc, point 3): an outcome whose
     * `targetCount` would exceed it is treated exactly like unlisted probability mass already is —
     * folded into "stay at [currentCount]" — since a canonical bucket (shared across every card at
     * a given count, regardless of that specific card's own rarity class) can propose an outcome
     * that's legal for a QUADRUPLE-rarity card but illegal for a lower-rarity one sitting at the
     * same count. Defaults to [Int.MAX_VALUE] (no ceiling) purely for callers that don't yet track
     * one — [regenerate] always passes a real, computed ceiling.
     *
     * Always consumes exactly one [Random.nextDouble] call, regardless of [config]'s own content,
     * [ceiling], or which branch fires — this is what keeps "same seed -> same game" true for
     * *any* valid configuration, not just the default one (see CLAUDE.md's own "Standing
     * requirement" note).
     */
    fun transition(
        currentCount: Int,
        random: Random,
        config: ReincarnationConfig = ReincarnationConfig.DEFAULT,
        cardName: String? = null,
        generationIndex: Int = 0,
        ceiling: Int = Int.MAX_VALUE,
    ): Int {
        require(currentCount >= 1) { "transition() expects currentCount >= 1 (a type with 0 copies has nothing to transition from), got $currentCount" }
        require(currentCount <= ceiling) {
            "currentCount ($currentCount) exceeds its own ceiling ($ceiling) for ${cardName ?: "<unnamed>"} - " +
                "this should be structurally impossible under the whole-game rarity-ceiling invariant unless a " +
                "prior mutation violated it"
        }
        val pressure = stagnationPressureFor(config, cardName, generationIndex)
        val roll = random.nextDouble()
        val baseOutcomes = when (currentCount) {
            1 -> config.transitionAtOne
            2 -> config.transitionAtTwo
            3 -> config.transitionAtThree
            4 -> config.transitionAtFour
            // A count above 4 is structurally impossible under the rarity-ceiling model (no
            // canonical rarity exceeds QUADRUPLE) - guarded, not crashed, matching this file's
            // existing philosophy for an otherwise-valid config with an undefined bucket.
            else -> emptyList()
        }
        val outcomes = if (pressure > 0.0) applyStagnationPressure(baseOutcomes, currentCount, pressure) else baseOutcomes
        var cumulative = 0.0
        var result = currentCount
        for (outcome in outcomes) {
            cumulative += outcome.probability
            if (roll < cumulative) {
                result = outcome.targetCount
                break
            }
        }
        // The rarity ceiling wins over any bucket outcome that would exceed it - folded into
        // "stay," exactly like unlisted probability mass already means for a defined bucket.
        return if (result > ceiling) currentCount else result
    }

    /**
     * How strongly to bias [cardName]'s transition this regeneration, in `[0.0, 1.0]`: the
     * product of its evidence-derived [StagnationPressureConfig.cardWeights] entry (0.0 for any
     * card not empirically associated with prolonged games — including every card the benchmark
     * data associates with *shorter* games, which this function never touches, per the explicit
     * "do not reduce cards merely because they become common if their prevalence contributes to
     * resolution" ruling) and [StagnationPressureConfig.escalation] evaluated at
     * [generationIndex] (0 at the very first reshuffle of a game, so that reshuffle evolves
     * purely naturally — escalating only across the *successive* reshuffles the user's spec asks
     * for). Returns 0.0 whenever [ReincarnationConfig.stagnationPressure] is unset (the default,
     * disabled state) or [cardName] is null — the only two ways [transition]'s pre-existing,
     * already-validated behavior stays perfectly reproducible for any caller that doesn't opt in.
     */
    private fun stagnationPressureFor(config: ReincarnationConfig, cardName: String?, generationIndex: Int): Double {
        val stagnation = config.stagnationPressure ?: return 0.0
        if (cardName == null) return 0.0
        val weight = stagnation.cardWeights[cardName] ?: return 0.0
        if (weight <= 0.0) return 0.0
        val escalationFactor = stagnation.escalation(generationIndex).coerceIn(0.0, 1.0)
        return (weight * escalationFactor).coerceIn(0.0, 1.0)
    }

    /**
     * Redistributes probability mass within one transition bucket's [outcomes], relative to
     * [currentCount], toward eventual resolution:
     * - If the bucket has an "up" outcome (`targetCount > currentCount` — this card type becoming
     *   *more* abundant, the multiplicity direction the evidence associates with longer games):
     *   [pressure] of its own probability is shaved off each such outcome; the removed mass is
     *   redirected to the bucket's "down" outcome(s) (`targetCount < currentCount`, proportionally
     *   if there's more than one) if any exist, or simply becomes additional implicit "stay"
     *   probability (the bucket's un-listed remainder) if the bucket has no down outcome at all.
     * - Otherwise, if the bucket has a "down" outcome but no "up" outcome at all (the 4-copy
     *   bucket under the rarity-ceiling model has no up outcome to shave — a card already at its
     *   own ceiling has nowhere higher to go — or any other bucket shaped that way): [pressure] of
     *   the bucket's own implicit "stay" mass is instead redirected into the down outcome(s)
     *   proportionally, the same "push toward resolution" intent applied the only way available
     *   when there's no up side to shave.
     * - If the bucket has neither an up nor a down outcome, nothing is biased.
     *
     * This never changes a bucket's *shape* (same listed outcomes, same [Random.nextDouble]
     * consumption downstream) — only which of its already-defined outcomes the single roll is more
     * or less likely to land in.
     */
    private fun applyStagnationPressure(outcomes: List<WeightedOutcome>, currentCount: Int, pressure: Double): List<WeightedOutcome> {
        val up = outcomes.filter { it.targetCount > currentCount }
        val down = outcomes.filter { it.targetCount < currentCount }
        val unaffected = outcomes.filter { it.targetCount == currentCount }
        if (up.isNotEmpty()) {
            var redirected = 0.0
            val reducedUp = up.map { outcome ->
                val delta = outcome.probability * pressure
                redirected += delta
                outcome.copy(probability = outcome.probability - delta)
            }
            val adjustedDown = if (down.isNotEmpty()) {
                val totalDown = down.sumOf { it.probability }
                down.map { it.copy(probability = it.probability + redirected * (it.probability / totalDown)) }
            } else {
                down // empty - the redirected mass simply isn't consumed by any listed outcome, so
                // it falls into the bucket's implicit "stay" remainder automatically.
            }
            return reducedUp + adjustedDown + unaffected
        }
        if (down.isNotEmpty()) {
            val stayMass = (1.0 - outcomes.sumOf { it.probability }).coerceAtLeast(0.0)
            if (stayMass <= 0.0) return outcomes
            val boost = stayMass * pressure
            val totalDown = down.sumOf { it.probability }
            val boostedDown = down.map { it.copy(probability = it.probability + boost * (it.probability / totalDown)) }
            return boostedDown + unaffected
        }
        return outcomes // no up, no down outcome in this bucket - nothing to bias.
    }

    /**
     * Regenerates [discardPile] into a new pool of exactly the same size, per [config] and the
     * user's own specified sequence:
     * 1. Every card type *currently present* in [discardPile] (count > 0) independently samples
     *    [transition] once, using [random] — the shared, game-seeded generator, so this is fully
     *    reproducible from a seed like everything else in this codebase — capped at that type's
     *    own remaining whole-game capacity (see [effectiveCeilingWithinDiscardPile] below).
     * 2. The provisional pool is built from the post-transition counts.
     * 3. If over target size: [ReincarnationConfig.cullSelector] removes the excess — by default,
     *    uniformly at random *without replacement* across individual provisional cards (not card
     *    types), so a type with more provisional copies gets proportionately more removal
     *    exposure, never artificially protected.
     * 4. If under target size: refill fills the remaining slots one at a time. For each slot,
     *    only card types with remaining capacity beneath their own whole-game rarity ceiling are
     *    eligible; [ReincarnationConfig.refillSelector] picks among exactly that eligible subset
     *    (by default, an independent uniform random pick *with replacement*, never favoring a
     *    canonically-common card over a canonically-rare one, and a type currently at 0 copies is
     *    exactly as eligible as any other *below its own ceiling*); the picked type's remaining
     *    capacity is immediately reduced before the next slot's eligibility is recomputed, so a
     *    type that reaches its own ceiling mid-refill drops out of eligibility for any further
     *    slot in this same regeneration. No refill outcome may ever exceed a type's own ceiling.
     * 5. The result is shuffled once more (drawing order should still be random even though
     *    composition was decided by the steps above).
     *
     * [eligibleTypes] must contain exactly one [FateHarvestCard] per legal type name for this
     * game (i.e. already filtered to the seated colors' own canonical + unrestricted cards, the
     * same filtering `PlayerCountBenchmarkTest.buildDeckForColors` already does for Baseline A) —
     * this is what "current multiplicity" (via [FateHarvestCard.name] lookup), each type's own
     * canonical ceiling ([FateHarvestCard.rarity]), and the default refill selector all draw from.
     *
     * [liveCountsOutsideDiscardPile] is how many copies of each card type currently exist *outside*
     * the discard pile being regenerated — in practice, exactly the sum of every player's hand at
     * this moment (never the draw pile: [FateHarvestDeck.draw] only ever calls this once the draw
     * pile is already empty), keyed by [FateHarvestCard.name], defaulting to an empty map (treated
     * as 0 for every type) for callers that don't track a real live game at all. A type's *whole-
     * game* rarity ceiling minus its count here is [effectiveCeilingWithinDiscardPile] — the most
     * that type may occupy within the regenerated pile itself, since copies sitting in a hand are
     * untouched by this regeneration and still count against that type's own original-rarity
     * maximum.
     *
     * [generationIndex] is how many regenerations have already happened this game *before* this
     * one (0 for the very first reshuffle) — the caller's own responsibility to track and
     * increment across a game's whole lifetime, passed straight through to every [transition]
     * call this regeneration makes so [ReincarnationConfig.stagnationPressure] (when configured)
     * can apply progressively stronger anti-stagnation pressure at later reshuffles while leaving
     * a game's earliest reshuffle(s) to evolve naturally. Meaningless (and harmless — no-op) for
     * a [config] that leaves [ReincarnationConfig.stagnationPressure] unset, which is why it
     * defaults to 0 rather than being required.
     */
    fun regenerate(
        discardPile: List<FateHarvestCard>,
        eligibleTypes: List<FateHarvestCard>,
        random: Random,
        config: ReincarnationConfig = ReincarnationConfig.DEFAULT,
        generationIndex: Int = 0,
        liveCountsOutsideDiscardPile: Map<String, Int> = emptyMap(),
    ): RegenerationResult {
        val cardByName = eligibleTypes.associateBy { it.name }
        fun effectiveCeilingWithinDiscardPile(card: FateHarvestCard): Int =
            (card.rarity.copies - (liveCountsOutsideDiscardPile[card.name] ?: 0)).coerceAtLeast(0)

        val preMultiplicity = discardPile.groupingBy { it.name }.eachCount()
        val postTransition = preMultiplicity.mapValues { (name, count) ->
            transition(count, random, config, name, generationIndex, effectiveCeilingWithinDiscardPile(cardByName.getValue(name)))
        }

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
                val runningCounts = eligibleTypes.associate { it.name to (postTransition[it.name] ?: 0) }.toMutableMap()
                val filled = provisional.toMutableList()
                repeat(refillCount) {
                    val eligibleForRefill = eligibleTypes.filter { runningCounts.getValue(it.name) < effectiveCeilingWithinDiscardPile(it) }
                    check(eligibleForRefill.isNotEmpty()) {
                        "No card type has remaining whole-game capacity for refill (target=$target, filled so " +
                            "far=${filled.size}) - every eligible type is already at its own original-rarity " +
                            "ceiling across the discard pile plus liveCountsOutsideDiscardPile; this should be " +
                            "structurally impossible for a legally constructed game and indicates a bug elsewhere"
                    }
                    val picked = config.refillSelector(eligibleForRefill, random)
                    filled += picked
                    runningCounts[picked.name] = runningCounts.getValue(picked.name) + 1
                }
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
    /** Outcomes for a type currently at exactly 4 copies — under the rarity-ceiling model, this is
     * necessarily that type's own ceiling (no canonical rarity exceeds QUADRUPLE), so this bucket
     * has no "up" outcome at all. Default: 4->3: 50%, remain at 4: 50% (the user's own original
     * "4 copies" rule). Previously generalized into an unbounded ">=4" bucket reachable via an
     * uncapped refill step — that generalization is corrected out; see this file's own class doc. */
    val transitionAtFour: List<WeightedOutcome> = listOf(WeightedOutcome(3, 0.50)),
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
    /** Evidence-weighted, escalating anti-stagnation pressure (Phase 1C) — `null` (the default)
     * disables it entirely, leaving [transition]/[regenerate] byte-identical to Phase 1B's own
     * already-validated behavior. See [StagnationPressureConfig]'s own doc. */
    val stagnationPressure: StagnationPressureConfig? = null,
) {
    companion object {
        /** This experimental mode's validated default parameter set — what every existing test
         * and the 5,000-game benchmark run actually used. Not an immutable engine constant: a
         * future server-authoritative configuration surface may construct a different
         * [ReincarnationConfig] entirely without touching [DynamicReincarnationRules]'s own logic. */
        val DEFAULT = ReincarnationConfig()

        /** Phase 1C's own named variant: the corrected rarity-ceiling transition/refill/cull rules
         * above, plus [StagnationPressureConfig.DEFAULT]'s evidence-derived, escalating suppression
         * of the card types the *corrected-model baseline* (see [StagnationPressureConfig]'s own
         * doc) associates with prolonged games. Never [DEFAULT] itself — a separate, explicitly-
         * opted-into preset. */
        val ANTI_STAGNATION = ReincarnationConfig(stagnationPressure = StagnationPressureConfig.DEFAULT)
    }
}

/**
 * **Phase 1C — experimental, not canonical.** Evidence-weighted, escalating anti-stagnation
 * pressure applied on top of the corrected, rarity-ceiling-respecting dynamic-reincarnation rule
 * above: as a game's Fate Harvest discard pile is reshuffled again and again, the card types the
 * evidence associates with *longer* games become progressively less likely to grow more abundant
 * at each successive reshuffle — disabled entirely ([ReincarnationConfig.stagnationPressure] left
 * `null`) is the corrected model's own unmodified behavior; this class only ever runs when a
 * caller opts in via [ReincarnationConfig.ANTI_STAGNATION] or an equivalent custom config.
 *
 * **Evidence source, corrected**: [cardWeights]' default, [CORRECTED_BASELINE_STAGNATION_WEIGHTS],
 * is derived from a dedicated corrected-model baseline benchmark
 * (`docs/benchmarks/dynamic-reincarnation-benchmark-corrected.md`) run with
 * [ReincarnationConfig.DEFAULT] (no stagnation pressure) under this file's own whole-game rarity
 * ceiling — **not** from the earlier, uncapped Phase 1B run
 * (`docs/benchmarks/dynamic-reincarnation-benchmark.md`), which the user explicitly ruled remains
 * valid evidence only about that superseded, uncapped model and must not be reused as evidence for
 * this corrected one. That older report is preserved unmodified as historical record; this class's
 * own weights come exclusively from the corrected-model run.
 *
 * **What "evidence-weighted" means here, concretely** — the two things the user's own spec asked
 * to be evidence-driven rather than uniform:
 * - **Which card types get suppressed at all, and how strongly.** [cardWeights] holds only the
 *   *positive* Pearson correlations from the corrected baseline's own pooled card-type-final-
 *   multiplicity-vs-game-length analysis, used directly as a 0.0-1.0 suppression weight (a
 *   stronger positive correlation gets proportionately stronger pressure once escalation is
 *   non-zero). Every card the corrected baseline associates with *shorter* games and every card
 *   with no measured correlation at all defaults to weight 0.0 and is never touched by this
 *   mechanism, regardless of how common it becomes — the user's own explicit "do not reduce cards
 *   merely because they become common if their prevalence contributes to resolution" ruling,
 *   applied literally: this file only ever suppresses, never boosts, and only ever suppresses a
 *   *positively*-weighted type.
 * - **Which multiplicity-state transition gets suppressed.** The corrected baseline only measures
 *   a card's *final* multiplicity (a count, itself now bounded by that card's own canonical
 *   rarity) against game length, not a separate per-transition-type breakdown — so "the
 *   multiplicity state associated with prolonging play," for a positively-weighted card, is read
 *   as *becoming more abundant, up to its own ceiling* (the correlation is with a higher final
 *   count, after all). Concretely: [DynamicReincarnationRules.applyStagnationPressure] shaves
 *   probability off that card's own "up" transition outcomes specifically (never its "down" or
 *   "stay" outcomes, which are left completely alone or even boosted by the redirected mass) —
 *   and, in the 4-copy bucket (which, under the rarity-ceiling model, has no "up" outcome at all —
 *   a card there is already at its own maximum possible count), boosts the existing drop-by-one
 *   probability instead. Both are the same underlying idea: nudge the type's own trajectory toward
 *   *fewer* copies, never touch what happens to any other type's own outcomes.
 *
 * **Escalation across successive reshuffles**, the user's other explicit requirement ("allow
 * early reincarnations to evolve naturally... stronger corrective pressure at later
 * reincarnations"): [escalation] maps a game's own regeneration count so far (0 at the very first
 * reshuffle) to a `[0.0, 1.0]` multiplier on every weighted card's pressure this regeneration.
 * The default, a plain linear ramp capped at 1.0, means the first reshuffle of every game applies
 * zero pressure (pure natural evolution, exactly as asked), and pressure keeps climbing at every
 * reshuffle after that until it saturates. This is a swappable *function* (matching
 * [ReincarnationConfig.refillSelector]/[ReincarnationConfig.cullSelector]'s own existing pattern
 * of exposing selection *strategies*, not just numbers) so a future config can substitute a
 * different curve shape (a delayed ramp, a step function, a sigmoid) without touching
 * [DynamicReincarnationRules]'s own logic at all — the two numbers baked into the default closure
 * (a 0.15-per-generation ramp rate, coerced to `[0.0, 1.0]`) are this session's own chosen
 * default, not a semantic invariant of what "escalation" means.
 *
 * **Determinism**: neither [cardWeights] nor [escalation] consumes [Random] at all — every
 * pressure computation is pure arithmetic derived from state the caller already tracks
 * (generation index) and a static evidence table, so [DynamicReincarnationRules.transition]'s own
 * "always exactly one [Random.nextDouble] call, regardless of config" guarantee holds completely
 * unchanged with this mechanism enabled — "same seed -> same game" still holds for
 * [ReincarnationConfig.ANTI_STAGNATION] exactly as it does for [ReincarnationConfig.DEFAULT].
 */
data class StagnationPressureConfig(
    /** Card name -> suppression weight in `[0.0, 1.0]`. A name absent from this map (via
     * [Map.get] returning `null`, handled the same as an explicit 0.0) is never suppressed. See
     * this class's own doc for why only positively-correlated cards from the corrected baseline
     * appear here at all. */
    val cardWeights: Map<String, Double> = CORRECTED_BASELINE_STAGNATION_WEIGHTS,
    /** Maps a game's own regeneration count so far (0 = about to run the very first reshuffle) to
     * a `[0.0, 1.0]` pressure multiplier. Default: a linear ramp, 0.0 at generation 0, +0.15 per
     * successive generation, capped at 1.0 from generation ~6.7 onward. */
    val escalation: (generationIndex: Int) -> Double = { generationIndex -> (generationIndex * 0.15).coerceIn(0.0, 1.0) },
) {
    companion object {
        /**
         * Positive Pearson correlations from the corrected-model baseline run
         * (`docs/benchmarks/dynamic-reincarnation-benchmark-corrected.md`'s own card-type-final-
         * multiplicity-vs-game-length table), kept only where r > 0 (associated with LONGER/
         * prolonged games) — every other card, including every negatively-correlated one, is
         * simply absent (defaults to weight 0.0 via [Map.get]). This session's own derived default
         * from that corrected run's empirical data, not an immutable engine constant — a future
         * re-analysis could supply a different [cardWeights] map without touching any other part
         * of this mechanism. Explicitly NOT derived from the earlier, superseded Phase 1B/uncapped
         * run — see this class's own doc.
         */
        val CORRECTED_BASELINE_STAGNATION_WEIGHTS: Map<String, Double> = mapOf(
            "Radiation Burst" to 0.408,
            "Graviton Rift" to 0.267,
            "Fluidic Wave" to 0.254,
            "Materialize Army" to 0.172,
            "Parallel Phasing" to 0.158,
        )

        /** Phase 1C's own validated default — [CORRECTED_BASELINE_STAGNATION_WEIGHTS] plus the
         * default linear [escalation] ramp. What [ReincarnationConfig.ANTI_STAGNATION] actually
         * uses. */
        val DEFAULT = StagnationPressureConfig()
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
