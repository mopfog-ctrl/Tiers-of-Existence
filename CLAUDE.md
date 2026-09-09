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
- **First Google Play release is single-player**: one human plus up to 5 selectable AI
  opponents (2-6 total, matching the rulebook's own player count), each AI a separately
  selectable behavior type. Confirmed by the user; this is why `TurnDriver`'s
  `TurnDecisionProvider` is per-player-pluggable rather than one shared policy — see "Turn-
  driving loop" below. No concrete AI behaviors exist yet (deliberately not built ahead of a
  real spec); a human seat is a session/setup concern the engine deliberately doesn't model at
  all (see that section for why).
- **Stats will be tracked eventually** — cards played (including by some future per-card-class
  breakdown), wins/losses/ties, etc. — but nothing about that exists yet, and deliberately so:
  the engine already exposes what a future stats layer would read (`CardPlayResult.Resolved`
  per play, `GameState.winners` at game end), so no engine change was needed preemptively;
  building a card-classification taxonomy now would mean guessing at categories with no spec.
- **The Compose UI (once built) needs to let a player view — and if a card allows it, play
  on — any of the other 3 Tier boards during their own Tier turn**, not just the Tier whose
  Phase is currently active (which is auto-displayed). Noted here since no UI exists yet to
  actually implement this against; nothing in `engine` blocks it already — every `TierBoard`/
  per-player per-Tier pool is freely readable regardless of whose turn it is or which Tier's
  Phase is active.
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
the main loop, and — corrected later, see below — IS itself a dice-driven sub-path once
entered; Wormhole of Construction's position on the loop varies by Tier — inside a Zone on
the 1st Tier, directly on the main loop on the 2nd).

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
- One Marauder per Tier per player, bypassable by Fate Harvest cards (rule #9) — no separate
  global cap beyond that, since Marauders have no Ion Battery/finite spawn reserve at all (see
  "Marauder model" below); Marauder Transport only moves to an adjacent Tier — see
  `MarauderPoolTest`.
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

## Marauder model: no Ion Battery, no finite spawn reserve

**Corrected.** An earlier engine version modeled `MarauderPool` with an `ionBattery: Int`
initialized to 4 (the Parts List's "4x Marauder tokens per color"), decremented on every
spawn and incremented on every destroy — treating physical component count as an engine
resource pool, mirroring Tier tokens' genuine Ion Battery. **This was wrong, confirmed by the
user**, who also flagged the one place the rulebook's own text points the other way
(rulebook.txt:163, "...they must wait until one of their Tier tokens in play has been
destroyed in order to have a token to draw from the Ion Battery... The same applies to
Marauder tokens") — read narrowly this sentence could imply a Marauder reserve too, but the
user's explicit ruling overrides that literal reading: Marauders have their own separate
`battery`/resource pile which is *not* the Ion Battery, and in practice don't need one at
all — the only real limits are the ordinary per-Tier cap and its Fate Harvest bypass; the
practical ceiling on how many Marauders a player can ever have in play is just however many
cards in the deck can spawn one, not any engine-tracked count. Removed:
- `MarauderPool.ionBattery` itself, and every increment/decrement of it (spawn, `destroy`,
  `destroyById`, `destroyAllInPlay`, `destroyAllAt`).
- `TierLevel.MARAUDER_TOKENS_PER_PLAYER` (the constant that used to seed it) — no longer
  referenced anywhere, since nothing tracks a Marauder reserve to seed.
- The `require(ionBattery > 0)` check inside `placeOnBirthCanal` — a legal Marauder spawn
  (ordinary, capped at 1 per Tier; or Fate-Harvest-bypassed, uncapped) can now never fail for
  a resource reason, only for the per-Tier cap when `bypassCap` isn't set.

**Preserved, unchanged:** the ordinary "one Marauder per Tier per player" cap
(`placeOnBirthCanal`'s default `bypassCap = false`) and its Fate Harvest bypass (rule #9,
`bypassCap = true` — Dwarf Star, Materialize Army, Essence Assimilator, Materialize Help all
already used this correctly and needed no changes); every Marauder still gets a stable
`TokenId` exactly as before (`MarauderPool` never generated ids from the removed reserve,
only from `TokenIdGenerator`); `moveToNeighboringTier`/`destroy`/`destroyById`/
`destroyAllInPlay`/`destroyAllAt`/`move`/`moveById` all keep their exact same mutation
behavior on `inPlay`, just without the now-removed reserve bookkeeping alongside it.

**Side effect: a previously-flagged critical-failure risk is now structurally impossible.**
The 4 Marauder-construction resolvers (`MarauderConstructionCardResolver`, used by Dwarf
Star/Materialize Army/Essence Assimilator/Materialize Help) used to be able to crash if a
player's Marauder "Ion Battery" was empty — a normal-looking mid-game state under the old
(wrong) model. With no reserve left to be empty, that crash path no longer exists at all, not
just handled gracefully.

See `MarauderPoolTest` for the regression coverage: ordinary legal spawning, the per-Tier cap
still applying where it should, the Fate Harvest bypass still working, more than 4 logical
Marauders existing at once when legal effects produce that (proving there's no hidden global
cap), destroying a Marauder not replenishing anything, and repeated spawn/destroy cycles
(deliberately far more than 4) never depending on any component inventory.

## Empty-Phase skipping: canonical behavior, plus a stalled-state fail-safe

`GameState.skipEmptyPhases()`'s canonical behavior is unchanged: any number of consecutive
empty Phases (an upper Tier with no player's tokens on it, an empty Marauder Phase) skip
straight through to the next Phase with an eligible turn — "Phases skip when there's nothing
to be done in them" — and this can legitimately mean traversing most or all of
`Phase.ROUND_ORDER` in one call (Round 1 always does: Marauder/4th/3rd/2nd are all empty until
the 1st Tier Phase, the only one anyone has tokens on yet). Nothing about that changed.

**Added: a defensive fail-safe, not a gameplay rule.** The previous unbounded `while` loop
would spin forever on a genuinely impossible/corrupted `GameState` — no Phase has an eligible
turn in any player, *and* no winner is declared. Ordinary canonical play can never legally
reach that state (the 1st Tier's own auto-replenishment — `TierTokenPool.refillInPlayIfRoom`
— keeps that Phase eligible for as long as any player has 1st Tier tokens left anywhere, and
the game ends once someone wins), but a malformed `GameState` (e.g. hand-built with every
pool emptied out) could. `skipEmptyPhases` now counts consecutive `advancePhase()` calls
within one search and throws `GameStalledException` (a dedicated, descriptively-named
`IllegalStateException` subtype in `state/GameState.kt`) once `GameState
.STALLED_STATE_PHASE_THRESHOLD` Phases have been traversed with nothing found — never a draw,
loss, elimination, or any other gameplay outcome, purely an engine-invariant diagnostic.

**Corrected: the threshold is many Phase cycles (500), not one.** The first version of this
fail-safe used exactly one cycle (`Phase.ROUND_ORDER.size`, 5) — a real test caught this being
too tight: skip debt (see "Stacked skip debt" below) can legitimately defer a sparse
single-player game's only eligible turn past one full cycle — even a single queued skip takes
a full cycle of the Round it consumes *plus* a full cycle of the next Round before that
player's Tier turn becomes eligible again (10 Phase-advances, found via `TurnDriverCardIntegrationTest`'s
Fate Harvest integration randomly drawing Phase Loss — see "Fate Harvest integration" below).
None of this is reachable in ordinary 2-6 player play (some other player almost always has
*some* eligible turn within the same Round), but a legitimately sparse `GameState` can hit it
without being corrupted at all — a truly malformed state (every pool emptied out) never finds
anyone eligible no matter how far the search goes, while a legitimate skip gap always resolves
within a bounded (if occasionally large) number of cycles, so a generous threshold (500
Phases, ~100 Rounds) tells the two apart without ever mistaking the latter for the former.

## Stacked skip debt: independent `SkipNextTierTurn` triggers genuinely stack — confirmed canon

**This is now confirmed canonical behavior, ruled by the user, correcting two earlier
mistakes in this same area** (first an incorrectly-implemented collapse, then briefly
mis-documented as intentional): independent skip triggers against the same (player, Tier) —
two separate Phase Loss draws, or Phase Loss plus the 1st Tier's "Lose next turn on this Tier"
Time Wrinkle square, both landing before the first is consumed — **stack**. Each one removes
exactly one distinct future eligible turn on that Tier, not a shared/collapsed one.

**Representation**: `GameState.pendingSkips` — a `MutableMap<Pair<PlayerColor, TierLevel>,
Int>` debt counter, replacing the earlier list-based `DeferredTurnModifier.SkipNextTierTurn`
entirely (that sealed class now only has `ExtraTierTurn`, which keeps its own unchanged,
already-correct stacking behavior — see below). `GameState.queueSkipNextTierTurn(player,
tier)` increments the counter by 1 per call; `GameState.buildTurnQueue()` consumes debt one
unit at a time, and only for a player who would otherwise actually be eligible for a turn on
that Tier this occurrence (a player not in that Phase's base turn list — because they simply
have no token there yet — has their debt left completely untouched, however large; per-Tier,
per-player, so a skip queued for one Tier never touches another Tier or another player's own
debt).

**Why a counter, not a list.** The original (wrong) implementation stored one
`DeferredTurnModifier.SkipNextTierTurn` list entry per trigger and, at the next matching
occurrence, collected *every* entry matching that (tier, player) and consumed them all
together in one `buildTurnQueue()` call — collapsing N stacked triggers into a single skipped
occurrence instead of N separate ones. An intermediate pass through this same area
(mis)documented that collapse as intentional/confirmed before checking with the user. Neither
was correct. The counter representation makes the collapse bug structurally impossible to
reintroduce, rather than something a future "simplification" back to a list could silently
bring back — see `DeferredTurnModifier`'s own class doc and `GameState.pendingSkips`'s.

**`ExtraTierTurn` already stacked correctly and needed no changes** — it was never affected by
the collapse bug (each queued instance already grants its own separate extra turn; see
`GameState.queueExtraTierTurn`'s existing behavior, unchanged). The one interaction point
between the two: if a player's *normal* turn on a Tier is skipped this occurrence (debt still
pending), a queued `ExtraTierTurn` for that same (player, Tier) has nothing to attach after —
it is *not* granted this occurrence either, and stays queued for whenever the player's normal
turn is next actually granted there (never lost).

**Audited interactions, all covered by `GameStateTest`'s "Stacked SkipNextTierTurn debt"
section**: one skip + the next normal turn (pre-existing single-skip test, unchanged
semantics); two stacked skips consuming two separate future occurrences (new — the
distinguishing case vs. the old collapse bug); a skip on one Tier never affecting another
Tier for the same player; independent players' skip debt never crossing over; debt queued
for a Tier the player has no token on yet staying pending — untouched, not dropped or
consumed — until they actually gain eligibility there; a skip plus a queued `ExtraTierTurn`
for the same (player, Tier) losing neither; and stacked skip debt being fully independent of
1st-Tier auto-replenishment (a replenished token pool has no bearing on debt, which is tracked
per (player, Tier) regardless of `inPlayCount`). A large-N case (50 stacked skips, single
sparse player) confirms the 500-Phase `STALLED_STATE_PHASE_THRESHOLD` still comfortably holds
after this correction — 51 occurrences (255 Phases) needed, well under the threshold — per the
user's explicit request to re-verify rather than assume; the threshold itself was left
unchanged (not tuned tighter), matching the user's own instruction not to over-optimize a
safeguard normal legal gameplay should never reach.

**Whether stacking (vs. the old collapse) is what "You lose the next turn on this Tier"
canonically means for two independently-drawn Phase Loss cards was genuinely unresolved by
the rulebook's own text** (`docs/card-mechanics-matrix.md` §3.5's "no turn may repeat more
than once from a single triggering effect... not the total number of independent triggers in
a Round" is about `ExtraTierTurn` not self-chaining from *one* trigger, and doesn't by itself
settle how *multiple* independent `SkipNextTierTurn` triggers should interact) — this section
existed specifically to flag that ambiguity for a ruling rather than guess. **The user has now
ruled: independent skip-next-turn effects stack, each player tracking pending skip count
separately per Tier** — implemented exactly as described above, matching that ruling.

See `GameStateTest`'s "skipEmptyPhases: canonical skipping, and the fail-safe stalled-state
guard" section: one empty upper-Tier Phase, several consecutive empty Tier Phases, and
reaching the correct next Phase with the correct player queued all still skip normally (no
exception); a single sparse player's queued Phase-Loss-style skip resumes normally after the
10-Phase gap described above, not as a stalled state; a deliberately-constructed all-empty
`GameState` (raw `PlayerState`s, no tokens started anywhere, bypassing `GameState.newGame`)
still throws `GameStalledException` instead of hanging; and a dedicated test demonstrates
1st-Tier auto-replenishment is exactly the mechanism that keeps ordinary play from ever
reaching the stalled state in the first place.

## Turn-resolution engine: base mechanics

`rules/TurnEngine.kt` does the roll → move → resolve-the-landed-square work, on top of
`GameState`'s turn-queue (`currentTurn`, `endTurn(grantAnotherTurn)`, `skipEmptyPhases()`) and
`Dice.rollForPhase(phase)` (both dice summed for a Tier Phase, purple only for the Marauder
Phase). Implemented: Tier token movement and landing effects for Nebula (staging pile +
promotion), Vortex of Regression, Wormhole of Construction, You Win (exact-landing only,
idempotent — `GameState.declareWinner` no-ops once a winner is set, so a later token's exact
landing in the same or a later resolution can never overwrite the first; the one confirmed
exception is a genuine tie within a single Galactic Roundabout resolution, see that card's
entry below and `GameState.declareSimultaneousWinners`), Infernal Abyss,
Hyperthrust (pass-through destroy + chained landing resolution), Warp (chained like
Hyperthrust but never destroys anything passed — see "Card engine" below), Zone of Protection
entry, Fate Harvest (draw + hold), and Marauder Construction Facility (flagged, build is a
separate opt-in call); Marauder movement with pass-through destruction (Reprieve-protected),
and the rulebook's "only Transport/Sensor/Abyss affect a Marauder" landing rules.
`GameState.endTurn(grantAnotherTurn = true)` is the mechanism for "Go again" chaining —
deciding *when* to pass that flag is left to whatever drives turns (`TurnDriver`, below);
`DeferredTurnModifier` (below) handles the two Time Wrinkle variants "Go again" can't, and all
3 confirmed Time Wrinkle variants are now implemented — see `SquareEffect.GoAgain`/
`LoseNextTierTurn`/`GrantedExtraTierTurn` and `TurnEngine.resolveTimeWrinkle`.

**Resolved:** Reprieve's protection is unconditional for Tier tokens — "Any normal token on
Reprieve cannot be destroyed," confirmed by the user, applying to Marauder pass-through
*and* Hyperthrust's identically-worded pass-through-destroy alike (no more special-casing
between the two). It does NOT protect Marauders — "Marauders can be [destroyed]" even while
sitting on a Reprieve square. `TurnEngine.destroyTokensPassed` implements this per-token-kind
rather than per-square.

## Turn-driving loop: mechanical roll → move → offer, now with Fate Harvest card play integrated

`rules/TurnDriver.kt` is the actual orchestrator sitting on top of `TurnEngine`/`GameState` —
until it existed, every doc reference to "whatever's driving turns" described a responsibility
with no concrete owner. `TurnDriver.driveOneTurn(state)` drives exactly one player's turn to
completion: reads `state.currentTurn`/`currentPhase`, rolls (via an injectable `rollForPhase:
(Phase) -> Int`, defaulting to `Dice.rollForPhase` — genuinely random in real play, a fixed
lambda in tests, since forcing an exact sequence out of `kotlin.random.Random`'s internals is
awkward), asks that player's own `TurnDecisionProvider` which of their eligible tokens/
Marauders to move, moves it via the identity-based movers (`TurnEngine.moveTierTokenById`/
`moveMarauderById`/`moveZoneToken` — never the position-based `moveTierToken`/`moveMarauder`,
since more than one of a player's own tokens can legally stack on the same square and a
position-based move risks silently moving the wrong one), resolves whatever that landing
produces, then ends the turn — chaining another turn on a "Go again" square, otherwise
advancing via `GameState.endTurn`.

### Fate Harvest integration: Immediate, Held, Delayed Motion, and Precedence, all through the real turn path

Previously this section described card play as deliberately deferred (`GameState
.queuePendingImmediateCard`/`pendingImmediateCards` recorded a drawn Immediate card without
resolving it). That gap is now closed — `TurnDriver` no longer uses that queue at all; it
resolves every card play through `CardEffectDispatcher`, the same dispatcher every
resolver-level test already exercised, now reached from the actual turn loop instead of only
from direct test calls.

- **Immediate cards** (rule 14, mandatory the instant drawn): when a landing (main-loop or, now,
  a previously-silently-dropped Zone-internal Fate Harvest draw — see below) produces
  `SquareEffect.DrewCard` with a `CardTiming.IMMEDIATE` card, `TurnDriver.resolveImmediateCard`
  asks the new `TurnDecisionProvider.chooseImmediateCardTargets` for its target(s) and resolves
  it right then, through the same Precedence window every other play gets. If the play comes
  back `Rejected` (no legal target exists — the rulebook and `docs/card-mechanics-matrix.md` are
  both silent on what should happen to a mandatory card with nowhere to land), the card is
  explicitly discarded rather than inventing a substitute effect or losing it — it was already
  drawn from the deck and Immediate cards never enter a hand, so discarding is the only place
  left for it that doesn't fabricate new behavior. A **pre-existing gap fixed as part of this
  work**: a Zone-internal Fate Harvest draw's `SquareEffect.DrewCard` used to be silently
  discarded by `TurnDriver.moveAndResolve` (only the `ExitedZone` half of `ZoneMoveResult` was
  ever inspected) — `resolveZoneInternalEffect` now handles it the same way a main-loop draw is.
- **Held cards**: `TurnDecisionProvider.chooseHeldCardBeforeRoll` offers one voluntary
  `CardScope.YOUR_TURN` play before rolling; `chooseCardAfterRollBeforeMove` offers one more
  after rolling but before the roll moves a token (Delayed Motion's own window — see below). Both
  go through `TurnDriver.offerHeldCardPlay`, which removes the chosen card from hand, resolves it
  through the Precedence-aware path, and returns it to hand if the play turns out illegal —
  matching `CardLifecycle.playFromHand`'s own already-established "a rejected play never
  consumes the card" contract (note `playFromHand` itself is still only used by its own isolated
  bookkeeping test — it doesn't validate card-specific targets, so `TurnDriver` doesn't call it;
  see that class's own doc). No new arbitrary windows beyond these two — every other Held-card
  play opportunity a full game would need (e.g. from a UI's own "play a card" button at another
  point in a turn) is left for a later pass, not invented here.
- **Delayed Motion**: unchanged mechanically from its pre-integration design (`GameState
  .beginPendingRoll`/`pendingRoll`/`clearPendingRoll`, `DelayedMotionResolver`) — what's new is
  that `chooseCardAfterRollBeforeMove` is now the actual legal opportunity to play it, called
  from `driveOneTurn` right after `beginPendingRoll` and before the token-choice/move. Not
  hardcoded to Delayed Motion specifically — any card whose own printed timing genuinely fits
  this exact window could be offered here too — but Delayed Motion is the only one in the deck
  that does.
- **Precedence** (rules 20-23): `TurnDriver.resolvePrecedenceWindow` opens a real
  `InteractionChain` around every point this driver suspends something a Precedence card could
  respond to — a pending card resolution (any Immediate/Held play, via `playWithPrecedenceWindow`)
  or a pending token move (right after a token is chosen, before it actually moves — the "rule 23
  worked example" checkpoint) — offers every seated player (`state.turnOrder.order`) one or more
  response rounds via `offerResponseRounds` (re-offering the round whenever a new entry reopens
  it, per `InteractionChain`'s own rules, until it auto-closes), then dispatches whatever
  survives in reverse play order (rule 22) before the suspended action itself proceeds. See
  `TurnDriverCardIntegrationTest`'s "rule 23 worked example through the real turn-driving path"
  test — the same scenario `PrecedenceCardEffectIntegrationTest` already proved at the
  resolver/chain level, now proven through `driveOneTurn` end to end.

**New hand/discard bookkeeping `TurnDriver` needed, since `InteractionChain`/
`CardEffectDispatcher` themselves never touch a hand or the deck's discard pile.** Two of these
were flagged as provisional (implementation convenience, not yet confirmed canon) when this
integration first landed — **both are now confirmed canon by the user**: "yes, both cards go to
discard. A card that has been played has been expended; Annulment nullifies what it does, not
the historical fact that the player played it."
- A card played into a Precedence chain (`offerResponseRounds`) is removed from the responder's
  hand the moment they choose to respond, and is **never returned to hand regardless of how it
  resolves** — a `Resolved` entry already discards itself via `CardLifecycle.attemptPlay`;
  anything else is explicitly discarded by `resolvePrecedenceWindow` so the physical card doesn't
  vanish from the game's card accounting. Once revealed as a response, it's spent — the same way
  a real card game doesn't let you take back a response whose target another response sniped
  first. Depended on by `TurnDriverCardIntegrationTest`'s "a Precedence response that rejects at
  resolution time (stale target) is discarded, not returned to the responder's hand" (new, added
  alongside this confirmation) and, less directly, its "rule 23 worked example" test (which
  wouldn't distinguish "discarded" from "returned but the card just wasn't checked," since that
  response happens to resolve `Resolved` either way).
- If an Annulment cancels the top-level suspended play (`InteractionChain
  .isSuspendedActionCancelled`, only meaningful for `PendingCardResolution`), that card is also
  explicitly discarded, never returned to hand. Depended on by `TurnDriverCardIntegrationTest`'s
  "Annulment cancelling a top-level Held-card play discards that card rather than returning it to
  hand" (new).
- **Bug found and fixed while adding that last test**: Annulment's OWN card, once played into a
  chain, was never being discarded at all — `InteractionChain.resolutionOrder()` (and therefore
  `CardEffectDispatcher.dispatchAll`) excludes every Annulment entry unconditionally (it has no
  effect of its own to dispatch), so `resolvePrecedenceWindow`'s original discard loop, which
  only walked `order` (the dispatched entries), never saw it — the card vanished from hand
  without ever reaching the discard pile. Same root cause applied to a *cancelled* chain entry
  (also excluded from `order`). Fixed by discarding every entry in
  `InteractionChain.entriesSnapshot()` that isn't among the ones that actually dispatched
  `Resolved`, rather than only walking `order` — now a cancelled entry's card and an Annulment's
  own card both reach the discard pile, matching the same confirmed canon. Covered by the new
  "Annulment cancelling..." test above (asserts GREEN's hand — the Annulment player — is empty)
  and, for a cancelled non-Annulment entry, implicitly by the reverse-order tests in
  `PrecedenceCardEffectIntegrationTest` (which don't check hands, only board state, since those
  are constructed from raw `CardPlayRequest`s rather than real hands).
- `FateHarvestDeck.forTesting(cards)` is a new, minimal test-only factory (a deck whose draw
  pile is exactly the given cards, in order) — added because these integration tests need a
  deterministic next draw, unlike the default shuffled 70-card deck every earlier test that drew
  cards was content to leave random.

**Fixed: Cleansing's second half is now fully wired into `TurnDriver`.** Previously flagged as
a known gap — no orchestration existed for the targeted opponent's own held-card discard
choice. Ruled by the user: the targeted opponent decides, never the source player, and once
Cleansing has legally reached the discard decision (an empty-handed opponent is already an
illegal target, per the existing ruling), choosing one card is mandatory. Added
`TurnDecisionProvider.chooseCleansingDiscard(state, decidingPlayer, sourcePlayer,
eligibleCards): FateHarvestCard` (defaults to `eligibleCards.first()`, matching
`FirstCandidateDecisionProvider`'s "first candidate" convention, so existing implementations
keep compiling); `TurnDriver.resolveAwaitingDecision` calls it — via `decisionsFor(decidingPlayer)`,
never the source player's provider — whenever a play resolves to `CardPlayResult
.AwaitingDecision(_, PendingDecision.OpponentDiscardChoice(decidingPlayer))` (routed through
`playWithPrecedenceWindow`, so it's reached from both Held-card-play checkpoints), validates
the returned card is one of `eligibleCards`, then calls `CleansingResolver.completeDiscard`.
The eligible-cards list is built from `decidingPlayer`'s own hand and handed only to
`decidingPlayer`'s provider — the source player's provider is never given the opponent's hand
contents to choose from. `PendingDecision.PrecedenceWindowOpen` is handled as a no-op in the
same switch, since no resolver actually produces it (`TurnDriver`'s own `resolvePrecedenceWindow`
handles every Precedence window directly instead). See `TurnDriverCardIntegrationTest`'s two
new Cleansing tests: the targeted opponent's provider makes the choice (and the source
player's provider throws if ever asked, proving it's never consulted), the selected card is
discarded and the other remains; and a second test confirming an empty-handed opponent stays
an illegal target that never discards Cleansing itself or consumes the Phase's card-play
allowance.

**`TurnDecisionProvider`** is the pluggable seam (the user's explicit choice over a
default-policy-only loop) for every real choice a player makes in this loop: which eligible
token/Marauder to move, whether to take each of the three optional square offers
(`MayEnterZone`/`MayBuildMarauder`/`MayTransport`), and — since the Fate Harvest integration
above — 4 more: `chooseHeldCardBeforeRoll`/`chooseCardAfterRollBeforeMove` (a `CardChoice?` or
null to decline), `chooseImmediateCardTargets` (a mandatory play's target list), and
`choosePrecedenceResponse` (respond to an open chain, or null to pass). All 4 default to
declining/choosing nothing directly on the interface, so an implementation written before this
integration existed (or one that simply doesn't want to play cards at all) keeps compiling and
behaving exactly as before — a provider must opt IN to card play. A UI or an AI implements this
interface directly; `FirstCandidateDecisionProvider` (always the first candidate, always
decline every offer, never plays or responds with a card) is the deterministic, dependency-free
default for tests/simulations that only care about the mechanical loop running correctly, not
about realistic play.

**`TurnDriver` resolves a decision provider per player, not one shared instance for the whole
driver** — `decisionsFor: (PlayerColor) -> TurnDecisionProvider`, resolved fresh (and cached
for the rest of that one turn) inside `driveOneTurn` each time. This is specifically for the
stated first-release shape: one human player plus up to 5 selectable AI opponents, each a
different AI behavior type — a single shared policy can't represent that. Two secondary
constructors cover the common cases without callers needing to build the lambda themselves: a
single `TurnDecisionProvider` for every player (the original, pre-multi-AI shape — every
existing test still uses this one, unchanged), and a `Map<PlayerColor, TurnDecisionProvider>`
keyed by seat (throws if asked to drive a player with no entry). A human seat is deliberately
NOT modeled anywhere in `TurnDriver`/`GameState` — who's human vs. which AI behavior a seat
uses is session/setup configuration, not a game rule, so it doesn't belong on the
UI-and-transport-agnostic `GameState`/`PlayerState`; whatever orchestrates a mixed human/AI
game is expected to simply never call `driveOneTurn` for a human player's turn (driving that
seat some other way, e.g. direct UI input into `TurnEngine`), rather than `TurnDriver` needing
to know a seat is human at all.

**New `TurnEngine` capability this needed**: all 3 confirmed Time Wrinkle variants are now
real, modeled effects rather than free-text `Square.note` a caller would have to parse itself
(the two mandatory ones — "Lose next turn on this Tier," "Take an extra turn, First Tier" —
are auto-applied inside `TurnEngine.resolveTimeWrinkle` immediately on landing, same as every
other non-optional square in this file; "Go again" stays purely reported via
`SquareEffect.GoAgain`, since ending/chaining a turn is `GameState.endTurn`'s job). An
unrecognized or absent Time Wrinkle note (the generic `placeholder()` test board's
un-annotated square) still resolves to `SquareEffect.None`, same as any other not-yet-modeled
square — no real board currently has a Time Wrinkle square whose text isn't one of the 3
confirmed variants, so this fallback isn't observed in practice yet, only guarded against.

## Card engine: shared infrastructure plus all 32 cards implemented

`docs/card-mechanics-matrix.md` is the implementation spec — an audit of all 32 unique Fate
Harvest cards' actual mechanical requirements (targets, Zone-of-Protection/Reprieve
interaction, Precedence/Annulment behavior, required engine state) cross-checked against
`docs/rulebook.txt` and independently re-verified once. Read it before touching card logic;
it also lists 17 rules questions the rulebook itself doesn't resolve (§4) — several have since
been resolved by direct user rulings (struck through in place, not deleted, so the original
question stays visible) and are why specific cards below are now implemented; the remaining
open ones are why specific cards below still aren't.

**Runtime card-play model** (`cards/play/`), deliberately separate from the catalog
(`FateHarvestCard` stays a plain data description, never mutated into carrying runtime
state):
- `CardTarget` — what a play points at: `Token` (a persistent `TokenId`, see below),
  `StagingPileToken` (owner+Tier only — Staging Pile contents are genuinely fungible, no
  identity needed), `TierChoice`, `PlayerChoice`, `BoardPosition` (a specific square, not a
  token — Plasma Burst's "3 neighboring squares," identified by the first of the 3).
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

**Cards implemented** (32 of 32 — all of them), via shared resolvers rather than one class per card
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
- `FluidicWaveResolver` (Fluidic Wave only) — an unconditional wipe of the entire 1st Tier:
  every player's in-play tokens and Staging Pile contents, Tier tokens and Marauders alike (no
  target to choose), except Tier tokens currently in a Zone of Protection. Confirmed with the
  user: goes no further than the card's own printed wording — Ion Battery reserves are
  untouched, only in-play/Staging Pile tokens are swept (see `TierTokenPool
  .destroyAllInPlayAndStagingPile`/`MarauderPool.destroyAllInPlay`, the new bulk-clear
  operations this needed). The 1st Tier's own auto-replenishment rule still applies afterward,
  same as any other slot-freeing mutation — the wipe doesn't leave a player's 1st Tier
  permanently empty if their Ion Battery has tokens left.
- `PlasmaBurstResolver` (Plasma Burst only) — removes every token (any owner, any type) from 3
  consecutive main-loop squares on a chosen Tier. Confirmed with the user: "3 neighboring
  squares" means 3 consecutive positions, and Plasma Burst does reach into a Zone of
  Protection (it's one of the 5 named rule-12 exceptions, per both the card's own text and the
  rulebook's general list) — when one of the 3 chosen squares is a Zone's own entry square,
  every token currently resident in that Zone is destroyed too, on top of the plain per-square
  sweep. New bulk-clear operations this needed: `TierTokenPool.destroyAllAt`/
  `destroyAllInZone`, `MarauderPool.destroyAllAt` (stacking is legal, so more than one token
  can occupy a single square or Zone).
- `RadiationBurstResolver` (Radiation Burst only) — empties every player's Staging Pile
  across every Tier (no target to choose). Confirmed with the user: "all" means every
  player's, every Tier, not just the caster's own or one Tier; emptying never triggers that
  Tier's normal promotion, even if a pile happened to be at or above threshold. New pool
  primitive: `TierTokenPool.emptyStagingPile` (bulk-return to the Ion Battery, no promotion
  check — distinct from `destroyFromStagingPile`'s single-token removal).
- `CirculateResolver` (Circulate only) — teleports a Tier token directly into the next Zone
  of Protection walking clockwise from its current position (`TierBoard.nextZoneEntry`, a
  new board query, wrapping the loop if needed) — no intermediate landing effects, no
  pass-through. Confirmed with the user, resolving §4 Q16: any player's Tier token currently
  in play is a legal target, not just the caster's own, but never one already inside a Zone
  of Protection — Circulate isn't a named rule-12 exception, so that's simply not a legal
  target at all, with no carve-out even for the player's own token.
- `LastGaspResolver` (Last Gasp only) — moves the player's own token (any type) 8 spaces,
  destroying everything passed *and* the moved token itself on arrival — the single most
  destructive card in the deck. Confirmed with the user: unlike every other pass-through
  effect (Marauder movement, Hyperthrust), Last Gasp's own other tokens caught in the path
  are destroyed too, no owner exemption. `TurnEngine.moveTierToken`/`moveMarauder` gained
  optional `destroysPassedTokens`/`exemptMoverOwnTokens` parameters for this (defaulting to
  preserve every existing caller's behavior exactly — Last Gasp is the only caller that opts
  out of the exemption). Zone-of-Protection residents stay immune either way (invisible to
  the position-based scan, as always); Reprieve protects a passed Tier token the same as it
  already does for Marauder/Hyperthrust pass-through, but not a passed Marauder, and not the
  mover from its own self-destruct (that's not a pass-through). If the mover's own landing
  square already destroys it (Infernal Abyss) or retires its identity into a fungible pool
  (staged on a Nebula), the explicit self-destruct step is a no-op rather than a crash or a
  double-destroy — `TokenId` identity means it's always found wherever it actually ended up,
  or correctly recognized as already gone.
- `GalacticRoundaboutResolver` (Galactic Roundabout only) — unconditional whole-board sweep,
  no target to choose: every Tier token (including Zone residents, a named rule-12 exception)
  and every Marauder, every player, every Tier, moves 2 spaces. Two rulings confirmed by the
  user, resolving matrix §4 Q5: (a) this uniform "shift everyone" does NOT trigger the normal
  Marauder pass-through-destroy rule — every Marauder here moves via the new
  `TurnEngine.moveMarauderById(..., destroysPassedTokens = false)`, the one caller that opts
  out (`moveMarauder`'s own `destroysPassedTokens` parameter defaults to `true` for every
  other, pre-existing caller, unchanged); (b) two or more players' tokens landing exactly on
  their own 4th Tier You Win square within this one resolution is a genuine tie/shared
  win, not "whichever token got processed first" — every such color is collected during the
  sweep and declared together via the new `GameState.declareSimultaneousWinners`, which can
  override the single winner an in-sweep `GameState.declareWinner` call already locked in
  (only when the game had no winner before the sweep began — see `GameState.winners`, the new
  tie-aware superset of the pre-existing single-winner `GameState.winner`). Needed new
  identity-based movement primitives to snapshot every token safely before any of them move
  (so a token created as a side effect mid-sweep, e.g. a Nebula promotion, never also gets
  swept up, and a token can't get double-moved or skipped just because an earlier move in the
  same sweep shifted who's standing where): `TierTokenPool.moveById`/`inPlayIds`/
  `zoneResidentIds`, `MarauderPool.moveById`/`inPlayIds`, and `TurnEngine.moveTierTokenById`/
  `moveMarauderById` (thin identity-based siblings of `moveTierToken`/`moveMarauder`, resolving
  each token's position fresh via `positionOf` rather than trusting a caller-supplied one).
  `TurnEngine.moveZoneToken` itself was refactored to take the specific `TokenId` directly
  (instead of re-deriving "the" resident of a Zone by number, ambiguous once more than one
  token can share a Zone) — its 3 pre-existing callers (`MovementCardResolver`/
  `ParallelPhasingResolver`/`LastGaspResolver`) updated accordingly, no behavior change for
  any of them since each already had the specific id in hand.
- `CleansingResolver` (Cleansing only) — the one card whose resolution can't finish
  synchronously: the source player chooses an opponent (`CardTarget.PlayerChoice`), but
  *which* of that opponent's own held cards to discard is the opponent's own choice, not
  something the resolver or the source player can pick for them. Once Cleansing itself is
  legally played (color/Phase/per-Phase-limit checks, discarding Cleansing itself), `resolve`
  returns `CardPlayResult.AwaitingDecision(request, PendingDecision.OpponentDiscardChoice
  (opponent))` rather than `Resolved` — mirroring the existing "offer, don't auto-apply"
  pattern (`SquareEffect.MayEnterZone`/`MayBuildMarauder`/`MayTransport`) instead of building a
  second, parallel pending-decision engine alongside `InteractionChain`: whatever's driving the
  game is expected to prompt the named opponent and then call `CleansingResolver
  .completeDiscard(state, decidingPlayer, cardToDiscard)` once they've answered, same as it's
  already expected to call `TurnEngine.enterZoneOfProtection` once a player answers *that*
  offer. Confirmed by the user, resolving matrix §4 Q12: a player with an empty hand is not a
  legal target at all — "Cleansing cannot be played against a player who has no cards in their
  hand" — so `resolve` rejects that target before `CardLifecycle.attemptPlay` ever runs;
  Cleansing itself is never discarded and never counts against the Phase's card-play limit for
  an illegal target, matching every other card's "an illegal target doesn't consume the play."
- `DelayedMotionResolver` (Delayed Motion only) — the one card that modifies a roll rather than
  a token, needing a genuine engine checkpoint between "roll happened" and "token moved" that
  nothing else in `TurnEngine` provides (every other movement-affecting card acts on an
  already-placed token instead). New: `GameState.pendingRoll`/`beginPendingRoll`/
  `clearPendingRoll`, backed by a small `PendingRoll(player, total)` class in `rules/` —
  whoever's driving a Tier/Marauder-Phase turn calls `beginPendingRoll(player, rolledValue)`
  right after `Dice.rollForPhase`, gives the player a chance to play this card, then reads
  `pendingRoll.total` back out to actually call `TurnEngine.moveTierToken`/`moveMarauder` (both
  fully unchanged — neither reads `GameState.pendingRoll` itself, so movement's
  already-established `spaces: Int` API needed zero changes). Confirmed by the user, resolving
  matrix §4 Q13: self-only — despite having no Precedence flag and no restated Your-Turn scope,
  this can only be played on the source player's own pending roll, enforced directly against
  `PendingRoll.player` rather than trusting the caller to only invoke it during the right
  player's turn.
- Annulment (Antimatter) has no resolver of its own — it's handled structurally by
  `InteractionChain` itself (see above) and never reaches `CardEffectDispatcher`, since a
  resolved chain's entries already have Annulment spliced out.

That's all 31 named cards dispatched by name plus Annulment = all 32 Fate Harvest cards
actually playable end to end.

**Warp is implemented**, not deferred — see below; it was the one item in this section that
used to say "ambiguous," and isn't anymore.

**Zone of Protection is real token state**, not just a reported landing event:
`TierTokenPool` tracks Zone-resident tokens (`zoneResidents`/`enterZone`/`leaveZone`/
`destroyInZone`) separately from main-loop positions — entering a Zone removes a token from
`inPlayPositions` entirely, which is what makes ordinary movement/pass-through scans skip
protected tokens automatically. Still counts toward the Tier's max-in-play cap.

**A Zone of Protection is a real dice-driven sub-path, not just an undifferentiated
"protected" flag** — confirmed by the user, reversing this section's own original assumption
(matrix §4 Q17, resolved) and closing what had been the last "moving a token *out* of a Zone
isn't implemented" gap. `TierTokenPool` now tracks each Zone resident's own 1-indexed
position within its Zone's `ProtectionZone.squares` sequence (`zonePositionOf`/
`advanceInZone`; `enterZone` starts a token at position 1, the first slot, directly from the
Zone's numbered entry square). `TurnEngine.moveZoneToken(state, color, tier, zoneNumber,
spaces)` is the movement primitive: it adds `spaces` to the resident's zone position; if the
result still fits within that Zone's own square count, the token stays a resident there and
that zone-internal square's own effect resolves (a zone-internal Nebula sends it to the
Staging Pile, a zone-internal Wormhole of Construction promotes it — confirmed for the 1st
Tier's Zone 2, which has one of these as its own 4th slot — a zone-internal Fate Harvest
draws a card, anything else is a no-op) — `ZoneMoveResult.StillInZone`. Otherwise it exits:
the overflow (spaces beyond the Zone's last slot) continues from the Zone's own main-loop
entry square via the ordinary `moveTierToken` path, so further chaining (a Warp square right
at the entry point, Hyperthrust, etc.) resolves exactly as any other main-loop move would —
`ZoneMoveResult.ExitedZone`. This is the mechanism behind the user's stated primary case: "A
tier token in the ZOP CAN be chosen to be moved during a player's turn, then rolling the dice
for it" — same as ordinary main-loop movement, *which* of a player's tokens to move on a
given turn (a main-loop one vs. a Zone-resident one) is a decision for whatever's driving the
turn, not something `TurnEngine` itself picks.

`MovementCardResolver`/`ParallelPhasingResolver`/`LastGaspResolver`'s own-token-own-Zone
carve-out (rule 12's second sentence) now dispatches to `moveZoneToken` with the card's own
fixed distance, instead of the earlier honest "not yet implemented" rejection. Pass-through
destruction (Last Gasp is the only card that both starts from a possibly-Zone-resident mover
and destroys what it passes) never reaches *other* tokens resident in that same Zone while
the mover is still inside it — only the exiting portion of the move (the ordinary main-loop
leg) can destroy anything, matching every pass-through card's own printed "except tokens in
the Zone of Protection" clause, so a Zone is never a place pass-through destruction reaches
into even when the mover itself started there.

**Entering a Zone is the player's own choice, not automatic** — confirmed by the user,
correcting an earlier assumption baked into the original implementation. Landing on a Zone's
numbered entry square only offers entry (`SquareEffect.MayEnterZone`, mirroring the existing
`MayBuildMarauder`/`MayTransport` "offer, don't auto-apply" pattern); the caller opts in via
`TurnEngine.enterZoneOfProtection` before the turn ends. If the choice isn't taken, the token
simply remains an ordinary, unprotected in-play token sitting on that square from then on —
nothing else marks it as special once the turn passes (there's no engine-enforced expiry
timer; like every other "must be played immediately"/turn-scoped rule in this codebase, the
caller driving turns is responsible for not offering the choice again later). This applies
uniformly to both an ordinary dice-rolled landing and a landing caused by a movement card
(both go through the same `TurnEngine.moveTierToken`) — a movement-card resolver doesn't
surface the offer itself, consistent with how it already doesn't surface `MayBuildMarauder`/
`MayTransport` either; only whatever's driving an ordinary Tier-Phase turn sees `MoveResult
.effect` directly and can act on it.

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

**Fixed as a side effect of the Marauder model correction (see "Marauder model" below):** the
4 Marauder-construction resolvers used to be able to crash if a player's Marauder "Ion
Battery" was empty — that was never a real rule (see below), and removing the fictitious
resource entirely means there's nothing left to be empty; the crash path no longer exists.

**Fixed: Tier-token resource/capacity re-audit (the broader pass the item above deferred to).**
Two genuine, player-reachable crash risks found and fixed, both distinct from the Marauder
correction — these are real Tier-token Ion Battery/Staging-Pile cases, not a resource that
shouldn't have existed at all:
- `TierTokenPool.destroyFromStagingPile` still `require`s a non-empty pile (an internal
  invariant, left in place) — but its one caller, `DestructionCardResolver.destroy`'s
  `CardTarget.StagingPileToken` branch (Divine Assistance/Insidious Flux), used to call it with
  no prior legality check at all, unlike every `CardTarget.Token` branch (already checked via
  `TokenLocator`). A Staging Pile that emptied out between a target being chosen and the play
  resolving (e.g. another effect draining it earlier in the same Precedence chain) would crash
  the resolver instead of rejecting like every other stale target. Fixed in
  `DestructionCardResolver.validateExistenceAndZone`: it now checks the target pile's count for
  a `StagingPileToken` target before `resolve` ever reaches `destroy`, returning
  `TargetValidationError.NoLegalTarget` exactly like a vanished `CardTarget.Token` does. See
  `DestructionCardResolversTest`'s "emptied Staging Pile" regression test.
- `TierTokenPool.startToken()` used to `error(...)` (crash) if a Tier's own Ion Battery AND
  Hatchery were both empty of movable tokens — reachable in genuine, if rare, ordinary play:
  every one of a Tier's `tokensPerPlayer` physical tokens can legitimately end up already
  accounted for (in play, Hatchery, and Staging Pile) with none left to start on a promotion.
  This is exactly the case the rulebook itself anticipates: "In the unlikely event that a player
  runs out of tokens of a certain Tier, they must wait" (rulebook.txt:150-163) — the crash was
  the engine failing to implement "must wait" at all. `startToken()` now returns `TokenId?`,
  returning `null` (a graceful "must wait," not a fabricated token or a crash) in that state
  instead of throwing; every caller (ordinary Wormhole-of-Construction promotion in
  `TurnEngine`, and the construction-card resolvers) already discarded or now safely handles the
  return value — `BirthCanalConstructionCardResolver` in particular already documented "one Tier
  being out of tokens doesn't block a construct on another Tier in the same card," which this
  fix makes actually true instead of a crash waiting to happen. See `TierTokenPoolTest`'s
  "startToken returns null instead of crashing" regression test, which also confirms a freed
  slot makes `startToken()` succeed again afterward — nothing is permanently exhausted, matching
  the rulebook's "wait until... a token is destroyed" language.

Direct Staging-Pile addition/promotion (`addToStagingPileDirectly`) and ordinary promotion into
an upper Tier were both re-checked and found sound: the former only ever grows the pile (no
`require` to trip), and the latter's only real risk was the `startToken()` exhaustion above,
now fixed. No other ordinary-deck card resolver mutates `TierTokenPool` in a way that bypasses
these two fixes — audited by grepping every resolver's calls to `startToken`/
`destroyFromStagingPile`/`addToStagingPileDirectly`/`placeOnBirthCanal`.

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

## Persistent token identity: verified intact, no regression

Re-checked the `TokenId`/`TokenLocator` architecture (see "Card engine" above) after the
Marauder-model and Tier-token resource/capacity corrections above — neither touched
`state/TokenId.kt` or `cards/play/TokenLocator.kt` at all. All original guarantees still hold:
identity survives movement/Zone entry-exit/Staging transitions; position is resolved fresh at
resolution time via `TokenLocator.locate`, never a stale recorded position; stacked tokens at
the same position are distinguishable (unique `ordinal`); Tier tokens vs. Marauders are
distinguishable (`TokenId.kind`); Zone location is modeled as pool state (`zoneOf`/`zonePositionOf`),
never as part of identity itself; a destroyed/promoted/staged target resolves as a controlled
`TokenLocation.NoLongerExists`, which every resolver rejects gracefully rather than crashing on;
a later occupant of a freed position is never silently retargeted, since lookups are always by
id, never by position. `PrecedenceCardEffectIntegrationTest`'s stale-target, reverse-order-conflict,
and cross-player regression tests (the ones exercising this most directly) all still pass
unchanged. No code changes were needed for this phase — verification only.

## Regular-game compound-effect atomicity: audited, sound

Audited every compound/multi-step Fate Harvest effect in the regular (non-special-case) card
set for atomicity — no partial application of a mandatory compound effect, no discard/mutation
followed by a mid-resolution crash. Two shapes exist:
- **Per-target-validated compounds** (`ParallelPhasingResolver`'s own+opponent targets,
  `GravitonRiftResolver`'s up-to-4 targets): both already fully validate every target's
  existence and Zone-of-Protection legality via `TokenLocator`/`TargetValidator` *before*
  `CardLifecycle.attemptPlay` ever discards/marks-played the card, and before any mutation
  happens — confirmed correct on re-reading both resolvers' code against their own doc
  comments' atomicity claims. No changes needed.
- **Unconditional whole-board/whole-pool sweeps** (`FluidicWaveResolver`, `RadiationBurstResolver`,
  `PlasmaBurstResolver`, `GalacticRoundaboutResolver`, `LastGaspResolver`): no target is chosen
  that could turn out illegal partway through, so there's no per-target validation to preflight
  in the first place — each mutates unconditionally once `CardLifecycle.attemptPlay` succeeds,
  and the underlying `TierTokenPool`/`MarauderPool` bulk-clear methods used
  (`destroyAllInPlayAndStagingPile`/`destroyAllAt`/`destroyAllInZone`/`emptyStagingPile`/
  `destroyAllInPlay`) have no `require`/`error` that could trip mid-sweep. `GalacticRoundaboutResolver`
  additionally snapshots every token id before moving any of them (already documented) so a
  side-effect-created token is never swept up and a destroyed bystander is skipped, not crashed
  on. `PlasmaBurstResolver` has one internal `requireNotNull(square.magnitude)` guarding a
  malformed Zone-of-Protection board square — reviewed and left as-is: this is a static
  board-data invariant (every confirmed Zone entry square carries its zone number), not a
  player-reachable failure mode, so it doesn't need the same preflight treatment as a genuine
  target-legality check.

**The two cards the item-5 directive named as lower priority — Corpuscle Rot, Verdant Growth —
turned out to already be fixed**, as a side effect of the Tier-token resource/capacity
correction above, not because they needed their own separate atomicity work. Both
(`CorpuscleRotResolver`, `BirthCanalConstructionCardResolver`) call `TierTokenPool.startToken()`
unconditionally on one or more Tiers after their own destroy/validate half already succeeded;
now that `startToken()` returns `null` instead of crashing when a Tier is genuinely exhausted,
neither can partially-crash mid-resolution — the destroy (or first construct) half still
applies, and an exhausted construct Tier simply no-ops, matching the rulebook's own "must wait"
language rather than inventing new behavior. See `DestructionCardResolversTest`'s "Corpuscle Rot
still resolves the destroy even if a construct Tier is fully exhausted" regression test, which
exercises exactly this.

## Regular Fate Harvest deck: targeted crash/hang audit, no new defects found

A dedicated pass over every resolver's own code (not just the compound-effect ones above),
plus the shared infrastructure every resolver funnels through
(`CardLifecycle.attemptPlay`/`TargetValidator`/`TokenLocator`/`InteractionChain`), specifically
for: uncaught exceptions from reachable states, stale-target handling, illegal-target lifecycle
consistency (never discarding/marking-played a card whose target turns out illegal),
capacity/resource failures, partial compound effects, Zone-of-Protection changes mid-resolution,
Precedence reverse-resolution interactions, and physical-component-inventory dependence.

**Result: no new regular-game defects found** — every resolver (`MovementCardResolver`,
`CirculateResolver`, `CleansingResolver`, `DelayedMotionResolver`,
`PhaseLossResolver`/`PhaseControlResolver`, `CardEffectDispatcher`, plus the ones already
covered above) validates its target(s) fully — existence via `TokenLocator`, Zone-of-Protection
legality via `TargetValidator`, card-specific restrictions — *before* calling
`CardLifecycle.attemptPlay`, so an illegal target is always `Rejected` without discarding the
card or consuming the Phase's play limit; this pattern is uniform across the whole deck, not
case-by-case luck. `CardEffectDispatcher`'s own `requireXTarget` helpers reject a
wrong-shaped/missing target the same way, before any resolver runs at all. `InteractionChain`
itself has no mutation-adjacent crash path — reverse-resolution against a target an
earlier-resolving response already moved or destroyed is handled by the same
`TokenLocator`-based re-lookup every resolver already does at resolution time (exercised by
`PrecedenceCardEffectIntegrationTest`, re-confirmed passing under this audit).

Two internal `require`/`requireNotNull` calls were specifically reviewed and left as-is, both
guarding a caller/data invariant rather than a player-reachable state: `PlasmaBurstResolver`'s
and `CirculateResolver`'s `requireNotNull(square.magnitude)` (a malformed Zone-of-Protection
board square — never null on any of the four confirmed, digitized boards) and
`PhaseLossResolver`'s `require(triggeringEvent is TriggeringEvent.DrawnFromSquare)` (Phase Loss
is only ever dispatched via the Immediate-draw path — a caller/dispatcher-correctness
invariant). Both run before any state mutation, so even a hypothetical violation would crash
cleanly with no partial commit, not silently corrupt state.

Physical-component-inventory dependence: fully addressed by the Marauder-model correction above
— no remaining card resolver reads or assumes a component count as a resource limit.

**Special/limited cards, reported separately, lower priority, per the user's own framing:**
Corpuscle Rot and Verdant Growth's only known atomicity concern (the `startToken()` exhaustion
crash) is already fixed as documented above — no other defect found specific to either card
during this audit.

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

**Re-verified in a later stress-testing pass** (after the Tier-token resource/capacity and Fate
Harvest/`TurnDriver` integration work above), specifically re-running the historical trigger
shape rather than assuming the earlier verification still holds: two different non-contiguous
multi-class `--tests` filter combinations (5 classes and 4 classes, spanning `state`/`rules`/
`cards.resolvers`), 15 runs each, 30/30 passed; 20 single-class filtered runs (10×
`GameStateTest`, 10× `GameStateInitializationStressTest`), 20/20 passed; the full suite alone,
50/50 passed across earlier stability checks in this same pass. No reappearance — the `by lazy`
fix continues to hold under every reproduction shape that ever found the original bug. (This
same stress-testing pass is also what caught the `skipEmptyPhases` threshold bug described
above — a genuinely different issue, unrelated to this NPE, surfaced by the same repeated-run
discipline.)

## Whole-game randomized simulation: `GameSimulationTest`, 8 real defects found and fixed

`engine/src/test/kotlin/com/tiersofexistence/engine/simulation/` is a new kind of test in this
suite — not a targeted unit/integration test exercising one rule in isolation, but a broad,
unscripted stress test that drives full games end to end through the real `TurnDriver` with
every decision made randomly-but-plausibly, then re-checks a fixed set of engine invariants
after every single completed turn. `RandomLegalDecisionProvider` (`RandomLegalDecisionProvider
.kt`) makes a randomized choice at every `TurnDecisionProvider` decision point — which token to
move, whether to take each optional offer, which held/Immediate/Precedence-response card to
play and at what shape-correct (not necessarily rules-legal) target, via `CardTargetSampler`.
"Shape-correct but not pre-validated" is deliberate: an illegal attempt exercises the engine's
own rejection path (card stays in hand / gracefully discarded) rather than needing this
provider to duplicate `TargetValidator`'s legality logic. `GameSimulationTest.kt` drives 100
full games (fixed seeds, reproducible) to completion (or an 8000-turn cap), checking every
turn: Tier-token conservation (`TierTokenPool.totalOwned` always equals `tokensPerPlayer`),
deck+hand+discard card conservation (always exactly 70), no impossible Phase state, no
unresolved `pendingRoll` after a turn, and winner-state correctness (a declared winner always
has an in-play 4th-Tier token on a `YOU_WIN` square) — plus the harness's own exception handling
catching any crash as a reportable violation with full seed/turn repro context. (Precedence
chain leakage and "no vanished tokens" aren't separately probed — `InteractionChain` is never
stored on `GameState` so there's no field to leak into, and both would manifest as a card-
conservation violation or a crash, both already covered.)

**This found 8 genuine, previously-unknown engine defects, all reachable in ordinary randomized
2-6 player play — none required inventing new gameplay rules to fix, only closing gaps against
already-established engine invariants/precedent:**

1. **`CorpuscleRotResolver`** used a raw `require(target.tierOrNull == TierLevel.FOURTH)` — a
   wrong-Tier target crashed with `IllegalArgumentException` instead of `CardPlayResult
   .Rejected`, the one resolver in the whole deck inconsistent with the uniform "an illegal
   target is always Rejected, never a crash" pattern the Phase 6 audit had otherwise confirmed
   deck-wide. Fixed to return `Rejected(..., TargetValidationError.WrongTokenType(...))`,
   matching `InfernalAbyssResolver`'s own-owner check right above it in the same file. The old
   test asserting the crash (`assertFailsWith<IllegalArgumentException>`) was itself testing the
   bug — rewritten to assert graceful rejection instead.
2. **`TierTokenPool.addToStagingPileDirectly`** (Lucky/Luckier/Emitting Nebula's "place a
   Dimensional Token in your Staging Pile") did `stagingPile += 1` with no corresponding
   decrement anywhere — manufacturing a brand-new physical token from nothing every time one of
   these 3 cards was played, breaking `totalOwned`'s own conservation invariant (`ionBattery +
   hatchery + stagingPile + inPlayCount == tokensPerPlayer`, checked in `TierTokenPool`'s own
   `init`, but never re-checked after construction until this harness did). Fixed to draw from
   Hatchery-then-Ion-Battery first, same source order `startToken()` already uses, gracefully
   placing nothing (returns false, same as "no promotion") if both are empty — "matter is
   neither destroyed nor created" applies here exactly as everywhere else a token changes zones.
3. **`CardEffectDispatcher.dispatch`** crashed with `IllegalStateException: No resolver
   registered yet for Annulment (Antimatter)` whenever a `TurnDecisionProvider` offered Annulment
   as a standalone top-level Held-card play (`chooseHeldCardBeforeRoll`/
   `chooseCardAfterRollBeforeMove`) rather than as a Precedence-chain response — nothing in
   `TurnDriver`/the interface prevented this, even though Annulment ("cancel the effect of any
   card that is played") has nothing to cancel with no preceding play. Fixed by adding an
   explicit `"Annulment (Antimatter)" -> CardPlayResult.Rejected(...)` case rather than falling
   through to the `error(...)` meant for a genuinely unregistered card name.
4. **`TurnEngine`'s `MARAUDER_CONSTRUCTION_FACILITY` landing** unconditionally produced
   `SquareEffect.MayBuildMarauder` regardless of whether the landing player already had a
   Marauder in play on that Tier — contradicting the square's own doc ("only if you don't
   already have one in play on this Tier"). Accepting that misleading offer crashed
   `TurnEngine.buildMarauder`'s uncapped `placeOnBirthCanal` call
   (`IllegalArgumentException: <Tier> already has a Marauder in play for this player`). Fixed by
   only offering `MayBuildMarauder` when `marauders.inPlayCount(tier) == 0`, matching the rule
   text exactly and closing the crash at its root rather than only at the accept site.
5. **`TurnDriver.moveAndResolve`**'s `TokenLocation.NoLongerExists` branch (for a Tier token) was
   a bare `error("TurnDecisionProvider chose $id, but it no longer exists")` — but this is
   legitimately reachable, not a provider bug: `chooseTokenToMove` returns a token that DOES
   exist at that moment, then the rule-23 Precedence window opened right after
   (`SuspendedAction.PendingMove`) can let an opponent destroy exactly that token before it
   moves (the mirror image of the already-tested "rescue a token" worked example — here the
   response destroys the mover's own chosen token instead). Fixed to treat this as a graceful
   no-op turn (nothing to move, turn simply ends) instead of crashing the whole turn loop.
6. **The same class of bug existed for Marauders**, one layer worse: `moveAndResolve`'s
   `TokenKind.MARAUDER` branch had no existence check *at all* before calling `TurnEngine
   .moveMarauderById`, which `require`s the Marauder still exists
   (`IllegalArgumentException: Marauder <id> is not in play on <Tier>`) — reachable the same way
   as #5, just for a Marauder-Phase turn. Fixed with the same `TokenLocator`-based check-and-
   no-op pattern as #5.
7. **The most severe finding: every card resolver that moves a token via `TurnEngine
   .moveTierToken`/`moveMarauder`/`moveZoneToken`/`moveTierTokenById`/`moveMarauderById` discarded
   the returned `MoveResult`/`ZoneMoveResult` entirely** — `MovementCardResolver` (Tactical
   Motion/Tactical Step/Evasive Action/Skip-Hop-and-Jump/Sidestep), `ParallelPhasingResolver`,
   `LastGaspResolver`, and `GalacticRoundaboutResolver`'s whole-board sweep. Most of what a
   landing does is already unconditional inside `TurnEngine` itself regardless of caller (Nebula
   staging, Wormhole promotion, You Win, Vortex, Infernal Abyss, Time Wrinkle's two mandatory
   variants) — genuinely caller-blind by design, not a bug. The *optional offers* (Zone entry /
   Marauder Construction / Transport) are ALSO deliberately not surfaced for a card-driven move —
   already documented precedent, not a bug either, since a token landed there by a card's own
   move just stays an ordinary token, same as declining would. But **a Fate Harvest draw of an
   `IMMEDIATE` card is different**: the draw itself already happened unconditionally
   (`state.deck.draw()`), and unlike a `HELD` card (safely added straight to hand by `TurnEngine`
   regardless of caller), an Immediate card is never resolved/discarded by `TurnEngine` itself —
   that's always `TurnDriver.resolveImmediateCard`'s job, which none of these resolvers can
   reach. The result: any card-driven move that happened to land a token on a Fate Harvest square
   and draw an Immediate card lost that card for good — not in a hand, not resolved, not
   discarded, just gone, a genuine deck/discard/hand-conservation violation (this is what
   `GameSimulationTest` actually caught first, as `draw + discard + hands != 70`). Fixed with a
   new shared helper, `discardStrandedImmediateCard` (`cards/resolvers/CardDrivenMoveEffects.kt`)
   — discards a stranded Immediate card rather than losing it or inventing a substitute
   resolution, the exact same precedent already established for "an Immediate card genuinely has
   nowhere to land" (`resolveImmediateCard`'s own doc) — wired into all 4 affected resolvers.
8. **`ParallelPhasingResolver`** validated both targets' legality up front (correctly, all-or-
   nothing, before either moves) but then moved each one using a *snapshot* position captured at
   validation time (`MovableCheck.InPlayAt(fromPosition)`) rather than re-locating fresh — if
   moving the own-token target first chained into a Hyperthrust pass-through that destroyed the
   already-validated opponent target, the second `move()` call used the now-stale position and
   crashed (`IllegalArgumentException: No in-play token at position N`). Fixed by re-locating
   each target via `TokenLocator` fresh at the exact moment it's moved (identity-based movers
   throughout, no position-based lookups at all), gracefully skipping that half of the compound
   effect if the target has since vanished — the play itself stays committed (the card is
   legitimately expended either way) — rather than trusting either target's pre-validated
   position, matching this codebase's established "never trust a stale recorded position"
   philosophy (see `TokenId`'s own class doc) that this resolver had, ironically, reintroduced
   internally despite using the stale-position-proof `CardTarget.Token` externally.

**Verification**: after all 8 fixes, 100 simulated games (fixed base seed) ran with 0 invariant
violations, 100/100 reaching a winner within the 8000-turn cap (avg ~1400 turns, max ~6400).
Two additional 100-game batches at different base seeds also ran clean (0 violations each; one
batch had 2/100 games hit the turn cap without a winner — not a violation, just a slow game
under fully-random undirected play, reported by the harness rather than silently ignored). The
full engine suite (317 tests) and the historical multi-class `--tests` NPE-trigger sample both
stayed green across repeated reruns after these fixes, confirming no regression. "Stale queue
slots" (a queued player's eligibility legitimately going stale before their own turn arrives,
per finding #5/#6's root cause) are tracked and reported by the harness as an expected, now-
gracefully-handled occurrence (not a violation) — several hundred were observed across the
combined 300 games, all handled without incident post-fix.

**Scaled to 2000 games (`GAME_COUNT` is now permanently 2000, ~25-50s added to the suite) — found
and fixed a 9th defect, this one only surfacing at the larger sample size.** The first 2000-game
run (original base seed) was clean, but a second 2000-game batch at a different base seed caught
2 new violations, both "Winner declared but has no 4th-Tier in-play token on a YOU_WIN square" —
one with the winning token found elsewhere on the board (moved away), one with it gone entirely
(destroyed). Root cause: **`TurnDriver.driveOneTurn` never stopped mid-turn once a win happened
partway through it.** `GameState.declareWinner`/`declareSimultaneousWinners` don't remove the
winning token from play or freeze anything — a winning token is a perfectly ordinary in-play
token afterward, per `TurnEngine`'s own YOU_WIN landing (correctly so; nothing in the rulebook
says otherwise). But nothing previously stopped the REST of that same turn once the win happened
partway through it: a pre-roll held-card play that itself won the game still let the same turn
go on to roll and move another token afterward (sometimes the very token that just won, moving
it off YOU_WIN), and a Precedence response to the pending move — from *any* seated player, not
just the one whose win it was — could still destroy the winning token before the turn ended.
`declareWinner`'s "first sticks" no-op already kept the recorded winner's *color* correct either
way, which is why this took 4000 games to surface at all — it's a real rules violation ("the
first player to land on You Win! wins the game" means the game ends right then, not "once this
turn finishes whatever else it was doing"), not a wrong-outcome bug. Fixed with a new
`TurnDriver.endTurnIfGameWon` check called after every point in `driveOneTurn` that could newly
produce a winner before its own turn-ending move (both held-card-play windows, and the
Precedence window around the pending move) — ends the turn immediately, doing nothing further,
the instant `state.winners` becomes non-empty. Re-verified: the batch that found the 2 violations
now runs clean at 0/2000, plus 2 more independent 2000-game batches (including the original)
also clean — 6000 total games, 0 violations, after this fix. Full suite and NPE-trigger sample
stayed green throughout.
