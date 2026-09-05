package com.tiersofexistence.engine.cards.resolvers

import com.tiersofexistence.engine.cards.play.CardPlayRequest
import com.tiersofexistence.engine.cards.play.CardPlayResult
import com.tiersofexistence.engine.cards.play.CardTarget
import com.tiersofexistence.engine.cards.play.TargetValidationError
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.rules.precedence.ChainEntry
import com.tiersofexistence.engine.state.GameState

/**
 * Maps a [CardPlayRequest] to the resolver that actually applies its effect, by card name. This
 * is what lets [com.tiersofexistence.engine.rules.precedence.InteractionChain.resolve]'s ordered
 * entries — or a plain drawn/held card play outside any chain — turn into real board mutations,
 * instead of every caller needing to know which of the resolver objects handles which card.
 *
 * Distances, fixed Tiers, and similar per-card constants (Tactical Step moves 1 space, Dwarf
 * Star always targets the 4th Tier, etc.) are literals here, matching each card's own printed
 * text — they are NOT read from [CardPlayRequest.targets], which only carries the player's own
 * choices (which token, which Tier to build on). Only covers the cards this task has actually
 * implemented so far; an unregistered card name is a programmer error (a resolver that doesn't
 * exist yet), not a legal-but-unhandled play, so it throws rather than returning a
 * [CardPlayResult] that would misleadingly imply the play was attempted.
 */
object CardEffectDispatcher {

    fun dispatch(state: GameState, request: CardPlayRequest): CardPlayResult {
        val target0 = request.targets.getOrNull(0)

        return when (request.card.name) {
            // --- Marauder construction (fixed Tier) ---
            "Dwarf Star" -> MarauderConstructionCardResolver.resolve(state, request, request.sourcePlayer, TierLevel.FOURTH)
            "Essence Assimilator" -> MarauderConstructionCardResolver.resolve(state, request, request.sourcePlayer, TierLevel.FIRST)
            "Materialize Help" -> MarauderConstructionCardResolver.resolve(state, request, request.sourcePlayer, TierLevel.THIRD)
            "Materialize Army" -> requireTierChoice(request, target0) {
                MarauderConstructionCardResolver.resolve(state, request, request.sourcePlayer, it)
            }

            // --- Birth Canal construction ---
            "Verdant Growth" -> BirthCanalConstructionCardResolver.resolve(
                state, request, request.sourcePlayer, listOf(TierLevel.FIRST, TierLevel.SECOND, TierLevel.THIRD),
            )
            "Elemental Rebirth" -> BirthCanalConstructionCardResolver.resolve(state, request, request.sourcePlayer, listOf(TierLevel.FIRST))
            "Planetary Nebula" -> BirthCanalConstructionCardResolver.resolve(state, request, request.sourcePlayer, listOf(TierLevel.SECOND))

            // --- Staging Pile direct construction ---
            "Lucky Nebula" -> StagingPileConstructionCardResolver.resolve(state, request, request.sourcePlayer, TierLevel.FIRST)
            "Luckier Nebula" -> StagingPileConstructionCardResolver.resolve(state, request, request.sourcePlayer, TierLevel.SECOND)
            "Emitting Nebula" -> StagingPileConstructionCardResolver.resolve(state, request, request.sourcePlayer, TierLevel.FIRST)

            // --- Movement (distance is the card's own fixed printed value) ---
            "Skip, Hop, and Jump (Dimensional)" -> requireTokenTarget(request, target0) { MovementCardResolver.resolve(state, request, it, spaces = 3) }
            "Evasive Action" -> requireTokenTarget(request, target0) { MovementCardResolver.resolve(state, request, it, spaces = 2) }
            "Sidestep (Extinction Avoidance)" -> requireTokenTarget(request, target0) { MovementCardResolver.resolve(state, request, it, spaces = 1) }
            "Tactical Motion" -> requireTokenTarget(request, target0) { MovementCardResolver.resolve(state, request, it, spaces = 2) }
            "Tactical Step" -> requireTokenTarget(request, target0) { MovementCardResolver.resolve(state, request, it, spaces = 1) }

            // --- Destruction ---
            "Divine Assistance" -> requireAnyTarget(request, target0) { DestructionCardResolver.resolve(state, request, it) }
            "Insidious Flux" -> requireAnyTarget(request, target0) { DestructionCardResolver.resolve(state, request, it) }
            "Infernal Abyss" -> requireTokenTarget(request, target0) { InfernalAbyssResolver.resolve(state, request, it) }
            "Corpuscle Rot" -> requireTokenTarget(request, target0) { CorpuscleRotResolver.resolve(state, request, it) }
            "Graviton Rift" -> requireAllTokenTargets(request) { GravitonRiftResolver.resolve(state, request, it) }

            // --- Turn manipulation ---
            "Phase Loss" -> PhaseLossResolver.resolve(state, request)
            "Phase Control" -> requireTierChoice(request, target0) { PhaseControlResolver.resolve(state, request, it) }

            else -> error("No resolver registered yet for ${request.card.name}")
        }
    }

    /** Applies every surviving entry from a resolved [com.tiersofexistence.engine.rules.precedence.InteractionChain]
     * (already in the correct resolution order — see [com.tiersofexistence.engine.rules.precedence.InteractionChain.resolve])
     * by dispatching each entry's own request in turn. */
    fun dispatchAll(state: GameState, entries: List<ChainEntry>): List<CardPlayResult> =
        entries.map { dispatch(state, it.request) }

    private inline fun requireTokenTarget(request: CardPlayRequest, target: CardTarget?, block: (CardTarget.Token) -> CardPlayResult): CardPlayResult =
        when (target) {
            is CardTarget.Token -> block(target)
            else -> CardPlayResult.Rejected(request, TargetValidationError.NoLegalTarget("${request.card.name} needs a token target, got $target"))
        }

    private inline fun requireAnyTarget(request: CardPlayRequest, target: CardTarget?, block: (CardTarget) -> CardPlayResult): CardPlayResult =
        target?.let(block) ?: CardPlayResult.Rejected(request, TargetValidationError.NoLegalTarget("${request.card.name} needs a target"))

    private inline fun requireTierChoice(request: CardPlayRequest, target: CardTarget?, block: (TierLevel) -> CardPlayResult): CardPlayResult =
        when (target) {
            is CardTarget.TierChoice -> block(target.tier)
            else -> CardPlayResult.Rejected(request, TargetValidationError.NoLegalTarget("${request.card.name} needs a Tier choice, got $target"))
        }

    private inline fun requireAllTokenTargets(request: CardPlayRequest, block: (List<CardTarget.Token>) -> CardPlayResult): CardPlayResult {
        val tokens = request.targets.filterIsInstance<CardTarget.Token>()
        if (tokens.size != request.targets.size) {
            return CardPlayResult.Rejected(request, TargetValidationError.NoLegalTarget("${request.card.name} needs only token targets, got ${request.targets}"))
        }
        return block(tokens)
    }
}
