# T.O.E. Phase 1C — Refined Anti-Stagnation Evidence Analysis

**Experimental, measurement only — Clearance C2 (instrument/test). No gameplay, regeneration semantics, rarity ceilings, or `StagnationPressureConfig` change was made in this pass.** Replaces the pooled-correlation evidence model (final card multiplicity vs. total game length, confounded by exposure - every one of the 32 cards showed a positive correlation) with a per-regeneration-event, forward-looking model (card multiplicity immediately after a regeneration vs. turns remaining from that point), conditioned on player count, regeneration depth, and rarity class. **This report does not derive or apply weights** - see Section 9 for whether the refined evidence supports card-identity-based weighting at all.

Preserves `docs/benchmarks/fate-harvest-regeneration-benchmark-corrected.md` (the corrected baseline) and `docs/benchmarks/anti-stagnation-benchmark-corrected.md` (the pooled-correlation Phase 1C report) unmodified, as historical/superseded-methodology record.

## 1. Tests, invariants, and determinism

- **Full engine suite**: run green (370 tests) before this benchmark, per the task's own required sequencing - see the commit history for this pass.
- **Whole-game rarity invariant**: `checkInvariants` (duplicated from the corrected baseline's own, unchanged) re-checks, after every single turn of every one of the 5000 games attempted, that draw pile + discard pile + hands + resolving cards never exceeds any card's own Generation-0 rarity, plus token/Phase/pendingRoll/winner-square conservation. Result: **0 violation(s)**.
- **Instrumentation RNG safety**: the added recording code (`RegenerationObservation` construction, the `turnsElapsedProvider()` read) touches no `Random` instance anywhere - it only reads values `FateHarvestRegenerationRules.regenerate` and the game loop's own turn counter already computed. `FateHarvestDeck.ReshuffleStrategy`'s own `rnd` parameter is passed through to `regenerate` completely unchanged from the corrected baseline's own call.
- **Determinism cross-check against the published corrected baseline**: this run deliberately reuses that benchmark's own `BASE_SEED` (`2_100_000_000L`) - if instrumentation altered gameplay at all, per-cohort aggregate turns/cap-rate would diverge from the already-published Table K1 figures.

| Player count | This run mean turns | Published mean turns | Match? | This run cap rate | Published cap rate | Match? |
|---|---|---|---|---|---|---|
| 2 | 669.0 | 669.0 | **Yes** | 0.00% | 0.00% | **Yes** |
| 3 | 869.7 | 869.7 | **Yes** | 0.00% | 0.00% | **Yes** |
| 4 | 1327.3 | 1327.3 | **Yes** | 0.00% | 0.00% | **Yes** |
| 5 | 1776.9 | 1776.9 | **Yes** | 0.40% | 0.40% | **Yes** |
| 6 | 2424.4 | 2424.4 | **Yes** | 3.10% | 3.10% | **Yes** |

**Determinism confirmed**: every cohort's aggregate statistics under this instrumented run exactly match the already-published corrected baseline - the same seeds produced the same gameplay, with instrumentation adding observation only.

## 2. Benchmark methodology and sample sizes

Same corrected, rarity-ceiling-respecting regeneration model as the existing baseline (`FateHarvestRegenerationConfig.DEFAULT`, no anti-stagnation pressure), same per-player-count color rotation and canonical color-card filtering. New: every regeneration event now records its own depth (1-based, this game's own Nth regeneration), how many turns had fully completed before the turn it happened during, and every eligible card's post-regeneration multiplicity. "Turns remaining" for an event is computed as `game.totalTurnsTaken - event.turnsElapsedAtRegeneration` once the game finishes. A card's own Generation-0 rarity is a static per-card-name fact (`FateHarvestCard.rarity.copies`), looked up rather than re-recorded per event. A card's own multiplicity is only ever included in that card's analysis for games where the card was actually eligible (unrestricted, or its own color was seated that game) - a color-restricted card being structurally absent (multiplicity always 0 because the color wasn't even seated) is excluded entirely from that card's sample, rather than diluting it with trivial zero-observations.

Scale: 1000 games/cohort × 5 player counts = 5000 total games attempted, same seed range as the corrected baseline (`BASE_SEED = 2100000000`).

| Player count | Games | Total regeneration events | Mean events/game | Median events/game |
|---|---|---|---|---|
| 2 | 1000 | 1048 | 1.0 | 1 |
| 3 | 1000 | 1583 | 1.6 | 1 |
| 4 | 1000 | 2748 | 2.7 | 2 |
| 5 | 1000 | 3926 | 3.9 | 3 |
| 6 | 1000 | 5631 | 5.6 | 4 |

Total regeneration events across all cohorts: 14936. Every result below reports its own observation count `n`; a minimum of 30 observations is required before a correlation is treated as reliable rather than merely reported (per the task's explicit "do not draw strong conclusions from sparse late-regeneration cells" instruction) - cells below that threshold are marked `insufficient`.

## 3. Within-player-count analysis

Primary relationship (see Section 5 for why this replaces final-multiplicity-vs-total-length): Pearson r between a card's post-regeneration multiplicity and turns remaining from that event, computed **separately per player count** rather than pooled - eliminating the 2P-6P pooling confound before any aggregate interpretation, per the task's explicit instruction.

| Card | 2P r (n) | 3P r (n) | 4P r (n) | 5P r (n) | 6P r (n) |
|---|---|---|---|---|---|
| Annulment (Antimatter) | -0.003 (n=1048) | -0.033 (n=1583) | 0.018 (n=2748) | -0.002 (n=3926) | 0.012 (n=5631) |
| Circulate (Elemental) | -0.009 (n=1048) | 0.021 (n=1583) | 0.006 (n=2748) | -0.001 (n=3926) | -0.005 (n=5631) |
| Cleansing (Atmospheric) | 0.026 (n=1048) | 0.008 (n=1583) | 0.008 (n=2748) | -0.003 (n=3926) | 0.015 (n=5631) |
| Corpuscle Rot | -0.026 (n=401) | -0.006 (n=834) | -0.001 (n=1881) | -0.028 (n=3313) | 0.000 (n=5631) |
| Delayed Motion | -0.018 (n=1048) | -0.031 (n=1583) | 0.003 (n=2748) | 0.003 (n=3926) | 0.012 (n=5631) |
| Divine Assistance | 0.000 (n=1048) | 0.000 (n=1583) | 0.000 (n=2748) | 0.000 (n=3926) | 0.000 (n=5631) |
| Dwarf Star | -0.049 (n=397) | 0.009 (n=811) | 0.013 (n=1861) | -0.014 (n=3254) | -0.001 (n=5631) |
| Elemental Rebirth | 0.000 (n=1048) | 0.000 (n=1583) | 0.000 (n=2748) | 0.000 (n=3926) | 0.000 (n=5631) |
| Emitting Nebula | 0.020 (n=1048) | -0.017 (n=1583) | 0.005 (n=2748) | -0.000 (n=3926) | 0.001 (n=5631) |
| Essence Assimilator | 0.000 (n=1048) | 0.000 (n=1583) | 0.000 (n=2748) | 0.000 (n=3926) | -0.003 (n=5631) |
| Evasive Action | 0.000 (n=1048) | 0.000 (n=1583) | 0.000 (n=2748) | 0.000 (n=3926) | 0.011 (n=5631) |
| Fluidic Wave | 0.000 (n=317) | 0.039 (n=740) | -0.053 (n=1818) | -0.025 (n=3197) | 0.014 (n=5631) |
| Galactic Roundabout | 0.000 (n=1048) | 0.000 (n=1583) | 0.000 (n=2748) | 0.000 (n=3926) | 0.000 (n=5631) |
| Graviton Rift | 0.000 (n=349) | 0.036 (n=843) | 0.015 (n=1875) | 0.002 (n=3305) | -0.020 (n=5631) |
| Infernal Abyss | 0.000 (n=1048) | 0.000 (n=1583) | 0.000 (n=2748) | 0.000 (n=3926) | 0.000 (n=5631) |
| Insidious Flux | 0.024 (n=1048) | -0.023 (n=1583) | 0.026 (n=2748) | -0.007 (n=3926) | -0.000 (n=5631) |
| Last Gasp | 0.038 (n=1048) | -0.022 (n=1583) | -0.026 (n=2748) | 0.009 (n=3926) | -0.016 (n=5631) |
| Luckier Nebula | 0.000 (n=1048) | 0.000 (n=1583) | 0.000 (n=2748) | 0.000 (n=3926) | 0.000 (n=5631) |
| Lucky Nebula | 0.000 (n=1048) | 0.000 (n=1583) | 0.017 (n=2748) | 0.000 (n=3926) | 0.000 (n=5631) |
| Materialize Army | 0.002 (n=1048) | -0.007 (n=1583) | 0.006 (n=2748) | -0.008 (n=3926) | -0.003 (n=5631) |
| Materialize Help | 0.000 (n=1048) | 0.000 (n=1583) | 0.000 (n=2748) | 0.000 (n=3926) | 0.000 (n=5631) |
| Parallel Phasing | 0.000 (n=1048) | 0.000 (n=1583) | 0.000 (n=2748) | 0.000 (n=3926) | 0.000 (n=5631) |
| Phase Control | 0.000 (n=1048) | 0.000 (n=1583) | 0.000 (n=2748) | 0.000 (n=3926) | 0.015 (n=5631) |
| Phase Loss | 0.000 (n=1048) | 0.000 (n=1583) | 0.000 (n=2748) | 0.000 (n=3926) | 0.000 (n=5631) |
| Planetary Nebula | -0.027 (n=1048) | -0.048 (n=1583) | -0.026 (n=2748) | -0.043 (n=3926) | -0.030 (n=5631) |
| Plasma Burst | 0.037 (n=330) | -0.008 (n=772) | 0.026 (n=1797) | -0.003 (n=3289) | 0.008 (n=5631) |
| Radiation Burst | -0.026 (n=1048) | 0.042 (n=1583) | -0.034 (n=2748) | 0.005 (n=3926) | 0.008 (n=5631) |
| Sidestep (Extinction Avoidance) | 0.000 (n=1048) | -0.019 (n=1583) | -0.005 (n=2748) | -0.008 (n=3926) | -0.007 (n=5631) |
| Skip, Hop, and Jump (Dimensional) | 0.025 (n=1048) | 0.027 (n=1583) | 0.001 (n=2748) | -0.005 (n=3926) | -0.008 (n=5631) |
| Tactical Motion | -0.009 (n=1048) | 0.038 (n=1583) | -0.035 (n=2748) | 0.031 (n=3926) | -0.004 (n=5631) |
| Tactical Step | -0.010 (n=1048) | -0.046 (n=1583) | -0.008 (n=2748) | 0.006 (n=3926) | -0.005 (n=5631) |
| Verdant Growth | -0.025 (n=302) | -0.019 (n=749) | -0.043 (n=1760) | -0.037 (n=3272) | -0.034 (n=5631) |

**Cross-player-count consistency summary** (reliable cells only, n>=30):

| Card | Reliable cells | Mean r | Range (min to max) | Sign-consistent? |
|---|---|---|---|---|
| Annulment (Antimatter) | 5 | -0.002 | -0.033 to 0.018 | **No** |
| Circulate (Elemental) | 5 | 0.003 | -0.009 to 0.021 | **No** |
| Cleansing (Atmospheric) | 5 | 0.011 | -0.003 to 0.026 | **No** |
| Corpuscle Rot | 5 | -0.012 | -0.028 to 0.000 | **No** |
| Delayed Motion | 5 | -0.006 | -0.031 to 0.012 | **No** |
| Divine Assistance | 5 | 0.000 | 0.000 to 0.000 | Yes |
| Dwarf Star | 5 | -0.009 | -0.049 to 0.013 | **No** |
| Elemental Rebirth | 5 | 0.000 | 0.000 to 0.000 | Yes |
| Emitting Nebula | 5 | 0.002 | -0.017 to 0.020 | **No** |
| Essence Assimilator | 5 | -0.001 | -0.003 to 0.000 | Yes |
| Evasive Action | 5 | 0.002 | 0.000 to 0.011 | Yes |
| Fluidic Wave | 5 | -0.005 | -0.053 to 0.039 | **No** |
| Galactic Roundabout | 5 | 0.000 | 0.000 to 0.000 | Yes |
| Graviton Rift | 5 | 0.007 | -0.020 to 0.036 | **No** |
| Infernal Abyss | 5 | 0.000 | 0.000 to 0.000 | Yes |
| Insidious Flux | 5 | 0.004 | -0.023 to 0.026 | **No** |
| Last Gasp | 5 | -0.003 | -0.026 to 0.038 | **No** |
| Luckier Nebula | 5 | 0.000 | 0.000 to 0.000 | Yes |
| Lucky Nebula | 5 | 0.003 | 0.000 to 0.017 | Yes |
| Materialize Army | 5 | -0.002 | -0.008 to 0.006 | **No** |
| Materialize Help | 5 | 0.000 | 0.000 to 0.000 | Yes |
| Parallel Phasing | 5 | 0.000 | 0.000 to 0.000 | Yes |
| Phase Control | 5 | 0.003 | 0.000 to 0.015 | Yes |
| Phase Loss | 5 | 0.000 | 0.000 to 0.000 | Yes |
| Planetary Nebula | 5 | -0.035 | -0.048 to -0.026 | Yes |
| Plasma Burst | 5 | 0.012 | -0.008 to 0.037 | **No** |
| Radiation Burst | 5 | -0.001 | -0.034 to 0.042 | **No** |
| Sidestep (Extinction Avoidance) | 5 | -0.008 | -0.019 to 0.000 | **No** |
| Skip, Hop, and Jump (Dimensional) | 5 | 0.008 | -0.008 to 0.027 | **No** |
| Tactical Motion | 5 | 0.004 | -0.035 to 0.038 | **No** |
| Tactical Step | 5 | -0.013 | -0.046 to 0.006 | **No** |
| Verdant Growth | 5 | -0.032 | -0.043 to -0.019 | Yes |

## 4. Regeneration-depth conditioning

Depths bucketed as 1, 2, 3, 4, and 5+ (deeper buckets pool remaining depths - see the aggregate sample-size table below for why: mean generations/game tops out around 5.6 even at 6P, per the corrected baseline's own Table K4, so per-depth cells beyond ~5 are sparse by construction, not a bug). Depth-bucket cells are pooled **across player counts** (a per-player-count-per-depth-per-card cross would be far too sparse at this scale - see the counts below) - this reintroduces the player-count confound within this specific cross-section, which is why Section 3's within-player-count table remains the primary evidence, not this one.

**Aggregate observation count per depth bucket (all cards pooled)** - establishes how much data conditioning on depth even has to work with:

| Depth bucket | Total observations | Distinct games reaching this depth |
|---|---|---|
| 1 | 125626 | 4159 |
| 2 | 86320 | 2828 |
| 3 | 60516 | 1967 |
| 4 | 43636 | 1410 |
| 5+ | 143491 | 1047 |

**Per-card, per-depth-bucket correlation (multiplicity vs. turns remaining, pooled across player counts)** - cells with n<30 marked insufficient rather than interpreted:

| Card | Depth 1 r (n) | Depth 2 r (n) | Depth 3 r (n) | Depth 4 r (n) | Depth 5+ r (n) |
|---|---|---|---|---|---|
| Annulment (Antimatter) | -0.010 (n=4159) | 0.032 (n=2828) | -0.010 (n=1967) | 0.027 (n=1410) | 0.005 (n=4572) |
| Circulate (Elemental) | -0.049 (n=4159) | 0.001 (n=2828) | -0.040 (n=1967) | -0.095 (n=1410) | -0.026 (n=4572) |
| Cleansing (Atmospheric) | 0.019 (n=4159) | 0.014 (n=2828) | -0.024 (n=1967) | -0.007 (n=1410) | 0.003 (n=4572) |
| Corpuscle Rot | -0.036 (n=2954) | -0.062 (n=2171) | -0.034 (n=1591) | -0.076 (n=1190) | -0.009 (n=4154) |
| Delayed Motion | -0.023 (n=4159) | -0.049 (n=2828) | -0.035 (n=1967) | -0.057 (n=1410) | -0.019 (n=4572) |
| Divine Assistance | 0.000 (n=4159) | 0.000 (n=2828) | 0.000 (n=1967) | 0.000 (n=1410) | 0.000 (n=4572) |
| Dwarf Star | -0.062 (n=2938) | -0.055 (n=2147) | -0.038 (n=1563) | -0.048 (n=1175) | -0.031 (n=4131) |
| Elemental Rebirth | 0.000 (n=4159) | 0.000 (n=2828) | 0.000 (n=1967) | 0.000 (n=1410) | 0.000 (n=4572) |
| Emitting Nebula | -0.014 (n=4159) | -0.052 (n=2828) | 0.002 (n=1967) | -0.022 (n=1410) | -0.022 (n=4572) |
| Essence Assimilator | 0.000 (n=4159) | -0.013 (n=2828) | 0.000 (n=1967) | 0.000 (n=1410) | 0.000 (n=4572) |
| Evasive Action | 0.000 (n=4159) | 0.000 (n=2828) | 0.000 (n=1967) | 0.000 (n=1410) | 0.010 (n=4572) |
| Fluidic Wave | -0.027 (n=2896) | -0.016 (n=2090) | 0.018 (n=1529) | 0.026 (n=1148) | 0.003 (n=4040) |
| Galactic Roundabout | 0.000 (n=4159) | 0.000 (n=2828) | 0.000 (n=1967) | 0.000 (n=1410) | 0.000 (n=4572) |
| Graviton Rift | 0.013 (n=2931) | -0.036 (n=2152) | -0.036 (n=1587) | -0.052 (n=1185) | -0.006 (n=4148) |
| Infernal Abyss | 0.000 (n=4159) | 0.000 (n=2828) | 0.000 (n=1967) | 0.000 (n=1410) | 0.000 (n=4572) |
| Insidious Flux | -0.034 (n=4159) | -0.049 (n=2828) | -0.012 (n=1967) | -0.021 (n=1410) | -0.013 (n=4572) |
| Last Gasp | -0.027 (n=4159) | 0.005 (n=2828) | -0.045 (n=1967) | -0.034 (n=1410) | -0.016 (n=4572) |
| Luckier Nebula | 0.000 (n=4159) | 0.000 (n=2828) | 0.000 (n=1967) | 0.000 (n=1410) | 0.000 (n=4572) |
| Lucky Nebula | 0.000 (n=4159) | 0.000 (n=2828) | 0.019 (n=1967) | 0.000 (n=1410) | 0.000 (n=4572) |
| Materialize Army | -0.024 (n=4159) | -0.035 (n=2828) | -0.006 (n=1967) | 0.011 (n=1410) | -0.021 (n=4572) |
| Materialize Help | 0.000 (n=4159) | 0.000 (n=2828) | 0.000 (n=1967) | 0.000 (n=1410) | 0.000 (n=4572) |
| Parallel Phasing | 0.000 (n=4159) | 0.000 (n=2828) | 0.000 (n=1967) | 0.000 (n=1410) | 0.000 (n=4572) |
| Phase Control | 0.000 (n=4159) | 0.000 (n=2828) | 0.000 (n=1967) | 0.000 (n=1410) | 0.016 (n=4572) |
| Phase Loss | 0.000 (n=4159) | 0.000 (n=2828) | 0.000 (n=1967) | 0.000 (n=1410) | 0.000 (n=4572) |
| Planetary Nebula | -0.137 (n=4159) | -0.041 (n=2828) | -0.044 (n=1967) | -0.082 (n=1410) | -0.099 (n=4572) |
| Plasma Burst | -0.062 (n=2899) | -0.047 (n=2133) | 0.015 (n=1558) | -0.072 (n=1146) | -0.026 (n=4083) |
| Radiation Burst | -0.027 (n=4159) | 0.001 (n=2828) | -0.032 (n=1967) | -0.043 (n=1410) | 0.013 (n=4572) |
| Sidestep (Extinction Avoidance) | -0.060 (n=4159) | -0.035 (n=2828) | -0.052 (n=1967) | -0.040 (n=1410) | -0.024 (n=4572) |
| Skip, Hop, and Jump (Dimensional) | -0.022 (n=4159) | -0.046 (n=2828) | -0.030 (n=1967) | -0.039 (n=1410) | -0.018 (n=4572) |
| Tactical Motion | -0.028 (n=4159) | 0.026 (n=2828) | 0.003 (n=1967) | 0.027 (n=1410) | -0.007 (n=4572) |
| Tactical Step | -0.027 (n=4159) | -0.013 (n=2828) | 0.029 (n=1967) | -0.030 (n=1410) | -0.021 (n=4572) |
| Verdant Growth | -0.080 (n=2874) | -0.104 (n=2099) | -0.080 (n=1546) | -0.068 (n=1132) | -0.050 (n=4063) |

Cards reaching the 30-observation reliability threshold at each depth bucket (out of 32 total): depth 1: 32, depth 2: 32, depth 3: 32, depth 4: 32, depth 5+: 32.

## 5. Lagged/forward-outcome analysis (primary evidence model)

Primary relationship, pooled across player counts for a single headline number per card (Section 3 above is the player-count-separated version of this same relationship, and is the one to trust first): `card multiplicity immediately after regeneration n` -> `turns remaining after regeneration n`. Contrasted directly below against the OLD model this replaces: `card's final multiplicity` -> `game's total turn count` (computed here from the same dataset, using only each game's own last regeneration event, for a like-for-like comparison rather than citing the separate historical report).

| Card | NEW: any-depth multiplicity -> turns remaining, r (n) | OLD: final multiplicity -> total game length, r (n) |
|---|---|---|
| Annulment (Antimatter) | 0.006 (n=14936) | 0.004 (n=4159) |
| Circulate (Elemental) | -0.037 (n=14936) | -0.007 (n=4159) |
| Cleansing (Atmospheric) | 0.003 (n=14936) | -0.007 (n=4159) |
| Corpuscle Rot | -0.035 (n=12060) | -0.052 (n=2954) |
| Delayed Motion | -0.035 (n=14936) | -0.039 (n=4159) |
| Divine Assistance | 0.000 (n=14936) | 0.000 (n=4159) |
| Dwarf Star | -0.047 (n=11954) | -0.053 (n=2938) |
| Elemental Rebirth | 0.000 (n=14936) | 0.000 (n=4159) |
| Emitting Nebula | -0.026 (n=14936) | -0.025 (n=4159) |
| Essence Assimilator | -0.005 (n=14936) | 0.000 (n=4159) |
| Evasive Action | 0.005 (n=14936) | 0.000 (n=4159) |
| Fluidic Wave | -0.005 (n=11703) | -0.008 (n=2896) |
| Galactic Roundabout | 0.000 (n=14936) | 0.000 (n=4159) |
| Graviton Rift | -0.016 (n=12003) | 0.000 (n=2931) |
| Infernal Abyss | 0.000 (n=14936) | 0.000 (n=4159) |
| Insidious Flux | -0.028 (n=14936) | -0.035 (n=4159) |
| Last Gasp | -0.022 (n=14936) | 0.009 (n=4159) |
| Luckier Nebula | 0.000 (n=14936) | 0.000 (n=4159) |
| Lucky Nebula | 0.007 (n=14936) | 0.003 (n=4159) |
| Materialize Army | -0.020 (n=14936) | -0.030 (n=4159) |
| Materialize Help | 0.000 (n=14936) | 0.000 (n=4159) |
| Parallel Phasing | 0.000 (n=14936) | 0.000 (n=4159) |
| Phase Control | 0.008 (n=14936) | -0.007 (n=4159) |
| Phase Loss | 0.000 (n=14936) | 0.000 (n=4159) |
| Planetary Nebula | -0.092 (n=14936) | -0.041 (n=4159) |
| Plasma Burst | -0.040 (n=11819) | -0.045 (n=2899) |
| Radiation Burst | -0.012 (n=14936) | -0.029 (n=4159) |
| Sidestep (Extinction Avoidance) | -0.043 (n=14936) | -0.050 (n=4159) |
| Skip, Hop, and Jump (Dimensional) | -0.030 (n=14936) | -0.047 (n=4159) |
| Tactical Motion | -0.001 (n=14936) | -0.010 (n=4159) |
| Tactical Step | -0.016 (n=14936) | -0.008 (n=4159) |
| Verdant Growth | -0.075 (n=11714) | -0.077 (n=2874) |

Note the OLD column's own n here (one observation per game a card was eligible in) is smaller than the previously-published Table K5's `n=5000` - that table pooled every game regardless of a color card's own eligibility, which Section 2 above identifies as its own separate confound this analysis corrects for throughout.

## 6. Original-rarity stratification and normalization

`normalized = multiplicity / Generation-0 rarity ceiling` (SINGLE=1, DOUBLE=2, TRIPLE=3, QUADRUPLE=4), so every card's state is expressed on the same [0,1] scale regardless of its own legal state space, before comparing across rarity classes. Raw (un-normalized) per-card correlations are Section 3/4/5's own tables above; this section adds the normalized view and the rarity-class-level aggregate.

**Per-card normalized correlation** (pooled across player counts and depths):

| Card | Rarity class | Raw r (n) | Normalized r (n) |
|---|---|---|---|
| Annulment (Antimatter) | TRIPLE | 0.006 (n=14936) | 0.006 (n=14936) |
| Circulate (Elemental) | QUADRUPLE | -0.037 (n=14936) | -0.037 (n=14936) |
| Cleansing (Atmospheric) | TRIPLE | 0.003 (n=14936) | 0.003 (n=14936) |
| Corpuscle Rot | SINGLE | -0.035 (n=12060) | -0.035 (n=12060) |
| Delayed Motion | TRIPLE | -0.035 (n=14936) | -0.035 (n=14936) |
| Divine Assistance | DOUBLE | 0.000 (n=14936) | 0.000 (n=14936) |
| Dwarf Star | SINGLE | -0.047 (n=11954) | -0.047 (n=11954) |
| Elemental Rebirth | TRIPLE | 0.000 (n=14936) | 0.000 (n=14936) |
| Emitting Nebula | TRIPLE | -0.026 (n=14936) | -0.026 (n=14936) |
| Essence Assimilator | DOUBLE | -0.005 (n=14936) | -0.005 (n=14936) |
| Evasive Action | TRIPLE | 0.005 (n=14936) | 0.005 (n=14936) |
| Fluidic Wave | SINGLE | -0.005 (n=11703) | -0.005 (n=11703) |
| Galactic Roundabout | SINGLE | 0.000 (n=14936) | 0.000 (n=14936) |
| Graviton Rift | SINGLE | -0.016 (n=12003) | -0.016 (n=12003) |
| Infernal Abyss | DOUBLE | 0.000 (n=14936) | 0.000 (n=14936) |
| Insidious Flux | DOUBLE | -0.028 (n=14936) | -0.028 (n=14936) |
| Last Gasp | QUADRUPLE | -0.022 (n=14936) | -0.022 (n=14936) |
| Luckier Nebula | DOUBLE | 0.000 (n=14936) | 0.000 (n=14936) |
| Lucky Nebula | TRIPLE | 0.007 (n=14936) | 0.007 (n=14936) |
| Materialize Army | SINGLE | -0.020 (n=14936) | -0.020 (n=14936) |
| Materialize Help | DOUBLE | 0.000 (n=14936) | 0.000 (n=14936) |
| Parallel Phasing | SINGLE | 0.000 (n=14936) | 0.000 (n=14936) |
| Phase Control | QUADRUPLE | 0.008 (n=14936) | 0.008 (n=14936) |
| Phase Loss | DOUBLE | 0.000 (n=14936) | 0.000 (n=14936) |
| Planetary Nebula | DOUBLE | -0.092 (n=14936) | -0.092 (n=14936) |
| Plasma Burst | SINGLE | -0.040 (n=11819) | -0.040 (n=11819) |
| Radiation Burst | SINGLE | -0.012 (n=14936) | -0.012 (n=14936) |
| Sidestep (Extinction Avoidance) | QUADRUPLE | -0.043 (n=14936) | -0.043 (n=14936) |
| Skip, Hop, and Jump (Dimensional) | DOUBLE | -0.030 (n=14936) | -0.030 (n=14936) |
| Tactical Motion | DOUBLE | -0.001 (n=14936) | -0.001 (n=14936) |
| Tactical Step | TRIPLE | -0.016 (n=14936) | -0.016 (n=14936) |
| Verdant Growth | SINGLE | -0.075 (n=11714) | -0.075 (n=11714) |

**Rarity-class-level aggregate** (every card sharing a rarity class pooled into one sample, testing whether rarity class itself - independent of which specific card - relates to turns remaining):

| Rarity class | Cards in class | Pooled normalized r (n) | Per-card normalized r: min to max |
|---|---|---|---|
| SINGLE | 10 | -0.043 (n=130997) | -0.075 to 0.000 |
| DOUBLE | 10 | -0.027 (n=149360) | -0.092 to 0.000 |
| TRIPLE | 8 | -0.009 (n=119488) | -0.035 to 0.007 |
| QUADRUPLE | 4 | -0.030 (n=59744) | -0.043 to 0.008 |

A narrow per-card range within a rarity class (close to the class's own pooled r) would suggest rarity class explains most of what's measured, with card identity adding little; a wide range would suggest the opposite - see Section 9 for which this data actually shows.

## 7. Persistence analysis

"Elevated" is defined as `normalized >= 0.5` (at or above half of a card's own rarity ceiling) - a fixed, documented threshold, not tuned. For every (game, card) pair with at least 2 regeneration events, each event from depth 2 onward is classified relative to that same card's own immediately preceding event in that same game: **persistent-elevated** (elevated now AND elevated at the previous regeneration), **transient-elevated** (elevated now but NOT at the previous regeneration), or **not-elevated** (not elevated now, regardless of before). Depth-1 events have no preceding regeneration to compare against and are excluded from this classification entirely (not folded into any category).

**Lag-1 autocorrelation per card** (correlation between a card's own multiplicity at regeneration d and at regeneration d+1, within the same game - how "sticky" that card's state is from one reshuffle to the next):

| Card | Lag-1 autocorrelation r (n consecutive pairs) |
|---|---|
| Annulment (Antimatter) | 0.005 (n=10777) |
| Circulate (Elemental) | -0.024 (n=10777) |
| Cleansing (Atmospheric) | -0.063 (n=10777) |
| Corpuscle Rot | -0.229 (n=9106) |
| Delayed Motion | -0.028 (n=10777) |
| Divine Assistance | 0.000 (n=10777) |
| Dwarf Star | -0.122 (n=9016) |
| Elemental Rebirth | 0.000 (n=10777) |
| Emitting Nebula | -0.015 (n=10777) |
| Essence Assimilator | -0.000 (n=10777) |
| Evasive Action | -0.000 (n=10777) |
| Fluidic Wave | -0.004 (n=8807) |
| Galactic Roundabout | 0.000 (n=10777) |
| Graviton Rift | -0.004 (n=9072) |
| Infernal Abyss | 0.000 (n=10777) |
| Insidious Flux | -0.027 (n=10777) |
| Last Gasp | 0.016 (n=10777) |
| Luckier Nebula | 0.000 (n=10777) |
| Lucky Nebula | 0.000 (n=10777) |
| Materialize Army | -0.021 (n=10777) |
| Materialize Help | 0.000 (n=10777) |
| Parallel Phasing | 0.000 (n=10777) |
| Phase Control | 0.000 (n=10777) |
| Phase Loss | 0.000 (n=10777) |
| Planetary Nebula | -0.184 (n=10777) |
| Plasma Burst | -0.112 (n=8920) |
| Radiation Burst | -0.017 (n=10777) |
| Sidestep (Extinction Avoidance) | -0.013 (n=10777) |
| Skip, Hop, and Jump (Dimensional) | -0.008 (n=10777) |
| Tactical Motion | 0.003 (n=10777) |
| Tactical Step | -0.005 (n=10777) |
| Verdant Growth | -0.135 (n=8840) |

**Outcome by persistence category** (pooled across every card and player count, depth-2-onward events only):

| Category | n | Mean turns remaining | SD | SEM |
|---|---|---|---|---|
| persistent-elevated | 306803 | 1464.7 | 1445.3 | 2.61 |
| transient-elevated | 11605 | 1577.8 | 1519.4 | 14.10 |
| not-elevated | 15555 | 1650.0 | 1536.0 | 12.32 |

**Persistent vs. transient elevated, direct comparison**: mean turns-remaining difference (persistent minus transient) = -113.1, z = -7.89, **statistically distinguishable** (n=306803 persistent, n=11605 transient).

**Caveat on the category sizes themselves**: persistent-elevated (306803) vastly outnumbers transient-elevated (11605) and not-elevated (15555) combined. This follows mechanically from `regenerate()`'s own refill step (Section 2/[FateHarvestRegenerationRules]'s own class doc): refill fills empty slots by uniform random draw across every eligible type up to its own ceiling, regardless of how much that type was actually played/discarded, so most cards land near their own ceiling (normalized close to 1.0) after most regenerations by construction, not because of any dynamic being measured here. The large-n side of this comparison is correspondingly dominated by that structural tendency rather than a balanced sample of genuinely varied persistent-vs-transient states - the z-score above is still a correct statistical statement about this dataset, but this imbalance is a reason for caution before reading it as a strong causal signal.

## 8. Tail-specific analysis

Tail defined **per player count, from this run's own games** (not the published baseline's figures, to stay self-consistent with this exact dataset) as the top 10% of games by total turns taken (i.e. `totalTurnsTaken >= that player count's own p90`) - documented threshold, not tuned. A stricter alternate definition, "capped" (hit the 8000-turn simulation cap without a winner), is reported alongside for comparison.

| Player count | p90 threshold (turns) | Tail games (>=p90) | Capped games |
|---|---|---|---|
| 2 | 1265 | 101 | 0 |
| 3 | 1674 | 101 | 0 |
| 4 | 2635 | 101 | 0 |
| 5 | 3578 | 101 | 4 |
| 6 | 5373 | 101 | 31 |

For each card, using that card's own **last regeneration event per eligible game** (its terminal state for that game) - mean normalized multiplicity in tail games vs. non-tail games, and the point-biserial correlation between normalized multiplicity and tail membership (0/1):

| Card | Tail mean normalized (n) | Non-tail mean normalized (n) | Diff | z | Distinguishable? | Point-biserial r (n) |
|---|---|---|---|---|---|---|
| Annulment (Antimatter) | 0.995 (505) | 0.996 (3654) | -0.001 | -0.43 | No | -0.007 (n=4159) |
| Circulate (Elemental) | 0.991 (505) | 0.983 (3654) | 0.008 | 3.22 | **Yes** | 0.042 (n=4159) |
| Cleansing (Atmospheric) | 0.938 (505) | 0.933 (3654) | 0.005 | 0.75 | No | 0.011 (n=4159) |
| Corpuscle Rot | 0.607 (359) | 0.593 (2595) | 0.014 | 0.51 | No | 0.009 (n=2954) |
| Delayed Motion | 0.974 (505) | 0.973 (3654) | 0.001 | 0.13 | No | 0.002 (n=4159) |
| Divine Assistance | 1.000 (505) | 1.000 (3654) | 0.000 | n/a (zero variance) | No | 0.000 (n=4159) |
| Dwarf Star | 0.707 (355) | 0.653 (2583) | 0.054 | 2.08 | **Yes** | 0.037 (n=2938) |
| Elemental Rebirth | 1.000 (505) | 1.000 (3654) | 0.000 | n/a (zero variance) | No | 0.000 (n=4159) |
| Emitting Nebula | 0.982 (505) | 0.983 (3654) | -0.001 | -0.32 | No | -0.005 (n=4159) |
| Essence Assimilator | 1.000 (505) | 1.000 (3654) | 0.000 | n/a (zero variance) | No | 0.000 (n=4159) |
| Evasive Action | 1.000 (505) | 1.000 (3654) | 0.000 | n/a (zero variance) | No | 0.000 (n=4159) |
| Fluidic Wave | 0.994 (319) | 0.995 (2577) | -0.001 | -0.18 | No | -0.004 (n=2896) |
| Galactic Roundabout | 1.000 (505) | 1.000 (3654) | 0.000 | n/a (zero variance) | No | 0.000 (n=4159) |
| Graviton Rift | 0.994 (346) | 0.997 (2585) | -0.003 | -0.64 | No | -0.015 (n=2931) |
| Infernal Abyss | 1.000 (505) | 1.000 (3654) | 0.000 | n/a (zero variance) | No | 0.000 (n=4159) |
| Insidious Flux | 0.986 (505) | 0.982 (3654) | 0.005 | 1.16 | No | 0.016 (n=4159) |
| Last Gasp | 0.999 (505) | 0.997 (3654) | 0.002 | 2.79 | **Yes** | 0.028 (n=4159) |
| Luckier Nebula | 1.000 (505) | 1.000 (3654) | 0.000 | n/a (zero variance) | No | 0.000 (n=4159) |
| Lucky Nebula | 1.000 (505) | 1.000 (3654) | 0.000 | 1.00 | No | 0.006 (n=4159) |
| Materialize Army | 0.988 (505) | 0.985 (3654) | 0.003 | 0.55 | No | 0.008 (n=4159) |
| Materialize Help | 1.000 (505) | 1.000 (3654) | 0.000 | n/a (zero variance) | No | 0.000 (n=4159) |
| Parallel Phasing | 1.000 (505) | 1.000 (3654) | 0.000 | n/a (zero variance) | No | 0.000 (n=4159) |
| Phase Control | 1.000 (505) | 1.000 (3654) | 0.000 | 1.00 | No | 0.006 (n=4159) |
| Phase Loss | 1.000 (505) | 1.000 (3654) | 0.000 | n/a (zero variance) | No | 0.000 (n=4159) |
| Planetary Nebula | 0.863 (505) | 0.830 (3654) | 0.033 | 2.67 | **Yes** | 0.039 (n=4159) |
| Plasma Burst | 0.640 (325) | 0.643 (2574) | -0.003 | -0.10 | No | -0.002 (n=2899) |
| Radiation Burst | 0.988 (505) | 0.986 (3654) | 0.002 | 0.45 | No | 0.007 (n=4159) |
| Sidestep (Extinction Avoidance) | 0.986 (505) | 0.983 (3654) | 0.002 | 0.78 | No | 0.011 (n=4159) |
| Skip, Hop, and Jump (Dimensional) | 0.981 (505) | 0.984 (3654) | -0.003 | -0.57 | No | -0.010 (n=4159) |
| Tactical Motion | 0.997 (505) | 0.997 (3654) | 0.000 | 0.24 | No | 0.004 (n=4159) |
| Tactical Step | 0.998 (505) | 0.998 (3654) | 0.000 | 0.02 | No | 0.000 (n=4159) |
| Verdant Growth | 0.665 (316) | 0.676 (2558) | -0.011 | -0.40 | No | -0.008 (n=2874) |

## 9. Interpretation

**Measured association**: Section 3's within-player-count table is this report's primary factual finding - the sign and magnitude of each card's own multiplicity-vs-turns-remaining relationship, per player count, with observation counts attached.

**Plausible interpretation**: of 32 cards, 0 show a sign-consistent (same direction, |r|>=0.05) relationship across at least 3 reliable player-count cells - listed here for reference, not as a weighting recommendation: none. Section 6's rarity-class-level aggregate additionally shows whether rarity class alone (independent of specific card identity) explains a comparable share of the per-card variation - if per-card normalized r values within a class cluster tightly around that class's own pooled r, card identity is adding little beyond "how rare is this card," which itself would argue against card-specific weighting.

**Evidence insufficient to distinguish**: at this 5,000-game scale, the depth-bucket-pooled-across-player-count cells in Section 4 were **not** sparse - every one of the 32 cards reached the 30-observation threshold at every depth bucket including 5+, so depth conditioning itself was adequately powered here. What this report deliberately does NOT compute at all - a per-player-count-AND-per-depth cross for a given card - would be far sparser still (Section 4's own methodology note explains why), so a genuinely underpowered cross-section exists, it is just outside this report's own scope rather than silently asserted as reliable. Section 7's persistent-vs-transient comparison and Section 8's tail comparison each report explicitly when their own sample was too small to support a distinguishability claim, rather than asserting one regardless.

**Causal hypothesis**: none is asserted anywhere in this report. Every relationship above is correlational; the base-rate confound the original pooled analysis had (more regenerations happen in longer games, mechanically) is substantially reduced by conditioning on player count and using turns-remaining-from-this-event rather than total-game-length, but a full causal claim (this card's multiplicity *causes* subsequent stagnation, rather than both being downstream of some other factor - e.g. which player's own play patterns happen to draw and discard which cards) is not established by any observational correlation alone, regardless of how it's conditioned.

**Outcome**: **C** — card identity does not meaningfully discriminate after conditioning. No card shows a sign-consistent, reliable (|r|>=0.05 across at least 3 player-count cells) relationship once player count and per-game eligibility are accounted for. This suggests card identity may be the wrong control variable for anti-stagnation - see Section 6's rarity-class comparison and Section 10 for what else the data points toward instead. Per the task's own instruction: no weights are manufactured from this result.

## 10. Newly discovered architectural or semantic ambiguities

- **"Turns elapsed at regeneration" is turn-granular, not sub-turn-granular.** A regeneration can happen at any point within a turn (a card-driven move chaining into a nested draw, a Held-card play's own Precedence window, etc.), but this instrumentation's turn counter only increments once a whole `driveOneTurn` call returns - so `turnsElapsedAtRegeneration` means "turns fully completed strictly before the turn during which this regeneration happened," never a fractional position within that turn. This is adequate for this analysis (turns remaining is still monotonically related to true time-to-outcome) but would need a finer-grained clock if a future pass wanted sub-turn resolution.
- **Color-eligibility as a per-card confound was not previously identified.** The original pooled Table K5 computed every card's correlation across all 5,000 games regardless of whether that card's own color was even seated that game - for the 6 color-restricted cards, a meaningful fraction of their own "observations" were therefore structural zeros carrying no information about the mechanism, silently diluting those specific correlations toward whatever a mix of real and structural-zero data happens to produce. This analysis excludes ineligible games from a card's own sample entirely - a genuinely new methodological point, not previously documented anywhere in this codebase's Phase 1B/1C history.
- **No existing engine-level instrumentation seam for "turn number at an arbitrary mid-turn event."** This analysis had to thread a closure-captured mutable counter through `buildInstrumentedDeckForColors` specifically because neither `GameState` nor `FateHarvestDeck` exposes anything like "how many turns has this game taken so far" - the turn count only ever lived as a local variable in whatever test harness happens to be driving `TurnDriver.driveOneTurn` in a loop. This was sufficient for this task (deliberately test-local, per the Clearance C2 instrument/test scope - no engine change was made), but any future instrumentation need at this same granularity would hit the identical gap.

## Closing statement

No balance change, canon decision, weight derivation, or configuration change was made based on the above - per this task's explicit scope, this is a measurement and interpretation pass only. Outcome: C.

Total games analyzed: 5000. Total invariant violations: 0.
