# T.O.E. Fate Harvest Regeneration Benchmark - CORRECTED whole-game rarity-ceiling model, clean rerun (Phase 1B, corrected)

**Experimental, not canonical.** Re-establishes Phase 1B's own Fate Harvest regeneration baseline under the corrected whole-game rarity-ceiling model (`FateHarvestRegenerationRules`) - no card type may ever exceed its own canonical rarity across draw pile + discard pile + every hand + every currently-resolving card, for the whole game. This is the clean rerun after fixing the resolving-card accounting gap the first attempt found - see `docs/benchmarks/dynamic-reincarnation-benchmark-corrected-diagnostic.md` (preserved, not reused as weighting evidence) for that defect's own record. Compared against the validated Baseline A fixed-composition benchmark (commit `d530a21`, `docs/benchmarks/player-count-benchmark.md`). The original, uncapped-model Phase 1B run (`docs/benchmarks/dynamic-reincarnation-benchmark.md`) remains preserved, unmodified, as historical record of that superseded model. No balance or canon decision is made or recommended by this report either - it measures effects only; deriving Phase 1C weights from Table K5 below is a separate, later step.

Scale: 1000 games/cohort x 5 player counts = 5000 total games, single stage, seed = 2100000000 + playerCount * 10000000 + gameIndex (independent of every other benchmark's own seed range).

## What changed since the first (contaminated) attempt

- **`GameState.resolvingCards`**: a new, explicit fourth card zone (alongside draw pile / discard pile / hands) - a drawn `CardTiming.IMMEDIATE` card lives here from the instant `TurnEngine` draws it until whatever resolves it (`TurnDriver.resolveImmediateCard`, or `discardStrandedImmediateCard` for a card-driven-move landing) discards it, closing the exact window the first attempt's 73 violations came from.
- **`GameState.liveCardCountsOutsideDiscardPile()`**: the new single source of truth for "how many copies of each type currently exist outside the discard pile being regenerated" - every player's hand plus every resolving card - replacing the earlier hand-only computation every `ReshuffleStrategy` closure had to reimplement itself.
- Every transition/refill/cull rule and the rarity-ceiling model itself are otherwise completely unchanged from the first attempt - this is a state-accounting fix, not a rule or resolution-order change (the user's own explicit instruction: "Fix state representation, not game sequencing").

## Table K1 - Corrected-model B vs. Baseline A (game-length and cost)

| Player count | B mean turns | A mean turns | Abs diff | % diff | B median | A median | B p90 | A p90 | B p95 | A p95 | B cap rate | A cap rate |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2 | 669.0 | 633.3 | 35.7 | 5.6% | 535 | 503 | 1265 | 1204.0 | 1601 | 1516.0 | 0.00% | 0.00% |
| 3 | 869.7 | 926.9 | -57.3 | -6.2% | 705 | 724 | 1674 | 1774.0 | 2164 | 2207.0 | 0.00% | 0.00% |
| 4 | 1327.3 | 1286.2 | 41.1 | 3.2% | 1067 | 1001 | 2635 | 2502.0 | 3416 | 3233.0 | 0.00% | 0.10% |
| 5 | 1776.9 | 1757.7 | 19.2 | 1.1% | 1421 | 1291 | 3578 | 3621.0 | 4544 | 4745.0 | 0.40% | 0.50% |
| 6 | 2424.4 | 2419.2 | 5.2 | 0.2% | 1796 | 1821 | 5373 | 5241.0 | 6888 | 6737.0 | 3.10% | 2.70% |

## Table K3 - SEM and statistical distinguishability from Baseline A

| Player count | B mean turns | B SEM | z (B vs A) | Distinguishable from A? |
|---|---|---|---|---|
| 2 | 669.0 | 15.25 | 1.87 | No |
| 3 | 869.7 | 19.67 | -2.18 | **Yes** |
| 4 | 1327.3 | 31.51 | 1.01 | No |
| 5 | 1776.9 | 42.69 | 0.34 | No |
| 6 | 2424.4 | 61.14 | 0.07 | No |

## Table K4 - Generation depth (deck regenerations per game)

| Player count | Mean generations/game | Median generations/game | Mean refill slots/game | Mean culled cards/game |
|---|---|---|---|---|
| 2 | 1.0 | 1 | 9.37 | 0.00 |
| 3 | 1.6 | 1 | 14.68 | 0.00 |
| 4 | 2.7 | 2 | 25.79 | 0.00 |
| 5 | 3.9 | 3 | 38.17 | 0.00 |
| 6 | 5.6 | 4 | 55.07 | 0.00 |

## Table K5 - Card-type final-multiplicity vs. game-length correlation, corrected model, clean run (pooled across all player counts, n=5000)

Pearson correlation between a card type's *final* multiplicity (bounded by its own canonical rarity - at that game's last regeneration, or Generation 0 if none occurred) and that game's total turn count. Correlational, not causal - pooled across player counts (a confound: player count itself strongly affects both game length and how many regenerations occur - see Table K4). **This table - from this clean run, not the contaminated first attempt or the original superseded uncapped-model Table G5 - is the evidence source for `StagnationPressureConfig.CORRECTED_BASELINE_STAGNATION_WEIGHTS`, once the weight re-derivation step actually runs (a separate, later step this file does not itself perform).**

**Top 5 positively correlated with longer games:**

| Card | r |
|---|---|
| Graviton Rift | 0.368 |
| Lucky Nebula | 0.365 |
| Elemental Rebirth | 0.365 |
| Evasive Action | 0.365 |
| Infernal Abyss | 0.365 |

**Top 5 negatively correlated (associated with shorter games):**

| Card | r |
|---|---|
| Corpuscle Rot | 0.189 |
| Verdant Growth | 0.194 |
| Plasma Burst | 0.204 |
| Dwarf Star | 0.206 |
| Planetary Nebula | 0.262 |

## Analysis

1. **Direction vs. Baseline A**: per-player-count absolute mean-turns difference - 2P: 35.7, 3P: -57.3, 4P: 41.1, 5P: 19.2, 6P: 5.2.
2. **Correctness**: 0 invariant violations across 5000 games, including the whole-game rarity-ceiling invariant (no card type's live population - draw pile + discard pile + hands + resolving cards - ever exceeded its own canonical rarity) and resolvingCards being empty between every turn. This is the clean result the first attempt's 73 violations were meant to become.

No balance change, canon decision, or recommendation is made based on the above - this is a corrected-model measurement, reported for a later, separate decision, and (only if clean) the intended evidence basis for Phase 1C's own re-derived weights.

Total games run: 5000. Total invariant violations: 0.
