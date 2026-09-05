package com.tiersofexistence.engine.rules

import com.tiersofexistence.engine.model.TierLevel

/**
 * The five Phases of a Round, in Phase Clock order (rulebook "Typical Game Round", p.4-5):
 * Marauder, then 4th Tier down to 1st Tier.
 */
sealed class Phase {
    data object Marauder : Phase()
    data class Tier(val tier: TierLevel) : Phase()

    companion object {
        /**
         * Full Phase order for one Round. 1st Tier is always last, so "the first Round of the
         * game only has a 1st Tier Phase. When the 1st Tier Phase ends, the Round is over"
         * (Rounds/Phases/Turns p.4) needs no special-casing: [TurnOrder.turnsFor] already
         * returns an empty list for a Phase with no eligible players (Marauder/4th/3rd/2nd in
         * Round 1), and `GameState.advancePhase` walking every entry in [ROUND_ORDER] once per
         * Round produces the right outcome on its own — a turn-resolution loop just needs to
         * call it once per Phase regardless of whether that Phase had any turns.
         *
         * Deliberately `by lazy` rather than a plain `=` initializer: building this list touches
         * the [Marauder] singleton and constructs [Tier] instances, both nested subclasses of
         * this very sealed class. An eager initializer runs as part of `Phase.Companion`'s own
         * construction, which is itself part of `Phase`'s `<clinit>` — so touching a subclass
         * whose superclass (`Phase`) is *still initializing* creates a legal-but-fragile
         * reentrant JVM class-initialization cycle (JVMS 5.5 permits it without deadlocking, but
         * it can observe `Phase` as "initialization in progress" rather than "initialized", so a
         * sealed-subtype singleton built during that window is not guaranteed non-null). This
         * was the root cause of a historical intermittent `NullPointerException` inside
         * [TurnOrder.turnsFor] (see CLAUDE.md and the stabilization report) — reproduced
         * reliably (~47% of runs) specifically when Gradle's test worker loads a non-contiguous,
         * multi-class `--tests`-filtered subset, which apparently perturbs class-load timing
         * enough to expose the race; a full unfiltered suite run never reproduced it. Deferring
         * construction to first actual use (`by lazy`) means `Phase`'s own `<clinit>` completes
         * fully before this list is ever built, so [Marauder]/[Tier] are always constructed
         * against a fully-initialized `Phase`, never a partially-initialized one.
         */
        val ROUND_ORDER: List<Phase> by lazy {
            listOf(
                Marauder,
                Tier(TierLevel.FOURTH),
                Tier(TierLevel.THIRD),
                Tier(TierLevel.SECOND),
                Tier(TierLevel.FIRST),
            )
        }
    }
}
