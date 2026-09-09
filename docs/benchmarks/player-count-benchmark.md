# T.O.E. Player-Count Gameplay-Length and Performance Benchmark

Baseline: commit `ba2448d`. No architectural or gameplay-rule changes were made for this task.

Seed scheme: `seed = stageBase + playerCount * 10000000 + gameIndex`, Stage 1 stageBase = 500000000, Stage 2 stageBase = 900000000 (independent, non-overlapping ranges).

Color rotation: cyclic rotation of `PlayerColor.entries` (GREEN, RED, BLACK, YELLOW, WHITE, BLUE) offset by each game's own index within its cohort, truncated to that cohort's player count - e.g. at 3 players, game 0 seats GREEN/RED/BLACK, game 1 seats RED/BLACK/YELLOW, etc., cycling every 6 games.

**Correctness defect found and fixed during this benchmarking pass (before the results below were produced):** the first full 8,750-game run hit a genuine invariant violation ("Winner declared but has no 4th-Tier in-play token on a YOU_WIN square" at 2P, seed 920001112, turn 445). Attempting to reproduce it standalone from that exact seed produced a completely different, clean game - tracing why led to a real engine defect: `FateHarvestDeck.draw()` fell back to the ambient/global `kotlin.random.Random` (not the game's own seeded generator) whenever it needed to reshuffle the discard pile back into the draw pile, which happens routinely once a long game exhausts its ~66-70 card deck. This broke "same seed -> same game" for every seeded simulation in this codebase (including `GameSimulationTest`) the moment any game ran long enough to reshuffle even once - not a benchmark-only issue. Fixed in `FateHarvestDeck` (now retains the `Random` it was constructed with and reuses it for every later reshuffle, for the deck's whole lifetime) - see that class's own doc for the full root-cause writeup. Verified fixed: the exact same seed range run twice now produces byte-identical gameplay statistics (turns, Rounds, decisions, cap counts) both times, differing only in wall-clock timing figures as expected. The results below are from the post-fix, now-genuinely-reproducible run - 0 invariant violations found in it.

## Table A - Canonical deck composition audit

| Card name | Rulebook multiplicity | Simulation multiplicity | Match? |
|---|---|---|---|
| Annulment (Antimatter) | 3 | 3 | Yes |
| Circulate (Elemental) | 4 | 4 | Yes |
| Cleansing (Atmospheric) | 3 | 3 | Yes |
| Corpuscle Rot | 1 | 1 | Yes |
| Delayed Motion | 3 | 3 | Yes |
| Divine Assistance | 2 | 2 | Yes |
| Dwarf Star | 1 | 1 | Yes |
| Elemental Rebirth | 3 | 3 | Yes |
| Emitting Nebula | 3 | 3 | Yes |
| Essence Assimilator | 2 | 2 | Yes |
| Evasive Action | 3 | 3 | Yes |
| Fluidic Wave | 1 | 1 | Yes |
| Galactic Roundabout | 1 | 1 | Yes |
| Graviton Rift | 1 | 1 | Yes |
| Infernal Abyss | 2 | 2 | Yes |
| Insidious Flux | 2 | 2 | Yes |
| Last Gasp | 4 | 4 | Yes |
| Luckier Nebula | 2 | 2 | Yes |
| Lucky Nebula | 3 | 3 | Yes |
| Materialize Army | 1 | 1 | Yes |
| Materialize Help | 2 | 2 | Yes |
| Parallel Phasing | 1 | 1 | Yes |
| Phase Control | 4 | 4 | Yes |
| Phase Loss | 2 | 2 | Yes |
| Planetary Nebula | 2 | 2 | Yes |
| Plasma Burst | 1 | 1 | Yes |
| Radiation Burst | 1 | 1 | Yes |
| Sidestep (Extinction Avoidance) | 4 | 4 | Yes |
| Skip, Hop, and Jump (Dimensional) | 2 | 2 | Yes |
| Tactical Motion | 2 | 2 | Yes |
| Tactical Step | 3 | 3 | Yes |
| Verdant Growth | 1 | 1 | Yes |
| **Total** | **70** | **70** | Yes (70) |

This is the canonical full-catalog audit (unfiltered, all 32 cards/70 physical copies) - the input every per-game deck below is built from. It is NOT what any individual game actually plays with: see Table A2 immediately below for the per-player-count deck actually used, now that canonical Color-card removal is applied (corrected - an earlier version of this benchmark used the unfiltered 70-card deck for every game; that was itself the non-canonical choice, per the user's ruling).

## Table A2 - Per-player-count deck composition audit (canonical Color-card removal applied)

Every game removes exactly one card (its restricted color's own, always `SINGLE` rarity) per unseated color before shuffling - never any other card, and every surviving card keeps its full canonical multiplicity. Which specific colors are unseated rotates game-to-game within a cohort (see `rotatedColors`), so "color-specific cards removed" below lists every color card this cohort's rotation actually excluded from at least one game, not one fixed set.

| Player count | Colors seated | Colors unseated | Color-specific cards removed (union across the cohort's rotation) | Expected deck size | Actual deck size observed (min-max) | Remaining-card multiplicities |
|---|---|---|---|---|---|---|
| 2 | 2 | 4 | Corpuscle Rot, Dwarf Star, Fluidic Wave, Graviton Rift, Plasma Burst, Verdant Growth | 66 | 66-66 (matches) | Canonical for every remaining card (removal only drops entire color-card definitions, never touches another card's copy count) |
| 3 | 3 | 3 | Corpuscle Rot, Dwarf Star, Fluidic Wave, Graviton Rift, Plasma Burst, Verdant Growth | 67 | 67-67 (matches) | Canonical for every remaining card (removal only drops entire color-card definitions, never touches another card's copy count) |
| 4 | 4 | 2 | Corpuscle Rot, Dwarf Star, Fluidic Wave, Graviton Rift, Plasma Burst, Verdant Growth | 68 | 68-68 (matches) | Canonical for every remaining card (removal only drops entire color-card definitions, never touches another card's copy count) |
| 5 | 5 | 1 | Corpuscle Rot, Dwarf Star, Fluidic Wave, Graviton Rift, Plasma Burst, Verdant Growth | 69 | 69-69 (matches) | Canonical for every remaining card (removal only drops entire color-card definitions, never touches another card's copy count) |
| 6 | 6 | 0 | (none - all 6 colors always seated) | 70 | 70-70 (matches) | Canonical for every remaining card (removal only drops entire color-card definitions, never touches another card's copy count) |

(Table A2 reports Stage 2's cohorts - Stage 1's smaller 250-game rotation shows the same expected/actual sizes, just a smaller removed-card union since fewer full 6-game rotation cycles complete.)

## Table B - Stage 1 baseline (250 games/cohort)

| Player count | Games | Finished | Capped | Cap rate | Mean turns | SEM | 95% CI (mean) | Median turns | p90 | p95 | Mean Rounds | Mean human-seat turns | Mean decision opportunities/seat | Total runtime (s) | Mean runtime/game (ms) | Mean runtime/turn (ms) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2 | 250 | 250 | 0 | 0.0% | 583.5 | 23.01 | [538.4, 628.6] | 480 | 1030 | 1256 | 202.9 | 291.8 | 1728.1 | 0.36 | 1.459 | 0.0025 |
| 3 | 250 | 250 | 0 | 0.0% | 906.8 | 36.28 | [835.7, 977.9] | 748 | 1768 | 2174 | 218.4 | 302.3 | 2201.9 | 0.58 | 2.303 | 0.0025 |
| 4 | 250 | 250 | 0 | 0.0% | 1254.9 | 58.87 | [1139.5, 1370.3] | 979 | 2780 | 3237 | 233.0 | 313.7 | 2726.8 | 0.86 | 3.440 | 0.0027 |
| 5 | 250 | 249 | 1 | 0.4% | 1931.7 | 97.80 | [1740.0, 2123.4] | 1378 | 3974 | 5219 | 292.6 | 386.3 | 3942.5 | 1.54 | 6.145 | 0.0032 |
| 6 | 250 | 247 | 3 | 1.2% | 2289.9 | 106.79 | [2080.6, 2499.2] | 1961 | 4791 | 5961 | 297.5 | 381.7 | 4468.8 | 2.13 | 8.510 | 0.0037 |

## Table C - Stage 2 scaling sample (1500 games/cohort)

| Player count | Games | Finished | Capped | Cap rate | Mean turns | SEM | 95% CI (mean) | Median turns | p90 | p95 | Mean Rounds | Mean human-seat turns | Mean decision opportunities/seat | Total runtime (s) | Mean runtime/game (ms) | Mean runtime/turn (ms) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2 | 1500 | 1500 | 0 | 0.0% | 633.3 | 11.43 | [610.9, 655.7] | 503 | 1204 | 1516 | 220.4 | 316.6 | 1877.7 | 1.87 | 1.246 | 0.0020 |
| 3 | 1500 | 1500 | 0 | 0.0% | 926.9 | 17.33 | [892.9, 960.9] | 724 | 1774 | 2207 | 223.8 | 309.0 | 2251.4 | 3.23 | 2.153 | 0.0023 |
| 4 | 1500 | 1498 | 2 | 0.1% | 1286.2 | 25.65 | [1235.9, 1336.5] | 1001 | 2502 | 3233 | 239.7 | 321.5 | 2799.2 | 5.07 | 3.380 | 0.0026 |
| 5 | 1500 | 1493 | 7 | 0.5% | 1757.7 | 36.81 | [1685.6, 1829.9] | 1291 | 3621 | 4745 | 267.2 | 351.5 | 3580.9 | 7.84 | 5.224 | 0.0030 |
| 6 | 1500 | 1460 | 40 | 2.7% | 2419.2 | 49.18 | [2322.8, 2515.6] | 1821 | 5241 | 6737 | 312.8 | 403.2 | 4739.5 | 12.42 | 8.278 | 0.0034 |

## Table D - Scaling comparison (Stage 2, 2-player = 1.00x baseline)

| Player count | Turn-length ratio vs 2P | Round ratio vs 2P | Engine-runtime ratio vs 2P | Runtime/turn ratio vs 2P |
|---|---|---|---|---|
| 2 | 1.00x | 1.00x | 1.00x | 1.00x |
| 3 | 1.46x | 1.02x | 1.73x | 1.18x |
| 4 | 2.03x | 1.09x | 2.71x | 1.34x |
| 5 | 2.78x | 1.21x | 4.19x | 1.51x |
| 6 | 3.82x | 1.42x | 6.64x | 1.74x |

## Table E - One-human local-app duration model (Stage 2 data)

**Assumptions (not observations)** - seconds per *substantive* decision and a per-turn mechanical overhead (rolling, moving the token, reading the landed square) for three pacing styles:

| Pacing | Seconds/decision opportunity | Seconds/turn mechanical overhead |
|---|---|---|
| Fast | 5 | 5 |
| Typical | 10 | 8 |
| Deliberate | 20 | 12 |

These are illustrative assumptions the user should adjust, not measured quantities. AI-seat compute time is excluded from these estimates (see Table B/C's measured mean runtime/turn - sub-millisecond to low-millisecond, negligible next to any human pacing tier above). UI/animation time is unknown and is NOT included in any estimate below; it would add to every figure.

| Player count | AI seats | Human turns/game | Human decisions/game | Fast estimate | Typical estimate | Deliberate estimate |
|---|---|---|---|---|---|---|
| 2 | 1 | 316.6 | 1877.7 | 182.9 min | 355.2 min | 689.2 min |
| 3 | 2 | 309.0 | 2251.4 | 213.4 min | 416.4 min | 812.3 min |
| 4 | 3 | 321.5 | 2799.2 | 260.1 min | 509.4 min | 997.4 min |
| 5 | 4 | 351.5 | 3580.9 | 327.7 min | 643.7 min | 1263.9 min |
| 6 | 5 | 403.2 | 4739.5 | 428.6 min | 843.7 min | 1660.5 min |

## Table F - Player-count monotonicity & statistical regime analysis (Stage 2, mean turns)

Only the **mean** has a closed-form standard error here (SEM via the central limit theorem, well justified at n=1,500/cohort); median/p90/p95 use a 500-resample percentile bootstrap 95% CI instead (fixed seed per cohort/statistic, reproducible) - deliberately reported as a genuinely wider/less certain interval rather than fabricating the same precision the mean gets. |z| > 1.96 is the conventional ~95% threshold for "distinguishable given sampling noise" used below.

| Player count | Mean turns | 95% CI (mean) | Median turns | Median 95% CI (bootstrap) | p90 | p90 95% CI (bootstrap) | p95 | p95 95% CI (bootstrap) | Cap rate |
|---|---|---|---|---|---|---|---|---|---|
| 2 | 633.3 | [610.9, 655.7] | 503 | [480, 523] | 1204 | [1156, 1262] | 1516 | [1408, 1583] | 0.0% |
| 3 | 926.9 | [892.9, 960.9] | 724 | [683, 768] | 1774 | [1693, 1854] | 2207 | [2047, 2460] | 0.0% |
| 4 | 1286.2 | [1235.9, 1336.5] | 1001 | [948, 1057] | 2502 | [2398, 2664] | 3233 | [3040, 3395] | 0.1% |
| 5 | 1757.7 | [1685.6, 1829.9] | 1291 | [1243, 1384] | 3621 | [3462, 3867] | 4745 | [4339, 5064] | 0.5% |
| 6 | 2419.2 | [2322.8, 2515.6] | 1821 | [1718, 1942] | 5241 | [4988, 5610] | 6737 | [6194, 7137] | 2.7% |

**Adjacent-player-count differences (mean turns, z-score, |z|>1.96 = statistically distinguishable):** 2P vs 3P: z=-14.14 (distinguishable); 3P vs 4P: z=-11.61 (distinguishable); 4P vs 5P: z=-10.51 (distinguishable); 5P vs 6P: z=-10.77 (distinguishable).

**Stage 1 (250 games) -> Stage 2 (1,500 games) stability per player count (mean turns, z-score):** 2P: z=-1.94 (stable); 3P: z=-0.50 (stable); 4P: z=-0.49 (stable); 5P: z=1.67 (stable); 6P: z=-1.10 (stable).

## Analysis

1. **Game length, 2 to 6 players (raw endpoints only - see item 12 for the full, non-monotonicity-assuming picture)**: mean turns/game is 633.3 at 2P and 2419.2 at 6P, a 3.82x ratio between just those two endpoints (see Table D) - this endpoint ratio does NOT by itself imply the relationship is monotonic in between; item 12 reports the actual ordering.
2. **Computational cost, 2 to 6 players**: mean engine runtime/game moves 1.25ms -> 8.28ms, a 6.64x ratio; mean runtime/turn moves 0.002ms -> 0.003ms (1.74x). This is game-structure scaling (A) times per-turn engine cost (B) - see items 2a/2b.
2a. **Game-structure scaling (turns/game, Rounds/game) by player count**: 2P: 633.3 turns / 220.4 Rounds, 3P: 926.9 turns / 223.8 Rounds, 4P: 1286.2 turns / 239.7 Rounds, 5P: 1757.7 turns / 267.2 Rounds, 6P: 2419.2 turns / 312.8 Rounds.
2b. **Computational scaling (engine runtime) by player count, kept separate from 2a rather than conflated**: 2P: 1.25ms/game, 0.0020ms/turn, 3P: 2.15ms/game, 0.0023ms/turn, 4P: 3.38ms/game, 0.0026ms/turn, 5P: 5.22ms/game, 0.0030ms/turn, 6P: 8.28ms/game, 0.0034ms/turn.
3. **Does one human seat get roughly the same, more, or fewer turns as player count rises?**: 2P: 316.6, 3P: 309.0, 4P: 321.5, 5P: 351.5, 6P: 403.2 - mean human-seat turns/game does not decrease from 2P to 6P (endpoints only - see item 12 for the full ordering, which need not be monotonic).
4. **Shortest/longest mean-duration player count**: shortest is 2P (633.3 turns), longest is 6P (2419.2 turns).
5. **Shortest/longest median-duration player count**: shortest is 2P (503 turns), longest is 6P (1821 turns). This agrees with the mean-based ranking in item 4.
6. **Is mean duration monotonic with player count (2->3->4->5->6)?**: **strictly increasing** - raw sequence: 2P=633.3, 3P=926.9, 4P=1286.2, 5P=1757.7, 6P=2419.2.
7. **Is median duration monotonic with player count?**: **strictly increasing** - raw sequence: 2P=503, 3P=724, 4P=1001, 5P=1291, 6P=1821.
8. **Is p90/p95 behavior monotonic with player count?**: p90 is **strictly increasing** (2P=1204, 3P=1774, 4P=2502, 5P=3621, 6P=5241); p95 is **strictly increasing** (2P=1516, 3P=2207, 4P=3233, 5P=4745, 6P=6737).
9. **Cap rate (hit the 8000-turn cap without a winner) by player count, and its monotonicity**: **non-decreasing (flat or increasing, never decreasing)** - 2P: 0/1500 (0.00%), 3P: 0/1500 (0.00%), 4P: 2/1500 (0.13%), 5P: 7/1500 (0.47%), 6P: 40/1500 (2.67%).
10. **Does any player count occupy a distinct statistical regime?**: adjacent-player-count mean-turns z-scores - 2P|3P z=-14.14, 3P|4P z=-11.61, 4P|5P z=-10.51, 5P|6P z=-10.77 (see Table F for the full CI table and which transitions clear the |z|>1.96 threshold; a player count bounded by two distinguishable transitions on either side, or whose CI does not overlap its neighbors', is the closest reading of "a distinct regime" this data supports - read directly off Table F rather than asserted here as a conclusion).
11. **Have the 250-game Stage 1 estimates stabilized by 1,500-game Stage 2?**: per player count (z-score, mean turns) - 2P: 583.5->633.3, z=-1.94 (stable), 3P: 906.8->926.9, z=-0.50 (stable), 4P: 1254.9->1286.2, z=-0.49 (stable), 5P: 1931.7->1757.7, z=1.67 (stable), 6P: 2289.9->2419.2, z=-1.10 (stable).
12. **Full, non-monotonicity-assuming game-length ordering by player count (mean turns, ascending)**: 2P (633.3) < 3P (926.9) < 4P (1286.2) < 5P (1757.7) < 6P (2419.2) - reported as observed, not assumed; see item 6 for whether this happens to be the same order as player count itself.
13. **What does this suggest about a plausible standalone-app session duration?**: see Table E - under the stated pacing assumptions (never treat these as measured), a Typical-pace human session ranges 2P: 355.2 min, 3P: 416.4 min, 4P: 509.4 min, 5P: 643.7 min, 6P: 843.7 min. UI/animation time is excluded and would add to every figure.
14. **Did the benchmark expose any new engine defect?**: **Yes, in an earlier run of this same benchmark** - see the correctness-defect note near the top of this report: `FateHarvestDeck.draw()`'s reshuffle used unseeded ambient randomness, breaking seeded reproducibility for any long game. Root-caused, fixed, and verified (byte-identical gameplay stats across repeat runs of this same seed range). The run whose results are tabulated below is post-fix and found zero invariant violations across 8750 total simulated games.
15. **Did the canonical deck audit expose any discrepancy in the existing simulation setup?**: No discrepancy in the catalog-level audit (Table A). Table A2's per-player-count filtered composition matches its own expected size (`64 + playerCount`) in every cohort - see that table's Match column. Per the addendum's correction: the benchmark itself previously used the unfiltered 70-card deck for every cohort, which was the actual discrepancy relative to canonical play - now fixed by `buildDeckForColors`.
16. **Turn cap saturation**: at least one Stage 2 cohort hit the 8000-turn cap without a winner - see item 9 for exact counts by player count.

No game-length, pacing, deck-size, card-balance, or player-count optimization was performed or recommended in this pass, per the addendum's explicit scope - the above are observations about the current canonical game's stochastic behavior only.

Total games run: 1250 (Stage 1) + 7500 (Stage 2) = 8750. Total invariant violations: 0.
