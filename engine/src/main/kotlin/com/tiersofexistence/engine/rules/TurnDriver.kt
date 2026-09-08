package com.tiersofexistence.engine.rules

import com.tiersofexistence.engine.cards.CardTiming
import com.tiersofexistence.engine.cards.play.TokenLocation
import com.tiersofexistence.engine.cards.play.TokenLocator
import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.model.TokenKind
import com.tiersofexistence.engine.state.GameState
import com.tiersofexistence.engine.state.TokenId

/**
 * Drives the mechanical roll → choose-a-token → move → resolve-the-landing part of one turn at a
 * time, delegating every real player choice to a [TurnDecisionProvider]. This is deliberately
 * NOT a full turn-driving engine yet: playing a Fate Harvest card (from hand, or an Immediate
 * card mandatory the instant it's drawn) is a separate, later pass — a drawn card is only ever
 * recorded, never auto-played, here:
 * - A [CardTiming.HELD] card drawn from a Fate Harvest square is already added to the drawing
 *   player's hand by [TurnEngine] itself (unchanged, pre-existing behavior) — this driver does
 *   nothing further with it; a later pass would let a player choose to play a held card between
 *   turns.
 * - A [CardTiming.IMMEDIATE] card drawn is queued via [GameState.queuePendingImmediateCard]
 *   rather than actually applied — rule 14's "must be played the instant it's drawn" isn't
 *   honored yet, deliberately: resolving one requires per-card target selection through
 *   [com.tiersofexistence.engine.cards.resolvers.CardEffectDispatcher], which is out of scope
 *   for this pass. [GameState.pendingImmediateCards] makes this gap visible rather than silently
 *   dropping the card.
 *
 * Time Wrinkle squares ARE fully handled: "Lose next turn on this Tier" and "Take an extra turn,
 * First Tier" are auto-applied inside [TurnEngine] itself now (see [SquareEffect.LoseNextTierTurn]/
 * [SquareEffect.GrantedExtraTierTurn]); "Go again" ([SquareEffect.GoAgain]) is acted on here by
 * passing `grantAnotherTurn = true` to [GameState.endTurn].
 *
 * [rollForPhase] defaults to [Dice.rollForPhase] (genuinely random) but can be overridden for a
 * deterministic test/simulation — a plain function reference rather than a `Random` seed, since
 * `kotlin.random.Random`'s internals make forcing an exact `nextInt` sequence awkward, and a
 * fixed lambda is simpler to reason about in a test than a seeded generator would be anyway.
 *
 * [decisionsFor] resolves a fresh [TurnDecisionProvider] per player rather than sharing one
 * across the whole driver — needed for a game seating several AI players with genuinely
 * different behavior (up to 5 AI opponents, each a different behavior type, is the stated first-
 * release shape), not just one uniform policy for everyone. The two secondary constructors cover
 * the common cases: a single [TurnDecisionProvider] for every player (unchanged from this
 * class's original shape), or a `Map<PlayerColor, TurnDecisionProvider>` keyed by seat. A human
 * seat is deliberately not modeled here at all — this class only ever drives a turn it's asked
 * to, so whatever's orchestrating a mixed human/AI game simply never calls [driveOneTurn] for a
 * human player's turn in the first place, and drives that seat some other way (direct UI input
 * into `TurnEngine`) instead.
 */
class TurnDriver(
    private val decisionsFor: (PlayerColor) -> TurnDecisionProvider,
    private val rollForPhase: (Phase) -> Int = { Dice.rollForPhase(it) },
) {
    /** Every player driven by the same [decisions], regardless of seat — the common
     * single-behavior case (also every existing test/simulation from before per-player behavior
     * was needed). */
    constructor(decisions: TurnDecisionProvider, rollForPhase: (Phase) -> Int = { Dice.rollForPhase(it) }) :
        this({ decisions }, rollForPhase)

    /** Each player driven by their own entry in [decisionsByPlayer] — the natural shape for "N
     * AI players, each a different behavior type." Throws if [driveOneTurn] is ever asked to
     * drive a player with no entry (a seat this map doesn't cover, e.g. a human player whose
     * turn should never have reached this driver in the first place — see the class doc). */
    constructor(decisionsByPlayer: Map<PlayerColor, TurnDecisionProvider>, rollForPhase: (Phase) -> Int = { Dice.rollForPhase(it) }) :
        this({ color -> decisionsByPlayer.getValue(color) }, rollForPhase)

    /**
     * Drives exactly one player's turn to completion: rolls, asks that player's own
     * [TurnDecisionProvider] (via [decisionsFor]) which eligible token to move, moves it,
     * resolves whatever that landing produces (including asking about any optional offer), then
     * ends the turn — chaining another turn for the same player on a "Go again" square,
     * otherwise advancing to the next eligible player/Phase (see [GameState.endTurn]).
     *
     * Returns false without doing anything if there's no current turn to drive — either the game
     * has already been won, or [GameState.skipEmptyPhases] hasn't been called yet on a
     * freshly-constructed [GameState] (call that first to prime the turn queue).
     */
    fun driveOneTurn(state: GameState): Boolean {
        val player = state.currentTurn ?: return false
        val decisions = decisionsFor(player)
        val phase = state.currentPhase
        val tier = (phase as? Phase.Tier)?.tier

        val roll = state.beginPendingRoll(player, rollForPhase(phase))
        val candidates = movableCandidates(state, player, tier)

        // Every player in the turn queue was already filtered on having something movable
        // (PlayerState.hasTierTurn/hasMarauderTurn), so an empty candidate list here would mean
        // that eligibility check and this one have drifted out of sync — stay defensive rather
        // than silently ending an ineligible "turn."
        check(candidates.isNotEmpty()) { "$player has a turn in $phase but no movable token/Marauder — turn-eligibility and movable-candidate logic have drifted apart" }

        val chosen = decisions.chooseTokenToMove(state, player, candidates)
        require(chosen in candidates) { "TurnDecisionProvider.chooseTokenToMove must return one of the offered candidates, got $chosen" }

        val grantAnotherTurn = moveAndResolve(state, decisions, player, chosen, roll.total)
        state.clearPendingRoll()
        state.endTurn(grantAnotherTurn)
        return true
    }

    /** Every token [player] could choose to move this turn: for a Tier Phase, [tier]'s own Tier
     * tokens (main-loop or Zone-resident alike — a Zone resident is just as legal a choice, per
     * the confirmed "a Zone is a dice-driven sub-path" ruling); for the Marauder Phase
     * ([tier] null), every Marauder in play across all four Tiers (a single Marauder-Phase turn
     * lets the player pick which one of their Marauders moves, per [com.tiersofexistence.engine
     * .state.PlayerState.hasMarauderTurn]'s "any Tier" eligibility). */
    private fun movableCandidates(state: GameState, player: PlayerColor, tier: TierLevel?): List<TokenId> {
        val playerState = state.players.getValue(player)
        return if (tier != null) {
            val pool = playerState.tierPool(tier)
            pool.inPlayIds + pool.zoneResidentIds
        } else {
            TierLevel.entries.flatMap { playerState.marauders.inPlayIds(it) }
        }
    }

    /** Moves [id] by [spaces] and resolves whatever that landing produces, returning true if the
     * turn should chain (a "Go again" square). Uses the identity-based movers
     * ([TurnEngine.moveTierTokenById]/[TurnEngine.moveMarauderById]/[TurnEngine.moveZoneToken])
     * throughout rather than resolving a position and moving by position — [id] is already known
     * exactly, and more than one of the player's own tokens can legally share a position
     * (stacking), so a position-based move risks silently moving the wrong one. */
    private fun moveAndResolve(state: GameState, decisions: TurnDecisionProvider, player: PlayerColor, id: TokenId, spaces: Int): Boolean =
        when (id.kind) {
            TokenKind.TIER_TOKEN -> {
                val moveResult = when (TokenLocator.locate(state, id)) {
                    is TokenLocation.InPlay -> TurnEngine.moveTierTokenById(state, id, spaces)
                    is TokenLocation.InZone -> {
                        // A Zone-internal square carries no turn-chaining/offer effect a driver
                        // needs to act on (see TurnEngine.resolveZoneLanding) — only the "exited
                        // back onto the main loop" case can produce one.
                        (TurnEngine.moveZoneToken(state, id, spaces) as? ZoneMoveResult.ExitedZone)?.moveResult
                    }
                    TokenLocation.NoLongerExists -> error("TurnDecisionProvider chose $id, but it no longer exists")
                }
                moveResult?.let { resolveTierEffect(state, decisions, player, id.tier, it) } ?: false
            }
            TokenKind.MARAUDER -> resolveMarauderEffect(state, decisions, player, id.tier, TurnEngine.moveMarauderById(state, id, spaces))
        }

    /** Acts on a Tier token's landing effect, asking [decisions] about any optional offer and
     * queuing a drawn Immediate card (see class doc). Returns true only for [SquareEffect.GoAgain]. */
    private fun resolveTierEffect(state: GameState, decisions: TurnDecisionProvider, player: PlayerColor, tier: TierLevel, result: MoveResult): Boolean {
        when (val effect = result.effect) {
            is SquareEffect.MayEnterZone -> {
                if (decisions.chooseEnterZone(state, player, tier, effect.zoneNumber)) {
                    TurnEngine.enterZoneOfProtection(state, player, tier, result.finalPosition, effect.zoneNumber)
                }
            }
            SquareEffect.MayBuildMarauder -> {
                if (decisions.chooseBuildMarauder(state, player, tier)) {
                    TurnEngine.buildMarauder(state, player, tier)
                }
            }
            is SquareEffect.DrewCard -> {
                if (effect.card.timing == CardTiming.IMMEDIATE) state.queuePendingImmediateCard(effect.card)
            }
            SquareEffect.GoAgain -> return true
            else -> Unit // None, SentToStagingPile, SentToStart, Promoted, Won, Destroyed,
            // LoseNextTierTurn/GrantedExtraTierTurn (already auto-applied inside TurnEngine),
            // MayTransport (Tier tokens never land on a Marauder Transport's effect).
        }
        return false
    }

    /** Acts on a Marauder's landing effect — the only offer a Marauder can get is
     * [SquareEffect.MayTransport]; a Marauder never chains a turn. */
    private fun resolveMarauderEffect(state: GameState, decisions: TurnDecisionProvider, player: PlayerColor, tier: TierLevel, result: MoveResult): Boolean {
        if (result.effect is SquareEffect.MayTransport) {
            val options = listOfNotNull(tier.next(), tier.previous())
            val choice = decisions.chooseTransport(state, player, tier, options)
            if (choice != null) TurnEngine.transportMarauder(state, player, tier, choice, result.finalPosition)
        }
        return false
    }
}
