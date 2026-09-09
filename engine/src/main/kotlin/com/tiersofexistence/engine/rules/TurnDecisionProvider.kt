package com.tiersofexistence.engine.rules

import com.tiersofexistence.engine.cards.FateHarvestCard
import com.tiersofexistence.engine.cards.play.CardTarget
import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.rules.precedence.InteractionChain
import com.tiersofexistence.engine.state.GameState
import com.tiersofexistence.engine.state.TokenId

/**
 * A player's choice to play one specific card, with whatever targets it needs — the shape every
 * card-play decision in [TurnDecisionProvider] returns (or null/empty to decline). Deliberately
 * a plain (card, targets) pair rather than a full [com.tiersofexistence.engine.cards.play.CardPlayRequest]
 * — the caller (a UI/AI) doesn't know or care about [com.tiersofexistence.engine.cards.play.TriggeringEvent]
 * or which player is asking; [TurnDriver] fills those in from context.
 */
data class CardChoice(val card: FateHarvestCard, val targets: List<CardTarget> = emptyList())

/**
 * Every real choice a player makes during the mechanical part of an ordinary turn — which token
 * to move, whether to take each of the game's optional square offers, and (since the Fate
 * Harvest integration below) which cards to play and how to respond to a Precedence window —
 * abstracted behind one interface so [TurnDriver] can be driven by a UI, an AI, or a
 * deterministic test double without rework.
 *
 * Every method receives the live [GameState] so an implementation can inspect as much context as
 * it needs (board layout, other players' positions, hand contents) to make its choice. The 4
 * card-play methods all default to declining/choosing nothing, so an existing implementation that
 * predates this integration (like [FirstCandidateDecisionProvider]) keeps compiling and behaving
 * exactly as before — a decision provider must opt IN to playing cards at all.
 */
interface TurnDecisionProvider {
    /**
     * [player]'s current turn has [candidates] eligible tokens to move — every one of their own
     * Tier tokens currently in play or Zone-resident on the active Tier Phase's Tier, or every
     * one of their Marauders currently in play on any Tier for the Marauder Phase. Must return
     * one of [candidates]; [TurnDriver] is only called with this when [candidates] is non-empty.
     */
    fun chooseTokenToMove(state: GameState, player: PlayerColor, candidates: List<TokenId>): TokenId

    /** After landing exactly on a Zone of Protection's numbered entry square
     * ([SquareEffect.MayEnterZone]) — does [player] choose to enter Zone [zoneNumber]? */
    fun chooseEnterZone(state: GameState, player: PlayerColor, tier: TierLevel, zoneNumber: Int): Boolean

    /** After landing on a Marauder Construction Facility ([SquareEffect.MayBuildMarauder]) —
     * does [player] choose to build a Marauder here? */
    fun chooseBuildMarauder(state: GameState, player: PlayerColor, tier: TierLevel): Boolean

    /** After a Marauder lands on a Marauder Transport ([SquareEffect.MayTransport]) — does
     * [player] choose to transport to one of [options] (always 1 or 2 neighboring Tiers), or
     * decline by returning null? [options] is never empty (every Tier has at least one
     * neighbor). */
    fun chooseTransport(state: GameState, player: PlayerColor, fromTier: TierLevel, options: List<TierLevel>): TierLevel?

    /**
     * [player]'s opportunity to voluntarily play one [com.tiersofexistence.engine.cards.CardScope.YOUR_TURN]
     * Held card from their hand before rolling this turn — return null to play nothing.
     * [TurnDriver] validates whatever is returned through the normal target-legality/color/Phase-
     * limit path regardless (an illegal choice is simply rejected and the card stays in hand), so
     * this never needs to duplicate that logic itself.
     */
    fun chooseHeldCardBeforeRoll(state: GameState, player: PlayerColor): CardChoice? = null

    /**
     * After [player] has rolled but before that roll is used to move a token — the checkpoint
     * Delayed Motion needs ("must be played after your die roll, but before you move the
     * token"). [roll] is the roll's current total (already reflecting any earlier bonus this same
     * checkpoint applied, though [TurnDriver] only offers this once per turn). Return null to
     * decline. Not restricted to Delayed Motion by this interface — [TurnDriver] still validates
     * legality normally — but Delayed Motion is the only card in the deck whose own printed
     * timing actually fits this exact window.
     */
    fun chooseCardAfterRollBeforeMove(state: GameState, player: PlayerColor, roll: Int): CardChoice? = null

    /**
     * [card] was just drawn as a mandatory [com.tiersofexistence.engine.cards.CardTiming.IMMEDIATE]
     * card (rule 14: must be played the instant it's drawn) — [player] must choose its target(s)
     * now. Only called for a card whose resolver actually needs a target (a zero-target Immediate
     * card like Fluidic Wave or Phase Loss resolves directly without calling this). An empty list
     * for a card that DOES need one simply means the play gets rejected as having
     * [com.tiersofexistence.engine.cards.play.TargetValidationError.NoLegalTarget] — see
     * [TurnDriver]'s handling of that case for what happens to the card next.
     */
    fun chooseImmediateCardTargets(state: GameState, player: PlayerColor, card: FateHarvestCard): List<CardTarget> = emptyList()

    /**
     * A Precedence interaction window is open around [chain] (rules 20-23) — does [player]
     * respond with a Precedence card from hand (and its targets), or pass? [TurnDriver] calls
     * this once per still-eligible player per response round, re-offering the round to everyone
     * again (including players who already passed) whenever anyone adds a new entry, until the
     * window auto-closes. The returned [CardChoice.card] must have
     * [com.tiersofexistence.engine.cards.FateHarvestCard.hasPrecedence] true — [TurnDriver]
     * enforces this defensively rather than trusting the provider.
     */
    fun choosePrecedenceResponse(state: GameState, player: PlayerColor, chain: InteractionChain): CardChoice? = null

    /**
     * Cleansing (Atmospheric): "That opponent must choose and discard one of their cards. The
     * person discarding chooses which card to discard." [decidingPlayer] is the opponent
     * [sourcePlayer] targeted with Cleansing — this is [decidingPlayer]'s OWN decision, never
     * [sourcePlayer]'s; [TurnDriver] always resolves it through [decidingPlayer]'s own
     * [TurnDecisionProvider] (via its per-player `decisionsFor`), never the source player's, and
     * never exposes [decidingPlayer]'s hand to [sourcePlayer] to make this choice for them —
     * that stays strictly a private decision for whichever provider represents [decidingPlayer]
     * (a real UI would present it as a private prompt / pass-and-play handoff / remote-player
     * prompt to that player specifically; this interface only models who decides and what their
     * legal options are, not how it's presented).
     *
     * [eligibleCards] is [decidingPlayer]'s own currently-held cards, and is never empty — a
     * player with no cards in hand isn't a legal Cleansing target at all (see
     * [com.tiersofexistence.engine.cards.resolvers.CleansingResolver]), so by the time this is
     * called, choosing one is mandatory, unlike every other decision on this interface. Must
     * return one of [eligibleCards]; [TurnDriver] validates this rather than trusting the
     * provider. Defaults to the first eligible card, matching [FirstCandidateDecisionProvider]'s
     * own "always the first candidate" convention, so an implementation written before this
     * method existed keeps compiling.
     */
    fun chooseCleansingDiscard(state: GameState, decidingPlayer: PlayerColor, sourcePlayer: PlayerColor, eligibleCards: List<FateHarvestCard>): FateHarvestCard =
        eligibleCards.first()
}

/**
 * The simplest possible [TurnDecisionProvider]: always moves the first candidate offered, always
 * declines every optional offer, and never plays or responds with a card. Deterministic and
 * dependency-free, so it's useful as a default for tests/simulations that only care about the
 * mechanical roll → move → offer loop running correctly, not about realistic play — a real UI or
 * AI should supply its own implementation.
 */
object FirstCandidateDecisionProvider : TurnDecisionProvider {
    override fun chooseTokenToMove(state: GameState, player: PlayerColor, candidates: List<TokenId>): TokenId = candidates.first()
    override fun chooseEnterZone(state: GameState, player: PlayerColor, tier: TierLevel, zoneNumber: Int): Boolean = false
    override fun chooseBuildMarauder(state: GameState, player: PlayerColor, tier: TierLevel): Boolean = false
    override fun chooseTransport(state: GameState, player: PlayerColor, fromTier: TierLevel, options: List<TierLevel>): TierLevel? = null
    override fun chooseHeldCardBeforeRoll(state: GameState, player: PlayerColor): CardChoice? = null
    override fun chooseCardAfterRollBeforeMove(state: GameState, player: PlayerColor, roll: Int): CardChoice? = null
    override fun chooseImmediateCardTargets(state: GameState, player: PlayerColor, card: FateHarvestCard): List<CardTarget> = emptyList()
    override fun choosePrecedenceResponse(state: GameState, player: PlayerColor, chain: InteractionChain): CardChoice? = null
    override fun chooseCleansingDiscard(state: GameState, decidingPlayer: PlayerColor, sourcePlayer: PlayerColor, eligibleCards: List<FateHarvestCard>): FateHarvestCard =
        eligibleCards.first()
}
