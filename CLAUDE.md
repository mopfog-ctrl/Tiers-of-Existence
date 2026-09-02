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
