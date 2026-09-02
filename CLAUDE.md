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
  `./gradlew :engine:test`.
- **`app/`** — Android/Compose UI module, depends on `engine`. Currently just a placeholder
  screen proving the dependency wires up.

Keep game logic in `engine`, not in Compose UI code — it's the only module we can actually
run/test in most sandboxes (see below).

## Sandbox build limitations

This repo has been developed in a sandbox with **no Android SDK** and a network policy that
blocks `dl.google.com` (Google's Maven repo, needed for the Android Gradle Plugin and
AndroidX/Compose artifacts — `mavenCentral()` works fine). Practically:

- `./gradlew :engine:test` works and is how engine logic gets verified here.
- Building or running `:app` requires Android Studio (or any environment with a real
  Android SDK and unrestricted network) — it cannot be verified in this sandbox.
- If your environment also lacks a JDK 17 toolchain, `engine/build.gradle.kts` requests
  `jvmToolchain(17)`; a `foojay-resolver-convention` plugin is wired up in
  `settings.gradle.kts` to auto-download one, but that also needs `dl.google.com`/Adoptium
  network access some sandboxes block.

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

## Turn-resolution engine: base mechanics built, card effects deferred

`rules/TurnEngine.kt` now does the roll → move → resolve-the-landed-square work, on top of
`GameState`'s new turn-queue (`currentTurn`, `endTurn(grantAnotherTurn)`, `skipEmptyPhases()`)
and `Dice.rollForPhase(phase)` (both dice summed for a Tier Phase, purple only for the
Marauder Phase). This is the base mechanics layer the user asked for first; see
`TurnEngine`'s class doc for the exact scope. Implemented: Tier token movement and landing
effects for Nebula (staging pile + promotion), Vortex of Regression, Wormhole of
Construction, You Win (exact-landing only), Infernal Abyss, Hyperthrust (pass-through
destroy + chained landing resolution), Fate Harvest (draw + hold), and Marauder Construction
Facility (flagged, build is a separate opt-in call); Marauder movement with pass-through
destruction (Reprieve-protected), and the rulebook's "only Transport/Sensor/Abyss affect a
Marauder" landing rules. `GameState.endTurn(grantAnotherTurn = true)` is the mechanism for
"Go again" chaining — deciding *when* to pass that flag is left to whatever drives turns.

Deliberately NOT implemented yet, so as not to guess at a much larger task — interpreting
how each of the other ~30 Fate Harvest cards actually changes game state is future work:
- Any individual card's effect beyond drawing/holding (Corpuscle Rot, Galactic Roundabout,
  Divine Assistance, etc. — none of them do anything yet when "played").
- Precedence-card interruption mid-roll (rule #23) — a live multi-player synchronization
  concern for the eventual UI, not something a stateless engine function can represent.
- Zone of Protection as real token state — landing on the entry square is reported but
  nothing tracks a token as "now protected."
- Warp's actual effect (genuinely ambiguous in the rulebook — "usually affects movement").
- Two Time Wrinkle variants ("lose next turn on this Tier," "take an extra turn, First
  Tier") that need deferred/cross-Phase state beyond what exists; "Go again" is supported.
- A judgment call, not confirmed with the user: Reprieve's protection is applied for a
  Marauder mover (matching the rulebook's literal wording) but not for Hyperthrust, whose
  identically-worded pass-through-destroy text never mentions Reprieve.
