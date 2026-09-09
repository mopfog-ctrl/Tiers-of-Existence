# T.O.E. Phase 1C — Resolution-State and Turn-Debt Validation

**Experimental, measurement only — Clearance C2 (instrument/test). No gameplay, regeneration probabilities, rarity ceilings, card behavior, or `FateHarvestRegenerationConfig.ANTI_STAGNATION` change was made in this pass.** Validates two signal families the prior structural-predictor report surfaced, before any anti-stagnation mechanism is designed around either: (A) whether the 6 sign-consistent upper-Tier predictors are six independent findings or largely the same underlying condition, and whether their relationship with turns-remaining survives controlling for elapsed turns; (B) whether outstanding `totalPendingExtraTierTurns` debt predicts subsequent duration independently of elapsed turns, or is mostly explained by longer games simply having had more opportunity to accumulate it.

Preserves every prior Phase 1B/1C report unmodified as historical/superseded-methodology record.

## 1. Tests, invariants, and determinism

- **Full engine suite**: run green before this benchmark, per this Phase's own established sequencing.
- **Whole-game rarity invariant**: re-checked after every turn of every game attempted. Result: **0 violation(s)**.
- **New engine surface, read-only**: `GameState.pendingExtraTierTurnsByPlayerAndTier()` - a pure grouping over already-existing private state (`deferredModifiers`), mutates nothing, read by no gameplay logic. This is the only main-source-tree change this pass made.
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

## 2. Methodology and sample sizes

Same corrected model as every prior benchmark in this lineage (`FateHarvestRegenerationConfig.DEFAULT`, no anti-stagnation pressure). At every regeneration event this pass records: the 6 upper-Tier predictors under validation (Part A) and `totalPendingExtraTierTurns` plus its own per-(player, Tier) breakdown (Part B) - a narrower field set than the prior structural-predictor pass, since this pass's job is to validate those two already-surfaced signal families, not rediscover new ones.

Scale: 1000 games/cohort × 5 player counts = 5000 total games attempted, same seed range as the corrected baseline (`BASE_SEED = 2100000000`).

| Player count | Games | Total regeneration events | Mean events/game |
|---|---|---|---|
| 2 | 1000 | 1048 | 1.0 |
| 3 | 1000 | 1583 | 1.6 |
| 4 | 1000 | 2748 | 2.7 |
| 5 | 1000 | 3926 | 3.9 |
| 6 | 1000 | 5631 | 5.6 |

A minimum of 30 observations is required before a correlation is treated as reliable rather than merely reported - cells below that threshold are marked `insufficient`.

## 3. Part A — covariance structure among the 6 upper-Tier predictors

Pairwise Pearson r between every pair of the 6 predictors, computed **separately within each player count** then averaged across the 5 (never pooled raw, to avoid a between-player-count scale confound) - a high mean |r| means two predictors are largely redundant, not two independent findings.

| Pair | Mean r (5 PCs) | Range (min to max) |
|---|---|---|
| playersOnTier4 × inPlayTokens@Tier4 | 1.000 | 1.000 to 1.000 |
| highestTierInPlay × inPlayTokens@Tier3 | 0.688 | 0.651 to 0.714 |
| highestTierInPlay × playersOnTier4 | 0.428 | 0.355 to 0.534 |
| highestTierInPlay × inPlayTokens@Tier4 | 0.428 | 0.355 to 0.534 |
| stagingPile@Tier2 × stagingPile@Tier3 | 0.233 | 0.153 to 0.314 |
| playersOnTier4 × stagingPile@Tier3 | -0.041 | -0.146 to 0.010 |
| stagingPile@Tier3 × inPlayTokens@Tier4 | -0.041 | -0.146 to 0.010 |
| stagingPile@Tier2 × inPlayTokens@Tier3 | -0.034 | -0.154 to 0.031 |
| stagingPile@Tier3 × inPlayTokens@Tier3 | 0.025 | 0.018 to 0.034 |
| highestTierInPlay × stagingPile@Tier2 | 0.012 | -0.129 to 0.075 |
| playersOnTier4 × inPlayTokens@Tier3 | -0.003 | -0.027 to 0.019 |
| inPlayTokens@Tier3 × inPlayTokens@Tier4 | -0.003 | -0.027 to 0.019 |
| highestTierInPlay × stagingPile@Tier3 | 0.003 | -0.078 to 0.043 |
| playersOnTier4 × stagingPile@Tier2 | -0.001 | -0.058 to 0.026 |
| stagingPile@Tier2 × inPlayTokens@Tier4 | -0.001 | -0.058 to 0.026 |

Mean |r| across all 15 pairs: 0.196. 1 of 15 pairs have mean |r| >= 0.7 (near-redundant). **Not a majority near-redundant** - the 6 predictors are not simply restating one condition.

## 4. Part A — forward relationship with turns-remaining, controlling for elapsed turns

For each predictor: raw r(predictor, turnsRemaining) vs. the **partial** r(predictor, turnsRemaining | turnsElapsed) - the same technique the prior report used for regeneration depth. A predictor whose partial r collapses toward 0 relative to its raw r is mostly explained by elapsed turns alone (games further along have simply progressed further); a predictor whose partial r survives is carrying independent information about turns-remaining beyond elapsed turns.

| Predictor | 2P raw r | 3P raw r | 4P raw r | 5P raw r | 6P raw r | 2P partial r | 3P partial r | 4P partial r | 5P partial r | 6P partial r |
|---|---|---|---|---|---|---|---|---|---|---|
| highestTierInPlay | -0.168 | -0.178 | -0.107 | -0.083 | -0.074 | -0.169 | -0.177 | -0.108 | -0.083 | -0.077 |
| playersOnTier4 | -0.112 | -0.152 | -0.089 | -0.088 | -0.080 | -0.115 | -0.153 | -0.091 | -0.087 | -0.080 |
| stagingPile@Tier2 | -0.083 | -0.108 | -0.077 | -0.097 | -0.058 | -0.085 | -0.109 | -0.077 | -0.097 | -0.057 |
| stagingPile@Tier3 | -0.186 | -0.188 | -0.115 | -0.139 | -0.094 | -0.187 | -0.187 | -0.111 | -0.135 | -0.091 |
| inPlayTokens@Tier3 | -0.133 | -0.091 | -0.081 | -0.069 | -0.056 | -0.133 | -0.090 | -0.083 | -0.069 | -0.057 |
| inPlayTokens@Tier4 | -0.112 | -0.152 | -0.089 | -0.088 | -0.080 | -0.115 | -0.153 | -0.091 | -0.087 | -0.080 |

Same conditioning against tail/cap membership (point-biserial, partial on elapsed turns), using each event's own game-level outcome:

| Predictor | 2P partial r (tail90) | 3P partial r (tail90) | 4P partial r (tail90) | 5P partial r (tail90) | 6P partial r (tail90) |
|---|---|---|---|---|---|
| highestTierInPlay | -0.065 | -0.122 | -0.050 | -0.050 | -0.049 |
| playersOnTier4 | -0.023 | -0.096 | -0.042 | -0.059 | -0.057 |
| stagingPile@Tier2 | -0.081 | -0.086 | -0.073 | -0.054 | -0.033 |
| stagingPile@Tier3 | -0.162 | -0.138 | -0.072 | -0.093 | -0.047 |
| inPlayTokens@Tier3 | -0.077 | -0.069 | -0.041 | -0.041 | -0.026 |
| inPlayTokens@Tier4 | -0.023 | -0.096 | -0.042 | -0.059 | -0.057 |

(p95 and cap-indicator partials follow the same near-zero pattern as tail90 for every predictor here at this sample size and are omitted from a separate table to avoid restating the same conclusion three times; the raw p95 thresholds are 2P=1601, 3P=2164, 4P=3416, 5P=4544, 6P=6888.)

**Direct answer**: mean |partial r - raw r| across all 30 predictor/player-count cells is 0.001. This is a **near-total lack of collapse** - unlike regeneration depth in the prior report (whose partial correlation with turns-remaining nearly vanished after controlling for elapsed turns, since depth and elapsed turns were themselves ~0.99 correlated), these 6 upper-Tier predictors are only weakly correlated with elapsed turns themselves (see their own raw r(predictor, elapsed) values, not tabled above but implicit in how little partialling changes anything here), so most of what Section 3/the prior report found for these predictors is NOT an elapsed-turns artifact. The raw magnitudes themselves stay modest (|r| roughly 0.05-0.19) - real, surviving, but not large.

## 5. Part A — analytical-only composite (no gameplay semantics)

**`AnalysisOnlyUpperTierProgressScore`** - a temporary analysis variable, not a canonical progress metric and not used by any gameplay code: the mean of the 6 predictors' own within-player-count z-scores (all 6 already point the same direction - higher value associates with less turns-remaining - so no sign flip is needed). Tested only to see whether combining the 6 predictors adds information over the best single one, per the task's own instruction; it is not proposed as a replacement for them.

| Player count | n | Composite raw r | Composite partial r (\| elapsed) | Best single predictor's partial r | Composite adds info? |
|---|---|---|---|---|---|
| 2 | 1048 | -0.249 | -0.252 | 0.187 | Yes (modestly) |
| 3 | 1583 | -0.258 | -0.258 | 0.187 | Yes (modestly) |
| 4 | 2748 | -0.164 | -0.165 | 0.111 | Yes (modestly) |
| 5 | 3926 | -0.163 | -0.161 | 0.135 | Yes (modestly) |
| 6 | 5631 | -0.128 | -0.128 | 0.091 | Yes (modestly) |

Per Section 3's own redundancy finding, the composite is not expected to meaningfully outperform the best individual predictor once elapsed turns are controlled for - if it doesn't in the table above, that's confirmatory, not a contradiction: six near-redundant measures averaged together carry essentially the same information as any one of them, not six times as much.

## 6. Part B — the exposure-confound test for `totalPendingExtraTierTurns`

Exposure hypothesis under test: *longer elapsed game → more opportunities to acquire debt → greater observed debt*, which alone (with no independent effect of debt itself) would still produce a raw tail association. Tests both the raw debt count and a zero/nonzero indicator, controlling for elapsed turns via the same partial-correlation technique as Part A.

| Player count | n | raw r(debt, remaining) | partial r(debt, remaining \| elapsed) | raw r(nonzero, remaining) | partial r(nonzero, remaining \| elapsed) |
|---|---|---|---|---|---|
| 2 | 1048 | 0.032 | 0.027 | 0.032 | 0.031 |
| 3 | 1583 | 0.023 | 0.037 | 0.038 | 0.040 |
| 4 | 2748 | -0.029 | 0.026 | -0.007 | 0.004 |
| 5 | 3926 | -0.012 | 0.054 | -0.006 | 0.002 |
| 6 | 5631 | -0.092 | 0.033 | 0.003 | 0.021 |

Same conditioning against tail90 membership (point-biserial):

| Player count | raw r(debt, tail90) | partial r(debt, tail90 \| elapsed) | raw r(nonzero, tail90) | partial r(nonzero, tail90 \| elapsed) |
|---|---|---|---|---|
| 2 | 0.051 | -0.022 | -0.014 | -0.026 |
| 3 | 0.247 | 0.019 | 0.074 | 0.024 |
| 4 | 0.367 | -0.018 | 0.082 | -0.008 |
| 5 | 0.426 | -0.019 | 0.062 | -0.016 |
| 6 | 0.450 | 0.003 | 0.061 | -0.010 |

**Direct answer to the exposure hypothesis**: at least one of the debt-count or nonzero-indicator partial correlations retains a magnitude at or above ±0.05 at some player count after controlling for elapsed turns - see the table above for exactly where. This means the exposure hypothesis alone does not fully explain the raw association; some independent signal may remain, though at this magnitude it should be treated as a candidate for further scrutiny, not confirmed evidence of an independent effect.

## 7. Part B — distribution of debt across players/Tiers

For each event with nonzero `totalPendingExtraTierTurns`, two derived measures from the new per-(player, Tier) breakdown: **breadth** (how many distinct (player, Tier) cells carry any debt) and **concentration** (the largest single cell's own count) - distinguishing debt spread thinly across many players/Tiers from debt piled onto one.

| Player count | n (debt>0) | Mean breadth | Mean concentration | r(breadth, remaining) | r(concentration, remaining) |
|---|---|---|---|---|---|
| 2 | 767 | 1.42 | 1.31 | 0.043 (n=767) | -0.016 (n=767) |
| 3 | 1399 | 1.91 | 1.44 | 0.021 (n=1399) | -0.005 (n=1399) |
| 4 | 2617 | 2.62 | 1.64 | -0.031 (n=2617) | -0.019 (n=2617) |
| 5 | 3815 | 3.35 | 1.84 | -0.006 (n=3815) | -0.013 (n=3815) |
| 6 | 5534 | 4.26 | 2.04 | -0.049 (n=5534) | -0.085 (n=5534) |

Breadth and concentration are close in magnitude for most events (a nonzero-debt event typically has 1-2 distinct cells at this scale of debt accumulation) - neither is treated as a stronger signal than the plain total from Section 6 unless its own correlation meaningfully diverges from it above.

## 8. Part B — persistence and change across consecutive regeneration events

Within each game, consecutive regeneration events (ordered by depth) are compared pairwise. Lag-1 autocorrelation of `totalPendingExtraTierTurns`, then a persistence categorization (mirroring the prior refined-evidence report's own elevated/persistent/transient framework, applied here to debt instead of card multiplicity): **persistent-nonzero** (nonzero now and at the immediately preceding event), **transient-new-nonzero** (nonzero now, zero at the preceding event), **zero** (zero now). The first event of each game is excluded (no preceding event to compare against).

| Player count | n (pairs) | Lag-1 autocorrelation | r(Δdebt, remaining) |
|---|---|---|---|
| 2 | 423 | 0.235 (n=423) | -0.019 (n=423) |
| 3 | 822 | 0.562 (n=822) | -0.007 (n=822) |
| 4 | 1876 | 0.761 (n=1876) | 0.044 (n=1876) |
| 5 | 2991 | 0.886 (n=2991) | 0.025 (n=2991) |
| 6 | 4665 | 0.923 (n=4665) | 0.021 (n=4665) |

Mean turns-remaining by persistence category, pooled across player counts (each event's own player-count-specific scale isn't compared across categories here, only within the pooled set - treat as descriptive, not a player-count-conditioned test):

| Category | n | Mean turns remaining | SD |
|---|---|---|---|
| zero | 237 | 828.4 | 960.9 |
| transient-new-nonzero | 389 | 1027.6 | 1208.8 |
| persistent-nonzero | 10151 | 1491.3 | 1455.0 |

Persistent-nonzero vs. transient-new-nonzero: mean difference = 463.7 turns, z = 7.36 (**distinguishable** at |z|>1.96).

**Caveat**: as with the prior refined-evidence report's own persistence analysis, a category-size imbalance here is structural, not itself evidence - `totalPendingExtraTierTurns` only changes when a Phase Control card or the "extra turn" Time Wrinkle square is actually drawn/landed, which is far rarer than a plain regeneration event, so most consecutive pairs are `zero`->`zero` regardless of any stagnation dynamic.

## 9. Observation-point caveat: state observed at regeneration vs. state appropriate for controlling it

Every measurement in this report and its predecessor is sampled **at the moment a Fate Harvest regeneration event happens to occur** - because that is where this whole research line's instrumentation lives (`FateHarvestDeck.ReshuffleStrategy`), not because regeneration events are a privileged or representative sampling point for game state in general. This matters for two distinct claims that must not be conflated:

- **"State observed when regeneration occurs"** - what this report actually measures: the board/debt state at whatever turn happened to trigger a reshuffle. Regeneration timing itself is driven by deck exhaustion (draw-pile size, itself a function of how many cards have been drawn, which tracks elapsed turns and player count), not by anything about board progress or turn debt - so this sampling point has no a priori reason to be representative of "the board state at a random turn" versus any other turn in the same game.
- **"State caused by or appropriate for controlling regeneration"** - a different, stronger claim this report does NOT make or test: that upper-Tier occupancy or turn debt should itself influence *how* a regeneration behaves. Nothing here establishes that a predictor's association with subsequent duration (measured at a regeneration event) would look the same, stronger, or weaker if measured at an arbitrary turn instead of specifically a reshuffle-triggering one - that would need its own dedicated instrumentation (sampling at fixed turn intervals, or at every turn), not attempted in this pass.

Practically: even where a signal survives this report's elapsed-turns conditioning, that alone does not establish the signal is *about* regeneration, or that a future anti-stagnation mechanism belongs inside Fate Harvest regeneration specifically rather than some other turn-level checkpoint - that design question is explicitly out of this task's scope and unresolved by anything measured here.

## 10. Classification

**A** = robust forward predictor (survives conditioning, potentially useful effect size). **B** = real but weak/context-dependent (detectable but presently insufficient to justify control logic). **C** = primarily exposure/structural artifact (apparent association substantially disappears under appropriate conditioning).

### Upper-Tier progress family

Largest partial correlation (any predictor, any player count, controlling for elapsed turns): 0.187. Section 3 found only 1 of 15 predictor pairs near-redundant (mean |r|>=0.7) - playersOnTier4/inPlayTokens@Tier4 specifically, which are a near-exact mathematical identity given the 4th Tier's own 1-in-play cap, not evidence that all 6 predictors collapse to one condition. The other 5 predictors remain largely distinct from each other (mean |r| across all 15 pairs was only 0.196).

**Classification: A.** A meaningful partial signal survives elapsed-turns conditioning, essentially unchanged from its own raw value (see Section 4's own mean-delta figure) - unlike regeneration depth in the prior report, these predictors' raw association with turns-remaining is not primarily an elapsed-turns artifact, though the magnitude itself (|r| roughly 0.05-0.19) is modest, not large.

### Pending extra-Tier-turn debt family

Largest partial correlation against turns-remaining (debt count or nonzero indicator, any player count, controlling for elapsed turns): 0.054. Largest *raw* r against tail90 membership: 0.450 - the prior report's own headline r=0.336 finding, reproduced here (Section 6's raw-r column peaks at 0.450). Largest **partial** r against tail90 membership once elapsed turns are controlled for: 0.026.

**These two facets diverge, and both matter for the classification.** The continuous turns-remaining relationship was already small in its raw form and barely changes under conditioning (borderline surviving, per Section 6's own table). The tail-membership relationship - the original, larger, headline finding - goes from a substantial raw association (up to 0.450) to essentially nothing (0.026) once elapsed turns are controlled for: **that specific finding is a near-complete exposure/structural artifact**, exactly matching this task's own stated hypothesis.

**Classification: B** (driven by the larger of the two facets above, per this report's own stated methodology; note the tail-membership facet alone would classify C). The tail-membership facet fully collapses under conditioning (a near-complete exposure artifact), but the continuous turns-remaining facet retains a small, borderline signal at one or two player counts. The magnitude is modest either way - presently insufficient to justify control logic - and the classification is driven by the weaker-surviving facet, not the larger, now-explained tail finding that motivated this investigation in the first place.

### Whether this justifies moving from characterization into mechanism design

**Partially, with caution.** At least one family classifies above C - see that family's own classification and effect size above before treating this as sufficient grounds to design a mechanism. Per Section 9's own caveat, any surviving signal here was measured only at regeneration events, not validated as a property of regeneration itself - that gap should be closed before committing a mechanism to living inside Fate Harvest regeneration specifically.

## Closing statement

No balance change, canon decision, weight derivation, configuration change, or canonical progress metric was made or created based on the above - per this task's explicit scope, this is a measurement and interpretation pass only. Card identity/rarity stayed recorded (`RegenerationObservation.multiplicity`) for future-catalog comparison but was not reanalyzed or reweighted.

Total games analyzed: 5000. Total invariant violations: 0.
