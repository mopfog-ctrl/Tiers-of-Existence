package com.tiersofexistence.engine.benchmark

/**
 * Every [com.tiersofexistence.engine.rules.TurnDecisionProvider] callback the benchmark
 * instruments — one entry per distinct decision-opportunity type, matching the interface's own
 * methods. `CLEANSING_DISCARD` is Cleansing's targeted-opponent decision — `TurnDriver` resolves
 * it via `decisionsFor(decidingPlayer)`, i.e. the same per-player provider lookup every other
 * callback goes through, so it's recorded against the deciding player's own tally exactly like
 * any other decision, not specially routed.
 */
enum class DecisionType {
    TOKEN_TO_MOVE,
    ENTER_ZONE,
    BUILD_MARAUDER,
    TRANSPORT,
    HELD_CARD_BEFORE_ROLL,
    CARD_AFTER_ROLL_BEFORE_MOVE,
    IMMEDIATE_CARD_TARGETS,
    PRECEDENCE_RESPONSE,
    CLEANSING_DISCARD,
}

/** One decision type's tally for one seat across however many games it's accumulated over —
 * [asked] is every call, regardless of how many legal alternatives existed;
 * [substantive] is the subset where more than one meaningfully different outcome was actually
 * available (see [InstrumentedDecisionProvider]'s per-method doc for exactly what "substantive"
 * means for each type — this is a best-effort, cheaply-computed distinction, not a full
 * re-derivation of [com.tiersofexistence.engine.cards.play.TargetValidator] legality). */
data class DecisionTally(var asked: Long = 0, var substantive: Long = 0) {
    fun record(isSubstantive: Boolean) {
        asked += 1
        if (isSubstantive) substantive += 1
    }

    operator fun plus(other: DecisionTally) = DecisionTally(asked + other.asked, substantive + other.substantive)
}

/** All decision tallies for one seat (one [com.tiersofexistence.engine.model.PlayerColor] in one
 * game, or aggregated across many). */
class SeatDecisionStats {
    private val tallies: MutableMap<DecisionType, DecisionTally> = mutableMapOf()

    fun record(type: DecisionType, isSubstantive: Boolean) {
        tallies.getOrPut(type) { DecisionTally() }.record(isSubstantive)
    }

    fun tally(type: DecisionType): DecisionTally = tallies[type] ?: DecisionTally()

    fun totalAsked(): Long = DecisionType.entries.sumOf { tally(it).asked }
    fun totalSubstantive(): Long = DecisionType.entries.sumOf { tally(it).substantive }

    companion object {
        fun merge(stats: List<SeatDecisionStats>): SeatDecisionStats {
            val merged = SeatDecisionStats()
            for (s in stats) {
                for (type in DecisionType.entries) {
                    val t = s.tally(type)
                    if (t.asked > 0) {
                        merged.tallies[type] = (merged.tallies[type] ?: DecisionTally()) + t
                    }
                }
            }
            return merged
        }
    }
}
