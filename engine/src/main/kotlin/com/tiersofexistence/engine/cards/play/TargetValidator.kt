package com.tiersofexistence.engine.cards.play

import com.tiersofexistence.engine.cards.CardTiming
import com.tiersofexistence.engine.cards.FateHarvestCard
import com.tiersofexistence.engine.model.PlayerColor

/**
 * Centralized, card-agnostic target/timing legality (Phase E) — shared rules every card
 * implementation reuses, with card-specific exceptions layered on top by the caller, rather
 * than each card's own resolver re-deriving color/Phase-limit/Zone-of-Protection checks from
 * scratch. See `docs/card-mechanics-matrix.md` §0's rule table for the rulebook basis of each
 * check here.
 */
object TargetValidator {

    /** The 5 named Fate Harvest Card Rule #12 exceptions that can still affect a token inside a
     * Zone of Protection even when played by another player (rulebook.txt:393-396, 433-438). */
    val ZONE_OF_PROTECTION_EXCEPTIONS: Set<String> = setOf(
        "Divine Assistance",
        "Corpuscle Rot",
        "Galactic Roundabout",
        "Plasma Burst",
        "Graviton Rift",
    )

    /** Rule 18: a Color card may only be played by the matching player. */
    fun validateColorRestriction(card: FateHarvestCard, sourcePlayer: PlayerColor): TargetValidationError? {
        val restrictedTo = card.restrictedTo ?: return null
        if (restrictedTo == sourcePlayer) return null
        return TargetValidationError.WrongColor(
            "${card.name} may only be played by the $restrictedTo player, not $sourcePlayer",
        )
    }

    /**
     * Rule 4: only one Fate Harvest card per player per Phase. Immediate cards are mandatory
     * once drawn, so they are never blocked here — what happens when a mandatory Immediate draw
     * collides with an already-spent limit is a genuine rulebook silence, not resolved by this
     * engine (see `docs/card-mechanics-matrix.md` §4 Q1); this engine's choice is to always let
     * the mandatory play happen rather than silently drop the drawn card. A Held card played
     * voluntarily from hand IS blocked once the limit is spent for that Phase.
     */
    fun validatePhaseCardLimit(card: FateHarvestCard, alreadyPlayedThisPhase: Boolean): TargetValidationError? {
        if (card.timing == CardTiming.IMMEDIATE) return null
        if (!alreadyPlayedThisPhase) return null
        return TargetValidationError.PhaseCardLimitReached(
            "Only one Fate Harvest card may be played per player per Phase (rule 4); already played this Phase, cannot also play ${card.name}",
        )
    }

    /**
     * Zone-of-Protection legality for a single [target]. [ownTokenMovementAllowed] should be
     * true ONLY for cards whose effect is the owner moving their OWN token (rule 12's second
     * sentence: "You may... play a Fate Harvest movement card to move one of your own tokens in
     * the Z.O.P."). This is deliberately NOT a blanket "your own choice bypasses protection"
     * rule — Infernal Abyss, for example, explicitly can't target the player's own Zone-resident
     * token even though it's a voluntary self-sacrifice ("You may not sacrifice any Tier tokens
     * that are in the Zone of Protection"). Callers must set this per card's own text, never
     * infer it from "is this the source player's own token."
     */
    fun validateZoneOfProtection(
        card: FateHarvestCard,
        sourcePlayer: PlayerColor,
        target: CardTarget,
        ownTokenMovementAllowed: Boolean,
    ): TargetValidationError? {
        if (target !is CardTarget.ZoneResidentToken) return null
        if (ownTokenMovementAllowed && target.owner == sourcePlayer) return null
        if (card.name in ZONE_OF_PROTECTION_EXCEPTIONS) return null
        return TargetValidationError.ZoneOfProtectionBlocksTarget(
            "${card.name} cannot affect ${target.owner}'s token in Zone of Protection ${target.zoneNumber} on ${target.tier}",
        )
    }
}
