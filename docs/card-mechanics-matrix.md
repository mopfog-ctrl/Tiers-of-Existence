# Fate Harvest Card Mechanics Matrix

**Status: implementation specification, not player-facing documentation.** This is Phase A
of the card-engine work described in the project's ongoing task: audit all 32 unique Fate
Harvest cards' *actual mechanical requirements* before writing any card-resolution code, so
that shared engine systems (Precedence chain, Zone-of-Protection state, deferred turn
modifiers, target validation) get designed from real requirements instead of being
retrofitted card-by-card.

Sources: `docs/rulebook.txt` (cited by line number below), the current
`engine/src/main/kotlin/com/tiersofexistence/engine/cards/FateHarvestCatalog.kt`, the
digitized board data in `board/BoardLayouts.kt`, `CLAUDE.md`, and confirmed user rulings
recorded in this task's instructions ("canonical interpretation rules"). Where the rulebook
and catalog are silent or contradictory, this document says so explicitly in the card's
**Ambiguities** field rather than inventing behavior — see §4 for the consolidated list.

**Governing principle:** cards sharing similar wording do NOT necessarily share mechanics.
Six cards move "any token ... forward N spaces" with nearly identical printed text
(Tactical Motion, Tactical Step, Evasive Action, Skip/Hop/and Jump, Sidestep, Parallel
Phasing) but differ in timing (Immediate vs Held), scope (Your Turn vs Any Time), and
Precedence — each is audited independently below.

---

## 0. Global Fate Harvest Card Rules (rulebook.txt:402-487)

These 24 numbered rules apply to every card and are the source of most of the shared engine
systems in §3. Paraphrased with engine implications:

| # | Rule (paraphrased) | Engine implication |
|---|---|---|
| 1 | Drawing from a Fate Harvest square only happens for Tier tokens, never Marauders. | Already correct: `TurnEngine.resolveTierLanding`'s `FATE_HARVEST` case; `resolveMarauderLanding` has no such case. |
| 2 | Read cards carefully. | Flavor; no engine effect. |
| 3 | The next player doesn't take their turn until the drawn card is played or held. | The draw-then-play-or-hold step is part of the *current* player's turn resolution, not a separate turn. No new state needed beyond making sure `TurnEngine` doesn't advance `currentTurn` until this resolves. |
| 4 | **Only one Fate Harvest card per player per Phase** (not per turn — see §4 Q1, already resolved in favor of "per Phase" per `PlayerState.hasPlayedCardThisPhase` and the commit that resolved this). | Must be *enforced*, not just tracked — currently `hasPlayedCardThisPhase` is set up but nothing checks it before allowing a play. Phase E target-validation work. |
| 5 | Played cards go to the discard pile. | `FateHarvestDeck.discard(card)` exists; must be called from card-resolution, not just theorized. |
| 6 | Empty draw pile → reshuffle discard pile. | Already implemented, `FateHarvestDeck.draw`. |
| 7 | Some movement cards move Tier tokens only or a specific Tier's tokens; others move any token including Marauders. | Per-card `affectedTokenTypes` field (below) — must be data-driven, not inferred from the word "any". |
| 8 | **When a Marauder is moved by a movement card, tokens passed (other than the Marauder owner's own) are destroyed exactly like ordinary Marauder movement.** | This is a *movement-mechanics* rule, not a card-specific one: any card that ends up moving a Marauder must invoke the same pass-through-destroy path as `TurnEngine.moveMarauder`/`destroyTokensPassed`, regardless of who played the card. See §4 Q4 for the "whose tokens are exempt when the mover and the card-player differ" ambiguity. |
| 9 | The 1-Marauder-per-Tier cap doesn't apply to card-added Marauders. | Matches existing `MarauderPool.placeOnBirthCanal(bypassCap = true)`. |
| 10 | Only Last Gasp! and the Hyperthrust square let a *Tier token's* movement destroy a Marauder. | Generic Tier-token movement (dice or most movement cards) must never destroy anything passed — only Hyperthrust-square chains and Last Gasp's own resolution may. Already correct for dice movement (`moveTierToken` has no pass-through destruction); must stay correct as movement cards are added. |
| 11 | "Move any token" = any Tier/player/type including Marauders, unless the card says otherwise; never the (unmodeled) Turn Indicator/Phase Clock tokens. | No engine action — those two tokens aren't simulated. |
| 12 | Zone of Protection blocks *most* Fate Harvest effects from other players; five named exceptions (Divine Assistance, Corpuscle Rot, Galactic Roundabout, Plasma Burst, Graviton Rift) can still reach in. A player may always play their own movement card on their own token inside their own Zone. | Central to Phase G/E: a `ZoneOfProtectionPolicy` shared rule with (a) a fixed exception-card list and (b) an "own token, own Zone" carve-out that applies to *every* movement card, not just the five. |
| 13 | Divine Assistance can't target the (unmodeled) Turn Indicator/Phase Clock tokens. | No engine action. |
| 14-17 | Immediate/Held, Your-Turn/Any-Time. | Matches `CardTiming`/`CardScope`, but see §4 Q7 — at least two cards (Planetary Nebula, Emitting Nebula) restrict to Your Turn *of a specific Phase*, which the current two-value `CardScope` enum can't express, and Delayed Motion's window ("after your roll, before you move") is a third axis entirely. |
| 18-19 | Color card trading rules. | Not modeled yet; no card in this matrix requires trade logic to *play* it (trading is a separate action between two players' hands), but the engine's hand model needs to support it eventually — out of scope for Phase A/B here, flagged for a later tranche. |
| 20 | Precedence cards supersede other cards and token movements; all Precedence cards (Annulment included) are playable any time. | `CardScope.ANY_TIME` is necessary but not sufficient for Precedence semantics — "supersedes ... token movements" means a Precedence card can interrupt an in-progress action that a *non-Precedence* Any-Time card cannot. This distinction doesn't exist in the engine yet. |
| 21 | "Precedence!" pauses the acting player until the Precedence card resolves. Precedence cards played after another card resolve *before* it. | The core LIFO (stack) behavior of the interaction engine, §3.1. |
| 22 | Multiple Precedence cards resolve in reverse order of play, **except** Annulment, which cancels the card immediately before it (and everything after it plays as if that card and Annulment never existed). | §3.2 — the one genuinely special case in the whole card set. |
| 23 | Precedence cards can interrupt before or after a die roll, blocking movement until they resolve. | Confirms Precedence's "interrupt" semantics apply even to the roll→move step, not just to other cards. Cross-references CLAUDE.md's flagged gap ("a live multi-player synchronization concern for the eventual UI") — the *engine* must still expose an explicit "is there an open interaction window blocking this roll's move" state, even though driving the UI's real-time synchronization is out of scope here. |
| 24 | Cards override conflicting board-square rules, **except** when the affected Tier token is on a Zone of Protection square. | Reinforces rule 12: Zone of Protection is the one thing that beats a Fate Harvest card (outside the 5 named exceptions). |

---

## 1. How to read §2

Each of the 32 cards gets the 22 fields the task specifies, using this vocabulary for
"Required engine state" (designed in Phase B/C/D/E/F/G, not yet implemented — named here so
the same primitive is reused across cards that need it instead of reinvented per card):

- **CardPlayRequest** — a play attempt: source player, the card, chosen target(s).
- **EffectContext** — resolution-time bundle: source, target(s), the triggering event
  (square landed on, or a direct hand-play), current Phase/turn.
- **InteractionChain / PrecedenceWindow** — Phase C's stateful response-chain machinery.
- **ZoneResidence** — Phase G: per Tier-token, which Zone (if any) it currently occupies.
- **StagingPileMutation** — a shared pool primitive (add-with-promotion-check,
  remove-one-arbitrary) used by both Nebula landings and any card that touches a Staging
  Pile directly.
- **DeferredTurnModifier** — a queued, bounded turn-state change:
  `SkipNextTierTurn(player, tier)` or `ExtraTierTurn(player, tier)`, consumed the next time
  that Tier's turn queue is built for that player. Both `Phase Loss` and the "Lose next turn
  on this Tier" Time Wrinkle square need the first; both `Phase Control` and the "Take an
  extra turn, First Tier" Time Wrinkle square need the second.
- **TemporaryMarauderAllowance** — a Marauder-Phase-turn countdown (see §4 Q2 — scaffolding
  only; not yet attached to a specific card, because none of the 4 Marauder-adding cards'
  printed text says "temporary").
- **PendingRoll checkpoint** — an explicit "die rolled, not yet moved" state for Delayed
  Motion, which doesn't exist in `TurnEngine` today (it currently takes `spaces` as a single
  atomic parameter).
- **HandDiscardRequest** — a decision required from a player *other than* the one who played
  the card (Cleansing), structurally similar to a Precedence response but not Precedence
  itself.
- **PhaseRestriction** — a card-specific "your turn, AND specifically during Tier X's Phase"
  restriction, narrower than plain `CardScope.YOUR_TURN`.

---

## 2. Card-by-card audit

### Singles (×1 copy each)

#### 1. Corpuscle Rot
- **Rarity/copies:** Single ×1
- **Immediate or Held:** Held
- **Scope:** Any Time
- **Color restriction:** Yellow only
- **Precedence:** No
- **Legal targets:** One 4th Tier token, any owner (text says "any 4th Tier token," not
  "opponent's" — see Ambiguities).
- **Affected token types:** Tier token only (for the destroy half); the two new tokens are
  also Tier tokens (1st and 2nd).
- **Affected Tier(s):** 4th (destroy), 1st and 2nd (construct).
- **Movement effect:** None.
- **Destruction/construction effect:** Compound — destroy one 4th Tier token, AND start one
  new token each on the 1st and 2nd Tier Birth Canals (own tokens, per theme).
- **Turn-state effect:** None.
- **Persistent duration:** None (one-shot).
- **Zone-of-Protection interaction:** Named exception (rule 12) — CAN destroy a 4th Tier
  token sitting in Zone 5.
- **Reprieve interaction:** Reprieve doesn't reach the 4th Tier's Abyss-destruction path;
  this is a direct card-destroy, not Marauder/Hyperthrust pass-through, so Reprieve (which
  per confirmed canon only protects against *pass-through* destruction) does not apply here
  at all — Reprieve is irrelevant to any directly-targeted destroy effect, this card
  included.
- **Can another card respond to it:** Yes, in principle any Precedence card could be played
  in response (e.g. Annulment to cancel it, or a movement card to relocate the targeted
  token before the destroy resolves) since it's Any Time, not itself Precedence — but per
  rule 20, only *Precedence* cards can interrupt; a plain Any-Time card cannot pre-empt an
  already-in-progress resolution.
- **Can it respond to another card:** No — it isn't Precedence, so it can't interrupt
  anything, though it can be played as a normal (non-interrupting) Any-Time action.
- **Annulment behavior:** Standard — if Annulment is played immediately after Corpuscle Rot,
  the whole compound effect (destroy + both constructs) is cancelled as one unit.
- **Required engine state:** CardPlayRequest (target selection: which 4th Tier token),
  ZoneResidence (to permit the ZoP target).
- **Known interactions:** None named specifically, but combos with Reprieve are a non-issue
  (see above) — worth a regression test proving that explicitly, since it's an easy engine
  bug to introduce by reusing the Marauder-pass-through Reprieve check for a card-destroy
  path.
- **Rulebook citation:** rulebook.txt:497-507 (card list), 433-438 (rule 12 exception list).
- **Ambiguities:** Can Yellow destroy their *own* 4th Tier token with this card (the text is
  unqualified "any"), or is it implicitly opponent-only? Flag for user confirmation — see §4 Q3.

#### 2. Galactic Roundabout
- **Rarity/copies:** Single ×1
- **Immediate or Held:** Immediate
- **Scope:** n/a (Immediate)
- **Color restriction:** None
- **Precedence:** No
- **Legal targets:** Every token in the game, all players, all types, including tokens
  inside a Zone of Protection — no target selection, it's unconditional and total.
- **Affected token types:** Both (Tier tokens and Marauders).
- **Affected Tier(s):** All four.
- **Movement effect:** Every token forward 2 spaces, resolved as ordinary movement (landing
  effects trigger normally per token, including chained effects like Hyperthrust squares or
  an exact You-Win landing).
- **Destruction/construction effect:** Indirect only, via each moved token's own landing
  effect (e.g. a Tier token could land on Infernal Abyss) — and via Marauder pass-through
  destruction if rule 8 applies to this card (see Ambiguities).
- **Turn-state effect:** None directly, though a Tier token could land on a Time
  Wrinkle/You-Win square as a side effect of the forced move.
- **Persistent duration:** None (one-shot, but touches the entire board at once).
- **Zone-of-Protection interaction:** Named exception (rule 12) — explicitly moves ZoP
  tokens too, unlike almost every other movement card.
- **Reprieve interaction:** Only relevant to whichever sub-question of "does Marauder
  movement here trigger pass-through destruction" resolves (see Ambiguities); if it does,
  Reprieve protects Tier tokens from it as usual.
- **Can another card respond to it:** Yes, a Precedence card could react to protect a
  specific token before this resolves — but since it's Immediate, per confirmed canon
  ("Precedence does not interrupt an Immediate effect already resolving") once this starts
  resolving it cannot be interrupted mid-resolution; a Precedence response must come *before*
  the atomic move-everyone step begins (i.e., in response to the draw/reveal, not mid-shift).
- **Can it respond to another card:** No (not Precedence).
- **Annulment behavior:** Standard — cancels the entire "move everyone 2" effect as one atomic unit if played before it resolves.
- **Required engine state:** A whole-board iteration helper (every player × every Tier ×
  both token kinds), reusing the same per-token movement/landing resolution `TurnEngine`
  already has, just looped.
- **Known interactions:** Two tokens could both land exactly on their respective 4th Tier
  You-Win squares in the same resolution if two players are close enough — see §4 Q5 for
  the ordering/tie question that creates for Phase I.
- **Rulebook citation:** rulebook.txt:509-515, 433-438 (rule 12).
- **Ambiguities:** (a) Does moving every Marauder by this card trigger the same
  pass-through-destroy as rule 8's normal card-driven Marauder movement, for every Marauder
  simultaneously, or is a uniform "shift everyone" effect exempt from that rule (it isn't a
  targeted "move a token" play in the usual sense)? (b) Resolution order across multiple
  players' tokens when it matters for pass-through/tie purposes. Both flagged in §4.

#### 3. Dwarf Star
- **Rarity/copies:** Single ×1
- **Immediate or Held:** Held
- **Scope:** Any Time
- **Color restriction:** White only
- **Precedence:** No
- **Legal targets:** None to choose — fixed effect (own Marauder, fixed Tier).
- **Affected token types:** Marauder.
- **Affected Tier(s):** 4th only.
- **Movement effect:** None.
- **Destruction/construction effect:** Construct — place a Marauder on the 4th Tier Birth
  Canal, bypassing the 1-per-Tier cap (rule 9).
- **Turn-state effect:** None.
- **Persistent duration:** None (the placed Marauder is permanent, no expiry stated).
- **Zone-of-Protection interaction:** None (placement, not movement into/through a Zone).
- **Reprieve interaction:** None.
- **Can another card respond to it:** Only a Precedence card, and only to something that
  matters before this resolves (nothing about this effect is contestable — no target to
  redirect).
- **Can it respond to another card:** No.
- **Annulment behavior:** Standard — cancels the placement.
- **Required engine state:** CardPlayRequest only; reuses `MarauderPool.placeOnBirthCanal(bypassCap = true)` unchanged.
- **Known interactions:** None named.
- **Rulebook citation:** rulebook.txt:517-524.
- **Ambiguities:** None.

#### 4. Radiation Burst
- **Rarity/copies:** Single ×1
- **Immediate or Held:** Held
- **Scope:** Your Turn
- **Color restriction:** None
- **Precedence:** No
- **Legal targets:** Unqualified "All Staging Piles" — no target selection if it truly means
  every player's every Tier's pile (see Ambiguities).
- **Affected token types:** Tier tokens (Staging Pile contents only).
- **Affected Tier(s):** Potentially all four (or just the player's own — ambiguous).
- **Movement effect:** None.
- **Destruction/construction effect:** Empties Staging Pile(s) — tokens return to their
  owners' Ion Batteries (per "matter is neither destroyed nor created"), most likely
  *without* triggering the next-Tier promotion a pile reaching its threshold would normally
  grant (emptying is a reset/punishment, not reaching the threshold) — but this is stated as
  an inference, not confirmed; see Ambiguities.
- **Turn-state effect:** None.
- **Persistent duration:** None (one-shot).
- **Zone-of-Protection interaction:** None (Staging Piles are off-board pools, not on-loop
  positions — Zone protection is a board-position concept and doesn't apply to a pool of
  waiting tokens the same way; not one of the 5 named exceptions either way).
- **Reprieve interaction:** None (not a pass-through effect).
- **Can another card respond to it:** Only Precedence, pre-resolution.
- **Can it respond to another card:** No.
- **Annulment behavior:** Standard.
- **Required engine state:** StagingPileMutation ("empty pile, return count to Ion Battery,
  no promotion check") applied per affected pool.
- **Known interactions:** Interacts with Nebula-landing and every other
  Staging-Pile-touching card (Insidious Flux, Lucky Nebula, Luckier Nebula, Emitting Nebula)
  purely by sharing the same pool primitive — no special-case behavior beyond that.
- **Rulebook citation:** rulebook.txt:526-531.
- **Ambiguities:** (1) "All Staging Piles" — every player's, or just the caster's own? (2)
  Does emptying skip or trigger the promotion-threshold check if a pile happened to be at or
  above threshold the instant before it's emptied? See §4 Q6.

#### 5. Materialize Army
- **Rarity/copies:** Single ×1
- **Immediate or Held:** Held
- **Scope:** Your Turn
- **Color restriction:** None
- **Precedence:** No
- **Legal targets:** Player chooses which Tier (own Marauder only).
- **Affected token types:** Marauder.
- **Affected Tier(s):** Player's choice of any one of the four.
- **Movement effect:** None.
- **Destruction/construction effect:** Construct — place own Marauder on the chosen Tier's
  Birth Canal, bypassing the cap.
- **Turn-state effect:** None.
- **Persistent duration:** None.
- **Zone-of-Protection interaction:** None.
- **Reprieve interaction:** None.
- **Can another card respond to it:** Precedence only, pre-resolution.
- **Can it respond to another card:** No.
- **Annulment behavior:** Standard.
- **Required engine state:** CardPlayRequest with a Tier-choice target; reuses `MarauderPool.placeOnBirthCanal(bypassCap = true)`.
- **Known interactions:** None named.
- **Rulebook citation:** rulebook.txt:533-538.
- **Ambiguities:** None.

#### 6. Graviton Rift
- **Rarity/copies:** Single ×1
- **Immediate or Held:** Held
- **Scope:** Any Time
- **Color restriction:** Black only
- **Precedence:** **Yes** (Group 5 card)
- **Legal targets:** Up to four independent choices — one token of *any* type, from *each*
  Tier that has at least one token present (skip Tiers with none); any owner, including
  Black's own tokens (text doesn't restrict to opponents).
- **Affected token types:** Both.
- **Affected Tier(s):** All four, independently.
- **Movement effect:** None.
- **Destruction/construction effect:** Destroy up to 4 tokens (one per Tier).
- **Turn-state effect:** None.
- **Persistent duration:** None (one-shot, but resolves against a live board state so timing
  matters a great deal — see below).
- **Zone-of-Protection interaction:** Named exception (rule 12) — can destroy ZoP Tier
  tokens.
- **Reprieve interaction:** Irrelevant — direct card-destroy, not pass-through (same
  reasoning as Corpuscle Rot).
- **Can another card respond to it:** Yes — being Precedence, this opens/extends an
  interaction window; other Precedence cards (including Annulment) can respond before it
  resolves.
- **Can it respond to another card:** Yes — this is exactly the kind of card rule 23
  describes: because it's Any-Time *and* Precedence, it can interrupt an in-progress
  action (e.g. snipe a token mid-flight before another effect would have moved it to
  safety, or destroy a Marauder about to land a killing blow) — this is Group 5, audited in
  full against the interaction engine once built (Phase J).
- **Annulment behavior:** Standard chain position — if Annulment is played immediately
  after this, the whole 4-Tier destruction is cancelled as one unit; if Graviton Rift itself
  is played as the response *to* something and later Annulled, only the card and its direct
  effect vanish, not whatever came before it in the chain (see §3.2's worked example).
- **Required engine state:** InteractionChain/PrecedenceWindow, CardPlayRequest with 4
  independent per-Tier optional targets, ZoneResidence.
- **Known interactions:** Directly comparable to Divine Assistance (single free-choice
  destroy) and Plasma Burst (positional 3-square destroy) — all three are "destroy" cards
  with different targeting shapes; worth cross-testing that each respects its own shape and
  none accidentally reuses another's.
- **Rulebook citation:** rulebook.txt:540-548, 461-484 (rules 20-23).
- **Ambiguities:** None beyond the general Precedence-chain design questions in §3.1/§3.2.

#### 7. Fluidic Wave
- **Rarity/copies:** Single ×1
- **Immediate or Held:** Held
- **Scope:** Any Time
- **Color restriction:** Blue only
- **Precedence:** **Yes** (Group 5 card)
- **Legal targets:** None to choose — unconditional wipe of every token (every player's,
  including Blue's own) on the 1st Tier.
- **Affected token types:** Both — "all tokens" (in-play and Staging Pile); Marauders on the
  1st Tier are included too ("all tokens" per rule 11's default, and the card text doesn't
  narrow it to Tier tokens).
- **Affected Tier(s):** 1st only.
- **Movement effect:** None.
- **Destruction/construction effect:** Mass-destroy every 1st Tier in-play token (all
  owners) and every 1st Tier Staging Pile token (all owners), **except** Tier tokens
  currently inside a 1st-Tier Zone of Protection (Zones 1 and 2).
- **Turn-state effect:** None directly (though wiping in-play tokens can free Hatchery slots
  and trigger the normal Hatchery-promotion-into-play side effect that any `destroyInPlay`
  call already produces).
- **Persistent duration:** None (one-shot, but touches an entire Tier's population).
- **Zone-of-Protection interaction:** **Not** one of the 5 named exceptions — ZoP Tier
  tokens are explicitly excluded from the wipe by the card's own text (redundant with, not
  contradicting, rule 12's default protection).
- **Reprieve interaction:** Reprieve is irrelevant here too — this is a direct card-destroy
  sweep across a whole Tier, not a pass-through effect, so a Tier token sitting on a
  Reprieve square gets destroyed by this card exactly like any other non-ZoP token (Reprieve
  only protects against Marauder/Hyperthrust/Last-Gasp pass-through, per confirmed canon —
  it has never been described as blanket immunity to *every* destroy effect).
- **Can another card respond to it:** Yes (Precedence).
- **Can it respond to another card:** Yes (Precedence + Any Time).
- **Annulment behavior:** Standard.
- **Required engine state:** InteractionChain, a "wipe a whole Tier" bulk operation over
  every player's pool for that Tier (in-play + Staging Pile), ZoneResidence to exclude
  protected tokens, StagingPileMutation for the pile half.
- **Known interactions:** Direct interaction with Marauders too, unlike most other
  destruction cards which are Tier-token-only or type-agnostic-but-single-target — worth a
  test confirming 1st-Tier Marauders are wiped along with Tier tokens (Marauders can never
  be in a ZoP anyway, so the ZoP exclusion never saves one).
- **Rulebook citation:** rulebook.txt:550-559.
- **Ambiguities:** None on scope/targets (text is unusually explicit for this card); the
  general Reprieve-doesn't-apply-to-direct-destroys reasoning above should be captured as an
  explicit regression test since it's easy to accidentally over-protect.

#### 8. Parallel Phasing
- **Rarity/copies:** Single ×1
- **Immediate or Held:** Immediate
- **Scope:** n/a
- **Color restriction:** None
- **Precedence:** No
- **Legal targets:** Two independent targets in one resolution: (a) one of your own tokens,
  any type — including your own token inside your own Zone (rule 12's carve-out); (b) one
  other player's token, any type, **not** if it's currently inside a Zone of Protection.
- **Affected token types:** Both.
- **Affected Tier(s):** Whichever Tier each chosen token happens to be on (independent).
- **Movement effect:** Both chosen tokens move forward 4 spaces, each resolved as an
  independent, complete movement (including chained landing effects).
- **Destruction/construction effect:** Indirect only, via each token's landing effect, or
  via pass-through destruction if either target is a Marauder (rule 8).
- **Turn-state effect:** None directly.
- **Persistent duration:** None.
- **Zone-of-Protection interaction:** Own token: exempt (rule 12 carve-out). Opponent's
  token: blocked outright if in a Zone — not a "reduced effect," the move is illegal for
  that target.
- **Reprieve interaction:** Only via Marauder-mover pass-through, if a Marauder is one of
  the two chosen tokens — same shared destroy-tokens-passed logic as ordinary Marauder
  movement.
- **Can another card respond to it:** Precedence only, pre-resolution (Immediate).
- **Can it respond to another card:** No.
- **Annulment behavior:** Standard — cancels both moves as one unit.
- **Required engine state:** CardPlayRequest with two independent target slots (one
  self-constrained, one opponent-constrained), reusing the shared movement/landing
  resolution and the Marauder-pass-through helper when a Marauder is targeted.
- **Known interactions:** Shares the "moving a Marauder via a card = pass-through destroy"
  question (§4 Q4) with every other any-token movement card.
- **Rulebook citation:** rulebook.txt:561-569.
- **Ambiguities:** None beyond §4 Q4 (shared).

#### 9. Plasma Burst
- **Rarity/copies:** Single ×1
- **Immediate or Held:** Held
- **Scope:** Your Turn
- **Color restriction:** Red only
- **Precedence:** No
- **Legal targets:** Player chooses a Tier, then "3 neighboring squares" on that Tier — every
  token (any owner, any type) on those 3 squares is removed. Exact selection mechanism for
  "3 neighboring squares" is unconfirmed (see Ambiguities).
- **Affected token types:** Both.
- **Affected Tier(s):** Player's choice of one of the four.
- **Movement effect:** None.
- **Destruction/construction effect:** Destroy all tokens on 3 contiguous squares.
- **Turn-state effect:** None.
- **Persistent duration:** None.
- **Zone-of-Protection interaction:** Named exception (rule 12) — can destroy ZoP Tier
  tokens, implying the "3 neighboring squares" selection can include Zone squares under some
  circumstance (see Ambiguities — this is the strongest textual evidence that "neighboring"
  isn't purely main-loop-only).
- **Reprieve interaction:** Irrelevant (direct card-destroy, not pass-through).
- **Can another card respond to it:** Precedence only, pre-resolution.
- **Can it respond to another card:** No.
- **Annulment behavior:** Standard.
- **Required engine state:** CardPlayRequest with a Tier choice plus a 3-square-range
  selection (needs a "contiguous square range, wrapping the loop, possibly spanning into a
  Zone" primitive that doesn't exist yet), ZoneResidence.
- **Known interactions:** None named specifically.
- **Rulebook citation:** rulebook.txt:571-579, 433-438 (rule 12).
- **Ambiguities:** How exactly are "3 neighboring squares" chosen — any 3 consecutive
  main-loop squares the player picks freely, or neighboring some fixed reference point (the
  player's own token, the Fate-Harvest-draw square, etc.)? Can the selection span from the
  main loop into a Zone of Protection's own squares (which would explain the ZoP-exception
  clause), or is the exception clause just covering the case where the 3 *main-loop* squares
  happen to include the printed ZoP entry square itself (which nominally "has a Tier token
  on it" only in the instant of transit, not really applicable)? Flagged in §4 Q8 — this is
  one of the harder open questions in the whole audit.

#### 10. Verdant Growth
- **Rarity/copies:** Single ×1
- **Immediate or Held:** Held
- **Scope:** Your Turn
- **Color restriction:** Green only
- **Precedence:** No
- **Legal targets:** None to choose — fixed effect on Green's own tokens.
- **Affected token types:** Tier tokens.
- **Affected Tier(s):** 1st, 2nd, and 3rd simultaneously.
- **Movement effect:** None.
- **Destruction/construction effect:** Construct — start one new token on each of the 1st,
  2nd, and 3rd Tier Birth Canals (own tokens).
- **Turn-state effect:** None.
- **Persistent duration:** None.
- **Zone-of-Protection interaction:** None.
- **Reprieve interaction:** None.
- **Can another card respond to it:** Precedence only, pre-resolution.
- **Can it respond to another card:** No.
- **Annulment behavior:** Standard — cancels all three constructs as one unit.
- **Required engine state:** CardPlayRequest only; three independent `startToken()` calls
  (each respecting that Tier's own Ion-Battery/Hatchery capacity independently — a Tier
  being out of available tokens doesn't block the other two Tiers' constructs; see
  Ambiguities for the edge case).
- **Known interactions:** None named.
- **Rulebook citation:** rulebook.txt:581-589.
- **Ambiguities:** If Green has zero tokens left in a given Tier's Ion Battery *and* Hatchery
  (extremely unlikely given 8/6/4 tokens per Tier, but not impossible very late-game), what
  happens to that Tier's construct — is the whole card's effect partially applied (the other
  two Tiers still get their token) or does the impossible sub-effect void the whole card?
  Low-priority edge case, flagged rather than guessed.

---

### Doubles (×2 copies each)

#### 11. Infernal Abyss
- **Rarity/copies:** Double ×2
- **Immediate or Held:** Immediate
- **Scope:** n/a
- **Color restriction:** None
- **Precedence:** No
- **Legal targets:** One of your own tokens, any type, any Tier — **except** a Tier token
  currently inside a Zone of Protection.
- **Affected token types:** Both (Tier token or your own Marauder).
- **Affected Tier(s):** Player's choice.
- **Movement effect:** None.
- **Destruction/construction effect:** Self-destroy (voluntary "sacrifice") one own token.
- **Turn-state effect:** None.
- **Persistent duration:** None.
- **Zone-of-Protection interaction:** Explicitly cannot target your own ZoP token (unlike
  the 5-exception cards, this one is narrower than default — ZoP protection applies even
  though it's the owner's own choice, because sacrifice is still a "destroy").
- **Reprieve interaction:** Irrelevant (direct, own-choice destroy).
- **Can another card respond to it:** Precedence only, pre-resolution.
- **Can it respond to another card:** No.
- **Annulment behavior:** Standard.
- **Required engine state:** CardPlayRequest with a self-only, ZoP-excluded target list;
  ZoneResidence.
- **Known interactions:** None named. Requires at least one legal (non-ZoP) own token to
  exist, which is guaranteed by game setup (every player always has at least their starting
  1st Tier token unless it happens to be sitting in a Zone, which the 1st Tier's Zones make
  possible — a genuine edge case worth a test: a player whose *only* token is in a ZoP draws
  this card).
- **Rulebook citation:** rulebook.txt:595-601.
- **Ambiguities:** What happens if the drawing player's *only* existing token is currently
  inside a Zone of Protection (no legal target exists)? The card "must be played
  immediately" but has no legal target. Flagged in §4 Q9.

#### 12. Divine Assistance
- **Rarity/copies:** Double ×2
- **Immediate or Held:** Immediate
- **Scope:** n/a
- **Color restriction:** None
- **Precedence:** No
- **Legal targets:** Any one token, any type, any owner, any Tier, including tokens
  currently in a Staging Pile (not just in-play) — excluding the two unmodeled meta-tokens.
- **Affected token types:** Both.
- **Affected Tier(s):** Any.
- **Movement effect:** None.
- **Destruction/construction effect:** Destroy one chosen token, wherever it is (in-play or
  Staging Pile).
- **Turn-state effect:** None.
- **Persistent duration:** None.
- **Zone-of-Protection interaction:** Named exception (rule 12) — can destroy a ZoP Tier
  token.
- **Reprieve interaction:** Irrelevant (direct destroy).
- **Can another card respond to it:** Precedence only, pre-resolution.
- **Can it respond to another card:** No.
- **Annulment behavior:** Standard.
- **Required engine state:** CardPlayRequest with a target that can point into *either* the
  in-play position list *or* a Staging Pile count (a target-kind distinction the current
  `TokenRef`/`TokenKind` model doesn't have — it only models `TIER_TOKEN`/`MARAUDER` at an
  in-play position); StagingPileMutation ("remove one arbitrary token, no owner attached
  beyond the pile itself"); ZoneResidence.
- **Known interactions:** Shares the Staging-Pile-destroy primitive with Insidious Flux
  (which is narrower: player picks the *pile*, this card can pick any single token
  anywhere including a pile).
- **Rulebook citation:** rulebook.txt:603-609, 439-440 (rule 13), 433-438 (rule 12).
- **Ambiguities:** None on mechanics; implementation needs the target-model extension noted
  above (not a rules ambiguity, an engine-modeling one — see Phase B design notes).

#### 13. Planetary Nebula
- **Rarity/copies:** Double ×2
- **Immediate or Held:** Held
- **Scope:** Your Turn, **and specifically only during the 2nd Tier Phase** (PhaseRestriction — narrower than plain Your-Turn).
- **Color restriction:** None
- **Precedence:** No
- **Legal targets:** None to choose — fixed effect on own 2nd Tier.
- **Affected token types:** Tier token.
- **Affected Tier(s):** 2nd only.
- **Movement effect:** None.
- **Destruction/construction effect:** Construct — start one token on the 2nd Tier Birth
  Canal directly (bypassing the normal Staging-Pile-threshold promotion path).
- **Turn-state effect:** None.
- **Persistent duration:** None.
- **Zone-of-Protection interaction:** None.
- **Reprieve interaction:** None.
- **Can another card respond to it:** Precedence only, pre-resolution.
- **Can it respond to another card:** No.
- **Annulment behavior:** Standard.
- **Required engine state:** PhaseRestriction (the CardScope enum alone can't express
  "your turn of *this specific* Phase" — this is a genuine gap; see §4 Q7); reuses
  `startToken()` on the 2nd Tier pool.
- **Known interactions:** Name-adjacent to Luckier Nebula (also 2nd Tier) and to
  Lucky/Emitting Nebula (1st Tier) — four different "Nebula" cards with different Tiers,
  timings, and destinations (Birth Canal vs. Staging Pile); a naming/confusion risk for
  implementation, not a rules ambiguity (see §4 Q10).
- **Rulebook citation:** rulebook.txt:611-617.
- **Ambiguities:** None on the rule itself (the Phase-restriction is explicit in the
  rulebook text), only the engine-modeling gap noted above.

#### 14. Luckier Nebula
- **Rarity/copies:** Double ×2
- **Immediate or Held:** Immediate
- **Scope:** n/a
- **Color restriction:** None
- **Precedence:** No
- **Legal targets:** None to choose.
- **Affected token types:** Tier token.
- **Affected Tier(s):** 2nd only.
- **Movement effect:** None.
- **Destruction/construction effect:** Construct — add one token directly to the player's
  own 2nd Tier Staging Pile (not in-play, not via a Nebula landing).
- **Turn-state effect:** None.
- **Persistent duration:** None.
- **Zone-of-Protection interaction:** None.
- **Reprieve interaction:** None.
- **Can another card respond to it:** Precedence only, pre-resolution.
- **Can it respond to another card:** No.
- **Annulment behavior:** Standard.
- **Required engine state:** StagingPileMutation ("add one, then run the normal
  promotion-threshold check") — this must reuse the *same* promotion check
  `resolveTierLanding`'s Nebula case runs, not bypass it; adding a token directly to a pile
  that's already at or one below threshold should promote exactly as if it arrived via a
  Nebula square.
- **Known interactions:** See card 13's naming note (§4 Q10).
- **Rulebook citation:** rulebook.txt:619-624.
- **Ambiguities:** None on the rule; the promotion-check-must-fire-here point above is an
  implementation requirement, not an open rules question (deriving directly from "once
  there are enough tokens in the Staging Pile" being state-triggered, not
  landing-triggered).

#### 15. Essence Assimilator
- **Rarity/copies:** Double ×2
- **Immediate or Held:** Immediate
- **Scope:** n/a
- **Color restriction:** None
- **Precedence:** No
- **Legal targets:** None to choose.
- **Affected token types:** Marauder.
- **Affected Tier(s):** 1st only.
- **Movement effect:** None.
- **Destruction/construction effect:** Construct — place own Marauder on 1st Tier Birth
  Canal, bypassing cap.
- **Turn-state effect:** None.
- **Persistent duration:** None.
- **Zone-of-Protection interaction:** None.
- **Reprieve interaction:** None.
- **Can another card respond to it:** Precedence only, pre-resolution.
- **Can it respond to another card:** No.
- **Annulment behavior:** Standard.
- **Required engine state:** CardPlayRequest only.
- **Known interactions:** None named.
- **Rulebook citation:** rulebook.txt:626-631.
- **Ambiguities:** None.

#### 16. Skip, Hop, and Jump (Dimensional)
- **Rarity/copies:** Double ×2
- **Immediate or Held:** Held
- **Scope:** Your Turn
- **Color restriction:** None
- **Precedence:** No
- **Legal targets:** Any token, any owner, any type — opponent's token blocked if it's in a
  Zone of Protection (your own token in your own Zone remains legal per rule 12's carve-out).
- **Affected token types:** Both.
- **Affected Tier(s):** Wherever the chosen token is.
- **Movement effect:** Forward 3 spaces, full landing resolution.
- **Destruction/construction effect:** Indirect only, via landing or Marauder pass-through
  (rule 8).
- **Turn-state effect:** None directly.
- **Persistent duration:** None.
- **Zone-of-Protection interaction:** As above.
- **Reprieve interaction:** Only via Marauder-mover pass-through.
- **Can another card respond to it:** Precedence only, pre-resolution.
- **Can it respond to another card:** No.
- **Annulment behavior:** Standard.
- **Required engine state:** Same generic "any-token movement card" shape as Parallel
  Phasing/Tactical Motion/Evasive Action/Tactical Step/Sidestep — should share one
  implementation parameterized by (distance, timing, scope, precedence, self-only?) rather
  than 6+ near-duplicate card classes.
- **Known interactions:** §4 Q4 (Marauder-mover pass-through ownership question), shared.
- **Rulebook citation:** rulebook.txt:633-643.
- **Ambiguities:** None beyond the shared Q4.

#### 17. Materialize Help
- **Rarity/copies:** Double ×2
- **Immediate or Held:** Immediate
- **Scope:** n/a
- **Color restriction:** None
- **Precedence:** No
- **Legal targets:** None to choose.
- **Affected token types:** Marauder.
- **Affected Tier(s):** 3rd only.
- **Movement effect:** None.
- **Destruction/construction effect:** Construct — place own Marauder on 3rd Tier Birth
  Canal, bypassing cap.
- **Turn-state effect:** None.
- **Persistent duration:** None.
- **Zone-of-Protection interaction:** None.
- **Reprieve interaction:** None.
- **Can another card respond to it:** Precedence only, pre-resolution.
- **Can it respond to another card:** No.
- **Annulment behavior:** Standard.
- **Required engine state:** CardPlayRequest only.
- **Known interactions:** None named.
- **Rulebook citation:** rulebook.txt:645-651.
- **Ambiguities:** None.

#### 18. Tactical Motion
- **Rarity/copies:** Double ×2
- **Immediate or Held:** Held
- **Scope:** Any Time
- **Color restriction:** None
- **Precedence:** **Yes** (Group 5 card)
- **Legal targets:** Any token, any owner, any type — opponent's ZoP token blocked, own ZoP
  token legal.
- **Affected token types:** Both.
- **Affected Tier(s):** Wherever the target is.
- **Movement effect:** Forward 2 spaces, full landing resolution.
- **Destruction/construction effect:** Indirect only (landing, or Marauder pass-through).
- **Turn-state effect:** None directly.
- **Persistent duration:** None.
- **Zone-of-Protection interaction:** As card 16.
- **Reprieve interaction:** Only via Marauder-mover pass-through.
- **Can another card respond to it:** Yes (Precedence).
- **Can it respond to another card:** Yes (Precedence + Any Time) — this is the archetypal
  "rescue a token from an about-to-resolve destroy/pass-through" card the rule-23 example
  describes.
- **Annulment behavior:** Standard chain position.
- **Required engine state:** InteractionChain; otherwise the same generic movement-card
  shape as card 16, but with Precedence's interrupt capability layered on top.
- **Known interactions:** Directly the rule-23 worked example
  ("you play a movement card that has Precedence... you can move a Tier token to safety
  that would have otherwise been destroyed by the Marauder") — needs an explicit test
  reproducing that exact scenario.
- **Rulebook citation:** rulebook.txt:653-662, 477-484 (rule 23).
- **Ambiguities:** §4 Q4 shared; otherwise none.

#### 19. Insidious Flux
- **Rarity/copies:** Double ×2
- **Immediate or Held:** Held
- **Scope:** Your Turn
- **Color restriction:** None
- **Precedence:** No
- **Legal targets:** Choose a Staging Pile (any Tier, any owner — not restricted to your
  own), then one arbitrary token within it is destroyed.
- **Affected token types:** Tier token (Staging Pile contents are always Tier tokens, never
  Marauders — Marauders have no Staging Pile).
- **Affected Tier(s):** Player's choice of any of the four.
- **Movement effect:** None.
- **Destruction/construction effect:** Destroy one token from a chosen Staging Pile.
- **Turn-state effect:** None.
- **Persistent duration:** None.
- **Zone-of-Protection interaction:** None (Staging Piles aren't board positions).
- **Reprieve interaction:** None.
- **Can another card respond to it:** Precedence only, pre-resolution.
- **Can it respond to another card:** No.
- **Annulment behavior:** Standard.
- **Required engine state:** StagingPileMutation ("remove one arbitrary, no promotion
  check needed since the count only goes down").
- **Known interactions:** Shares the Staging-Pile-destroy primitive with Divine Assistance
  (narrower here: player picks the *pile* first, not an arbitrary token anywhere).
- **Rulebook citation:** rulebook.txt:664-669.
- **Ambiguities:** None.

#### 20. Phase Loss
- **Rarity/copies:** Double ×2
- **Immediate or Held:** Immediate
- **Scope:** n/a
- **Color restriction:** None
- **Precedence:** No
- **Legal targets:** None to choose — always targets the drawing player, on the Tier whose
  Phase is currently active (the Tier of the Fate Harvest square just landed on).
- **Affected token types:** n/a (turn-state, not token-state).
- **Affected Tier(s):** The one Tier whose turn triggered the draw.
- **Movement effect:** None.
- **Destruction/construction effect:** None.
- **Turn-state effect:** Deferred — the player skips their *next* turn on that specific
  Tier only (not this Round's remaining Phases, not other Tiers). Identical mechanic to the
  "Lose next turn on this Tier" Time Wrinkle square (1st Tier board, index 5).
- **Persistent duration:** Single-shot deferred flag, consumed the next time that
  (player, Tier) would otherwise get a turn.
- **Zone-of-Protection interaction:** None.
- **Reprieve interaction:** None.
- **Can another card respond to it:** Precedence only, pre-resolution.
- **Can it respond to another card:** No.
- **Annulment behavior:** Standard — cancels the deferred skip before it's ever queued.
- **Required engine state:** DeferredTurnModifier: `SkipNextTierTurn(player, tier)`,
  applied wherever `TurnOrder.turnsFor` (or its caller) builds the next turn queue for that
  Tier — must consume-and-clear the flag rather than re-skip every subsequent turn.
- **Known interactions:** Shares its exact mechanic with the "Lose next turn on this Tier"
  Time Wrinkle square — implement once, use from both trigger sites.
- **Rulebook citation:** rulebook.txt:671-678; `BoardLayouts.kt:62` for the matching Time
  Wrinkle square text.
- **Ambiguities:** None on the rule itself.

---

### Triples (×3 copies each)

#### 21. Annulment (Antimatter)
- **Rarity/copies:** Triple ×3
- **Immediate or Held:** Held
- **Scope:** Any Time (via rule 20, not its own printed text — see the existing code comment
  in `FateHarvestCatalog.kt`, already correct, don't "fix" it).
- **Color restriction:** None
- **Precedence:** **Yes** — the special case (Group 5, but mechanically unique within it)
- **Legal targets:** The single card immediately preceding it in the current play sequence
  (not a free choice of "any card ever played" — "immediately before" is load-bearing, see
  rule 22).
- **Affected token types:** n/a directly — cancels whatever the targeted card would have
  affected.
- **Affected Tier(s):** n/a directly (inherits from the cancelled card).
- **Movement effect:** None of its own.
- **Destruction/construction effect:** None of its own — it prevents the *targeted* card's
  effect (whatever that was) from happening at all.
- **Turn-state effect:** None of its own.
- **Persistent duration:** None (one-shot cancellation).
- **Zone-of-Protection interaction:** None of its own.
- **Reprieve interaction:** None of its own.
- **Can another card respond to it:** Yes — another Precedence card (including a second
  Annulment) can be played after it, per the normal LIFO chain; per rule 22, if *that*
  second card is itself an Annulment, it cancels the first Annulment (its immediate
  predecessor), which per canon means "the resulting chain behaves according to the
  rulebook's Annulment rule" recursively — see §3.2's worked examples, including the
  double-Annulment case.
- **Can it respond to another card:** Yes — this is its entire purpose; it specifically
  targets "the card played immediately before it," so it must be played as a response within
  an already-open interaction window (a card must exist to cancel).
- **Annulment behavior:** *Is* the Annulment behavior — see §3.2 for the full state-machine
  description: it removes the card immediately before it from the chain, and every
  Precedence card played after it (if any) resolves as though both the cancelled card and
  this Annulment never existed — i.e., their card-play stack entries are spliced out, not
  merely "no-opped in place," which matters for what "immediately before/after" means for a
  *third* card added after this Annulment.
- **Required engine state:** The full InteractionChain/PrecedenceWindow design from Phase C,
  with the splice-not-noop semantics from §3.2. This is the one card that cannot be
  implemented as "yet another effect resolver" — it's a structural operation on the chain
  itself.
- **Known interactions:** Interacts with every one of the other 5 Precedence cards
  (Graviton Rift, Fluidic Wave, Tactical Motion, Tactical Step, Last Gasp) as a potential
  target or responder; also interacts with itself (chained Annulments).
- **Rulebook citation:** rulebook.txt:684-693, 469-476 (rule 22, the load-bearing one),
  461-468 (rules 20-21).
- **Ambiguities:** Per confirmed canon, "no turn may be repeated more than once from a
  single triggering effect" bounds *turn-repetition* chains but says nothing about bounding
  *Annulment* chains — is there any limit on how many Annulments can be played in sequence,
  each cancelling the previous Annulment (a "does Annulment cancel Annulment cancelling
  Annulment..." ladder)? The rulebook text describes only the 2-card case explicitly (rule
  22's own wording). §4 Q11.

#### 22. Evasive Action
- **Rarity/copies:** Triple ×3
- **Immediate or Held:** Immediate
- **Scope:** n/a
- **Color restriction:** None
- **Precedence:** No
- **Legal targets:** Any token, any owner, any type — opponent's ZoP token blocked, own ZoP
  token legal.
- **Affected token types:** Both.
- **Affected Tier(s):** Wherever the target is.
- **Movement effect:** Forward 2 spaces, full landing resolution.
- **Destruction/construction effect:** Indirect only.
- **Turn-state effect:** None directly.
- **Persistent duration:** None.
- **Zone-of-Protection interaction:** As card 16.
- **Reprieve interaction:** Only via Marauder-mover pass-through.
- **Can another card respond to it:** Precedence only, pre-resolution (Immediate).
- **Can it respond to another card:** No.
- **Annulment behavior:** Standard.
- **Required engine state:** Same generic movement-card shape as card 16, Immediate variant
  (mandatory target choice at draw time, no hand-holding period).
- **Known interactions:** §4 Q4 shared. Mechanically identical distance/target shape to
  Tactical Motion, differing only in Immediate-vs-Held/Any-Time-vs-Precedence — an easy pair
  to accidentally cross-wire in implementation; needs a test distinguishing them explicitly.
- **Rulebook citation:** rulebook.txt:695-704.
- **Ambiguities:** None beyond the shared Q4.

#### 23. Lucky Nebula
- **Rarity/copies:** Triple ×3
- **Immediate or Held:** Immediate
- **Scope:** n/a
- **Color restriction:** None
- **Precedence:** No
- **Legal targets:** None to choose.
- **Affected token types:** Tier token.
- **Affected Tier(s):** 1st only.
- **Movement effect:** None.
- **Destruction/construction effect:** Construct — add one token directly to own 1st Tier
  Staging Pile (with promotion check, same as Luckier Nebula's reasoning).
- **Turn-state effect:** None.
- **Persistent duration:** None.
- **Zone-of-Protection interaction:** None.
- **Reprieve interaction:** None.
- **Can another card respond to it:** Precedence only, pre-resolution.
- **Can it respond to another card:** No.
- **Annulment behavior:** Standard.
- **Required engine state:** Same StagingPileMutation as Luckier Nebula, targeting the 1st
  Tier pool instead of the 2nd.
- **Known interactions:** **Name collision risk** with Luckier Nebula ("Lucky" vs.
  "Luckier") — different rarity (×3 vs ×2), different Tier (1st vs 2nd), same timing
  (Immediate), same mechanical shape. Flagged explicitly so implementation doesn't
  transpose them; §4 Q10.
- **Rulebook citation:** rulebook.txt:706-711.
- **Ambiguities:** None on the rule.

#### 24. Tactical Step
- **Rarity/copies:** Triple ×3
- **Immediate or Held:** Held
- **Scope:** Any Time
- **Color restriction:** None
- **Precedence:** **Yes** (Group 5 card)
- **Legal targets:** Any token, any owner, any type — opponent's ZoP token blocked, own ZoP
  token legal.
- **Affected token types:** Both.
- **Affected Tier(s):** Wherever the target is.
- **Movement effect:** Forward 1 space, full landing resolution.
- **Destruction/construction effect:** Indirect only.
- **Turn-state effect:** None directly.
- **Persistent duration:** None.
- **Zone-of-Protection interaction:** As card 16.
- **Reprieve interaction:** Only via Marauder-mover pass-through.
- **Can another card respond to it:** Yes (Precedence).
- **Can it respond to another card:** Yes (Precedence + Any Time) — the smallest-magnitude
  member of the "rescue/nudge" Precedence family (1 space, vs. Tactical Motion's 2), still
  enough to move a token off a square about to be affected, or to complete an exact
  You-Win landing by exactly 1.
- **Annulment behavior:** Standard chain position.
- **Required engine state:** InteractionChain; generic movement-card shape with Precedence.
- **Known interactions:** Same family as Tactical Motion — the two differ *only* in
  distance (1 vs. 2); both need independent tests since "smallest distance card can complete
  an exact landing" is exactly the kind of edge case Phase I's victory-timing tests must
  cover.
- **Rulebook citation:** rulebook.txt:713-720.
- **Ambiguities:** §4 Q4 shared; otherwise none.

#### 25. Elemental Rebirth
- **Rarity/copies:** Triple ×3
- **Immediate or Held:** Immediate
- **Scope:** n/a
- **Color restriction:** None
- **Precedence:** No
- **Legal targets:** None to choose (assumed self — see Ambiguities).
- **Affected token types:** Tier token.
- **Affected Tier(s):** 1st only.
- **Movement effect:** None.
- **Destruction/construction effect:** Construct — start one token on the 1st Tier Birth
  Canal.
- **Turn-state effect:** None.
- **Persistent duration:** None.
- **Zone-of-Protection interaction:** None.
- **Reprieve interaction:** None.
- **Can another card respond to it:** Precedence only, pre-resolution.
- **Can it respond to another card:** No.
- **Annulment behavior:** Standard.
- **Required engine state:** CardPlayRequest only; reuses `startToken()` on the 1st Tier
  pool.
- **Known interactions:** None named.
- **Rulebook citation:** rulebook.txt:722-727.
- **Ambiguities:** The card text says "Place *a* token," not "your token" — unlike every
  other single-Tier construct card, which is more explicit ("start a new token on the 1st,
  2nd, and 3rd Tier Birth Canals" for Verdant Growth, "Yellow player... starts a new token"
  for Corpuscle Rot). High-confidence default is self (own token), consistent with the rest
  of the construct-card family, but flagged since it's the one card in that family phrased
  fully impersonally. §4 Q3 (grouped with Corpuscle Rot's similar "any"-token ambiguity).

#### 26. Cleansing (Atmospheric)
- **Rarity/copies:** Triple ×3
- **Immediate or Held:** Held
- **Scope:** Your Turn
- **Color restriction:** None
- **Precedence:** No
- **Legal targets:** One opponent (chosen by the player); that opponent then chooses *which
  of their own held cards* to discard — a genuine two-step decision spanning two different
  players.
- **Affected token types:** n/a (hand/card state, not token state).
- **Affected Tier(s):** n/a.
- **Movement effect:** None.
- **Destruction/construction effect:** None (card-hand effect, not board effect).
- **Turn-state effect:** None.
- **Persistent duration:** None (one-shot, but its resolution can't complete without a
  second player's input — see Required engine state).
- **Zone-of-Protection interaction:** None.
- **Reprieve interaction:** None.
- **Can another card respond to it:** Precedence only, pre-resolution.
- **Can it respond to another card:** No.
- **Annulment behavior:** Standard — cancels the whole choose-an-opponent-and-force-discard
  effect before the opponent ever has to pick.
- **Required engine state:** HandDiscardRequest — a pending-decision abstraction *owned by
  the targeted opponent*, not the card's player; structurally similar to (but distinct from)
  the Precedence response window, since Cleansing itself isn't Precedence and this isn't a
  response to anything, just an effect that can't complete synchronously. The interaction
  engine (Phase C) should generalize "pending decision, owner = some other player" so this
  and Precedence responses share the same underlying primitive instead of two parallel
  systems.
- **Known interactions:** None named.
- **Rulebook citation:** rulebook.txt:729-735.
- **Ambiguities:** What happens if the targeted opponent's hand is empty (nothing to
  discard)? No-op is the obvious reading (there's nothing to force), but flagged since the
  rulebook doesn't address it explicitly. §4 Q12.

#### 27. Delayed Motion
- **Rarity/copies:** Triple ×3
- **Immediate or Held:** Held
- **Scope:** Neither Your-Turn nor Any-Time in the usual sense — a bespoke third timing
  window: **"must be played after your die roll but before you move the token."**
- **Color restriction:** None
- **Precedence:** No
- **Legal targets:** None to choose — applies to the roll that was just made, by the player
  whose roll it is (implicitly self — this only makes sense played on your own pending
  move).
- **Affected token types:** n/a directly (modifies the roll, not a token).
- **Affected Tier(s):** n/a directly.
- **Movement effect:** Indirect — adds +2 to the die roll that will be used for the pending
  movement.
- **Destruction/construction effect:** None.
- **Turn-state effect:** None beyond modifying the in-progress roll.
- **Persistent duration:** None (one-shot, scoped entirely to one roll→move step).
- **Zone-of-Protection interaction:** None directly (though +2 could change whether a move
  lands exactly on a ZoP entry square, an ordinary consequence of any movement modifier).
- **Reprieve interaction:** None.
- **Can another card respond to it:** Precedence only, and only within that same narrow
  post-roll/pre-move window.
- **Can it respond to another card:** No — it isn't Precedence, and its own timing window is
  itself the interesting constraint, not a response to another card.
- **Annulment behavior:** Standard — if Annulled, the roll reverts to its un-modified value.
- **Required engine state:** A genuine engine gap: `TurnEngine` currently takes a roll result
  and a move distance as one atomic step (`moveTierToken(..., spaces: Int)`), with no
  explicit "roll has happened, movement hasn't yet" checkpoint a card could hook into. Needs
  a PendingRoll state (Phase B/D) — roll → PendingRoll(dieValue) → [cards may modify it] →
  move(PendingRoll.total). This is infrastructure every other movement-affecting-the-roll
  card would also need, even though Delayed Motion is currently the only card that does this
  (no other card modifies a roll rather than moving a token post-hoc).
- **Known interactions:** None named — but by construction, this is the *only* card whose
  timing window sits strictly between "roll" and "move," which is exactly the gap
  `TurnEngine`'s current single-step API can't represent; a regression test should prove the
  modified roll is what actually gets used for movement (easy to get wrong if the roll and
  the move end up decoupled in the implementation).
- **Rulebook citation:** rulebook.txt:737-745.
- **Ambiguities:** Can this be played on *any* player's roll, or only your own upcoming
  move (i.e., can a card that only makes sense "before you move the token" be played by
  someone other than the mover, the way Precedence cards routinely respond to other players'
  actions)? Rule 20's "supersedes token movements" language is about Precedence cards
  specifically, and this card has no Precedence flag — so by default it should only be
  playable during *your own* pending roll→move step, but "your turn" isn't explicitly
  restated for this card's scope the way it is for plainer Your-Turn cards. Flagged, §4 Q13.

#### 28. Emitting Nebula
- **Rarity/copies:** Triple ×3
- **Immediate or Held:** Held
- **Scope:** Your Turn, **and specifically only during the 1st Tier Phase** (PhaseRestriction, same shape as Planetary Nebula).
- **Color restriction:** None
- **Precedence:** No
- **Legal targets:** None to choose.
- **Affected token types:** Tier token.
- **Affected Tier(s):** 1st only.
- **Movement effect:** None.
- **Destruction/construction effect:** Construct — add one token directly to own 1st Tier
  Staging Pile (with promotion check).
- **Turn-state effect:** None.
- **Persistent duration:** None.
- **Zone-of-Protection interaction:** None.
- **Reprieve interaction:** None.
- **Can another card respond to it:** Precedence only, pre-resolution.
- **Can it respond to another card:** No.
- **Annulment behavior:** Standard.
- **Required engine state:** PhaseRestriction (shared with Planetary Nebula); same
  StagingPileMutation as Lucky Nebula, targeting the same pool — the two 1st-Tier
  Staging-Pile-add cards (Lucky Nebula, Emitting Nebula) differ only in Immediate-vs-Held
  and the added Phase restriction, otherwise identical mechanically.
- **Known interactions:** Same naming-family risk as card 23 (§4 Q10) — four "Nebula" cards
  total across this matrix (Planetary, Luckier, Lucky, Emitting), each subtly different.
- **Rulebook citation:** rulebook.txt:747-753.
- **Ambiguities:** None on the rule.

---

### Quadruples (×4 copies each)

#### 29. Last Gasp
- **Rarity/copies:** Quadruple ×4
- **Immediate or Held:** Held
- **Scope:** Any Time
- **Color restriction:** None
- **Precedence:** **Yes** (Group 5 card)
- **Legal targets:** One of *your own* tokens only, any type — this is the mover; everything
  else the 8-space path touches is affected as a side effect, not a separate target choice.
- **Affected token types:** Both (as mover: your own, any type; as pass-through victims:
  potentially any type, any owner — see Ambiguities).
- **Affected Tier(s):** Wherever your chosen token is.
- **Movement effect:** Forward 8 spaces.
- **Destruction/construction effect:** The single most destructive card in the deck —
  destroys every token passed along the 8-space path (**not** just opponents' — the card
  text says "any tokens you pass," with no "yours are immune" clause, unlike the general
  Marauder-pass-through rule; see Ambiguities), *and* destroys the moved token itself once
  it arrives, except anything (moved token or passed token) sitting in a Zone of Protection.
  This is one of only two ways (with the Hyperthrust square) a Tier token's movement can
  destroy a Marauder (rule 10).
- **Turn-state effect:** None.
- **Persistent duration:** None (one-shot, but the single largest-blast-radius movement
  effect in the game).
- **Zone-of-Protection interaction:** Explicitly protects — "except any tokens in the Zone
  of Protection, which are protected" — covers both pass-through victims and the mover
  itself if the mover's own path *starts or passes through* a Zone (the mover's final
  position, if it lands inside a Zone, would also be protected from the "moved token is also
  destroyed" clause — see Ambiguities for whether landing exactly in a Zone is even possible
  given Zones aren't dice-driven main-loop squares).
- **Reprieve interaction:** Reprieve protects Tier tokens from this card's pass-through
  destruction (confirmed canon: "applying to Marauder pass-through and Hyperthrust's
  identically-worded pass-through-destroy alike" — Last Gasp's pass-through is the same
  category of effect and should be covered by the same Reprieve check, even though the
  rulebook's Reprieve paragraph itself only explicitly names Marauders). Reprieve does NOT
  protect the mover from the "moved token is also destroyed" clause — that isn't a
  pass-through, it's a direct self-destruction the card causes on its own mover.
- **Can another card respond to it:** Yes (Precedence).
- **Can it respond to another card:** Yes (Precedence + Any Time).
- **Annulment behavior:** Standard chain position — cancels the entire move + all
  destruction as one unit.
- **Required engine state:** InteractionChain; the shared Marauder-pass-through-destroy path
  extended to cover Tier-token movers too (currently `destroyTokensPassed` is only called
  from Marauder movement and Hyperthrust — Last Gasp needs the same "destroy everything
  strictly between from/to" helper applied to a *Tier token's* 8-space move, which the
  current code structure doesn't do for any other Tier-token movement); a flag for "does the
  mover's own tokens get exempted," pending §4 Q14.
- **Known interactions:** The only card allowed to destroy a Marauder via non-Hyperthrust
  Tier-token movement (rule 10) — needs an explicit test proving *only* this card and the
  Hyperthrust square can do that, and that no other movement card (even a
  Precedence-flagged one like Tactical Motion) can.
- **Rulebook citation:** rulebook.txt:759-768, 428-429 (rule 10).
- **Ambiguities:** Does "any tokens you pass are destroyed" include the mover's *own* other
  tokens caught in the 8-space path, or is the general Marauder-pass-through "yours are
  immune" exemption implicitly still in force here too? The card text's wording is
  meaningfully different from every other pass-through rule in the rulebook (it omits the
  usual owner-exemption clause entirely, right where it explicitly calls out the ZoP
  exemption instead) — this materially changes the card's risk/reward and needs explicit
  user confirmation rather than inferring the general rule applies. §4 Q14 (flagged as
  high-priority, since it blocks correctly implementing this card at all).

#### 30. Phase Control
- **Rarity/copies:** Quadruple ×4
- **Immediate or Held:** Immediate
- **Scope:** n/a
- **Color restriction:** None
- **Precedence:** No
- **Legal targets:** Player chooses any one of the four Tiers (not necessarily the Tier
  they're currently playing).
- **Affected token types:** n/a (turn-state).
- **Affected Tier(s):** Player's choice.
- **Movement effect:** None directly (grants another turn, during which normal
  roll-and-move happens).
- **Destruction/construction effect:** None.
- **Turn-state effect:** Deferred extra turn on the chosen Tier — two sub-cases: (a) if that
  Tier's Phase turn for this player hasn't happened yet this Round, queue the extra turn to
  fire immediately after the normal one; (b) if that Tier's Phase already completed for this
  player this Round, grant the extra turn *immediately*, out of the normal Phase sequence
  entirely.
- **Persistent duration:** Single-shot deferred/immediate grant, bounded to exactly one
  extra turn per play (per confirmed canon: "no turn may be repeated more than once from a
  single triggering effect" — this card's own text independently confirms the same bound:
  "an extra turn... taken after your normal turn," singular).
- **Zone-of-Protection interaction:** None.
- **Reprieve interaction:** None.
- **Can another card respond to it:** Precedence only, pre-resolution.
- **Can it respond to another card:** No.
- **Annulment behavior:** Standard — cancels the grant before it's queued (or before the
  immediate out-of-sequence turn is taken, for case (b), if Annulled fast enough — see
  Ambiguities for the case-(b) timing edge).
- **Required engine state:** DeferredTurnModifier: `ExtraTierTurn(player, tier)` for case
  (a); an "insert an out-of-sequence turn right now" mechanism for case (b), which is a
  different code path from `GameState.endTurn(grantAnotherTurn = true)` (that mechanism
  keeps the *same* player active on the *current* Phase — this card can grant a turn on a
  *different, already-completed* Phase mid-Round, which the current turn-queue model has no
  way to express at all).
- **Known interactions:** Shares its "extra turn" mechanic conceptually with the "Take an
  extra turn, First Tier" Time Wrinkle square (2nd Tier board, index 7) — but that square is
  *hardcoded* to always grant a 1st Tier extra turn regardless of which Tier you're
  currently on, unlike this card's free Tier choice; both need the same
  `ExtraTierTurn(player, tier)` primitive, parameterized differently.
- **Rulebook citation:** rulebook.txt:770-777; `BoardLayouts.kt:163` for the Time Wrinkle
  analog.
- **Ambiguities:** For case (b) ("if your turn on that Tier already ended this Round, play it
  immediately") — does "immediately" mean literally interrupting whatever's currently
  resolving (jumping the active player queue right now), or does it mean "at the next
  opportunity, out of normal order, but still after the currently-resolving action
  finishes"? Given this card is Immediate (not Precedence), per confirmed canon
  ("Precedence does not interrupt an Immediate effect already resolving" — and conversely, a
  non-Precedence Immediate effect shouldn't get to interrupt an in-progress action either),
  the more consistent reading is the latter (queued to happen right after the current
  action, not a true interrupt) — flagged for confirmation rather than assumed. §4 Q15.

#### 31. Circulate (Elemental)
- **Rarity/copies:** Quadruple ×4
- **Immediate or Held:** Held
- **Scope:** Your Turn
- **Color restriction:** None
- **Precedence:** No
- **Legal targets:** Any Tier token (Marauders excluded — they can't enter a Zone anyway);
  owner unqualified — see Ambiguities for whether this can target an opponent's token.
- **Affected token types:** Tier token only.
- **Affected Tier(s):** Wherever the target is.
- **Movement effect:** Teleport directly into "the next Zone of Protection on that Tier,"
  found by walking forward from the token's current position to the nearest upcoming Zone
  entry, wrapping the loop if needed — **not** ordinary space-by-space movement, so no
  intermediate landing effects resolve and (per the general shape of Zone entry) no
  pass-through destruction applies either.
- **Destruction/construction effect:** None directly.
- **Turn-state effect:** None.
- **Persistent duration:** None (one-shot teleport); the resulting Zone residence itself is
  ongoing state (Phase G), same as reaching a Zone by ordinary dice movement.
- **Zone-of-Protection interaction:** This card's entire purpose is Zone entry — it's the
  clearest example of why Phase G's Zone-residence state needs to be a real, addressable
  target (find "the next Zone entry square, given a current position" requires board-aware
  lookup logic that doesn't exist yet, since Zones aren't traversed by ordinary movement).
- **Reprieve interaction:** None.
- **Can another card respond to it:** Precedence only, pre-resolution.
- **Can it respond to another card:** No.
- **Annulment behavior:** Standard.
- **Required engine state:** A `nextZoneEntry(tier, fromPosition): Square` board query;
  ZoneResidence to record the resulting protected state.
- **Known interactions:** None named.
- **Rulebook citation:** rulebook.txt:779-784.
- **Ambiguities:** (1) Can this target an opponent's Tier token (moving them into safety,
  seemingly always a "helpful" effect regardless of who plays it), or is it implicitly
  self-only like most other "your turn" positioning cards? (2) If the chosen token is
  already inside a Zone, does "the next Zone of Protection" mean the *following* Zone
  further around the loop (skipping the current one), or is this an illegal/no-op play?
  §4 Q16.

#### 32. Sidestep (Extinction Avoidance)
- **Rarity/copies:** Quadruple ×4
- **Immediate or Held:** Held
- **Scope:** Your Turn
- **Color restriction:** None
- **Precedence:** No
- **Legal targets:** Any token, any owner, any type — opponent's ZoP token blocked, own ZoP
  token legal.
- **Affected token types:** Both.
- **Affected Tier(s):** Wherever the target is.
- **Movement effect:** Forward 1 space, full landing resolution.
- **Destruction/construction effect:** Indirect only.
- **Turn-state effect:** None directly.
- **Persistent duration:** None.
- **Zone-of-Protection interaction:** As card 16.
- **Reprieve interaction:** Only via Marauder-mover pass-through.
- **Can another card respond to it:** Precedence only, pre-resolution.
- **Can it respond to another card:** No.
- **Annulment behavior:** Standard.
- **Required engine state:** Same generic movement-card shape as card 16, distance 1 —
  mechanically identical to Tactical Step except Held/Your-Turn/no-Precedence instead of
  Held/Any-Time/Precedence. Another pair (with Tactical Step) that's easy to cross-wire;
  needs distinguishing tests.
- **Known interactions:** §4 Q4 shared.
- **Rulebook citation:** rulebook.txt:786-793.
- **Ambiguities:** None beyond the shared Q4.

---

## 3. Cross-cutting mechanical systems required

### 3.1 The Precedence interaction chain

Six cards need this (Group 5): Graviton Rift, Fluidic Wave, Tactical Motion, Annulment,
Tactical Step, Last Gasp. Per rules 20-23:

1. Some triggering event becomes **pending** — either a normal action about to resolve (a
   die roll about to become a move, a card about to resolve) or nothing in particular
   (Precedence cards can just be played proactively on someone's turn).
2. Playing a Precedence card announces "Precedence!" and **opens or extends an interaction
   window**: the player whose action is pending stops; all players capable of responding
   (in practice, anyone holding an eligible Precedence card) get the opportunity in turn
   order.
3. Each eligible player either **passes** or **adds another Precedence card** to the chain.
4. The window stays open as long as the most recent action was a new Precedence card play;
   it **closes** once everyone eligible has passed in succession.
5. Once closed, the chain resolves in **reverse order of play** (rule 22) — except any
   Annulment in the chain, which splices out its immediately-preceding card (see §3.2).
6. After the chain fully resolves, control **returns to the suspended underlying action**
   (the die roll gets moved, or the original non-Precedence card resolves next, having
   possibly been invalidated by what the chain did — e.g. its target no longer exists).

This must be a real state machine (per the task's explicit instruction), not
`List<Card>` + reverse iteration, because the eventual UI/network layer needs to answer, at
any point: who's currently allowed to act, what they're responding to, whether they've
passed, whether the window is still open, and what the chain currently contains. A rough
shape (final naming decided in Phase C):

```
InteractionChain(
    suspendedAction: SuspendedAction,       // the roll/move/card this chain is blocking
    entries: List<ChainEntry>,              // in play order
    passedPlayers: Set<PlayerColor>,        // consecutive passes since the last new entry
    eligiblePlayers: List<PlayerColor>,     // turn-order-derived response order
    state: OPEN | RESOLVING | CLOSED,
)
ChainEntry(player: PlayerColor, card: FateHarvestCard, target: EffectTarget?)
```

### 3.2 Annulment's splice semantics, worked

Per canon: "Annulment cancels the immediately preceding applicable card, with the resulting
chain behaving according to the rulebook's Annulment rule." Concretely, for a chain played
in order `[A, B, C, D]` where `D` is Annulment:

- `D` (Annulment) cancels `C` (its immediate predecessor). Per rule 22: "other cards played
  after it are played as if that card and Annulment didn't exist." So the *effective* chain
  becomes `[A, B]` — both `C` and `D` are removed, not just "skipped."
- This matters because "played after it" for a hypothetical 5th card `E` refers to *E's*
  position relative to the *original* play order (E was played after D), but once resolved,
  E's own reverse-order resolution position is computed against the spliced chain `[A, B,
  E]`, not `[A, B, C, D, E]` — i.e., Annulment's removal happens *before* final reverse-order
  resolution is computed, not as a separate pass afterward.
- **Double Annulment**: `[A, B]` where `B` is Annulment targeting `A` (also Annulment) —
  `B` cancels `A`. If `A` itself was cancelling some earlier card `X` in a longer chain
  `[X, A, B]`, then per the splice rule, cancelling `A` means `A` "didn't exist" — so does
  `X` get restored (since the thing that cancelled it is now itself gone), or does `X` stay
  cancelled (Annulment's cancellation being a one-way, permanent removal from the chain once
  applied)? **This is not explicitly answered by the rulebook text** and needs a user
  ruling — see §4 Q11. The engine's chain-splice implementation must be built so this
  question has one clear, testable answer once resolved, not left as emergent/undefined
  behavior of whatever data structure happens to get chosen.
- Annulment targeting a **non-Precedence** card: the rulebook doesn't restrict Annulment's
  target to *other Precedence cards* — "cancel the effect of any card that is played" (its
  own text) plausibly includes cancelling a plain Immediate or Held card a player just
  played (not necessarily inside a Precedence chain at all, since rule 20 says all
  Precedence cards, Annulment included, can be played "at any time" — including as a
  same-instant response to *any* card play, Precedence or not). The engine should treat
  Annulment's legal target as "the most recently resolved-or-resolving card play, of any
  kind," not "the most recent Precedence-chain entry" — a narrower reading that would
  under-implement the card.

### 3.3 Zone of Protection as real state (Phase G)

Already partially designed by the existing `ProtectionZone`/`TierBoard` data (see
`board/TierBoard.kt`), but not yet tracked as *token* state. Needs, per-Tier-token:

- Whether it's on the main loop or inside a specific numbered Zone.
- If inside a Zone, which Zone (there can be more than one per Tier, e.g. 1st Tier's Zones 1
  and 2).
- The Marauder Transport-in-Zone exception (4th Tier's Zone 5, 1st Tier's Zone 2's Wormhole
  of Construction slot): a Marauder *can* be on that specific square without breaking "a
  Marauder cannot enter the Zone of Protection," because per the confirmed board data it's
  the *same physical square* as a main-loop Marauder Transport, not a real Zone entry for
  Marauders. Landing there via ordinary Marauder movement is not "entering the Zone."
- The 5 named card exceptions (rule 12) that can still reach into a Zone: Divine Assistance,
  Corpuscle Rot, Galactic Roundabout, Plasma Burst, Graviton Rift.
- The owner's-own-movement-card carve-out (rule 12's second sentence): any movement card,
  named-exception or not, can move the *owner's own* token while it's in their own Zone.
- Leaving a Zone: the rulebook never describes a dice-driven path *out* of a Zone (Zones
  aren't a numbered sub-track token-by-token) — the only confirmed way a token leaves is via
  a card that moves it elsewhere (e.g., one of the 5 exceptions, or the owner's own
  movement) or is destroyed while still inside. This needs explicit user confirmation before
  building any "automatic Zone exit" logic — flagged, §4 Q17.

### 3.4 Marauder-driven pass-through as a movement property, not a card property

Rule 8 means "destroy everything passed except the mover's own tokens" must live on
*Marauder movement itself* (already `TurnEngine.destroyTokensPassed`, used by dice-driven
Marauder movement, Marauder Sensor chaining, and Hyperthrust), reused by every card that
ends up moving a Marauder — not reimplemented per-card. Last Gasp needs a *variant* of this
same helper for Tier-token movement (per rule 10, normally Tier tokens never destroy on
pass), with the open ownership-exemption question flagged in §4 Q14.

### 3.5 Deferred turn-state modifiers

Two shapes cover every turn-manipulation card and Time Wrinkle square found in this audit:

- `SkipNextTierTurn(player, tier)` — Phase Loss, "Lose next turn on this Tier" (1st Tier,
  index 5).
- `ExtraTierTurn(player, tier)` — Phase Control (free Tier choice, two sub-cases per card
  30), "Take an extra turn, First Tier" (2nd Tier, index 7, hardcoded to Tier 1), "Go again"
  (already supported via `GameState.endTurn(grantAnotherTurn = true)` for the *simple*
  same-Phase-continuation case — the 1st Tier's two "Go again" squares and 3rd Tier's one
  don't need the full deferred-modifier machinery, only Phase Control's cross-Phase case
  does).

Both need the bound from confirmed canon: **no turn may repeat more than once from a single
triggering effect** — i.e., a granted extra turn cannot itself grant another extra turn from
the *same* originating play (playing a second Phase Control card, or landing on a second
"Go again" square during the extra turn itself, is a *new* triggering effect and is allowed;
what's bounded is recursion from one trigger, not the total number of independent triggers
in a Round).

### 3.6 Temporary Marauder allowance — unresolved

Per the task's stated canon: "Extra Marauders created by the applicable effect persist for
five Marauder-Phase turns, not five generic player turns." **None of the four
Marauder-adding cards' printed text (Dwarf Star, Materialize Army, Essence Assimilator,
Materialize Help) mentions any expiration** — each reads as a permanent placement that
simply bypasses the 1-per-Tier cap (matching rule 9's plain wording: "this limit does not
apply to Marauders added by Fate Harvest cards," with no duration attached). `rulebook.txt`
has no occurrence of "five" or "5" in connection with Marauders. This is flagged as the
highest-priority open question in the whole audit (§4 Q2) — the engine should have the
countdown primitive ready (`TemporaryMarauderAllowance(player, tier, remainingMarauderPhaseTurns)`,
decremented only when that player's Marauder Phase turn actually occurs, not on generic
turns, matching the "Marauder-Phase turns not generic turns" clarification) but must not be
wired to any specific card until the user identifies which one(s) it governs.

### 3.7 Victory timing

Already correctly gated on exact landing (`SquareType.YOU_WIN`, `TurnEngine`). What's new
here: any card or square-chain that can move a token onto You Win as a side effect of a
larger atomic resolution (Galactic Roundabout moving every token; Hyperthrust/Warp chaining
into a landing; a Precedence card altering a move mid-resolution to complete or spoil an
exact landing) must only check/declare victory **after** that entire atomic action (and any
open interaction window it spawned) has fully resolved — never from an intermediate
sub-step. `GameState.declareWinner` is a simple flag-set today, which is fine as a
mechanism; what needs auditing per-card is *when* it gets called relative to a card's other
sub-effects (see §4 Q5 for the Galactic Roundabout multi-winner ordering question, and Phase
I's required tests: ordinary exact landing, overshoot, movement-card exact landing, chained
movement, movement altered mid-interaction-chain, and the roundabout-style near-win case).

---

## 4. Consolidated ambiguities requiring user judgment

1. ~~Fate Harvest card-play-limit: per-turn or per-Phase?~~ **Already resolved** (per-Phase,
   confirmed by an earlier commit and `PlayerState.hasPlayedCardThisPhase`) — listed here
   only to flag that it is not yet *enforced* anywhere in `TurnEngine`, and that a genuinely
   open sub-question remains: if a player has already used their one card this Phase and
   then draws a *mandatory Immediate* card, must they still play it (seemingly forced to
   exceed the stated limit), or does the limit block even a mandatory Immediate play (and if
   so, what happens to the drawn card — discarded unplayed? held despite being Immediate)?
   The rulebook doesn't address this collision at all.
2. **Which card(s) grant a *temporary* extra Marauder** with the confirmed "five
   Marauder-Phase turns" duration? None of the four Marauder-construction cards' printed
   text (or rule 9) mentions an expiration; `rulebook.txt` has no textual match for this
   duration at all. Needs a direct user ruling before any card is wired to the
   `TemporaryMarauderAllowance` primitive (§3.6).
3. Do "any"-token destroy/construct cards without an explicit "opponent's" qualifier
   (Corpuscle Rot's 4th-Tier-token destroy, Elemental Rebirth's "place a token") allow
   targeting/benefiting your **own** side, or are they implicitly opponent-only /
   self-only respectively? Both read as unqualified "any" in the printed text.
4. When a card moves a Marauder that **isn't** the card-player's own (e.g. Parallel
   Phasing/Tactical Motion/etc. targeting an opponent's Marauder), does pass-through
   destruction exempt the tokens of the **Marauder's owner** (consistent with the
   underlying "Marauders don't destroy your own tokens" rule, regardless of who caused the
   move) or the **card-player** (a literal reading of rule 8's "yours are immune to your
   Marauder," which presumes the mover and owner are the same person)? This affects every
   any-token movement card whenever it's used on someone else's Marauder.
5. Galactic Roundabout: (a) does its simultaneous whole-board Marauder movement trigger
   ordinary pass-through destruction per-Marauder, and (b) if two players' tokens land
   exactly on their respective 4th Tier You-Win squares within the same resolution, is
   there a defined winner-ordering rule (first in turn order, first token processed, etc.)?
6. Radiation Burst: (a) "All Staging Piles" — every player's every Tier, or just the
   caster's own? (b) Does emptying a pile that happens to be at/above its promotion
   threshold trigger the promotion, or does emptying bypass it entirely?
7. `CardScope` (currently `YOUR_TURN | ANY_TIME`) can't express two real restrictions found
   in this audit: a "your turn, but only during Tier X's specific Phase" restriction
   (Planetary Nebula → 2nd Tier Phase, Emitting Nebula → 1st Tier Phase), and Delayed
   Motion's unique "after roll, before move" window. Needs a modeling decision in Phase B,
   not a rules question, but flagged here since it affects how faithfully the matrix's Scope
   field can be expressed in code.
8. Plasma Burst: how exactly are the "3 neighboring squares" selected — any 3 consecutive
   main-loop squares of the player's choice, relative to some fixed reference point, and/or
   can the selection span into a Zone of Protection's own off-loop squares (which would
   explain why this card is a named ZoP exception)?
9. Infernal Abyss: what happens if the drawing player's only existing token(s) are all
   currently inside a Zone of Protection, leaving no legal (mandatory) sacrifice target for
   this Immediate card?
10. Naming-collision risk (not a rules ambiguity, an implementation-hygiene flag): four
    differently-shaped "Nebula" cards (Planetary Nebula, Luckier Nebula, Lucky Nebula,
    Emitting Nebula) and near-duplicate movement cards sharing distance/timing/Precedence
    combinations across Tactical Motion/Tactical Step/Evasive Action/Skip Hop and
    Jump/Sidestep/Parallel Phasing — implementation should name types/tests explicitly
    enough to prevent silent transposition, and cross-tests should assert each card's
    specific (distance, timing, scope, precedence) tuple independently.
11. Annulment chains longer than two cards: does cancelling an Annulment (via a second
    Annulment) restore whatever the first Annulment had itself cancelled, or does a
    cancellation, once applied, stay permanently removed from the chain regardless of what
    happens to the Annulment that caused it? The rulebook's rule 22 only describes the
    two-card case explicitly.
12. Cleansing: no-op confirmation when the targeted opponent's hand is empty (high-confidence
    default, not explicitly stated).
13. Delayed Motion: can it be played on any player's pending roll, or only the roller's own
    (no explicit Your-Turn restatement for this card, unlike most other turn-scoped cards)?
14. **Last Gasp**: does "any tokens you pass are destroyed" include the mover's *own* other
    tokens caught in the 8-space path (the card's wording omits the usual owner-exemption
    clause present in every other pass-through rule), or does the general Marauder-style
    owner exemption still implicitly apply? High-priority — this materially changes the
    card's risk profile and blocks a confident implementation.
15. Phase Control's case (b) ("play your turn on that Tier immediately" when the Tier's
    Phase already ended this Round): does "immediately" mean a true interrupt of whatever's
    currently resolving, or "next, out of normal Phase order, once the current action
    finishes" (the latter is more consistent with this card being Immediate rather than
    Precedence, but isn't stated outright)?
16. Circulate: (a) can it target an opponent's Tier token, or is it implicitly self-only;
    (b) if the chosen token is already inside a Zone, does "the next Zone of Protection"
    mean the following Zone further around the loop, or is the play illegal/a no-op?
17. Zone of Protection: the rulebook never describes an ordinary (non-card) way to leave a
    Zone once entered — confirm there is genuinely no dice-driven exit path before the
    engine assumes Zone residence is otherwise permanent until a qualifying card moves the
    token out or destroys it.

---

## 5. Summary: mechanical complexity classes found

For Phase J's grouping, the cards in this matrix sort into:

- **Group 1 (straightforward state/movement, no response chain):** Dwarf Star, Materialize
  Army, Verdant Growth, Essence Assimilator, Materialize Help, Planetary Nebula, Luckier
  Nebula, Lucky Nebula, Emitting Nebula, Elemental Rebirth, Skip/Hop/and Jump, Evasive
  Action, Sidestep, Parallel Phasing (compound but no response chain).
- **Group 2 (destruction/protection, needs Zone + Reprieve validation):** Corpuscle Rot,
  Divine Assistance, Insidious Flux, Infernal Abyss, Plasma Burst.
- **Group 3 (turn manipulation, needs deferred-turn state):** Phase Loss, Phase Control,
  Radiation Burst (pool-wide, arguably its own bucket — see §4 Q6), Circulate (Zone-entry
  movement, arguably Group 2/4 hybrid), Cleansing (needs the pending-decision primitive,
  not deferred-turn, but shares "can't resolve synchronously" shape).
- **Group 4 (cross-Tier/global effects):** Galactic Roundabout, Fluidic Wave.
- **Group 5 (Precedence, full interaction engine required):** Graviton Rift, Fluidic Wave
  (also Group 4), Tactical Motion, Annulment, Tactical Step, Last Gasp.

Delayed Motion doesn't cleanly fit any group — it needs the new PendingRoll checkpoint
before any group's shared infrastructure can implement it, and should probably be built
alongside whichever tranche builds Phase D's roll/move separation.
