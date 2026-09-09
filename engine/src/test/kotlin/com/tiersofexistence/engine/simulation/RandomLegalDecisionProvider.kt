package com.tiersofexistence.engine.simulation

import com.tiersofexistence.engine.cards.FateHarvestCard
import com.tiersofexistence.engine.cards.play.CardTarget
import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.rules.CardChoice
import com.tiersofexistence.engine.rules.TurnDecisionProvider
import com.tiersofexistence.engine.rules.precedence.InteractionChain
import com.tiersofexistence.engine.state.GameState
import com.tiersofexistence.engine.state.TokenId
import kotlin.random.Random

/**
 * Best-effort, shape-correct (but not necessarily rules-legal) target construction for a card
 * play attempt, keyed by card name exactly like [com.tiersofexistence.engine.cards.resolvers
 * .CardEffectDispatcher]'s own dispatch table — this only needs to get the *shape* of
 * [CardTarget] right (a Token vs. a TierChoice vs. a PlayerChoice, etc.), never full legality:
 * [com.tiersofexistence.engine.cards.play.TargetValidator]/[com.tiersofexistence.engine.cards
 * .play.CardLifecycle] enforce actual legality downstream and reject gracefully (card stays in
 * hand, or is discarded per confirmed canon) if a sampled target turns out illegal — that
 * rejection path is itself real gameplay behavior worth exercising, not something this sampler
 * needs to avoid. Returns an empty list (or a shorter-than-ideal one) when no plausible candidate
 * exists at all, which every caller already treats as "decline"/"no legal target."
 */
internal object CardTargetSampler {

    private fun allTierTokenIds(state: GameState): List<TokenId> =
        state.players.values.flatMap { ps ->
            TierLevel.entries.flatMap { tier -> ps.tierPool(tier).inPlayIds + ps.tierPool(tier).zoneResidentIds }
        }

    private fun ownTierTokenIds(state: GameState, player: PlayerColor): List<TokenId> {
        val ps = state.players.getValue(player)
        return TierLevel.entries.flatMap { tier -> ps.tierPool(tier).inPlayIds + ps.tierPool(tier).zoneResidentIds }
    }

    private fun allMarauderIds(state: GameState): List<TokenId> =
        state.players.values.flatMap { ps -> TierLevel.entries.flatMap { tier -> ps.marauders.inPlayIds(tier) } }

    private fun ownMarauderIds(state: GameState, player: PlayerColor): List<TokenId> =
        TierLevel.entries.flatMap { tier -> state.players.getValue(player).marauders.inPlayIds(tier) }

    private fun stagingPileCandidates(state: GameState): List<CardTarget.StagingPileToken> =
        state.players.values.flatMap { ps ->
            TierLevel.entries.mapNotNull { tier -> if (ps.tierPool(tier).stagingPile > 0) CardTarget.StagingPileToken(ps.color, tier) else null }
        }

    fun sampleTargets(card: FateHarvestCard, state: GameState, player: PlayerColor, random: Random): List<CardTarget> =
        when (card.name) {
            // --- No target needed at all ---
            "Dwarf Star", "Essence Assimilator", "Materialize Help",
            "Verdant Growth", "Elemental Rebirth", "Planetary Nebula",
            "Lucky Nebula", "Luckier Nebula", "Emitting Nebula",
            "Fluidic Wave", "Radiation Burst", "Galactic Roundabout",
            "Phase Loss", "Delayed Motion",
            -> emptyList()

            // --- A whole Tier, no specific token ---
            "Materialize Army", "Phase Control" -> listOf(CardTarget.TierChoice(TierLevel.entries.random(random)))

            // --- Any player's Tier token, single target ---
            "Skip, Hop, and Jump (Dimensional)", "Evasive Action", "Sidestep (Extinction Avoidance)",
            "Tactical Motion", "Tactical Step", "Circulate (Elemental)",
            -> allTierTokenIds(state).randomOrNull(random)?.let { listOf(CardTarget.Token(it)) } ?: emptyList()

            // --- The player's own token (Tier token or Marauder), single target ---
            "Last Gasp" ->
                (ownTierTokenIds(state, player) + ownMarauderIds(state, player)).randomOrNull(random)
                    ?.let { listOf(CardTarget.Token(it)) } ?: emptyList()

            "Infernal Abyss", "Corpuscle Rot" ->
                ownTierTokenIds(state, player).randomOrNull(random)?.let { listOf(CardTarget.Token(it)) } ?: emptyList()

            // --- Any target: a token OR a Staging Pile slot ---
            "Divine Assistance", "Insidious Flux" -> {
                val candidates: List<CardTarget> = allTierTokenIds(state).map { CardTarget.Token(it) } + stagingPileCandidates(state)
                candidates.randomOrNull(random)?.let { listOf(it) } ?: emptyList()
            }

            // --- Own token + an opponent's token ---
            "Parallel Phasing" -> {
                val own = ownTierTokenIds(state, player).randomOrNull(random)
                val opponent = allTierTokenIds(state).filter { it.owner != player }.randomOrNull(random)
                if (own != null && opponent != null) listOf(CardTarget.Token(own), CardTarget.Token(opponent)) else emptyList()
            }

            // --- Up to one token per Tier ---
            "Graviton Rift" -> {
                val perTier = TierLevel.entries.mapNotNull { tier ->
                    state.players.values.flatMap { it.tierPool(tier).inPlayIds + it.tierPool(tier).zoneResidentIds }.randomOrNull(random)
                }
                perTier.shuffled(random).take(4).map { CardTarget.Token(it) }
            }

            // --- A specific board square on a chosen Tier ---
            "Plasma Burst" -> {
                val tier = TierLevel.entries.random(random)
                val board = state.boards.getValue(tier)
                listOf(CardTarget.BoardPosition(tier, board.squares.indices.random(random)))
            }

            // --- Another player ---
            "Cleansing (Atmospheric)" -> {
                val opponents = state.players.keys.filter { it != player }
                opponents.randomOrNull(random)?.let { listOf(CardTarget.PlayerChoice(it)) } ?: emptyList()
            }

            else -> emptyList()
        }
}

/**
 * A [TurnDecisionProvider] that makes randomized-but-plausible choices at every decision point —
 * for simulation/stress-testing the engine through real games rather than hand-scripted
 * scenarios. "Plausible" is deliberately weaker than "always legal": card targets are shape-
 * correct (see [CardTargetSampler]) but not pre-validated against full rules legality, since the
 * engine's own [com.tiersofexistence.engine.cards.play.TargetValidator]/[com.tiersofexistence
 * .engine.cards.play.CardLifecycle] are what's actually being exercised — an illegal attempt is
 * simply rejected (card stays in hand or is gracefully discarded), which is itself real,
 * worth-exercising engine behavior, not a bug in this provider. The two exceptions where an
 * illegal-shaped choice would crash rather than gracefully reject — a non-Precedence card
 * offered as a chain response, or a card not actually in hand — are avoided by construction here
 * (see [choosePrecedenceResponse]/[maybePlayFromHand]), matching [TurnDriver][com.tiersofexistence
 * .engine.rules.TurnDriver]'s own documented contract for those two cases.
 */
class RandomLegalDecisionProvider(private val random: Random) : TurnDecisionProvider {

    private val playHeldCardProbability = 0.5
    private val enterZoneProbability = 0.5
    private val buildMarauderProbability = 0.5
    private val transportProbability = 0.4
    private val respondProbability = 0.3

    override fun chooseTokenToMove(state: GameState, player: PlayerColor, candidates: List<TokenId>): TokenId =
        candidates.random(random)

    override fun chooseEnterZone(state: GameState, player: PlayerColor, tier: TierLevel, zoneNumber: Int): Boolean =
        random.nextDouble() < enterZoneProbability

    override fun chooseBuildMarauder(state: GameState, player: PlayerColor, tier: TierLevel): Boolean =
        random.nextDouble() < buildMarauderProbability

    override fun chooseTransport(state: GameState, player: PlayerColor, fromTier: TierLevel, options: List<TierLevel>): TierLevel? =
        if (random.nextDouble() < transportProbability) options.random(random) else null

    override fun chooseHeldCardBeforeRoll(state: GameState, player: PlayerColor): CardChoice? = maybePlayFromHand(state, player)

    override fun chooseCardAfterRollBeforeMove(state: GameState, player: PlayerColor, roll: Int): CardChoice? = maybePlayFromHand(state, player)

    override fun chooseImmediateCardTargets(state: GameState, player: PlayerColor, card: FateHarvestCard): List<CardTarget> =
        CardTargetSampler.sampleTargets(card, state, player, random)

    override fun choosePrecedenceResponse(state: GameState, player: PlayerColor, chain: InteractionChain): CardChoice? {
        if (random.nextDouble() >= respondProbability) return null
        // Must only ever offer a card with hasPrecedence = true — TurnDriver.offerResponseRounds
        // `require`s this and would crash rather than reject, since a Precedence-chain response
        // is a narrower legal-shape contract than an ordinary Held-card play.
        val card = state.players.getValue(player).hand.filter { it.hasPrecedence }.randomOrNull(random) ?: return null
        return CardChoice(card, CardTargetSampler.sampleTargets(card, state, player, random))
    }

    override fun chooseCleansingDiscard(
        state: GameState,
        decidingPlayer: PlayerColor,
        sourcePlayer: PlayerColor,
        eligibleCards: List<FateHarvestCard>,
    ): FateHarvestCard = eligibleCards.random(random)

    private fun maybePlayFromHand(state: GameState, player: PlayerColor): CardChoice? {
        if (random.nextDouble() >= playHeldCardProbability) return null
        val card = state.players.getValue(player).hand.randomOrNull(random) ?: return null
        return CardChoice(card, CardTargetSampler.sampleTargets(card, state, player, random))
    }
}
