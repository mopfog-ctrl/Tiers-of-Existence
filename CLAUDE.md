# Tiers of Existence — Android App

Android adaptation of "Tiers of Existence," a 2013 physical board/card game (rulebook:
`docs/rulebook.txt`, extracted from the original .doc — this is the source of truth for
rules questions). 2-6 players, roll-and-move across four "Tier" boards, with a 70-card
Fate Harvest deck driving most of the strategy. Thematically, per the user, a player's
tokens represent a being's progression from very basic (1st Tier) toward a nearly fully
formed being (4th Tier) — worth keeping in mind for token art/UI, not an engine mechanic.

## Assumptions made so far (confirm/revisit with the user)

- **Platform**: native Android (Kotlin + Jetpack Compose).
- **Multiplayer**: not yet decided — local pass-and-play vs. online. Nothing in the code
  currently assumes either; `state/GameState` is UI- and transport-agnostic.
- Game rules and card text below are transcribed directly from the rulebook. Anything not
  explicit in the text (see "Known gap" below) is marked as such in code comments — don't
  invent behavior for those without checking with the user first.

## Board layouts: all four digitized and confirmed

The rulebook explains what each square *type* does (Nebula, Wormhole of Construction,
Fate Harvest, Marauder Transport, Zone of Protection, etc. — see `SquareType`) but never
gives the actual square-by-square sequence of any of the four physical boards; that only
existed in the printed board artwork.

All four boards are now digitized in `BoardLayouts` (`firstTier()`, `secondTier()`,
`thirdTier()`, `fourthTier()`, all used by `GameState`'s default `current()`), through
several rounds of photos, zoomed crops, and the user directly dictating/correcting square
order — including every Zone of Protection's contents. See the `BoardLayouts` class doc
comment for the structural rules that came out of this process (loop = outer perimeter
only; Zone of Protection is real off-loop squares reached from a numbered entry square on
the main loop, not a dice-driven sub-path; Wormhole of Construction's position on the loop
varies by Tier — inside a Zone on the 1st Tier, directly on the main loop on the 2nd).

Still worth treating this as "digitized and confirmed so far," not permanently settled —
several earlier "confident" reads (based on a flat photo) turned out wrong once the user
checked them against the physical board, so a further correction is plausible any time the
user's description doesn't match what's in the code.

## Architecture

Gradle multi-module project:

- **`engine/`** — pure Kotlin/JVM module, no Android dependency. All game rules and state
  live here: token lifecycle (Ion Battery → Staging Pile → promotion, Hatchery overflow),
  the full Fate Harvest card catalog, turn/phase order, dice, and `TurnEngine` (roll → move →
  resolve the landed square). Fully unit-testable without an Android SDK or emulator —
  `./gradlew :engine:test`. Within `engine/`, the card-playing layer is split from the
  catalog: `cards/` holds the static catalog (`FateHarvestCard`/`FateHarvestCatalog`/
  `FateHarvestDeck`) plus the live-play model (`cards/play/`: `CardTarget`, `CardPlayRequest`,
  `CardPlayResult`, `TargetValidator`, `CardLifecycle`, `TokenLocator`) and the actual per-card
  effect implementations (`cards/resolvers/`, dispatched by name via `CardEffectDispatcher`);
  `rules/precedence/` holds the Precedence interaction-chain state machine
  (`InteractionChain`); `state/` holds `GameState`/the token pools/`TokenId` (see "Card engine"
  below for what each of these actually does and how far along it is).
- **`app/`** — Android/Compose UI module, depends on `engine`. Currently just a placeholder
  screen proving the dependency wires up.

Keep game logic in `engine`, not in Compose UI code — it's the only module we can actually
run/test in most sandboxes (see below).

## Sandbox build limitations

This repo has been developed in a sandbox with **no Android SDK** and a network policy that
blocks `dl.google.com` (Google's Maven repo, needed for the Android Gradle Plugin and
AndroidX/Compose artifacts — `mavenCentral()` works fine). Practically:

- Building or running `:app` requires Android Studio (or any environment with a real
  Android SDK and unrestricted network) — it cannot be verified in this sandbox.
- `./gradlew :engine:test` is how engine logic gets verified here, but a bare invocation of
  that command can still fail for reasons unrelated to engine code, because Gradle evaluates
  every project in `settings.gradle.kts` (including `:app`) during configuration even when
  only `:engine`'s task is requested. Two specific failure modes seen in some sandboxes, both
  about the *sandbox*, not the code:
  - `:app`'s Android Gradle Plugin can't resolve (needs `dl.google.com`) even though nothing
    about `:engine:test` needs it — pass `--configure-on-demand` to avoid eagerly configuring
    `:app` at all.
  - If your environment also lacks a JDK 17 toolchain, `engine/build.gradle.kts` requests
    `jvmToolchain(17)`; the `foojay-resolver-convention` plugin wired up in
    `settings.gradle.kts` to auto-download one also needs `dl.google.com`/Adoptium network
    access some sandboxes block. If only a newer JDK (e.g. 21) is available and there's no way
    to provision 17, a **local, uncommitted** edit bumping `jvmToolchain(17)` to match what's
    actually installed lets `:engine:test` run — revert it before committing; do not check in
    a toolchain change made only to work around a specific sandbox's missing JDK.

## Card/token rule cross-checks already validated

- Fate Harvest deck = 70 cards: 10 Singles×1 + 10 Doubles×2 + 8 Triples×3 + 4 Quadruples×4
  = 10+20+24+16 = 70, matching the Parts List. See `FateHarvestCatalogTest`.
- Staging Pile thresholds (1st: 4, 2nd: 3, 3rd: 2) and max-in-play (2 for Tiers 1-3, 1 for
  Tier 4), with Hatchery overflow — see `TierTokenPoolTest`.
- One Marauder per Tier per player (4 total per player, one per Tier), Marauder Transport
  only moves to an adjacent Tier — see `MarauderPoolTest`.
- 4th Tier's You Win square must be landed on *exactly*; a roll that would move a token
  past it doesn't win — that token just continues around the loop again, same as any other
  square. Enforced in `TurnEngine.moveTierToken` (see `TurnEngineTest`).
- A Marauder Transport square living inside a Zone of Protection (1st Tier's Zone 2, 4th
  Tier's Zone 5) is a confirmed exception to "Marauders cannot enter the Zone of Protection"
  — a Marauder may land there, but still can't affect any other token still in the Zone.
- Every field on all 32 unique Fate Harvest cards (name, rarity/copies, `timing`, `scope`,
  `restrictedTo`, `hasPrecedence`, effect text) cross-checked card-by-card against the
  rulebook's Fate Harvest Card List — zero discrepancies. Two things worth knowing, not
  bugs: Infernal Abyss's rulebook text has an optional flavor cue ("You should cry,
  'Ahhhhhhh...'") not reflected in the catalog's `effect` string, harmless since it's
  flavor, not a mechanical requirement (unlike Last Gasp's mandatory exclamation, which
  *is* encoded); and Annulment's `scope = ANY_TIME` comes from Fate Harvest Card Rule #20
  (all Precedence cards may be played any time), not from Annulment's own card text — see
  the comment on that card in `FateHarvestCatalog.kt`.

- **Confirmed correct, no changes needed:** all 6 Precedence-flagged cards in
  `FateHarvestCatalog` (Graviton Rift, Fluidic Wave, Tactical Motion, Annulment, Tactical
  Step, Last Gasp) were cross-checked against every "has Precedence" mention in the
  rulebook's card list (rulebook.txt:546, 555, 657, 686, 717, 765) — exact match both ways.
  `PlayerState.hasTierTurn(tier)`/`hasMarauderTurn()` also correctly model that a Tier with
  no Tier tokens has no Tier Phase turns, while Marauder Phase eligibility is a fully
  separate, Tier-independent check (a Marauder can have a turn on a Tier with zero Tier
  tokens present).

## Turn-resolution engine: base mechanics

`rules/TurnEngine.kt` does the roll → move → resolve-the-landed-square work, on top of
`GameState`'s turn-queue (`currentTurn`, `endTurn(grantAnotherTurn)`, `skipEmptyPhases()`) and
`Dice.rollForPhase(phase)` (both dice summed for a Tier Phase, purple only for the Marauder
Phase). Implemented: Tier token movement and landing effects for Nebula (staging pile +
promotion), Vortex of Regression, Wormhole of Construction, You Win (exact-landing only,
idempotent — `GameState.declareWinner` no-ops once a winner is set, so a later token's exact
landing in the same or a later resolution can never overwrite the first), Infernal Abyss,
Hyperthrust (pass-through destroy + chained landing resolution), Warp (chained like
Hyperthrust but never destroys anything passed — see "Card engine" below), Zone of Protection
entry, Fate Harvest (draw + hold), and Marauder Construction Facility (flagged, build is a
separate opt-in call); Marauder movement with pass-through destruction (Reprieve-protected),
and the rulebook's "only Transport/Sensor/Abyss affect a Marauder" landing rules.
`GameState.endTurn(grantAnotherTurn = true)` is the mechanism for "Go again" chaining —
deciding *when* to pass that flag is left to whatever drives turns; `DeferredTurnModifier`
(below) handles the two Time Wrinkle variants "Go again" can't.

**Resolved:** Reprieve's protection is unconditional for Tier tokens — "Any normal token on
Reprieve cannot be destroyed," confirmed by the user, applying to Marauder pass-through
*and* Hyperthrust's identically-worded pass-through-destroy alike (no more special-casing
between the two). It does NOT protect Marauders — "Marauders can be [destroyed]" even while
sitting on a Reprieve square. `TurnEngine.destroyTokensPassed` implements this per-token-kind
rather than per-square.

## Card engine: shared infrastructure plus 24 of 32 cards implemented

`docs/card-mechanics-matrix.md` is the implementation spec — an audit of all 32 unique Fate
Harvest cards' actual mechanical requirements (targets, Zone-of-Protection/Reprieve
interaction, Precedence/Annulment behavior, required engine state) cross-checked against
`docs/rulebook.txt` and independently re-verified once. Read it before touching card logic;
it also lists 17 open rules questions the rulebook doesn't resolve (§4), several of which are
why specific cards below aren't implemented yet.

**Runtime card-play model** (`cards/play/`), deliberately separate from the catalog
(`FateHarvestCard` stays a plain data description, never mutated into carrying runtime
state):
- `CardTarget` — what a play points at: `Token` (a persistent `TokenId`, see below),
  `StagingPileToken` (owner+Tier only — Staging Pile contents are genuinely fungible, no
  identity needed), `TierChoice`, `PlayerChoice`.
- `CardPlayRequest`/`TriggeringEvent`/`CardPlayResult` — a live play attempt, why it's
  happening (drawn from a square / played from hand / responding in a Precedence chain), and
  its outcome (`Resolved`/`Rejected` with a reason/`AwaitingDecision`/`EnteredHand`).
- `TargetValidator` — shared, card-agnostic legality: Color restriction (rule 18), the
  per-Phase play limit (rule 4), `restrictedToPhase` (Planetary/Emitting Nebula's own-Phase
  restriction), and Zone-of-Protection legality (the 5 named rule-12 exceptions plus the
  "your own movement card on your own token" carve-out, which callers opt into per-card
  rather than inferring from "is this the player's own token" — Infernal Abyss is the
  counter-example that still blocks the player's own Zone-resident token).
- `CardLifecycle` — the Immediate (draw → validate → resolve atomically → discard) vs. Held
  (draw → hand → validate-on-play → resolve → leave hand, giving the card back on a rejected
  attempt) lifecycle, built on `TargetValidator`.
- `TokenLocator` — resolves a `TokenId` to its live `TokenLocation` (`InPlay`/`InZone`/
  `NoLongerExists`) at the moment a resolver actually needs it; see `TokenId` below for why
  this exists.

**Token identity** (`state/TokenId.kt`): every Tier token and Marauder gets a stable
`TokenId(owner, kind, tier, ordinal)` when it enters play (`TierTokenPool.startToken`/
`MarauderPool.placeOnBirthCanal`, both return it), retained across every move/Zone
entry-exit/Staging transition until it leaves play, then retired for good (a later token
started from the same Ion Battery gets an unrelated id — no identity is modeled across
destruction and rebirth). Position is deliberately not part of identity. This exists because
a Precedence chain can hold several responses targeting the same token; since they resolve in
reverse play order, an earlier-resolving response can move or destroy a token a later
response still needs to find — `CardTarget.Token` only ever carries the id, and every
resolver re-locates it via `TokenLocator` at resolution time rather than trusting a stale
recorded position. If a target no longer exists by then, resolution rejects gracefully
(`TargetValidationError.NoLegalTarget`) rather than crashing — see
`PrecedenceCardEffectIntegrationTest` for the reverse-order-conflict and
destroyed-before-resolution regression cases, and `DestructionCardResolversTest` for the
unit-level one.

**Precedence interaction chain** (`rules/precedence/InteractionChain.kt`) is a genuine state
machine per rules 20-23, not a list plus reverse iteration: `SuspendedAction` (a pending
roll/move/card resolution) opens a chain with an eligible-player list; each player `respond`s
(adding a Precedence card, which re-opens the response round for everyone) or `pass`es; the
window auto-closes once everyone's passed since the last new entry; `resolve()` returns
surviving entries in reverse play order for the caller to apply. Exposes who's currently
eligible to act, what's being responded to, who's passed, and the full entry history
(cancelled entries kept, not deleted). Annulment gets its own structural handling — it
splices out the immediately preceding still-standing entry (or, as the chain's first entry
against a pending card resolution, cancels that card directly) rather than being just another
resolvable entry; double-Annulment leaves the earlier cancellation permanent (a documented
engine choice, matrix §4 Q11, not a rulebook-confirmed one).

**`CardEffectDispatcher`** maps a `CardPlayRequest` to its resolver by card name — the piece
that lets a resolved `InteractionChain`'s entries (or a plain drawn/held play) actually mutate
`GameState`, instead of every caller needing to know which resolver object handles which
card.

**Cards implemented** (24 of 32), via shared resolvers rather than one class per card
(`cards/resolvers/`):
- `MovementCardResolver` (any-token, fixed distance, opponent's Zone-resident token off
  limits): Tactical Motion, Tactical Step, Evasive Action, Skip/Hop/and Jump, Sidestep.
- `ParallelPhasingResolver` (Parallel Phasing only) — the one movement-family card that needs
  two independent targets in one resolution (the player's own token, plus another player's,
  both moved 4 spaces) rather than `MovementCardResolver`'s single-target shape; the opponent
  target gets no rule-12 Zone-of-Protection carve-out, unlike the player's own target. Both
  targets are fully validated before either is moved (same all-or-nothing pattern as
  `GravitonRiftResolver`).
- `MarauderConstructionCardResolver` (places a Marauder, bypassing the per-Tier cap): Dwarf
  Star, Materialize Army, Essence Assimilator, Materialize Help.
- `BirthCanalConstructionCardResolver` (starts a fresh token on one or more Birth Canals):
  Verdant Growth, Elemental Rebirth, Planetary Nebula.
- `StagingPileConstructionCardResolver` (adds directly to a Staging Pile, same promotion
  check a Nebula landing runs): Lucky Nebula, Luckier Nebula, Emitting Nebula.
- `DestructionCardResolver` (destroys one token anywhere, including a named Zone-of-
  Protection exception): Divine Assistance, Insidious Flux.
- `InfernalAbyssResolver`, `CorpuscleRotResolver`, `GravitonRiftResolver` — each layers a
  card-specific rule (self-only-and-no-Zone-carve-out; compound destroy+construct;
  up-to-4-Tiers, all targets validated before any are destroyed) on top of
  `DestructionCardResolver`'s core.
- `PhaseLossResolver`, `PhaseControlResolver` — thin wrappers over `DeferredTurnModifier`
  (below).
- Annulment (Antimatter) has no resolver of its own — it's handled structurally by
  `InteractionChain` itself (see above) and never reaches `CardEffectDispatcher`, since a
  resolved chain's entries already have Annulment spliced out.

That's 23 cards dispatched by name plus Annulment = 24 of 32 actually playable end to end.

**Not yet implemented** (9 of 32), each blocked on a specific open rules question rather than
missing effort — see the cited matrix question before attempting:
- **Plasma Burst** — how "3 neighboring squares" are selected (§4 Q8).
- **Last Gasp** — whether its pass-through destroys the mover's own other tokens too, since
  its wording omits the usual owner-exemption clause (§4 Q14).
- **Fluidic Wave**, **Galactic Roundabout** — cross-Tier/whole-board effects; Galactic
  Roundabout also has an open question about whether its Marauder movement triggers
  pass-through destruction and how simultaneous near-wins resolve (§4 Q5). Of the 6
  Precedence-flagged cards, 4 are implemented (Tactical Motion, Tactical Step, Annulment,
  Graviton Rift) — Fluidic Wave and Last Gasp are the two still not implemented.
- **Cleansing** — needs a generalized pending-decision primitive for "a player other than the
  one who played the card must choose" (sketched as `PendingDecision` but not wired up).
- **Radiation Burst** — whose Staging Piles "all" refers to, and whether emptying triggers
  promotion (§4 Q6).
- **Delayed Motion** — needs a post-roll/pre-move checkpoint in `TurnEngine` that doesn't
  exist yet (no other card modifies a roll rather than a token).
- **Circulate** — needs a "find the next Zone of Protection from here" board query, plus an
  open question about targeting an opponent's token (§4 Q16).

**Warp is implemented**, not deferred — see below; it was the one item in this section that
used to say "ambiguous," and isn't anymore.

**Zone of Protection is real token state**, not just a reported landing event:
`TierTokenPool` tracks Zone-resident tokens (`zoneResidents`/`enterZone`/`leaveZone`/
`destroyInZone`) separately from main-loop positions — entering a Zone removes a token from
`inPlayPositions` entirely, which is what makes ordinary movement/pass-through scans skip
protected tokens automatically. Still counts toward the Tier's max-in-play cap. Moving a
token *out* of a Zone via a movement card (rule 12's "your own token, your own Zone"
carve-out) is still not implemented — the rulebook never states what distance/starting point
that move would use (matrix §4 Q17) — `MovementCardResolver` gives an honest
"not yet implemented" rejection rather than silently no-op'ing.

**Warp uses each square's own printed magnitude**, never a hardcoded "Warp always means +5":
1st Tier Warp squares move 5, the 2nd Tier's moves 7, resolved via `TurnEngine.resolveWarp`
exactly like Hyperthrust's chaining but without pass-through destruction (Warp's rulebook
text has no destroy clause). The 1st Tier's one confirmed compound square — Birth Canal/Start
also printing "Warp 5 spaces" — chains a second Warp move after the Birth Canal's own (no-op)
landing resolves.

**Deferred turn-state modifiers** (`rules/DeferredTurnModifier.kt`) replace directly advancing
the turn iterator several times: `SkipNextTierTurn` (Phase Loss, the "Lose next turn on this
Tier" Time Wrinkle square) and `ExtraTierTurn` (Phase Control's same-Round case, the "Take an
extra turn, First Tier" Time Wrinkle square) are queued on `GameState` and consumed exactly
once when the matching Tier's turn queue is next built — independent triggers still stack,
but a single trigger can never repeat a turn more than once. `GameState.queueExtraTierTurn`
splices directly into the live queue when its target Tier's Phase is already active and the
player's turn hasn't happened yet this Round; Phase Control's other case ("if your turn
already ended this Round, play it immediately") is deliberately not a true interrupt — see
that method's doc and matrix §4 Q15.

**Known critical-failure risks, deliberately not fixed yet** (raised in an earlier review,
explicitly deferred by the user pending a later pass — not silently missed): the 4
Marauder-construction resolvers can crash if a player's Marauder Ion Battery is empty (a
normal state — 4 Marauders total, 1 per Tier × 4 Tiers); `TierTokenPool.destroyFromStagingPile`
crashes rather than rejecting gracefully if the chosen pile is empty. See
`.claude/agents/rules-reference.md`'s "resource-exhaustion" checklist item, added specifically
so a future review catches these before they're forgotten.

**Fixed**: the 1st-Tier Ion Battery auto-replenishment gap flagged above used to be listed as
a critical-failure risk here — it isn't anymore. The rulebook's 1st-Tier-specific rule ("On
the First Tier... When there are less than two 1st Tier tokens in play..., a new... token is
taken from the Ion Battery and placed on Start") is now implemented in
`TierTokenPool.refillInPlayIfRoom`: unlike the Hatchery-refill rule that already existed for
the 2nd/3rd/4th Tier, the 1st Tier has no Hatchery of its own — its overflow pool *is* the Ion
Battery — so once Hatchery is exhausted, refill additionally falls back to pulling straight
from `ionBattery` for `TierLevel.FIRST` specifically, looping until the 2-in-play cap is
filled or the Ion Battery itself runs dry (which it gracefully accepts, settling for fewer
than 2 in play rather than crashing or fabricating tokens). This closes the "permanently
stranded out of 1st Tier turns" failure mode: previously, a player whose sole in-play 1st Tier
token was destroyed would sit at `inPlayCount == 0` forever, since `PlayerState.hasTierTurn`
checks `inPlayCount > 0` and nothing ever refilled it. Deliberately scoped to only trigger from
the four existing slot-freeing mutations (`destroyInPlay`/`destroyById`/`sendToStagingPile`/
`promoteInPlayToken`/`destroyInZone`), not from `startToken()` itself or at `GameState`
construction — the rulebook's own setup instructions explicitly place a single token at game
start ("Take out one 1st Tier token... place it on the Start/Birth Canal square"), and several
existing tests intentionally start a pool with just one 1st Tier token in play for a narrower
scenario; scoping the fix to actual gameplay mutations (not initial setup) avoids
contradicting either. See `TierTokenPoolTest`'s "1st Tier Ion Battery auto-replenishment"
tests for the soft-lock regression coverage and the graceful-exhaustion case.

## `TurnOrder`/`GameState` construction NPE: root-caused and fixed

Historically, `./gradlew :engine:test` intermittently failed (~50% of runs in one sandbox
session) with a `NullPointerException` inside `TurnOrder.turnsFor`'s null-check on its `phase`
parameter, originating from `GameState.<init> -> buildTurnQueue() ->
turnOrder.turnsFor(currentPhase, players)`. An earlier investigation session couldn't
reproduce it at all despite ~50 manual `./gradlew` invocations and concluded (correctly, as
far as it went, but incompletely) that there was no structural bug in `Phase.ROUND_ORDER`'s
initialization.

A later session reproduced it reliably by testing a variable the earlier investigation hadn't
tried: **how the test command selects which classes to run.** Running the full suite
unfiltered never failed (15/15), and filtering to a *single* test class never failed (10/10),
but filtering with **multiple** `--tests <FullyQualifiedClass>` patterns in one
invocation (Gradle selecting a non-contiguous subset of test classes) failed on 7 of 15 runs —
a rate matching the historical ~50% closely enough to be the same bug. Captured stack traces
from failing runs all matched the historical shape exactly:
`NullPointerException: Parameter specified as non-null is null: method
TurnOrder.turnsFor, parameter phase`, with `Phase.ROUND_ORDER[phaseIndex]` (i.e.
`GameState.currentPhase`) evaluating to a literal Java `null` despite `ROUND_ORDER` being a
`List<Phase>` with no way to construct a null element in its source.

**Root cause**: `Phase.ROUND_ORDER` lived in `Phase`'s companion object, eagerly initialized as
`listOf(Marauder, Tier(TierLevel.FOURTH), ...)`. Building that list evaluates `Marauder` (a
`data object`) and constructs `Tier(...)` instances — both nested subclasses of the sealed
class `Phase` itself. But that list-construction code runs as part of the companion object's
own construction, which is itself part of `Phase`'s `<clinit>`. So the first time anything
touches `Phase`, the JVM ends up needing to initialize a *subclass* of `Phase`
(`Phase$Marauder`/`Phase$Tier`) while `Phase`'s own class-initialization is still in progress —
a legal, non-deadlocking reentrant cycle per JVMS §5.5, but one where the subclass's
superclass-initialization check can observe `Phase` as "still initializing" rather than fully
initialized. Under most classloading orders this resolves harmlessly; the specific
non-contiguous multi-class discovery order Gradle uses for a multi-pattern `--tests` filter
apparently perturbs timing enough to expose the narrow window where a sealed-subtype singleton
gets read before it's actually assigned, producing a null list element.

**Fix**: `engine/src/main/kotlin/com/tiersofexistence/engine/rules/Phase.kt` — changed
`ROUND_ORDER` from an eager `=` initializer to `by lazy { ... }`. This defers building the list
until the *first actual read* of `Phase.ROUND_ORDER`, by which point `Phase`'s own `<clinit>`
has always already completed (accessing a lazy property never runs the initializer as a side
effect of the containing class's own class-initialization), so `Marauder`/`Tier(...)` are
always constructed against a fully-initialized `Phase` — no reentrancy, no race. This is a
pure initialization-timing change: `ROUND_ORDER`'s value, order, and every other observable
behavior are unchanged; no turn-order canon was touched.

**Verified**: post-fix, the exact multi-class `--tests` command that failed 7/15 times before
the fix passed 40/40 across two follow-up batches; the full suite (both normal and
`--no-daemon`) passed 15/15 total post-fix runs. `GameStateInitializationStressTest`
(thousands of `GameState` constructions in a tight loop, exercising the exact failing path)
continues to pass and stays in the suite as a permanent regression guard, though note it alone
never reproduced the bug in either investigation session — the multi-class `--tests` filter
was the necessary trigger, not raw iteration count within a single already-loaded JVM.
