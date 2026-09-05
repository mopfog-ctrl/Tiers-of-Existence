package com.tiersofexistence.engine.state

import com.tiersofexistence.engine.model.TierLevel

/**
 * One player's Tier tokens for a single Tier. Physical tokens are fungible, so we track
 * counts per zone rather than individual token identity ("Matter is neither destroyed nor
 * created!" — Ion Batteries and Tokens, rulebook p.3).
 *
 * Zones, per the rulebook:
 * - [ionBattery]: the player's draw pile — tokens not currently on a board.
 * - [hatchery]: tokens waiting for an in-play slot to free up (beyond [TierLevel.maxInPlay]).
 * - [stagingPile]: tokens landed on a Nebula, waiting to reach [TierLevel.stagingPileThreshold].
 * - [inPlayPositions]: main-loop board square indices of tokens currently on this Tier's board.
 * - [zoneResidents]: Zone-of-Protection numbers (see
 *   [com.tiersofexistence.engine.board.ProtectionZone.number]) currently occupied by tokens of
 *   this pool. A token here is NOT also in [inPlayPositions] — entering a Zone removes it from
 *   the main loop (see [enterZone]), which is what makes ordinary movement/pass-through scans
 *   (keyed on [inPlayPositions]) naturally skip protected tokens without a separate check
 *   scattered through movement code. Still counted as "in play" for [inPlayCount]/the
 *   [TierLevel.maxInPlay] cap — a token inside a Zone is still on the board, per the rulebook's
 *   "a token in play is on the board, but does not include tokens in Staging Piles."
 */
class TierTokenPool(val tier: TierLevel) {
    var ionBattery: Int = tier.tokensPerPlayer
        private set
    var hatchery: Int = 0
        private set
    var stagingPile: Int = 0
        private set
    val inPlayPositions: MutableList<Int> = mutableListOf()
    val zoneResidents: MutableList<Int> = mutableListOf()

    val inPlayCount: Int get() = inPlayPositions.size + zoneResidents.size

    /** Total tokens owned across every zone; must always equal [TierLevel.tokensPerPlayer]. */
    val totalOwned: Int get() = ionBattery + hatchery + stagingPile + inPlayCount

    init {
        check(totalOwned == tier.tokensPerPlayer)
    }

    /**
     * Starts a token on this Tier's Birth Canal (index 0), per rulebook "Birth Canal" rule.
     * Prefers a token waiting in the Hatchery over drawing a fresh one from the Ion Battery,
     * matching "a token may be moved to the Birth Canal/Start square" once a Hatchery slot's
     * blocking in-play token frees up (Gameboard Rules, Second/Third/Fourth Tier paragraph).
     *
     * If there's no room in play, the new token queues in the Hatchery instead
     * (Gameboard Rules: "Only two Tier tokens are allowed in play... Extra tokens must wait
     * on the Hatchery.").
     */
    fun startToken() {
        val hasRoom = inPlayCount < tier.maxInPlay
        when {
            hasRoom && hatchery > 0 -> {
                hatchery -= 1
                inPlayPositions += 0
            }
            hasRoom && ionBattery > 0 -> {
                ionBattery -= 1
                inPlayPositions += 0
            }
            !hasRoom && ionBattery > 0 -> {
                ionBattery -= 1
                hatchery += 1
            }
            else -> error("No tokens available to start on $tier (Ion Battery and Hatchery both empty of movable tokens)")
        }
    }

    /** Removes an in-play token (destroyed) and returns it to the Ion Battery. */
    fun destroyInPlay(position: Int) {
        require(inPlayPositions.remove(position)) { "No in-play token at position $position on $tier" }
        ionBattery += 1
        promoteFromHatcheryIfRoom()
    }

    /** Moves an in-play token to its Nebula Staging Pile (Gameboard Rules: "Nebula"). */
    fun sendToStagingPile(position: Int) {
        require(inPlayPositions.remove(position)) { "No in-play token at position $position on $tier" }
        stagingPile += 1
        promoteFromHatcheryIfRoom()
    }

    /**
     * Removes an in-play token that was promoted to the next Tier via a Wormhole of
     * Construction (the token itself returns to the Ion Battery; the caller is responsible
     * for starting the next Tier's token).
     */
    fun promoteInPlayToken(position: Int) {
        require(inPlayPositions.remove(position)) { "No in-play token at position $position on $tier" }
        ionBattery += 1
        promoteFromHatcheryIfRoom()
    }

    /**
     * Consumes [TierLevel.stagingPileThreshold] tokens from the Staging Pile once it's full,
     * returning them to the Ion Battery, per "Once there are enough tokens in the Staging
     * Pile, they are placed in your Ion Battery and you start a token of the next Tier."
     * Returns true if a promotion was triggered (caller must then start a token on the next Tier).
     */
    fun tryPromoteFromStagingPile(): Boolean {
        val threshold = tier.stagingPileThreshold ?: return false
        if (stagingPile < threshold) return false
        stagingPile -= threshold
        ionBattery += threshold
        return true
    }

    /** Moves a token to Start (e.g. Vortex of Regression) without changing zone counts. */
    fun moveInPlay(fromPosition: Int, toPosition: Int) {
        require(inPlayPositions.remove(fromPosition)) { "No in-play token at position $fromPosition on $tier" }
        inPlayPositions += toPosition
    }

    /** Removes a Staging Pile token directly (Insidious Flux, Divine Assistance) — no board
     * position involved, doesn't run the promotion check since the pile only shrinks. */
    fun destroyFromStagingPile() {
        require(stagingPile > 0) { "No token in the Staging Pile on $tier" }
        stagingPile -= 1
        ionBattery += 1
    }

    /** Adds a token directly to the Staging Pile (Lucky/Luckier/Emitting Nebula), running the
     * same promotion check a Nebula landing would. Returns true if a promotion was triggered
     * (caller must then start a token on the next Tier), matching [sendToStagingPile]'s caller
     * contract via [tryPromoteFromStagingPile]. */
    fun addToStagingPileDirectly(): Boolean {
        stagingPile += 1
        return tryPromoteFromStagingPile()
    }

    /**
     * Moves an in-play token off the main loop and into Zone [zoneNumber] — landing on that
     * Zone's numbered entry square (Gameboard Rules: "Zone of Protection"). See the class doc:
     * this removes the token from [inPlayPositions] entirely, which is what makes it invisible
     * to ordinary movement/pass-through scans without a separate check.
     */
    fun enterZone(fromPosition: Int, zoneNumber: Int) {
        require(inPlayPositions.remove(fromPosition)) { "No in-play token at position $fromPosition on $tier" }
        zoneResidents += zoneNumber
    }

    /** Moves a token out of Zone [zoneNumber] back onto the main loop at [toPosition] — only
     * ever triggered by a specific card effect (the rulebook describes no ordinary/dice-driven
     * way to leave a Zone; see `docs/card-mechanics-matrix.md` §4 Q17). */
    fun leaveZone(zoneNumber: Int, toPosition: Int) {
        require(zoneResidents.remove(zoneNumber)) { "No token in Zone $zoneNumber on $tier" }
        inPlayPositions += toPosition
    }

    /** Destroys a token that's currently inside Zone [zoneNumber] — only legal for the 5 named
     * Fate Harvest Card Rule #12 exceptions (Divine Assistance, Corpuscle Rot, Galactic
     * Roundabout, Plasma Burst, Graviton Rift); callers are responsible for that check. */
    fun destroyInZone(zoneNumber: Int) {
        require(zoneResidents.remove(zoneNumber)) { "No token in Zone $zoneNumber on $tier" }
        ionBattery += 1
        promoteFromHatcheryIfRoom()
    }

    private fun promoteFromHatcheryIfRoom() {
        if (hatchery > 0 && inPlayCount < tier.maxInPlay) {
            hatchery -= 1
            inPlayPositions += 0
        }
    }
}
