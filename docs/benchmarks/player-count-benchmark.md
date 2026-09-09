# T.O.E. Player-Count Gameplay-Length and Performance Benchmark

Baseline: commit `ba2448d`. No architectural or gameplay-rule changes were made for this task.

Seed scheme: `seed = stageBase + playerCount * 10000000 + gameIndex`, Stage 1 stageBase = 500000000, Stage 2 stageBase = 900000000 (independent, non-overlapping ranges).

Color rotation: cyclic rotation of `PlayerColor.entries` (GREEN, RED, BLACK, YELLOW, WHITE, BLUE) offset by each game's own index within its cohort, truncated to that cohort's player count - e.g. at 3 players, game 0 seats GREEN/RED/BLACK, game 1 seats RED/BLACK/YELLOW, etc., cycling every 6 games.

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

Every game in every cohort below (Stage 1 and Stage 2 alike) uses the complete, unfiltered 70-card canonical deck via `FateHarvestDeck.newShuffled(random)` - no color-based card removal is applied for this benchmark, per explicit instruction; only shuffle order is randomized.

## Table B - Stage 1 baseline (250 games/cohort)

| Player count | Games | Finished | Capped | Mean turns | Median turns | p90 | p95 | Mean Rounds | Mean human-seat turns | Mean decision opportunities/seat | Total runtime (s) | Mean runtime/game (ms) | Mean runtime/turn (ms) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2 | 250 | 250 | 0 | 651.5 | 528 | 1173 | 1557 | 228.2 | 325.8 | 2073.1 | 0.68 | 2.717 | 0.0042 |
| 3 | 250 | 250 | 0 | 934.7 | 744 | 1842 | 2159 | 225.9 | 311.6 | 2406.5 | 0.65 | 2.590 | 0.0028 |
| 4 | 250 | 250 | 0 | 1253.4 | 968 | 2413 | 3134 | 233.1 | 313.3 | 2831.4 | 0.91 | 3.622 | 0.0029 |
| 5 | 250 | 249 | 1 | 1794.4 | 1179 | 4280 | 5281 | 273.3 | 358.9 | 3739.2 | 1.47 | 5.872 | 0.0033 |
| 6 | 250 | 239 | 11 | 2433.5 | 1713 | 5535 | 7329 | 315.3 | 405.6 | 4756.2 | 2.18 | 8.707 | 0.0036 |

## Table C - Stage 2 scaling sample (1500 games/cohort)

| Player count | Games | Finished | Capped | Mean turns | Median turns | p90 | p95 | Mean Rounds | Mean human-seat turns | Mean decision opportunities/seat | Total runtime (s) | Mean runtime/game (ms) | Mean runtime/turn (ms) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2 | 1500 | 1500 | 0 | 610.1 | 490 | 1118 | 1431 | 213.3 | 305.1 | 1941.7 | 2.00 | 1.335 | 0.0022 |
| 3 | 1500 | 1500 | 0 | 879.2 | 701 | 1690 | 2136 | 212.6 | 293.1 | 2262.7 | 3.28 | 2.184 | 0.0025 |
| 4 | 1500 | 1500 | 0 | 1304.2 | 974 | 2599 | 3533 | 242.2 | 326.1 | 2952.0 | 5.27 | 3.516 | 0.0027 |
| 5 | 1500 | 1490 | 10 | 1800.6 | 1381 | 3682 | 4629 | 273.9 | 360.1 | 3746.3 | 8.29 | 5.528 | 0.0031 |
| 6 | 1500 | 1453 | 47 | 2407.2 | 1830 | 5120 | 6702 | 311.0 | 401.2 | 4713.5 | 12.41 | 8.271 | 0.0034 |

## Table D - Scaling comparison (Stage 2, 2-player = 1.00x baseline)

| Player count | Turn-length ratio vs 2P | Round ratio vs 2P | Engine-runtime ratio vs 2P | Runtime/turn ratio vs 2P |
|---|---|---|---|---|
| 2 | 1.00x | 1.00x | 1.00x | 1.00x |
| 3 | 1.44x | 1.00x | 1.64x | 1.14x |
| 4 | 2.14x | 1.14x | 2.63x | 1.23x |
| 5 | 2.95x | 1.28x | 4.14x | 1.40x |
| 6 | 3.95x | 1.46x | 6.20x | 1.57x |

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
| 2 | 1 | 305.1 | 1941.7 | 187.2 min | 364.3 min | 708.2 min |
| 3 | 2 | 293.1 | 2262.7 | 213.0 min | 416.2 min | 812.9 min |
| 4 | 3 | 326.1 | 2952.0 | 273.2 min | 535.5 min | 1049.2 min |
| 5 | 4 | 360.1 | 3746.3 | 342.2 min | 672.4 min | 1320.8 min |
| 6 | 5 | 401.2 | 4713.5 | 426.2 min | 839.1 min | 1651.4 min |

## Analysis

1. **Game length, 2 to 6 players**: mean turns/game moves from 610.1 (2P) to 2407.2 (6P), a 3.95x ratio (see Table D).
2. **Computational cost, 2 to 6 players**: mean engine runtime/game moves 1.34ms -> 8.27ms, a 6.20x ratio; mean runtime/turn moves 0.002ms -> 0.003ms (1.57x).
3. **Does one human seat get roughly the same, more, or fewer turns as player count rises?**: 2P: 305.1, 3P: 293.1, 4P: 326.1, 5P: 360.1, 6P: 401.2 - mean human-seat turns/game does not decrease as player count rises, since total game turns grow sub-linearly relative to player count while more seats share them.
4. **Shortest/longest typical player count**: shortest mean-turns cohort is 2P (610.1 turns), longest is 6P (2407.2 turns).
5. **Any player count disproportionately prone to very long games?**: p95 turns by player count - 2P: 1431, 3P: 2136, 4P: 3533, 5P: 4629, 6P: 6702; capped (hit the 8000-turn cap without a winner): 2P: 0/1500, 3P: 0/1500, 4P: 0/1500, 5P: 10/1500, 6P: 47/1500.
6. **Have the 250-game Stage 1 estimates stabilized by 1,500-game Stage 2?**: 2P mean turns 651.5 (Stage 1) -> 610.1 (Stage 2); 3P mean turns 934.7 (Stage 1) -> 879.2 (Stage 2); 4P mean turns 1253.4 (Stage 1) -> 1304.2 (Stage 2); 5P mean turns 1794.4 (Stage 1) -> 1800.6 (Stage 2); 6P mean turns 2433.5 (Stage 1) -> 2407.2 (Stage 2).
7. **What does this suggest about a plausible standalone-app session duration?**: see Table E - under the stated pacing assumptions (never treat these as measured), a Typical-pace human session ranges 2P: 364.3 min, 3P: 416.2 min, 4P: 535.5 min, 5P: 672.4 min, 6P: 839.1 min. UI/animation time is excluded and would add to every figure.
8. **Did the benchmark expose any new engine defect?**: No - zero invariant violations across 8750 total simulated games (Stage 1 + Stage 2).
9. **Did the canonical deck audit expose any discrepancy in the existing simulation setup?**: No - `FateHarvestDeck.newShuffled()` already builds the full, unfiltered 70-card canonical deck unconditionally (no colors/player-count parameter exists), matching this benchmark's own full-deck requirement with no code changes needed.
10. **Turn cap saturation**: at least one Stage 2 cohort hit the 8000-turn cap without a winner - see row 5 above for counts.

Total games run: 1250 (Stage 1) + 7500 (Stage 2) = 8750. Total invariant violations: 0.
