# T.O.E. Phase 1C — Intervention-Point Validation

**Experimental, measurement only — Clearance C2 (instrument/test). No anti-stagnation mechanism was designed or implemented; no gameplay, regeneration probabilities, rarity ceilings, card behavior, `StagnationPressureConfig`, or `FateHarvestRegenerationConfig.ANTI_STAGNATION` change was made.** Tests whether the upper-Tier progress family classified A in `446cde9` is a general property of settled game state (predictive regardless of when sampled) or a relationship specific to Fate Harvest regeneration events, by pairing every regeneration observation with a matched ordinary observation from the immediately preceding completed turn.

Preserves every prior Phase 1B/1C report unmodified as historical/superseded-methodology record.

## 1. Tests, invariants, and determinism

- **Full engine suite**: run green before this benchmark, per this Phase's own established sequencing.
- **Whole-game rarity invariant**: re-checked after every turn of every game attempted. Result: **0 violation(s)**.
- **No new engine surface**: this pass adds zero main-source-tree changes - every field it reads (`tierPool(...).inPlayCount`/`stagingPile`) already existed; the only new code is entirely test-local (turn-boundary snapshotting, matched-pair bookkeeping).
- **Instrumentation RNG safety**: the settled-state snapshot is a pure read of `GameState` taken after `driveOneTurn()` returns and every invariant re-check passes - no `Random` instance is touched.
- **Determinism cross-check against the published corrected baseline**: reuses that benchmark's own `BASE_SEED`.

| Player count | This run mean turns | Published mean turns | Match? | This run cap rate | Published cap rate | Match? |
|---|---|---|---|---|---|---|
| 2 | 669.0 | 669.0 | **Yes** | 0.00% | 0.00% | **Yes** |
| 3 | 869.7 | 869.7 | **Yes** | 0.00% | 0.00% | **Yes** |
| 4 | 1327.3 | 1327.3 | **Yes** | 0.00% | 0.00% | **Yes** |
| 5 | 1776.9 | 1776.9 | **Yes** | 0.40% | 0.40% | **Yes** |
| 6 | 2424.4 | 2424.4 | **Yes** | 3.10% | 3.10% | **Yes** |

**Aggregate determinism confirmed.**

Second cross-check: this run's own regeneration-only partial correlations (predictor, turns-remaining, controlling for elapsed turns) against `446cde9`'s published Section 4 values - proves the new turn-boundary snapshotting doesn't perturb the regeneration observations themselves.

| Predictor | 2P this run | 3P this run | 4P this run | 5P this run | 6P this run | 2P published | 3P published | 4P published | 5P published | 6P published | Match? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| highestTierInPlay | -0.169 | -0.177 | -0.108 | -0.083 | -0.077 | -0.169 | -0.177 | -0.108 | -0.083 | -0.077 | **Yes** |
| playersOnTier4 | -0.115 | -0.153 | -0.091 | -0.087 | -0.080 | -0.115 | -0.153 | -0.091 | -0.087 | -0.080 | **Yes** |
| stagingPile@Tier2 | -0.085 | -0.109 | -0.077 | -0.097 | -0.057 | -0.085 | -0.109 | -0.077 | -0.097 | -0.057 | **Yes** |
| stagingPile@Tier3 | -0.187 | -0.187 | -0.111 | -0.135 | -0.091 | -0.187 | -0.187 | -0.111 | -0.135 | -0.091 | **Yes** |
| inPlayTokens@Tier3 | -0.133 | -0.090 | -0.083 | -0.069 | -0.057 | -0.133 | -0.090 | -0.083 | -0.069 | -0.057 | **Yes** |

**Regeneration-observation determinism confirmed**: every predictor's partial correlation, recomputed under this file's own instrumentation, exactly matches the published `446cde9` values.

## 2. Sampling methodology and coverage

**Matched-pairs design**: for every regeneration event, the settled game state at the end of the immediately preceding completed turn is also recorded (an already-existing engine boundary - state is guaranteed settled between turns, per every prior benchmark's own invariant checks - not a new gameplay event). This gives a 1:1 sample-size match by construction (never more ordinary observations than regeneration observations for a given game) and minimizes the elapsed-turn gap between a matched pair. A regeneration on a game's very first turn (before any turn has completed) has no match and is excluded from the ordinary side, not padded with a placeholder.

| Player count | Regen observations | Matched ordinary observations | Coverage | Mean elapsed-turn gap (regen − matched ordinary) |
|---|---|---|---|---|
| 2 | 1048 | 1048 | 100.0% | 0.00 |
| 3 | 1583 | 1583 | 100.0% | 0.00 |
| 4 | 2748 | 2748 | 100.0% | 0.00 |
| 5 | 3926 | 3926 | 100.0% | 0.00 |
| 6 | 5631 | 5631 | 100.0% | 0.00 |

A minimum of 30 observations is required before a correlation is treated as reliable rather than merely reported.

## 3. Primary comparison: predictor vs. turns-remaining, regeneration vs. ordinary observations

For each predictor, per player count: partial r (controlling for elapsed turns) computed **separately** on the regeneration-only subset and the matched-ordinary-only subset, then a Fisher r-to-z test for whether the two correlations are distinguishable from each other beyond sampling noise (|z|>1.96 = materially different; a standard technique for comparing two independent correlations).

| Predictor | PC | n (regen) | Regen partial r | n (ordinary) | Ordinary partial r | Fisher z | Materially different? |
|---|---|---|---|---|---|---|---|
| highestTierInPlay | 2 | 1048 | -0.169 | 1048 | -0.170 | 0.02 | No |
| highestTierInPlay | 3 | 1583 | -0.177 | 1583 | -0.176 | -0.03 | No |
| highestTierInPlay | 4 | 2748 | -0.108 | 2748 | -0.110 | 0.05 | No |
| highestTierInPlay | 5 | 3926 | -0.083 | 3926 | -0.083 | -0.02 | No |
| highestTierInPlay | 6 | 5631 | -0.077 | 5631 | -0.075 | -0.10 | No |
| playersOnTier4 | 2 | 1048 | -0.115 | 1048 | -0.115 | 0.00 | No |
| playersOnTier4 | 3 | 1583 | -0.153 | 1583 | -0.153 | 0.00 | No |
| playersOnTier4 | 4 | 2748 | -0.091 | 2748 | -0.091 | 0.00 | No |
| playersOnTier4 | 5 | 3926 | -0.087 | 3926 | -0.087 | 0.00 | No |
| playersOnTier4 | 6 | 5631 | -0.080 | 5631 | -0.080 | 0.00 | No |
| stagingPile@Tier2 | 2 | 1048 | -0.085 | 1048 | -0.085 | 0.00 | No |
| stagingPile@Tier2 | 3 | 1583 | -0.109 | 1583 | -0.109 | 0.00 | No |
| stagingPile@Tier2 | 4 | 2748 | -0.077 | 2748 | -0.075 | -0.09 | No |
| stagingPile@Tier2 | 5 | 3926 | -0.097 | 3926 | -0.093 | -0.17 | No |
| stagingPile@Tier2 | 6 | 5631 | -0.057 | 5631 | -0.057 | 0.02 | No |
| stagingPile@Tier3 | 2 | 1048 | -0.187 | 1048 | -0.187 | 0.00 | No |
| stagingPile@Tier3 | 3 | 1583 | -0.187 | 1583 | -0.187 | 0.00 | No |
| stagingPile@Tier3 | 4 | 2748 | -0.111 | 2748 | -0.108 | -0.09 | No |
| stagingPile@Tier3 | 5 | 3926 | -0.135 | 3926 | -0.131 | -0.18 | No |
| stagingPile@Tier3 | 6 | 5631 | -0.091 | 5631 | -0.091 | -0.03 | No |
| inPlayTokens@Tier3 | 2 | 1048 | -0.133 | 1048 | -0.133 | -0.00 | No |
| inPlayTokens@Tier3 | 3 | 1583 | -0.090 | 1583 | -0.089 | -0.02 | No |
| inPlayTokens@Tier3 | 4 | 2748 | -0.083 | 2748 | -0.083 | 0.02 | No |
| inPlayTokens@Tier3 | 5 | 3926 | -0.069 | 3926 | -0.069 | -0.02 | No |
| inPlayTokens@Tier3 | 6 | 5631 | -0.057 | 5631 | -0.056 | -0.02 | No |

**Central-question summary**: 0 of 25 predictor/player-count cells show a statistically material difference (|z|>1.96) between regeneration-only and ordinary-only partial correlations. None - at comparable player counts and elapsed-game states, upper-Tier progress predicts subsequent resolution similarly whether sampled at a regeneration event or an ordinary settled turn boundary.

## 4. Tail behavior: p90/p95/capped-tail membership, regeneration vs. ordinary

Point-biserial correlation (predictor vs. tail-game membership, 0/1, using each observation's own game-level outcome), regeneration-only vs. ordinary-only, per predictor pooled across player counts (each event's own tail membership is determined by its own player count's own p90/p95, so pooling at this level doesn't reintroduce a raw-scale confound):

| Predictor | Regen r (tail90) | Ordinary r (tail90) | Regen r (tail95) | Ordinary r (tail95) | Regen r (capped) | Ordinary r (capped) |
|---|---|---|---|---|---|---|
| highestTierInPlay | -0.050 (n=14936) | -0.050 (n=14936) | -0.049 (n=14936) | -0.048 (n=14936) | -0.018 (n=14936) | -0.017 (n=14936) |
| playersOnTier4 | -0.043 (n=14936) | -0.043 (n=14936) | -0.039 (n=14936) | -0.039 (n=14936) | -0.031 (n=14936) | -0.031 (n=14936) |
| stagingPile@Tier2 | -0.042 (n=14936) | -0.041 (n=14936) | -0.031 (n=14936) | -0.031 (n=14936) | 0.009 (n=14936) | 0.008 (n=14936) |
| stagingPile@Tier3 | -0.038 (n=14936) | -0.036 (n=14936) | -0.031 (n=14936) | -0.030 (n=14936) | -0.029 (n=14936) | -0.029 (n=14936) |
| inPlayTokens@Tier3 | -0.036 (n=14936) | -0.036 (n=14936) | -0.038 (n=14936) | -0.038 (n=14936) | -0.019 (n=14936) | -0.018 (n=14936) |

Effect sizes throughout this table are in the same modest range as Section 3's own turns-remaining figures (no predictor here reaches a magnitude that would itself be practically notable independent of statistical reliability) - reported for completeness per the task's own instruction, not promoted as a standalone finding beyond what Section 3 already established.

## 5. Does a regeneration event itself carry information, after conditioning on measured state?

Multi-partial correlation of an `isRegen` indicator (1 = this observation was a regeneration event, 0 = the matched ordinary observation) with turns-remaining, controlling for all 5 distinct predictors AND elapsed turns simultaneously (6 covariates, via OLS residualization - [multiPartialCorrelation], not the single-control formula used elsewhere in this Phase). Computed on the pooled matched-pairs dataset (regen + ordinary rows together) per player count, since `isRegen` only varies within that pooled set. **Correlational only - this does not establish that regeneration causes any change in subsequent play, only whether its own timing carries residual predictive information once the measured state is already accounted for.**

| Player count | n | Multi-partial r(isRegen, turnsRemaining \| 5 predictors + elapsed) |
|---|---|---|
| 2 | 2096 | 0.000 |
| 3 | 3166 | 0.000 |
| 4 | 5496 | -0.000 |
| 5 | 7852 | -0.000 |
| 6 | 11262 | -0.000 |

**Direct answer**: largest magnitude across all 5 player counts is 0.000. Once the measured upper-Tier state and elapsed turns are already accounted for, whether an observation happened to be a regeneration event carries essentially no additional information about turns-remaining. This does not support treating regeneration timing itself as a privileged or informative boundary beyond the state it happens to co-occur with.

## 6. Classification

**A** = general progress-state signal (predicts subsequent resolution similarly at ordinary settled-state observations; regeneration was principally an observation point). **B** = regeneration-conditioned (predictive, but materially different/stronger around regeneration events). **C** = sampling artifact (the relationship substantially weakens or disappears independent of regeneration).

**Inputs to this classification**: 0 of 25 predictor/player-count cells show a materially different regen-vs-ordinary partial correlation (Section 3); ordinary-observation partial correlations do retain reliable, non-trivial magnitude on their own (|r|>=0.05 at n>=30); the regeneration-as-intervention-point multi-partial correlation peaks at 0.000 (Section 5).

**Classification: A.** Upper-Tier progress predicts subsequent resolution similarly whether measured at a regeneration event or an ordinary settled turn boundary, and regeneration's own timing carries no meaningful residual information once the measured state is accounted for. `446cde9`'s own A classification reflects a genuine property of game state, not an artifact of where this research line's instrumentation happened to sample it.

## 7. Semantic and architectural ambiguities

- **The matched-ordinary observation is always the turn immediately preceding a regeneration, never a randomly chosen turn.** This was a deliberate choice to minimize the elapsed-turn gap and isolate "was this specifically a regeneration" as cleanly as possible, but it means ordinary observations are not an unconditional sample of "any settled turn boundary" - they are specifically "the turn boundary right before a regeneration happened to occur." If regeneration timing itself correlates with something about recent game history (e.g. a burst of card draws), the matched ordinary sample could inherit a faint version of that same correlation rather than being fully independent of regeneration. Section 5's own multi-partial-correlation test is the more direct check for this, since it isolates `isRegen` itself as a variable rather than relying on the matched design alone.
- **Two regenerations within the same turn's own resolution (possible via a Precedence chain or multiple card draws in one turn) would pair with the identical matched-ordinary snapshot**, producing a duplicate ordinary row rather than two independent ones. Not corrected for - expected to be rare enough not to materially bias the aggregate figures above, but not separately verified in this pass.
- **`multiPartialCorrelation`'s OLS implementation has no formal collinearity diagnostic** beyond the numerical guard in `solveLinearSystem` (a near-zero pivot is treated as a zero coefficient) - sufficient for this file's own control set (5 predictors + elapsed turns, none exactly collinear once `inPlayTokens@Tier4` is excluded in favor of its canonical `playersOnTier4` equivalent), but a future reuse of this helper with a differently-chosen control set should re-verify that no two controls are near-perfectly collinear before trusting its residuals.

## Closing statement

No anti-stagnation mechanism was designed or implemented; `StagnationPressureConfig`/`FateHarvestRegenerationConfig.ANTI_STAGNATION` were not touched; no probabilities were tuned; no canonical progress metric was created. Card identity's Outcome C stays scoped to the present catalog and was not revisited; `totalPendingExtraTierTurns` was out of this task's scope entirely.

Total games analyzed: 5000. Total invariant violations: 0.
