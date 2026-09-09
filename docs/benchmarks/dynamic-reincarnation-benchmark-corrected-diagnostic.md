# T.O.E. Experimental Dynamic Fate Harvest Reincarnation Benchmark - CORRECTED whole-game rarity-ceiling model (Phase 1B, corrected)

**Experimental, not canonical.** Re-establishes Phase 1B's own dynamic-reincarnation baseline under the corrected whole-game rarity-ceiling model (`DynamicReincarnationRules`) - no card type may ever exceed its own canonical rarity across draw pile + discard pile + every hand, for the whole game. Compared against the validated Baseline A fixed-composition benchmark (commit `d530a21`, `docs/benchmarks/player-count-benchmark.md`). The original, uncapped-model Phase 1B run (`docs/benchmarks/dynamic-reincarnation-benchmark.md`) remains preserved, unmodified, as historical record of that superseded model - it is explicitly NOT the baseline this report's own Table K5 evidence is used for. No balance or canon decision is made or recommended by this report either - it measures effects only.

Scale: 1000 games/cohort x 5 player counts = 5000 total games, single stage, seed = 2000000000 + playerCount * 10000000 + gameIndex (independent of every other benchmark's own seed range).

## What changed from the original Phase 1B run

- **Whole-game rarity ceiling**: a card type's canonical rarity (1/2/3/4 copies) is now enforced as a hard maximum on its live population across draw pile + discard pile + every hand, not merely within the discard pile being regenerated - refill only ever selects among types with remaining whole-game capacity, and `transition` can never propose a count exceeding that capacity (folded into "stay," same as unlisted probability mass already means).
- **The generalized ">=4 copies" bucket is removed** - it existed only to handle refill pushing a type past 4 in the old uncapped model, a state the corrected model makes structurally impossible. The 4-copy bucket's own rule (50% -> 3, 50% remain at 4) is unchanged, just no longer generalized upward.
- Every other transition/refill/cull rule (1/2/3-copy transition probabilities, uniform-without-replacement culling, uniform-with-replacement refill among currently-eligible types, regeneration target size always exactly the discard pile's own size) is completely unchanged from the original Phase 1B run.

## Table K1 - Corrected-model B vs. Baseline A (game-length and cost)

| Player count | B mean turns | A mean turns | Abs diff | % diff | B median | A median | B p90 | A p90 | B p95 | A p95 | B cap rate | A cap rate |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2 | 640.0 | 633.3 | 6.7 | 1.1% | 499 | 503 | 1255 | 1204.0 | 1534 | 1516.0 | 0.00% | 0.00% |
| 3 | 911.2 | 926.9 | -15.7 | -1.7% | 717 | 724 | 1742 | 1774.0 | 2300 | 2207.0 | 0.00% | 0.00% |
| 4 | 1298.6 | 1286.2 | 12.4 | 1.0% | 1018 | 1001 | 2529 | 2502.0 | 3234 | 3233.0 | 0.10% | 0.10% |
| 5 | 1705.7 | 1757.7 | -52.0 | -3.0% | 1291 | 1291 | 3487 | 3621.0 | 4426 | 4745.0 | 0.50% | 0.50% |
| 6 | 2323.8 | 2419.2 | -95.4 | -3.9% | 1740 | 1821 | 4916 | 5241.0 | 6270 | 6737.0 | 2.00% | 2.70% |

## Table K3 - SEM and statistical distinguishability from Baseline A

| Player count | B mean turns | B SEM | z (B vs A) | Distinguishable from A? |
|---|---|---|---|---|
| 2 | 640.0 | 13.89 | 0.37 | No |
| 3 | 911.2 | 21.24 | -0.57 | No |
| 4 | 1298.6 | 31.50 | 0.31 | No |
| 5 | 1705.7 | 43.53 | -0.91 | No |
| 6 | 2323.8 | 57.03 | -1.27 | No |

## Table K4 - Generation depth (deck regenerations per game)

| Player count | Mean generations/game | Median generations/game | Mean refill slots/game | Mean culled cards/game |
|---|---|---|---|---|
| 2 | 1.0 | 1 | 8.86 | 0.00 |
| 3 | 1.7 | 1 | 15.66 | 0.00 |
| 4 | 2.7 | 2 | 25.34 | 0.00 |
| 5 | 3.8 | 3 | 36.26 | 0.00 |
| 6 | 5.4 | 4 | 53.13 | 0.00 |

## Table K5 - Card-type final-multiplicity vs. game-length correlation, corrected model (pooled across all player counts, n=5000)

Pearson correlation between a card type's *final* multiplicity (now bounded by its own canonical rarity - at that game's last regeneration, or Generation 0 if none occurred) and that game's total turn count. Correlational, not causal - pooled across player counts (a confound: player count itself strongly affects both game length and how many regenerations occur - see Table K4). **This table, not the original superseded Table G5, is the evidence source for `StagnationPressureConfig.CORRECTED_BASELINE_STAGNATION_WEIGHTS`.**

**Top 5 positively correlated with longer games:**

| Card | r |
|---|---|
| Materialize Help | 0.375 |
| Luckier Nebula | 0.375 |
| Lucky Nebula | 0.375 |
| Phase Control | 0.375 |
| Divine Assistance | 0.375 |

**Top 5 negatively correlated (associated with shorter games):**

| Card | r |
|---|---|
| Verdant Growth | 0.186 |
| Plasma Burst | 0.193 |
| Dwarf Star | 0.230 |
| Corpuscle Rot | 0.234 |
| Planetary Nebula | 0.264 |

## Analysis

1. **Direction vs. Baseline A**: per-player-count absolute mean-turns difference - 2P: 6.7, 3P: -15.7, 4P: 12.4, 5P: -52.0, 6P: -95.4.
2. **Correctness**: **73 violation(s) found** - see raw detail.

No balance change, canon decision, or recommendation is made based on the above - this is a corrected-model measurement, reported for a later, separate decision, and as the evidence basis for Phase 1C's own re-derived weights.

Total games run: 5000. Total invariant violations: 73.
