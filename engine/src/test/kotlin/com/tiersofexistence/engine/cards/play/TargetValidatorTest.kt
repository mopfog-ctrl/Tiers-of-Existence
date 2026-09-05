package com.tiersofexistence.engine.cards.play

import com.tiersofexistence.engine.cards.FateHarvestCatalog
import com.tiersofexistence.engine.model.PlayerColor.BLACK
import com.tiersofexistence.engine.model.PlayerColor.RED
import com.tiersofexistence.engine.model.PlayerColor.YELLOW
import com.tiersofexistence.engine.model.TierLevel
import org.junit.jupiter.api.Test
import kotlin.test.assertIs
import kotlin.test.assertNull

class TargetValidatorTest {

    private fun cardNamed(name: String) = FateHarvestCatalog.all.single { it.name == name }

    // --- Color restriction (rule 18) ---

    @Test
    fun `a Color card played by its own color is legal`() {
        val corpuscleRot = cardNamed("Corpuscle Rot") // Yellow-restricted
        assertNull(TargetValidator.validateColorRestriction(corpuscleRot, YELLOW))
    }

    @Test
    fun `a Color card played by another color is rejected`() {
        val corpuscleRot = cardNamed("Corpuscle Rot")
        val error = TargetValidator.validateColorRestriction(corpuscleRot, RED)
        assertIs<TargetValidationError.WrongColor>(error)
    }

    @Test
    fun `a non-Color card has no color restriction`() {
        val tacticalMotion = cardNamed("Tactical Motion")
        assertNull(TargetValidator.validateColorRestriction(tacticalMotion, RED))
    }

    // --- Per-Phase card limit (rule 4) ---

    @Test
    fun `a Held card is blocked once the per-Phase limit is spent`() {
        val insidiousFlux = cardNamed("Insidious Flux")
        val error = TargetValidator.validatePhaseCardLimit(insidiousFlux, alreadyPlayedThisPhase = true)
        assertIs<TargetValidationError.PhaseCardLimitReached>(error)
    }

    @Test
    fun `a Held card is legal if the per-Phase limit hasn't been spent`() {
        val insidiousFlux = cardNamed("Insidious Flux")
        assertNull(TargetValidator.validatePhaseCardLimit(insidiousFlux, alreadyPlayedThisPhase = false))
    }

    @Test
    fun `a mandatory Immediate card is never blocked by the per-Phase limit`() {
        val divineAssistance = cardNamed("Divine Assistance")
        assertNull(TargetValidator.validatePhaseCardLimit(divineAssistance, alreadyPlayedThisPhase = true))
    }

    // --- Phase restriction (Planetary Nebula / Emitting Nebula) ---

    @Test
    fun `Planetary Nebula is legal during the 2nd Tier Phase`() {
        val planetaryNebula = cardNamed("Planetary Nebula")
        assertNull(TargetValidator.validatePhaseRestriction(planetaryNebula, com.tiersofexistence.engine.rules.Phase.Tier(TierLevel.SECOND)))
    }

    @Test
    fun `Planetary Nebula is illegal outside the 2nd Tier Phase`() {
        val planetaryNebula = cardNamed("Planetary Nebula")
        val error = TargetValidator.validatePhaseRestriction(planetaryNebula, com.tiersofexistence.engine.rules.Phase.Marauder)
        assertIs<TargetValidationError.WrongScope>(error)
    }

    @Test
    fun `a card without a phase restriction is legal in any Phase`() {
        val tacticalMotion = cardNamed("Tactical Motion")
        assertNull(TargetValidator.validatePhaseRestriction(tacticalMotion, com.tiersofexistence.engine.rules.Phase.Marauder))
    }

    // --- Zone of Protection (rule 12) ---

    @Test
    fun `a named exception card can affect an opponent's Zone-resident token`() {
        val graviton = cardNamed("Graviton Rift")
        val target = CardTarget.ZoneResidentToken(owner = RED, tier = TierLevel.FIRST, zoneNumber = 1)

        val error = TargetValidator.validateZoneOfProtection(graviton, sourcePlayer = BLACK, target, ownTokenMovementAllowed = false)

        assertNull(error)
    }

    @Test
    fun `a non-exception card cannot affect an opponent's Zone-resident token`() {
        val tacticalMotion = cardNamed("Tactical Motion")
        val target = CardTarget.ZoneResidentToken(owner = RED, tier = TierLevel.FIRST, zoneNumber = 1)

        val error = TargetValidator.validateZoneOfProtection(tacticalMotion, sourcePlayer = BLACK, target, ownTokenMovementAllowed = true)

        assertIs<TargetValidationError.ZoneOfProtectionBlocksTarget>(error)
    }

    @Test
    fun `own-token-movement carve-out lets a player move their own Zone-resident token`() {
        val tacticalMotion = cardNamed("Tactical Motion")
        val target = CardTarget.ZoneResidentToken(owner = RED, tier = TierLevel.FIRST, zoneNumber = 1)

        val error = TargetValidator.validateZoneOfProtection(tacticalMotion, sourcePlayer = RED, target, ownTokenMovementAllowed = true)

        assertNull(error)
    }

    @Test
    fun `the own-token carve-out does not apply to a card that isn't flagged as own-movement`() {
        // Infernal Abyss: a voluntary self-sacrifice, but the rulebook explicitly still blocks
        // targeting your own Zone-resident token — the carve-out is NOT "your own choice always
        // bypasses protection," only "your own MOVEMENT card moving your own token."
        val infernalAbyss = cardNamed("Infernal Abyss")
        val target = CardTarget.ZoneResidentToken(owner = RED, tier = TierLevel.FIRST, zoneNumber = 1)

        val error = TargetValidator.validateZoneOfProtection(infernalAbyss, sourcePlayer = RED, target, ownTokenMovementAllowed = false)

        assertIs<TargetValidationError.ZoneOfProtectionBlocksTarget>(error)
    }

    @Test
    fun `a Token target (not Zone-resident) is never blocked by Zone-of-Protection validation`() {
        val tacticalMotion = cardNamed("Tactical Motion")
        val target = CardTarget.Token(owner = RED, kind = com.tiersofexistence.engine.rules.TokenKind.TIER_TOKEN, tier = TierLevel.FIRST, position = 5)

        val error = TargetValidator.validateZoneOfProtection(tacticalMotion, sourcePlayer = BLACK, target, ownTokenMovementAllowed = false)

        assertNull(error)
    }
}
