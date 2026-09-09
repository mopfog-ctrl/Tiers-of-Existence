# T.O.E. Experimental Dynamic Fate Harvest Reincarnation Benchmark (Phase 1B)

**Experimental, not canonical.** Compares the candidate dynamic-reincarnation reshuffle rule (`DynamicReincarnationRules`) against the validated Baseline A fixed-composition benchmark (commit `d530a21`, `docs/benchmarks/player-count-benchmark.md`). No balance or canon decision is made or recommended by this report - it measures effects only, per the task's own explicit scope.

Scale: 1000 games/cohort x 5 player counts = 5000 total games, single stage, seed = 1300000000 + playerCount * 10000000 + gameIndex (independent of both Baseline A seed ranges).

## Resolved experimental parameters

- **1-copy transition** (corrected from the originally-stated duplicated-outcome version): 1->2: 33%, 1->0: 33%, remain at 1: 34%.
- **Overflow correction**: applied only after every card type's own transition is resolved (never mid-sequence, so no card type gets a structural ordering advantage) - uniform random removal *without replacement across individual physical cards*, not card types, so a type with more provisional copies gets proportionately more removal exposure.
- **Underflow correction**: remaining slots filled one at a time, each an independent uniform random pick *with replacement* across every color-legal card type (not weighted by canonical rarity) - a type currently at 0 copies is exactly as eligible as any other.
- **Regeneration target size**: exactly the discard pile's own size at that moment, never the game's global deck size - required for whole-game card-total conservation, since cards currently held in a hand aren't touched by a regeneration event. Enforced as a hard invariant inside `FateHarvestDeck.draw()` itself (a strategy that returns the wrong size throws immediately).
- **"Current multiplicity"**: the literal count of each type physically present in the discard pile about to regenerate - not a separately-tracked persistent ecology counter. See `DynamicReincarnationRules`'s own class doc for the full reasoning.

## Table G1 - Experimental B vs. Baseline A (game-length and cost)

| Player count | B mean turns | A mean turns | Abs diff | % diff | B median | A median | B p90 | A p90 | B p95 | A p95 | B cap rate | A cap rate |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2 | 652.2 | 633.3 | 18.9 | 3.0% | 478 | 503 | 1236 | 1204.0 | 1575 | 1516.0 | 0.00% | 0.00% |
| 3 | 988.9 | 926.9 | 62.0 | 6.7% | 688 | 724 | 2007 | 1774.0 | 2956 | 2207.0 | 0.30% | 0.00% |
| 4 | 1572.3 | 1286.2 | 286.1 | 22.2% | 1009 | 1001 | 3560 | 2502.0 | 4924 | 3233.0 | 2.00% | 0.10% |
| 5 | 2083.8 | 1757.7 | 326.1 | 18.6% | 1213 | 1291 | 5738 | 3621.0 | 8000 | 4745.0 | 5.70% | 0.50% |
| 6 | 2614.0 | 2419.2 | 194.8 | 8.1% | 1557 | 1821 | 8000 | 5241.0 | 8000 | 6737.0 | 10.40% | 2.70% |

## Table G2 - Experimental B vs. Baseline A (structure, seat load, engine cost)

| Player count | B Rounds/game | A Rounds/game | B human-seat turns | A human-seat turns | B decisions/seat | A decisions/seat | B ms/game | A ms/game | B ms/turn | A ms/turn |
|---|---|---|---|---|---|---|---|---|---|---|
| 2 | 227.6 | 220.4 | 326.1 | 316.6 | 1940.1 | 1877.7 | 1.887 | 1.247 | 0.0029 | 0.0020 |
| 3 | 241.1 | 223.8 | 329.6 | 309.0 | 2434.0 | 2251.4 | 2.388 | 2.212 | 0.0024 | 0.0024 |
| 4 | 296.1 | 239.7 | 393.1 | 321.5 | 3499.2 | 2799.2 | 4.306 | 3.417 | 0.0027 | 0.0027 |
| 5 | 324.8 | 267.2 | 416.8 | 351.5 | 4422.8 | 3580.9 | 6.457 | 5.402 | 0.0031 | 0.0031 |
| 6 | 347.2 | 312.8 | 435.7 | 403.2 | 5387.7 | 4739.5 | 9.364 | 8.253 | 0.0036 | 0.0034 |

## Table G3 - SEM and 95% CI (Experimental B mean turns), and statistical distinguishability from Baseline A

| Player count | B mean turns | B SEM | B 95% CI | A mean turns | A SEM | z (B vs A) | Distinguishable from A? |
|---|---|---|---|---|---|---|---|
| 2 | 652.2 | 18.92 | [615.1, 689.3] | 633.3 | 11.43 | 0.86 | No |
| 3 | 988.9 | 30.45 | [929.2, 1048.6] | 926.9 | 17.33 | 1.77 | No |
| 4 | 1572.3 | 50.61 | [1473.1, 1671.5] | 1286.2 | 25.65 | 5.04 | **Yes** (|z|>1.96) |
| 5 | 2083.8 | 66.81 | [1952.9, 2214.8] | 1757.7 | 36.81 | 4.28 | **Yes** (|z|>1.96) |
| 6 | 2614.0 | 77.23 | [2462.6, 2765.4] | 2419.2 | 49.18 | 2.13 | **Yes** (|z|>1.96) |

## Table G4 - Generation depth (deck regenerations per game)

| Player count | Mean generations/game | Median generations/game | Mean refill slots/game | Mean culled cards/game |
|---|---|---|---|---|
| 2 | 1.0 | 1 | 2.31 | 1.05 |
| 3 | 1.9 | 1 | 4.60 | 2.01 |
| 4 | 3.4 | 2 | 9.17 | 2.99 |
| 5 | 4.9 | 2 | 13.35 | 4.44 |
| 6 | 6.5 | 3 | 17.90 | 5.42 |

## Table G5 - Card-type final-multiplicity vs. game-length correlation (pooled across all player counts, n=5000)

Pearson correlation between a card type's *final* multiplicity (at that game's last regeneration, or Generation 0 if none occurred) and that game's total turn count. Correlational, not causal - from randomized simulation, pooled across player counts (a confound: player count itself strongly affects both game length and how many regenerations occur - see Table G4 - so a correlation here reflects both any direct card-type effect AND this pooling confound; not separated further in this pass).

**Top 5 positively correlated with longer games:**

| Card | r |
|---|---|
| Radiation Burst | 0.408 |
| Graviton Rift | 0.267 |
| Fluidic Wave | 0.254 |
| Materialize Army | 0.172 |
| Parallel Phasing | 0.158 |

**Top 5 negatively correlated (associated with shorter games):**

| Card | r |
|---|---|
| Phase Control | -0.401 |
| Sidestep (Extinction Avoidance) | -0.400 |
| Circulate (Elemental) | -0.392 |
| Last Gasp | -0.370 |
| Lucky Nebula | -0.227 |

## Analysis

1. **Direction and consistency of effect**: dynamic reincarnation consistently LENGTHENS games (every player count's mean turns is higher under Experimental B than Baseline A). Per-player-count absolute mean-turns difference vs. Baseline A: 2P: 18.9, 3P: 62.0, 4P: 286.1, 5P: 326.1, 6P: 194.8.
2. **Player-count dependence**: percentage difference from Baseline A by player count - 2P: 2.99%, 3P: 6.69%, 4P: 22.25%, 5P: 18.55%, 6P: 8.05%.
3. **Central tendency vs. tails**: median difference from Baseline A by player count - 2P: -25.0, 3P: -36.0, 4P: 8.0, 5P: -78.0, 6P: -264.0; p95 difference - 2P: 59.0, 3P: 749.0, 4P: 1691.0, 5P: 3255.0, 6P: 1263.0. A p95 shift materially larger (in either direction) than the median shift at the same player count indicates the mechanic is primarily reshaping the tail, not the typical game.
4. **Cap-rate effect**: percentage-point difference in cap rate (hit the 8000-turn cap without a winner) vs. Baseline A - 2P: 0.00pp, 3P: 0.30pp, 4P: 1.90pp, 5P: 5.20pp, 6P: 7.70pp.
5. **Statistical distinguishability**: see Table G3 - the z-score column reports, per player count, whether Experimental B's mean turns is distinguishable from Baseline A's given sampling noise (|z|>1.96), rather than treating any nonzero observed difference as necessarily real.
6. **Generation depth**: mean regenerations/game by player count - 2P: 1.0, 3P: 1.9, 4P: 3.4, 5P: 4.9, 6P: 6.5 (see Table G4). A mechanic that only acts at recycle boundaries has more opportunity to influence sufficiently long games - higher-player-count cohorts (longer games, per both Baseline A and this experimental run) see correspondingly more regenerations.
7. **Card types most associated with game length**: see Table G5 - reported as correlations from randomized simulation, explicitly not causal claims, and explicitly confounded with player count (pooled across cohorts rather than separated, in this pass).
8. **Correctness**: 0 invariant violations across 5000 experimental games (total-conservation, token-conservation, Phase-validity, and winner-square checks all held throughout; the fixed-composition per-card-name invariant was deliberately NOT applied to this experimental mode, per its own design).

No balance change, canon decision, or recommendation is made based on the above - this is a measurement of the current candidate mechanic's effect, reported for a later, separate decision.

Total experimental games run: 5000. Total invariant violations: 0.
