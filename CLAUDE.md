# Tiers of Existence — Android App

Android adaptation of "Tiers of Existence," a 2013 physical board/card game (rulebook:
`docs/rulebook.txt`, extracted from the original .doc — this is the source of truth for
rules questions). 2-6 players, roll-and-move across four "Tier" boards, with a 70-card
Fate Harvest deck driving most of the strategy.

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
  the full Fate Harvest card catalog, turn/phase order, dice. Fully unit-testable without
  an Android SDK or emulator — `./gradlew :engine:test`.
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
  square. Not yet enforced anywhere in code (no turn-resolution logic exists yet — see
  "Architecture" below), but confirmed by the user and worth keeping in mind once movement
  is implemented.
- A Marauder Transport square living inside a Zone of Protection (1st Tier's Zone 2, 4th
  Tier's Zone 5) is a confirmed exception to "Marauders cannot enter the Zone of Protection"
  — a Marauder may land there, but still can't affect any other token still in the Zone.

## Known gaps: turn/phase resolution

`rules/Phase.kt` and `rules/TurnOrder.kt` correctly model the *shape* of a Round (5 Phases,
in the right order; who's eligible for a turn in a given Phase) but there's no
turn-resolution logic yet — no code rolls dice, moves a token, or drives a turn end-to-end.
Confirmed against the rulebook's "Rounds, Phases, and Turns" section and the 1st Tier's
Phase Clock / Turn Indicator art and the Round Gear spinner (both match `Phase.ROUND_ORDER`
exactly: Marauder, 4th, 3rd, 2nd, 1st). Specific gaps to close when building that logic,
not bugs in what exists today:

- **Phase-skipping isn't implemented.** `GameState.advancePhase()` just steps through all 5
  `ROUND_ORDER` entries every Round; nothing skips a Phase with zero eligible players, and
  nothing ends a Round early after the 1st Tier Phase the way Round 1 should ("the first
  Round of the game only has a 1st Tier Phase. When the 1st Tier Phase ends, the Round is
  over."). Whatever drives turns needs to do this explicitly.
- **No current-turn pointer.** `GameState` tracks which Phase and Round, but nothing tracks
  *whose turn within the Phase* it currently is (the physical Turn Indicator token on the
  Round Gear spinner). Needed before turns can actually be sequenced.
- **Dice aren't wired to phases at all.** `Dice.rollBlack()`/`rollPurple()` exist but nothing
  calls them. A Tier Phase turn rolls both dice together ("the pair"); the Marauder Phase
  rolls purple only.
- **Resolved: Fate Harvest card-play limit is per-Phase, not per-turn.** Rule #4 ("only one
  card per player per Phase") appeared to conflict with two other passages describing a
  per-*turn* limit instead (the "Rounds, Phases, and Turns" section, rulebook.txt:203-206,
  and the Fate Harvest square's own description, rulebook.txt:357-358). Resolved per the
  user: the numbered "Fate Harvest Card Rules" section (rulebook.txt:402+, which explicitly
  claims to be "the rules governing the use of Fate Harvest cards") is the actual card
  rules; the other two are general Rules of Play / Game Board Rules narrative that merely
  touches on the topic. The rulebook itself says "In a case where a card conflicts with the
  Rules of Play, the card takes precedence" (rulebook.txt:356-357) — so Rule #4 wins.
  `PlayerState.hasPlayedCardThisPhase`, reset each Phase in `GameState.advancePhase()`,
  already implements this correctly; no code change was needed.

- **Confirmed correct, no changes needed:** all 6 Precedence-flagged cards in
  `FateHarvestCatalog` (Graviton Rift, Fluidic Wave, Tactical Motion, Annulment, Tactical
  Step, Last Gasp) were cross-checked against every "has Precedence" mention in the
  rulebook's card list (rulebook.txt:546, 555, 657, 686, 717, 765) — exact match both ways.
  `PlayerState.hasTierTurn(tier)`/`hasMarauderTurn()` also correctly model that a Tier with
  no Tier tokens has no Tier Phase turns, while Marauder Phase eligibility is a fully
  separate, Tier-independent check (a Marauder can have a turn on a Tier with zero Tier
  tokens present).
