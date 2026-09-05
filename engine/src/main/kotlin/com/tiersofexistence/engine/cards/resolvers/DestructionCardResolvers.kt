package com.tiersofexistence.engine.cards.resolvers

import com.tiersofexistence.engine.cards.play.CardLifecycle
import com.tiersofexistence.engine.cards.play.CardPlayRequest
import com.tiersofexistence.engine.cards.play.CardPlayResult
import com.tiersofexistence.engine.cards.play.CardTarget
import com.tiersofexistence.engine.cards.play.TargetValidationError
import com.tiersofexistence.engine.cards.play.TargetValidator
import com.tiersofexistence.engine.cards.play.TokenLocation
import com.tiersofexistence.engine.cards.play.TokenLocator
import com.tiersofexistence.engine.cards.play.tierOrNull
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.model.TokenKind
import com.tiersofexistence.engine.state.GameState

/**
 * Shared resolver for cards that directly destroy one token, wherever it is — Divine Assistance
 * (any token anywhere, including a Staging Pile — a named Zone-of-Protection exception),
 * Insidious Flux (one arbitrary token from a chosen Staging Pile). Destruction cards never get
 * the rule-12 "own movement" carve-out (unlike [MovementCardResolver]) — a destroy is never a
 * "move your own token" — so [TargetValidator.validateZoneOfProtection] is always called with
 * `ownTokenMovementAllowed = false` here; whether a ZoP target is reachable at all comes down
 * entirely to whether the card is one of the 5 named rule-12 exceptions.
 *
 * A [CardTarget.Token] is re-located via [TokenLocator] at resolution time (never a stale
 * recorded position) — if it no longer exists, [resolve] rejects gracefully instead of crashing.
 */
object DestructionCardResolver {
    fun resolve(state: GameState, request: CardPlayRequest, target: CardTarget): CardPlayResult {
        val rejection = validateExistenceAndZone(state, request, target)
        if (rejection != null) return rejection

        val playResult = CardLifecycle.attemptPlay(state, request)
        if (playResult !is CardPlayResult.Resolved) return playResult

        destroy(state, target)
        return playResult
    }

    /**
     * Checks [target] can actually be destroyed right now — still exists (for identity-based
     * targets, via [TokenLocator]) and isn't Zone-protected against this card — without mutating
     * anything. Returns the rejection to return, or null if [target] is clear to destroy. Split
     * out from [resolve] so [GravitonRiftResolver] can validate every one of its multiple targets
     * up front before committing to (and partially applying) the whole play.
     */
    fun validateExistenceAndZone(state: GameState, request: CardPlayRequest, target: CardTarget): CardPlayResult.Rejected? {
        if (target !is CardTarget.Token) return null
        val location = TokenLocator.locate(state, target.id)
        if (location is TokenLocation.NoLongerExists) {
            return CardPlayResult.Rejected(request, TargetValidationError.NoLegalTarget("${target.id} no longer exists"))
        }
        val zoneError = TargetValidator.validateZoneOfProtection(
            request.card,
            request.sourcePlayer,
            target.id.owner,
            location,
            ownTokenMovementAllowed = false,
        )
        return zoneError?.let { CardPlayResult.Rejected(request, it) }
    }

    internal fun destroy(state: GameState, target: CardTarget) {
        when (target) {
            is CardTarget.Token -> {
                val player = state.players.getValue(target.id.owner)
                when (target.id.kind) {
                    TokenKind.TIER_TOKEN -> player.tierPool(target.id.tier).destroyById(target.id)
                    TokenKind.MARAUDER -> player.marauders.destroyById(target.id)
                }
            }
            is CardTarget.StagingPileToken -> state.players.getValue(target.owner).tierPool(target.tier).destroyFromStagingPile()
            else -> error("Unsupported destruction target: $target")
        }
    }
}

/**
 * Infernal Abyss: a voluntary self-sacrifice of exactly one of the player's own tokens, from any
 * Tier, any type — but, unusually for a "your own choice" effect, still explicitly barred from
 * targeting the player's own Zone-resident token (rulebook: "You may not sacrifice any Tier
 * tokens that are in the Zone of Protection"). This is why [DestructionCardResolver] is called
 * with `ownTokenMovementAllowed = false` here too, same as any other destruction card — Infernal
 * Abyss gets NO Zone-of-Protection carve-out of its own, despite being self-targeted.
 */
object InfernalAbyssResolver {
    fun resolve(state: GameState, request: CardPlayRequest, target: CardTarget.Token): CardPlayResult {
        if (target.id.owner != request.sourcePlayer) {
            return CardPlayResult.Rejected(
                request,
                TargetValidationError.WrongTokenType("Infernal Abyss may only sacrifice your own token, not ${target.id.owner}'s"),
            )
        }
        return DestructionCardResolver.resolve(state, request, target)
    }
}

/**
 * Graviton Rift: the Black player removes any one token (of any type) from EACH Tier — up to 4
 * total, skipping any Tier with no tokens on it. [targets] should contain at most one entry per
 * Tier (the caller only includes an entry for a Tier where a token was actually chosen); a
 * named Zone-of-Protection exception (rule 12), so [DestructionCardResolver]'s ZoP check always
 * passes here regardless — still run uniformly rather than special-cased away, so this stays
 * correct if the exception list ever changes.
 *
 * Every target's existence/Zone legality is validated BEFORE any of them are destroyed — a
 * compound effect like this must not partially apply just because one target (of possibly four)
 * turns out to have already stopped existing by resolution time; the whole play is rejected
 * instead, atomically, rather than destroying some targets and then crashing or silently
 * skipping the rest.
 */
object GravitonRiftResolver {
    fun resolve(state: GameState, request: CardPlayRequest, targets: List<CardTarget.Token>): CardPlayResult {
        val tiers = targets.map { it.id.tier }
        require(tiers.size == tiers.distinct().size) { "Graviton Rift may only target one token per Tier, got $targets" }

        for (target in targets) {
            val rejection = DestructionCardResolver.validateExistenceAndZone(state, request, target)
            if (rejection != null) return rejection
        }

        val playResult = CardLifecycle.attemptPlay(state, request)
        if (playResult !is CardPlayResult.Resolved) return playResult

        targets.forEach { DestructionCardResolver.destroy(state, it) }
        return playResult
    }
}

/**
 * Corpuscle Rot: a compound Yellow-only effect — destroy one 4th Tier token (any owner, a named
 * Zone-of-Protection exception, reusing [DestructionCardResolver] for that half) AND start one
 * new token each on the Yellow player's own 1st and 2nd Tier Birth Canals. Per
 * `docs/card-mechanics-matrix.md`'s Ambiguity Q3, the rulebook's "any 4th Tier token" is
 * unqualified — this resolver does not restrict [target] to an opponent's token, matching that
 * reading rather than guessing a narrower one.
 */
object CorpuscleRotResolver {
    fun resolve(state: GameState, request: CardPlayRequest, target: CardTarget.Token): CardPlayResult {
        require(target.tierOrNull == TierLevel.FOURTH) { "Corpuscle Rot's destroy target must be on the 4th Tier, was $target" }

        val destroyResult = DestructionCardResolver.resolve(state, request, target)
        if (destroyResult !is CardPlayResult.Resolved) return destroyResult

        val player = state.players.getValue(request.sourcePlayer)
        player.tierPool(TierLevel.FIRST).startToken()
        player.tierPool(TierLevel.SECOND).startToken()
        return destroyResult
    }
}
