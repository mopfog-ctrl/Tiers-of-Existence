# T.O.E. Experimental Anti-Stagnation Fate Harvest Regeneration Benchmark (Phase 1C, vs. corrected baseline)

**Experimental, not canonical.** Compares the evidence-weighted anti-stagnation reshuffle rule (`FateHarvestRegenerationConfig.ANTI_STAGNATION`) against **the clean corrected-model baseline as the control** (`PlayerCountFateHarvestRegenerationCorrectedBenchmarkTest`, `docs/benchmarks/fate-harvest-regeneration-benchmark-corrected.md`, 5,000 games, 0 violations, hardcoded below as `CORRECTED_BASELINE`), with unmodified Baseline A (commit `d530a21`, `docs/benchmarks/player-count-benchmark.md`) shown alongside for additional context only. Neither `FateHarvestRegenerationRules`'s transition/refill/cull mechanics nor the whole-game rarity ceiling are altered here - only `StagnationPressureConfig`'s evidence-derived suppression weights (see `FateHarvestRegenerationRules.kt`'s own class doc for their derivation from the corrected baseline's full Table K5) are new. No balance or canon decision is made or recommended by this report - it measures effects only, per the task's own explicit scope.

Scale: 1000 games/cohort x 5 player counts = 5000 total games, single stage, seed = 1700000000 + playerCount * 10000000 + gameIndex (independent of Baseline A's and the corrected baseline's own seed ranges).

## Mechanism

- **Same underlying transition/refill/cull rules and whole-game rarity ceiling as the corrected-model control** (`FateHarvestRegenerationRules`, `FateHarvestRegenerationConfig.DEFAULT`), with `FateHarvestRegenerationConfig.stagnationPressure` additionally set to `StagnationPressureConfig.DEFAULT` - the only difference from the control.
- **Evidence-weighted suppression, derived from the corrected baseline's own full Table K5** (all 32 card types, not an excerpt): every card type in the deck showed a positive pooled correlation with game length on this clean data (r = 0.189 to 0.368, none negative - see `StagnationPressureConfig`'s own class doc for that finding and the likely base-rate confound behind it), so `StagnationPressureConfig.CORRECTED_BASELINE_STAGNATION_WEIGHTS` suppresses every card type, each proportionally to its own measured correlation strength - the strongest (Graviton Rift, 0.368) gets roughly double the pressure of the weakest (Corpuscle Rot, 0.189). Had any card shown a negative correlation (associated with shorter games) or no measured correlation at all, it would default to weight 0.0 and never be suppressed regardless of how common it becomes - that carve-out is still implemented, it simply never fires on this data.
- **Multiplicity-state targeting**: suppression acts specifically on a weighted card's own upward-count transitions (becoming more abundant) - shifting that probability mass toward its downward transitions - and, in the 4+-copy bucket (which has no upward outcome to begin with), boosts the existing drop-by-one probability instead.
- **Escalation across successive reshuffles**: a linear ramp from 0.0 pressure at a game's very first reshuffle (pure natural evolution, exactly as asked) to full weighted pressure by roughly the 7th reshuffle of that same game - the generation counter is tracked per game and threaded through every regeneration event.

## Table H1 - Anti-Stagnation (C) vs. corrected-baseline control (Control) vs. Baseline A - game length

| Player count | C mean turns | Control mean turns | A mean turns | C vs Control % | C vs A % | C median | Control median | A median | C p95 | Control p95 | A p95 | C cap rate | Control cap rate | A cap rate |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2 | 625.0 | 669.0 | 633.3 | -6.58% | -1.32% | 505 | 535 | 503 | 1476 | 1601.0 | 1516.0 | 0.00% | 0.00% | 0.00% |
| 3 | 911.5 | 869.7 | 926.9 | 4.81% | -1.66% | 729 | 705 | 724 | 2097 | 2164.0 | 2207.0 | 0.00% | 0.00% | 0.00% |
| 4 | 1251.3 | 1327.3 | 1286.2 | -5.72% | -2.71% | 972 | 1067 | 1001 | 3154 | 3416.0 | 3233.0 | 0.10% | 0.00% | 0.10% |
| 5 | 1801.3 | 1776.9 | 1757.7 | 1.37% | 2.48% | 1347 | 1421 | 1291 | 4804 | 4544.0 | 4745.0 | 0.50% | 0.40% | 0.50% |
| 6 | 2472.7 | 2424.4 | 2419.2 | 1.99% | 2.21% | 1945 | 1796 | 1821 | 6547 | 6888.0 | 6737.0 | 2.40% | 3.10% | 2.70% |

## Table H2 - SEM and statistical distinguishability of C from the corrected-baseline control and from Baseline A

| Player count | C mean turns | C SEM | z (C vs Control) | Distinguishable from Control? | z (C vs A) | Distinguishable from A? |
|---|---|---|---|---|---|---|
| 2 | 625.0 | 13.71 | -2.15 | **Yes** | -0.47 | No |
| 3 | 911.5 | 19.91 | 1.49 | No | -0.58 | No |
| 4 | 1251.3 | 30.07 | -1.74 | No | -0.88 | No |
| 5 | 1801.3 | 45.50 | 0.39 | No | 0.75 | No |
| 6 | 2472.7 | 60.38 | 0.56 | No | 0.69 | No |

## Table H3 - Generation depth (deck regenerations per game)

| Player count | Mean generations/game | Median generations/game | Mean refill slots/game | Mean culled cards/game |
|---|---|---|---|---|
| 2 | 0.9 | 1 | 8.60 | 0.00 |
| 3 | 1.7 | 1 | 16.54 | 0.00 |
| 4 | 2.6 | 2 | 26.54 | 0.00 |
| 5 | 4.0 | 3 | 44.55 | 0.00 |
| 6 | 5.8 | 4 | 67.42 | 0.00 |

## Analysis

1. **Effect vs. the corrected-baseline control**: Anti-Stagnation has a MIXED / player-count-dependent effect relative to the corrected-baseline control (not uniformly shorter or longer across all 5 cohorts). Per-player-count absolute mean-turns difference vs. the control: 2P: -44.0, 3P: 41.8, 4P: -76.0, 5P: 24.4, 6P: 48.3.
2. **Tail effect specifically (p95)**: p95 difference vs. the corrected-baseline control by player count - 2P: -125.0, 3P: -67.0, 4P: -262.0, 5P: 260.0, 6P: -341.0. A negative shift here indicates the mechanism is successfully compressing the control's own long-game tail; the mechanism's whole design intent is to act preferentially on this tail (deeper games mean more regenerations, which means more escalated pressure), so this is the single most direct test of whether it worked as designed.
3. **Comparison against unmodified Baseline A**: per-player-count absolute mean-turns difference vs. Baseline A - 2P: -8.3, 3P: -15.4, 4P: -34.9, 5P: 43.6, 6P: 53.5 (Baseline A never uses any regeneration mechanic at all, so this shows the net effect of rarity-ceiling regeneration-plus-suppression together, not the suppression's effect in isolation - see point 1/2 above for that).
4. **Cap-rate effect vs. the corrected-baseline control**: percentage-point difference in cap rate (hit the 8000-turn cap without a winner) - 2P: 0.00pp, 3P: 0.00pp, 4P: 0.10pp, 5P: 0.10pp, 6P: -0.70pp. A negative value at a given player count means fewer games under Anti-Stagnation hit the cap than under the control.
5. **Statistical distinguishability**: see Table H2 - z-scores against both the corrected-baseline control and Baseline A, rather than treating any nonzero observed difference as necessarily real.
6. **Generation depth**: mean regenerations/game by player count - 2P: 0.9, 3P: 1.7, 4P: 2.6, 5P: 4.0, 6P: 5.8 (see Table H3) - compare against the corrected baseline's own Table K4 figures to see whether suppression changed how often regeneration itself happens, not just its outcome.
7. **Correctness**: 0 invariant violations across 5000 experimental games (total-conservation, token-conservation, Phase-validity, and winner-square checks all held throughout; the fixed-composition per-card-name invariant was deliberately NOT applied to this experimental mode, since per-type multiplicity evolving is the whole point of both the corrected model and this suppression variant of it).

No balance change, canon decision, or recommendation is made based on the above - this is a measurement of the current candidate mechanic's effect, reported for a later, separate decision.

Total experimental games run: 5000. Total invariant violations: 0.
