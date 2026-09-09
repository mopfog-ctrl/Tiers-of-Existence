# T.O.E. Phase 1C — Structural Stagnation Predictor Analysis

**Experimental, measurement only — Clearance C2 (instrument/test). No gameplay, regeneration probabilities, rarity ceilings, card behavior, or `FateHarvestRegenerationConfig.ANTI_STAGNATION` change was made in this pass.** The prior refined-evidence report established Outcome C for card identity under the *presently tested* catalog - this report does not revisit that question. It asks a different one: do *structural* game-state properties (Tier occupancy, Marauder/Staging-Pile state, hand sizes, deck-pile sizes, outstanding skip/extra-turn debt, proximity to victory, and an aggregate composition summary) predict subsequent play length, and does regeneration depth predict it independently of elapsed turns.

Preserves every prior Phase 1B/1C report unmodified as historical/superseded-methodology record.

## 1. Tests, invariants, and determinism

- **Full engine suite**: run green before this benchmark, per this Phase's own established sequencing.
- **Whole-game rarity invariant**: re-checked after every turn of every game attempted (token/Phase/pendingRoll/winner-square conservation, plus draw+discard+hands+resolving never exceeding a card's own Generation-0 rarity). Result: **0 violation(s)**.
- **New engine surface, read-only**: `GameState.totalPendingSkipDebt`/`totalPendingExtraTierTurns` - both pure sums over already-existing private state (`pendingSkips`/`deferredModifiers`), mutate nothing, read by no gameplay logic. This is the only main-source-tree change this pass made.
- **Instrumentation RNG safety**: every new recorded field is a pure read of already-canonical `GameState` at the moment of a regeneration - no `Random` instance is touched by any of it.
- **Determinism cross-check against the published corrected baseline**: reuses that benchmark's own `BASE_SEED`.

| Player count | This run mean turns | Published mean turns | Match? | This run cap rate | Published cap rate | Match? |
|---|---|---|---|---|---|---|
| 2 | 669.0 | 669.0 | **Yes** | 0.00% | 0.00% | **Yes** |
| 3 | 869.7 | 869.7 | **Yes** | 0.00% | 0.00% | **Yes** |
| 4 | 1327.3 | 1327.3 | **Yes** | 0.00% | 0.00% | **Yes** |
| 5 | 1776.9 | 1776.9 | **Yes** | 0.40% | 0.40% | **Yes** |
| 6 | 2424.4 | 2424.4 | **Yes** | 3.10% | 3.10% | **Yes** |

**Determinism confirmed**: every cohort's aggregate statistics under this instrumented run exactly match the already-published corrected baseline.

## 2. Benchmark methodology and sample sizes

Same corrected model as every prior benchmark in this lineage (`FateHarvestRegenerationConfig.DEFAULT`, no anti-stagnation pressure). At every regeneration event, in addition to depth/turns-elapsed/per-card multiplicity (retained, not reanalyzed here), this pass records: the highest Tier any player has an in-play token on; how many players have a 4th-Tier in-play/Zone token; total in-play tokens, Staging Pile counts, and Marauder counts, each broken out per Tier and summed across every player; total/mean/max hand size across players; draw pile, discard pile, and resolving-card counts; total outstanding skip-turn and extra-tier-turn debt (via the two new read-only `GameState` accessors); the closest any player's 4th-Tier main-loop token is to the `YOU_WIN` square (null if nobody has reached the 4th Tier board yet - Zone-resident 4th-Tier tokens are excluded from this specific measure, since their own sub-path position isn't directly comparable to a main-loop distance without a separate model this task doesn't ask for); and the mean, across every eligible card type, of that type's own multiplicity divided by its own rarity ceiling - an aggregate composition summary, not a per-card one.

Scale: 1000 games/cohort × 5 player counts = 5000 total games attempted, same seed range as the corrected baseline (`BASE_SEED = 2100000000`).

| Player count | Games | Total regeneration events | Mean events/game |
|---|---|---|---|
| 2 | 1000 | 1048 | 1.0 |
| 3 | 1000 | 1583 | 1.6 |
| 4 | 1000 | 2748 | 2.7 |
| 5 | 1000 | 3926 | 3.9 |
| 6 | 1000 | 5631 | 5.6 |

A minimum of 30 observations is required before a correlation is treated as reliable rather than merely reported - cells below that threshold are marked `insufficient`.

## 3. Within-player-count analysis: structural predictors vs. turns remaining

Primary relationship: Pearson r between each structural predictor's value immediately after a regeneration and turns remaining from that event, computed **separately per player count** - eliminating the 2P-6P pooling confound before any aggregate interpretation, matching this Phase's own established methodology.

| Predictor | 2P r (n) | 3P r (n) | 4P r (n) | 5P r (n) | 6P r (n) |
|---|---|---|---|---|---|
| depth | 0.043 (n=1048) | -0.029 (n=1583) | -0.068 (n=2748) | -0.057 (n=3926) | -0.133 (n=5631) |
| turnsElapsedAtRegeneration | 0.043 (n=1048) | -0.028 (n=1583) | -0.071 (n=2748) | -0.061 (n=3926) | -0.137 (n=5631) |
| highestTierInPlay | -0.168 (n=1048) | -0.178 (n=1583) | -0.107 (n=2748) | -0.083 (n=3926) | -0.074 (n=5631) |
| playersOnTier4 | -0.112 (n=1048) | -0.152 (n=1583) | -0.089 (n=2748) | -0.088 (n=3926) | -0.080 (n=5631) |
| totalHandCards | 0.026 (n=1048) | 0.031 (n=1583) | 0.010 (n=2748) | 0.049 (n=3926) | 0.019 (n=5631) |
| meanHandCards | 0.026 (n=1048) | 0.031 (n=1583) | 0.010 (n=2748) | 0.049 (n=3926) | 0.019 (n=5631) |
| maxHandCards | 0.031 (n=1048) | 0.031 (n=1583) | -0.002 (n=2748) | 0.029 (n=3926) | 0.022 (n=5631) |
| drawPileSize | 0.000 (n=1048) | 0.000 (n=1583) | 0.000 (n=2748) | 0.000 (n=3926) | 0.000 (n=5631) |
| discardPileSize | -0.025 (n=1048) | -0.031 (n=1583) | -0.008 (n=2748) | -0.048 (n=3926) | -0.018 (n=5631) |
| resolvingCardsCount | -0.024 (n=1048) | 0.001 (n=1583) | -0.034 (n=2748) | -0.002 (n=3926) | -0.021 (n=5631) |
| totalPendingSkipDebt | 0.000 (n=1048) | -0.013 (n=1583) | -0.006 (n=2748) | -0.007 (n=3926) | -0.024 (n=5631) |
| totalPendingExtraTierTurns | 0.032 (n=1048) | 0.023 (n=1583) | -0.029 (n=2748) | -0.012 (n=3926) | -0.092 (n=5631) |
| meanFractionOfCeiling (aggregate composition, not per-card) | -0.046 (n=1048) | -0.019 (n=1583) | -0.007 (n=2748) | -0.053 (n=3926) | -0.019 (n=5631) |
| inPlayTokens@Tier1 | 0.000 (n=1048) | 0.000 (n=1583) | 0.000 (n=2748) | 0.000 (n=3926) | 0.014 (n=5631) |
| stagingPile@Tier1 | 0.011 (n=1048) | -0.050 (n=1583) | -0.015 (n=2748) | -0.031 (n=3926) | -0.007 (n=5631) |
| marauders@Tier1 | -0.028 (n=1048) | 0.037 (n=1583) | 0.000 (n=2748) | 0.016 (n=3926) | 0.004 (n=5631) |
| inPlayTokens@Tier2 | -0.053 (n=1048) | -0.057 (n=1583) | -0.023 (n=2748) | -0.021 (n=3926) | -0.028 (n=5631) |
| stagingPile@Tier2 | -0.083 (n=1048) | -0.108 (n=1583) | -0.077 (n=2748) | -0.097 (n=3926) | -0.058 (n=5631) |
| marauders@Tier2 | -0.038 (n=1048) | -0.042 (n=1583) | -0.012 (n=2748) | 0.005 (n=3926) | 0.011 (n=5631) |
| inPlayTokens@Tier3 | -0.133 (n=1048) | -0.091 (n=1583) | -0.081 (n=2748) | -0.069 (n=3926) | -0.056 (n=5631) |
| stagingPile@Tier3 | -0.186 (n=1048) | -0.188 (n=1583) | -0.115 (n=2748) | -0.139 (n=3926) | -0.094 (n=5631) |
| marauders@Tier3 | -0.048 (n=1048) | -0.012 (n=1583) | -0.025 (n=2748) | -0.027 (n=3926) | -0.015 (n=5631) |
| inPlayTokens@Tier4 | -0.112 (n=1048) | -0.152 (n=1583) | -0.089 (n=2748) | -0.088 (n=3926) | -0.080 (n=5631) |
| stagingPile@Tier4 | 0.000 (n=1048) | 0.000 (n=1583) | 0.000 (n=2748) | 0.000 (n=3926) | 0.000 (n=5631) |
| marauders@Tier4 | -0.009 (n=1048) | 0.010 (n=1583) | 0.030 (n=2748) | 0.003 (n=3926) | 0.000 (n=5631) |

**Cross-player-count consistency summary** (reliable cells only, n>=30):

| Predictor | Reliable cells | Mean r | Range (min to max) | Sign-consistent? |
|---|---|---|---|---|
| depth | 5 | -0.049 | -0.133 to 0.043 | **No** |
| turnsElapsedAtRegeneration | 5 | -0.051 | -0.137 to 0.043 | **No** |
| highestTierInPlay | 5 | -0.122 | -0.178 to -0.074 | Yes |
| playersOnTier4 | 5 | -0.104 | -0.152 to -0.080 | Yes |
| totalHandCards | 5 | 0.027 | 0.010 to 0.049 | Yes |
| meanHandCards | 5 | 0.027 | 0.010 to 0.049 | Yes |
| maxHandCards | 5 | 0.022 | -0.002 to 0.031 | **No** |
| drawPileSize | 5 | 0.000 | 0.000 to 0.000 | Yes |
| discardPileSize | 5 | -0.026 | -0.048 to -0.008 | Yes |
| resolvingCardsCount | 5 | -0.016 | -0.034 to 0.001 | **No** |
| totalPendingSkipDebt | 5 | -0.010 | -0.024 to 0.000 | **No** |
| totalPendingExtraTierTurns | 5 | -0.016 | -0.092 to 0.032 | **No** |
| meanFractionOfCeiling (aggregate composition, not per-card) | 5 | -0.029 | -0.053 to -0.007 | Yes |
| inPlayTokens@Tier1 | 5 | 0.003 | 0.000 to 0.014 | Yes |
| stagingPile@Tier1 | 5 | -0.018 | -0.050 to 0.011 | **No** |
| marauders@Tier1 | 5 | 0.006 | -0.028 to 0.037 | **No** |
| inPlayTokens@Tier2 | 5 | -0.036 | -0.057 to -0.021 | Yes |
| stagingPile@Tier2 | 5 | -0.085 | -0.108 to -0.058 | Yes |
| marauders@Tier2 | 5 | -0.015 | -0.042 to 0.011 | **No** |
| inPlayTokens@Tier3 | 5 | -0.086 | -0.133 to -0.056 | Yes |
| stagingPile@Tier3 | 5 | -0.144 | -0.188 to -0.094 | Yes |
| marauders@Tier3 | 5 | -0.025 | -0.048 to -0.012 | Yes |
| inPlayTokens@Tier4 | 5 | -0.104 | -0.152 to -0.080 | Yes |
| stagingPile@Tier4 | 5 | 0.000 | 0.000 to 0.000 | Yes |
| marauders@Tier4 | 5 | 0.007 | -0.009 to 0.030 | **No** |

## 4. Critical temporal question: does regeneration depth predict stagnation independently of elapsed turns?

Raw pairwise correlations, then the partial correlation of depth with turns-remaining *controlling for* turns-elapsed (the standard technique for "does X predict Y independently of Z" - `r(depth,remaining) - r(depth,elapsed)*r(elapsed,remaining)`, normalized by `sqrt((1-r(depth,elapsed)^2)*(1-r(elapsed,remaining)^2))`), computed **within each player count**:

| Player count | n | r(depth, turnsRemaining) | r(depth, turnsElapsed) | r(turnsElapsed, turnsRemaining) | Partial r(depth, turnsRemaining \| turnsElapsed) |
|---|---|---|---|---|---|
| 2 | 1048 | 0.043 | 0.990 | 0.043 | 0.002 |
| 3 | 1583 | -0.029 | 0.991 | -0.028 | -0.007 |
| 4 | 2748 | -0.068 | 0.995 | -0.071 | 0.029 |
| 5 | 3926 | -0.057 | 0.996 | -0.061 | 0.039 |
| 6 | 5631 | -0.133 | 0.998 | -0.137 | 0.050 |

Pooled across all player counts (n=14936, for reference only - reintroduces the player-count confound, so the within-player-count rows above are the ones to trust): raw r(depth, remaining) = 0.044, partial r(depth, remaining | elapsed) = 0.091.

**Direct answer**: at least one player count shows a partial correlation exceeding ±0.05 after controlling for elapsed turns - see the per-player-count row(s) above for exactly where; this would mean depth carries some independent signal beyond what elapsed-turns alone explains at that player count, worth a closer look before concluding depth is redundant with elapsed turns everywhere.

## 5. Proximity to victory

`minDistanceToWin` (squares remaining, main-loop only, for whichever player is closest to the 4th Tier's own `YOU_WIN` square) is only defined once at least one player has an in-play 4th-Tier token on the main loop - many regeneration events happen before any player reaches the 4th Tier at all, so this predictor's own `n` is reported honestly per player count rather than padded with a sentinel value.

| Player count | n (defined) | n (total events) | Coverage | r(minDistanceToWin, turnsRemaining) |
|---|---|---|---|---|
| 2 | 48 | 1048 | 4.6% | 0.237 (n=48) |
| 3 | 60 | 1583 | 3.8% | -0.017 (n=60) |
| 4 | 73 | 2748 | 2.7% | 0.235 (n=73) |
| 5 | 88 | 3926 | 2.2% | -0.167 (n=88) |
| 6 | 109 | 5631 | 1.9% | -0.099 (n=109) |

A negative r here would be the mechanically expected direction (closer to winning -> less play remaining) and would mainly validate that this measure behaves sensibly, rather than being a novel anti-stagnation finding on its own.

## 6. Tail-specific analysis

Tail defined **per player count, from this run's own games** as the top 10% of games by total turns taken (`totalTurnsTaken >= that player count's own p90`) - same documented threshold as the prior refined-evidence report. "Capped" (hit the 8000-turn cap without a winner) reported alongside for comparison.

| Player count | p90 threshold (turns) | Tail games (>=p90) | Capped games |
|---|---|---|---|
| 2 | 1265 | 101 | 0 |
| 3 | 1674 | 101 | 0 |
| 4 | 2635 | 101 | 0 |
| 5 | 3578 | 101 | 4 |
| 6 | 5373 | 101 | 31 |

For each structural predictor, using that predictor's own **last regeneration event per game** (its terminal state for that game) - point-biserial correlation between the predictor and tail membership (0/1), pooled across player counts (each game's own tail membership is determined by its own player count's p90, so this pooling doesn't reintroduce a raw-scale confound the way pooling turn counts directly would):

| Predictor | Point-biserial r (n) |
|---|---|
| depth | 0.592 (n=4159) |
| turnsElapsedAtRegeneration | 0.603 (n=4159) |
| highestTierInPlay | -0.004 (n=4159) |
| playersOnTier4 | 0.009 (n=4159) |
| totalHandCards | -0.048 (n=4159) |
| meanHandCards | -0.026 (n=4159) |
| maxHandCards | -0.023 (n=4159) |
| drawPileSize | 0.000 (n=4159) |
| discardPileSize | -0.003 (n=4159) |
| resolvingCardsCount | 0.021 (n=4159) |
| totalPendingSkipDebt | -0.014 (n=4159) |
| totalPendingExtraTierTurns | 0.336 (n=4159) |
| meanFractionOfCeiling (aggregate composition, not per-card) | 0.040 (n=4159) |
| inPlayTokens@Tier1 | -0.056 (n=4159) |
| stagingPile@Tier1 | -0.038 (n=4159) |
| marauders@Tier1 | -0.011 (n=4159) |
| inPlayTokens@Tier2 | -0.015 (n=4159) |
| stagingPile@Tier2 | -0.028 (n=4159) |
| marauders@Tier2 | -0.009 (n=4159) |
| inPlayTokens@Tier3 | -0.009 (n=4159) |
| stagingPile@Tier3 | 0.013 (n=4159) |
| marauders@Tier3 | -0.000 (n=4159) |
| inPlayTokens@Tier4 | 0.009 (n=4159) |
| stagingPile@Tier4 | 0.000 (n=4159) |
| marauders@Tier4 | -0.013 (n=4159) |

## 7. Interpretation

**Measured association**: Section 3's within-player-count table is this report's primary factual finding. **Plausible interpretation**: of 25 structural predictors, 6 show a sign-consistent (same direction, |r|>=0.05) relationship with turns remaining across at least 3 reliable player-count cells: highestTierInPlay, playersOnTier4, stagingPile@Tier2, inPlayTokens@Tier3, stagingPile@Tier3, inPlayTokens@Tier4. These are candidates for further, separate investigation - not weights, and not proof of causation.

**Notable tail-only finding, not otherwise flagged above**: Section 6's point-biserial table shows `totalPendingExtraTierTurns` (r=0.336) with a tail-membership association well above every other non-tautological predictor there, despite not clearing Section 3's own within-player-count sign-consistency bar for turns-remaining. This is reported as a measured association only - it is plausible that outstanding debt of this kind simply has more opportunity to accumulate the longer a game already runs (the same base-rate-confound shape flagged for card identity in the prior refined-evidence report), not that it independently drives a game into the tail; distinguishing those two would need its own dedicated analysis, not asserted here.

**Evidence insufficient to distinguish**: Section 5's proximity-to-victory measure is only defined for a fraction of events (most regenerations happen before any player reaches the 4th Tier board at all) - see that section's own coverage percentages before treating its correlation as reliable. Section 6's tail comparison pools across player counts at the tail-membership level specifically to keep its own n usable; a full player-count-separated tail analysis would need a substantially larger sample than this run's ~1000 games/cohort, since tail games are by definition only ~10% of each cohort.

**Causal hypothesis**: none is asserted anywhere in this report. Every relationship above is correlational, computed on top of an already-conditioned (player-count-separated) dataset, but conditioning on player count does not establish causation for any individual predictor - several of these structural measures are themselves correlated with each other (e.g. higher hand sizes and higher draw/discard pile sizes both track overall game progress), so a predictor showing a relationship here could be standing in for a different, unmeasured common cause rather than driving the outcome itself.

## 8. Newly discovered architectural or semantic ambiguities

- **Skip-turn and extra-tier-turn debt had no public read accessor at all before this pass.** `GameState.pendingSkips`/`deferredModifiers` were both fully private, with only write-only queue methods (`queueSkipNextTierTurn`/`queueExtraTierTurn`) exposed - there was no way for any external observer, instrumentation or otherwise, to read the current outstanding debt. Two small, read-only, purely additive accessors (`GameState.totalPendingSkipDebt`/`totalPendingExtraTierTurns`) were added specifically to make this measurable; see this class's own doc for why this was judged in-scope for a Clearance C2 instrument/test pass (read-only, no gameplay behavior change) versus the prior refined-evidence pass's choice to keep an equivalent gap ("turn number at an arbitrary mid-turn event") purely test-local.
- **"Distance to win" is defined narrowly (main-loop 4th-Tier position only) by deliberate choice, not oversight.** A Zone-resident 4th-Tier token's own progress toward winning would need a different, zone-sub-path-aware distance model (how many zone-internal squares remain, then the exit-and-continue distance on the main loop) - genuinely more complex, and the task's own "without inventing new gameplay semantics" instruction argued for the narrower, unambiguous main-loop-only measure over a compound heuristic spanning both. A player whose only 4th-Tier token is Zone-resident is simply excluded from `minDistanceToWin` for that event, same as a player with no 4th-Tier token at all.
- **No existing engine-level way to distinguish "structurally impossible for this player count" from "legitimately never observed at this sample size"** for a sparse predictor like proximity-to-victory - Section 5's own coverage percentages are the closest available signal, but a future pass wanting to condition tail analysis on "games that got at least this close to winning" would need to decide that threshold explicitly rather than infer it from this report alone.

## Closing statement

No balance change, canon decision, weight derivation, or configuration change was made based on the above - per this task's explicit scope, this is a measurement and interpretation pass only.

Total games analyzed: 5000. Total invariant violations: 0.
