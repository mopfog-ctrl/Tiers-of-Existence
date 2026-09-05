package com.tiersofexistence.engine.cards

import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.TierLevel

/** Whether a card must be played the instant it's drawn, or may be held in hand. */
enum class CardTiming { IMMEDIATE, HELD }

/** When during the game a held/playable card may be played. Null means the rulebook text doesn't state one explicitly. */
enum class CardScope { YOUR_TURN, ANY_TIME }

/** The four print-run rarities; [copies] is how many physical copies are in the 70-card deck. */
enum class CardRarity(val copies: Int) {
    SINGLE(1),
    DOUBLE(2),
    TRIPLE(3),
    QUADRUPLE(4),
}

/**
 * One entry in the Fate Harvest Card List (rulebook p.11+). [effect] and [flavorText] are
 * transcribed verbatim (flavor text lightly trimmed). Color cards ([restrictedTo] non-null)
 * may only be played by that player color (Fate Harvest Card Rules #18).
 *
 * [restrictedToPhase] is a second, narrower timing restriction two cards carry on top of
 * [CardScope.YOUR_TURN] — Planetary Nebula ("only playable during your turn of the 2nd Tier
 * Phase") and Emitting Nebula ("only playable during your turn of the 1st Tier Phase"). This is
 * printed card text, like [restrictedTo], not derived/runtime state — `docs/card-mechanics-
 * matrix.md` §4 Q7 flagged that the two-value [CardScope] enum can't express it on its own.
 */
data class FateHarvestCard(
    val name: String,
    val rarity: CardRarity,
    val timing: CardTiming,
    val scope: CardScope?,
    val restrictedTo: PlayerColor? = null,
    val restrictedToPhase: TierLevel? = null,
    val hasPrecedence: Boolean = false,
    val effect: String,
    val flavorText: String,
) {
    val isColorCard: Boolean get() = restrictedTo != null
}
