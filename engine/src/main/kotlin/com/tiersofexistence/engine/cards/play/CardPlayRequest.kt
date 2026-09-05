package com.tiersofexistence.engine.cards.play

import com.tiersofexistence.engine.cards.FateHarvestCard
import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.TierLevel

/**
 * What caused a card to be in play right now. Needed by target/timing validation (an Immediate
 * card drawn from a square resolves in a different context than a Held card played later out of
 * hand) and by the interaction engine (a card played as a Precedence response belongs to a chain,
 * see [com.tiersofexistence.engine.rules.precedence.InteractionChain]).
 */
sealed class TriggeringEvent {
    /** Drawn from a Fate Harvest square during a Tier Phase turn — the only path for a mandatory
     * Immediate card, and the usual path for a fresh Held draw (which may instead go to hand). */
    data class DrawnFromSquare(val tier: TierLevel, val squarePosition: Int) : TriggeringEvent()

    /** Played later out of a player's hand (Held cards only — an Immediate card is never held). */
    data object PlayedFromHand : TriggeringEvent()

    /** Played as a Precedence response inside an already-open interaction chain. */
    data class RespondingInChain(val chainId: Long) : TriggeringEvent()
}

/**
 * One attempt to play a card: who, which card, at what/whom, and why it's happening now.
 * Distinct from [FateHarvestCard] (the catalog entry, describing a card in the abstract) — this
 * is the live, per-play request that gets validated ([com.tiersofexistence.engine.cards.play.TargetValidator])
 * and resolved.
 */
data class CardPlayRequest(
    val sourcePlayer: PlayerColor,
    val card: FateHarvestCard,
    val targets: List<CardTarget> = emptyList(),
    val triggeringEvent: TriggeringEvent,
)
