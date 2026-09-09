package com.tiersofexistence.engine.state

import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.TierLevel
import com.tiersofexistence.engine.model.TokenKind

/** One in-play token's stable identity paired with its current main-loop position. */
private data class InPlaySlot(val id: TokenId, val position: Int)

/** One Zone-resident token's stable identity, which Zone it's currently in, and its own
 * position within that Zone's [com.tiersofexistence.engine.board.ProtectionZone.squares] —
 * 1-indexed, since a Zone is a real dice-driven sub-path (confirmed by the user), not just an
 * undifferentiated "protected" flag. Position 1 is the first slot, entered directly from the
 * Zone's own main-loop entry square. */
private data class ZoneSlot(val id: TokenId, val zoneNumber: Int, val zonePosition: Int)

/**
 * One player's Tier tokens for a single Tier. Physical tokens are fungible in the sense that
 * nothing about a token's own printed appearance distinguishes it from another of the same
 * Tier/color — but each one, once in play, is tracked by a stable [TokenId] (see that class doc)
 * so a Fate Harvest card can target "this specific token" and have it found correctly even after
 * it has moved, per [positionOf]/[zoneOf]. [owner] is needed to mint that identity.
 *
 * Zones, per the rulebook:
 * - [ionBattery]: the player's draw pile — tokens not currently on a board. On the 1st Tier
 *   specifically, this doubles as the overflow pool [hatchery] is elsewhere: "all tokens beyond
 *   the two in play... remain in the player's Ion Battery... When there are less than two 1st
 *   Tier tokens in play..., a new... token is taken from the Ion Battery and placed on Start" —
 *   see [refillInPlayIfRoom].
 * - [hatchery]: tokens waiting for an in-play slot to free up (beyond [TierLevel.maxInPlay]) on
 *   the 2nd/3rd/4th Tier. The 1st Tier's own overflow rule above uses the Ion Battery directly
 *   instead — nothing normally accumulates here for a 1st Tier pool (see [refillInPlayIfRoom]),
 *   though [startToken] can still route an overflow here if ever called with the cap already
 *   reached, since it's drained as readily as the Ion Battery would be.
 * - [stagingPile]: tokens landed on a Nebula, waiting to reach [TierLevel.stagingPileThreshold].
 *   Staging Pile contents are genuinely fungible (no card ever needs to pick a *specific*
 *   Staging Pile token over another one), so unlike in-play/Zone tokens this is still a plain
 *   count, not identity-tracked.
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
class TierTokenPool(val tier: TierLevel, val owner: PlayerColor) {
    var ionBattery: Int = tier.tokensPerPlayer
        private set
    var hatchery: Int = 0
        private set
    var stagingPile: Int = 0
        private set

    private val inPlay: MutableList<InPlaySlot> = mutableListOf()
    private val inZone: MutableList<ZoneSlot> = mutableListOf()

    /** Main-loop positions of every in-play token, in no particular guaranteed order — a
     * read-only view for callers that only care about positions, not identity. */
    val inPlayPositions: List<Int> get() = inPlay.map { it.position }

    /** [TokenId]s of every in-play (main-loop) token, in no particular guaranteed order — one
     * entry per token, including stacked duplicates at the same position. Unlike
     * [inPlayPositions], this lets a caller that needs to move or check on *every* token (e.g.
     * Galactic Roundabout's whole-board sweep) snapshot identities up front and resolve each
     * one's position fresh via [positionOf] at the moment it's actually moved, rather than
     * re-deriving identity from a position that may have already shifted underneath it. */
    val inPlayIds: List<TokenId> get() = inPlay.map { it.id }

    /** Zone numbers of every Zone-resident token — a read-only view; see [inPlayPositions]. */
    val zoneResidents: List<Int> get() = inZone.map { it.zoneNumber }

    /** [TokenId]s of every Zone-resident token, in no particular guaranteed order — one entry
     * per token, including more than one token sharing the same Zone; see [inPlayIds]. */
    val zoneResidentIds: List<TokenId> get() = inZone.map { it.id }

    val inPlayCount: Int get() = inPlay.size + inZone.size

    /** Total tokens owned across every zone; must always equal [TierLevel.tokensPerPlayer]. */
    val totalOwned: Int get() = ionBattery + hatchery + stagingPile + inPlayCount

    init {
        check(totalOwned == tier.tokensPerPlayer)
    }

    /** The [TokenId] of the in-play token at [position], or null if none is there. Ambiguous if
     * more than one token shares a position (landing on a token doesn't destroy it, so stacking
     * is legal) — returns the first found, same arbitrary-but-deterministic tie-break the
     * position-based methods below have always had. */
    fun idAt(position: Int): TokenId? = inPlay.firstOrNull { it.position == position }?.id

    /** The [TokenId] of the token currently inside Zone [zoneNumber], or null if none is there. */
    fun idInZone(zoneNumber: Int): TokenId? = inZone.firstOrNull { it.zoneNumber == zoneNumber }?.id

    /** [id]'s current main-loop position, or null if it's not in play there right now (destroyed,
     * promoted, staged, or currently a Zone resident instead — see [zoneOf]). */
    fun positionOf(id: TokenId): Int? = inPlay.firstOrNull { it.id == id }?.position

    /** [id]'s current Zone number, or null if it's not a Zone resident right now. */
    fun zoneOf(id: TokenId): Int? = inZone.firstOrNull { it.id == id }?.zoneNumber

    /** [id]'s current 1-indexed position within its Zone's own square sequence, or null if it's
     * not a Zone resident right now. See [ZoneSlot]. */
    fun zonePositionOf(id: TokenId): Int? = inZone.firstOrNull { it.id == id }?.zonePosition

    /**
     * Starts a token on this Tier's Birth Canal (index 0), per rulebook "Birth Canal" rule.
     * Prefers a token waiting in the Hatchery over drawing a fresh one from the Ion Battery,
     * matching "a token may be moved to the Birth Canal/Start square" once a Hatchery slot's
     * blocking in-play token frees up (Gameboard Rules, Second/Third/Fourth Tier paragraph).
     *
     * If there's no room in play, the new token queues in the Hatchery instead
     * (Gameboard Rules: "Only two Tier tokens are allowed in play... Extra tokens must wait
     * on the Hatchery."). Returns the new token's [TokenId] when it actually enters play or the
     * Hatchery, so a caller that needs to reference it later (a UI, a test) can capture it
     * without a separate lookup. A token that overflows straight to the Hatchery has no
     * observable identity yet — no [com.tiersofexistence.engine.cards.play.CardTarget] can
     * reference a Hatchery-resident token — so the id returned in that case is not retained;
     * [refillInPlayIfRoom] mints a fresh one once it actually enters play later, which is
     * harmless since nothing could have held a reference to the discarded one in the meantime.
     *
     * Returns null, rather than crashing, when every one of this Tier's [tier.tokensPerPlayer]
     * physical tokens is already accounted for elsewhere (some combination of in play, Hatchery,
     * and Staging Pile) — i.e. this Tier's own Ion Battery is empty and there's no Hatchery-
     * waiting token to promote either. This is a rare but legitimate, rulebook-anticipated state,
     * not an engine invariant violation: "In the unlikely event that a player runs out of tokens
     * of a certain Tier, they must wait" (rulebook.txt:150-163) — a null return IS that "must
     * wait," not a bug. Every caller (an upper-Tier promotion via Wormhole of Construction, or a
     * construction card starting a fresh token) is expected to simply skip starting a token when
     * this happens, rather than treating it as a crash — see e.g.
     * [com.tiersofexistence.engine.cards.resolvers.BirthCanalConstructionCardResolver]'s doc for
     * why one Tier being exhausted must not block a compound card's other Tiers.
     */
    fun startToken(): TokenId? {
        val hasRoom = inPlayCount < tier.maxInPlay
        if (!hasRoom && ionBattery <= 0) return null
        if (hasRoom && hatchery <= 0 && ionBattery <= 0) return null
        val id = TokenIdGenerator.next(owner, TokenKind.TIER_TOKEN, tier)
        when {
            hasRoom && hatchery > 0 -> {
                hatchery -= 1
                inPlay += InPlaySlot(id, 0)
            }
            hasRoom && ionBattery > 0 -> {
                ionBattery -= 1
                inPlay += InPlaySlot(id, 0)
            }
            else -> {
                ionBattery -= 1
                hatchery += 1
            }
        }
        return id
    }

    /** Removes an in-play token (destroyed) and returns it to the Ion Battery. */
    fun destroyInPlay(position: Int) {
        val slot = inPlay.firstOrNull { it.position == position }
        require(slot != null) { "No in-play token at position $position on $tier" }
        inPlay.remove(slot)
        ionBattery += 1
        refillInPlayIfRoom()
    }

    /** Destroys the token identified by [id], wherever it currently is (in play or in a Zone).
     * Callers are expected to have already confirmed [id] still exists (e.g. via
     * [com.tiersofexistence.engine.cards.play.TokenLocator]) — this throws if it doesn't, as an
     * internal-consistency check, not a player-facing legality check. */
    fun destroyById(id: TokenId) {
        val inPlaySlot = inPlay.firstOrNull { it.id == id }
        if (inPlaySlot != null) {
            inPlay.remove(inPlaySlot)
            ionBattery += 1
            refillInPlayIfRoom()
            return
        }
        val zoneSlot = inZone.firstOrNull { it.id == id }
        if (zoneSlot != null) {
            inZone.remove(zoneSlot)
            ionBattery += 1
            refillInPlayIfRoom()
            return
        }
        error("Token $id no longer exists on $tier")
    }

    /** Moves an in-play token to its Nebula Staging Pile (Gameboard Rules: "Nebula"). */
    fun sendToStagingPile(position: Int) {
        val slot = inPlay.firstOrNull { it.position == position }
        require(slot != null) { "No in-play token at position $position on $tier" }
        inPlay.remove(slot)
        stagingPile += 1
        refillInPlayIfRoom()
    }

    /**
     * Removes an in-play token that was promoted to the next Tier via a Wormhole of
     * Construction (the token itself returns to the Ion Battery; the caller is responsible
     * for starting the next Tier's token).
     */
    fun promoteInPlayToken(position: Int) {
        val slot = inPlay.firstOrNull { it.position == position }
        require(slot != null) { "No in-play token at position $position on $tier" }
        inPlay.remove(slot)
        ionBattery += 1
        refillInPlayIfRoom()
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
        val slot = inPlay.firstOrNull { it.position == fromPosition }
        require(slot != null) { "No in-play token at position $fromPosition on $tier" }
        inPlay.remove(slot)
        inPlay += InPlaySlot(slot.id, toPosition)
    }

    /** Moves the in-play token [id] to [toPosition] — identity-based, so it's unambiguous even
     * when another token (this pool's own or another player's) already shares [id]'s starting
     * position. Prefer this over [moveInPlay] whenever the specific token is already known by
     * id, rather than derived from a position lookup — e.g. Galactic Roundabout's whole-board
     * sweep, which must move each snapshotted token exactly once even if an earlier token in
     * the same sweep has already moved onto a not-yet-processed token's position. */
    fun moveById(id: TokenId, toPosition: Int) {
        val slot = inPlay.firstOrNull { it.id == id }
        require(slot != null) { "Token $id is not in play on $tier" }
        inPlay.remove(slot)
        inPlay += InPlaySlot(id, toPosition)
    }

    /** Removes a Staging Pile token directly (Insidious Flux, Divine Assistance) — no board
     * position involved, doesn't run the promotion check since the pile only shrinks. */
    fun destroyFromStagingPile() {
        require(stagingPile > 0) { "No token in the Staging Pile on $tier" }
        stagingPile -= 1
        ionBattery += 1
    }

    /** Radiation Burst: empties the Staging Pile entirely, returning every token in it to the
     * Ion Battery. Confirmed by the user: this runs NO promotion check, even if the pile
     * happened to be at or above [TierLevel.stagingPileThreshold] when emptied — unlike an
     * ordinary Nebula landing or a direct add ([addToStagingPileDirectly]), reaching the
     * threshold this way never counts as "enough to promote." A no-op if already empty. */
    fun emptyStagingPile() {
        if (stagingPile == 0) return
        ionBattery += stagingPile
        stagingPile = 0
    }

    /**
     * Fluidic Wave: destroys every in-play token and empties the Staging Pile in one sweep, all
     * returning to the Ion Battery — but deliberately leaves [inZone] (and [hatchery]) alone,
     * matching the card's own text: "removes all tokens from the 1st Tier... in play as well as
     * tokens in Staging Piles, but does not include Tier tokens in the Zone of Protection." Runs
     * no promotion check (these tokens are being destroyed, not banked toward a Wormhole-style
     * advance) but does run [refillInPlayIfRoom] afterward like every other slot-freeing
     * mutation — on the 1st Tier this can immediately repopulate up to 2 fresh in-play tokens
     * from the Ion Battery, per that Tier's own auto-replenishment rule.
     */
    fun destroyAllInPlayAndStagingPile() {
        ionBattery += inPlay.size + stagingPile
        inPlay.clear()
        stagingPile = 0
        refillInPlayIfRoom()
    }

    /**
     * Adds a token directly to the Staging Pile (Lucky/Luckier/Emitting Nebula: "Place a
     * Dimensional Token in your Staging Pile"), running the same promotion check a Nebula
     * landing would. Returns true if a promotion was triggered (caller must then start a token
     * on the next Tier), matching [sendToStagingPile]'s caller contract via
     * [tryPromoteFromStagingPile].
     *
     * **Fixed: this token must come from somewhere.** An earlier version simply did
     * `stagingPile += 1` with no corresponding decrement anywhere else — manufacturing a brand
     * new physical token out of nothing, breaking [totalOwned]'s own invariant (caught by
     * `GameSimulationTest`'s randomized-play harness: every game that played a Lucky/Luckier/
     * Emitting Nebula drifted to `totalOwned == tokensPerPlayer + 1` for that Tier). "Place a
     * Dimensional Token in your Staging Pile" is exactly what an ordinary Nebula landing already
     * does for an in-play token — moving an *existing* token into the pile, never creating one —
     * so this direct version needs the same source [startToken] already draws from: a
     * Hatchery-waiting token preferred over a fresh one from the Ion Battery (same order
     * [startToken] uses). Gracefully places nothing (returns false, same as "no promotion") when
     * both are empty, matching [startToken]'s own "must wait" contract rather than going
     * negative — the rulebook's "matter is neither destroyed nor created" applies here exactly as
     * everywhere else a Tier token changes zones.
     */
    fun addToStagingPileDirectly(): Boolean {
        when {
            hatchery > 0 -> hatchery -= 1
            ionBattery > 0 -> ionBattery -= 1
            else -> return false
        }
        stagingPile += 1
        return tryPromoteFromStagingPile()
    }

    /**
     * Moves an in-play token off the main loop and into Zone [zoneNumber], at the Zone's own
     * first slot (position 1) — landing on that Zone's numbered entry square (Gameboard Rules:
     * "Zone of Protection"). See the class doc: this removes the token from [inPlayPositions]
     * entirely, which is what makes it invisible to ordinary movement/pass-through scans without
     * a separate check. The token's [TokenId] is carried over unchanged.
     */
    fun enterZone(fromPosition: Int, zoneNumber: Int) {
        val slot = inPlay.firstOrNull { it.position == fromPosition }
        require(slot != null) { "No in-play token at position $fromPosition on $tier" }
        inPlay.remove(slot)
        inZone += ZoneSlot(slot.id, zoneNumber, zonePosition = 1)
    }

    /** Moves [id] to [newZonePosition] within the Zone it's already resident in, without leaving
     * it — the "stay inside the Zone" case of [com.tiersofexistence.engine.rules.TurnEngine
     * .moveZoneToken], now that a Zone is a confirmed real dice-driven sub-path. */
    fun advanceInZone(id: TokenId, newZonePosition: Int) {
        val slot = inZone.firstOrNull { it.id == id }
        require(slot != null) { "Token $id is not a Zone resident on $tier" }
        inZone.remove(slot)
        inZone += ZoneSlot(id, slot.zoneNumber, newZonePosition)
    }

    /** Moves the token identified by [id] out of whichever Zone it's resident in, back onto the
     * main loop at [toPosition] — either via a specific card effect (rule 12's own-token-own-Zone
     * carve-out) or via [com.tiersofexistence.engine.rules.TurnEngine.moveZoneToken] moving it
     * past the end of its Zone's own square sequence. Identity-based (not zone-number-based)
     * since more than one token can share a Zone. */
    fun leaveZone(id: TokenId, toPosition: Int) {
        val slot = inZone.firstOrNull { it.id == id }
        require(slot != null) { "Token $id is not a Zone resident on $tier" }
        inZone.remove(slot)
        inPlay += InPlaySlot(id, toPosition)
    }

    /** Zone-internal Nebula: moves the Zone-resident token [id] straight to the Staging Pile,
     * same as [sendToStagingPile] does for a main-loop Nebula — the token leaves the Zone
     * entirely (no longer resident anywhere), matching how landing on a main-loop Nebula removes
     * a token from [inPlayPositions]. */
    fun sendZoneResidentToStagingPile(id: TokenId) {
        val slot = inZone.firstOrNull { it.id == id }
        require(slot != null) { "Token $id is not a Zone resident on $tier" }
        inZone.remove(slot)
        stagingPile += 1
        refillInPlayIfRoom()
    }

    /** Zone-internal Wormhole of Construction: promotes the Zone-resident token [id] to the next
     * Tier, same as [promoteInPlayToken] does for a main-loop Wormhole (confirmed for the 1st
     * Tier's Zone 2, which has one of these as its own 4th slot) — the caller is responsible for
     * starting the next Tier's token, same contract as [promoteInPlayToken]. */
    fun promoteZoneResident(id: TokenId) {
        val slot = inZone.firstOrNull { it.id == id }
        require(slot != null) { "Token $id is not a Zone resident on $tier" }
        inZone.remove(slot)
        ionBattery += 1
        refillInPlayIfRoom()
    }

    /** Destroys a token that's currently inside Zone [zoneNumber] — only legal for the 5 named
     * Fate Harvest Card Rule #12 exceptions (Divine Assistance, Corpuscle Rot, Galactic
     * Roundabout, Plasma Burst, Graviton Rift); callers are responsible for that check. */
    fun destroyInZone(zoneNumber: Int) {
        val slot = inZone.firstOrNull { it.zoneNumber == zoneNumber }
        require(slot != null) { "No token in Zone $zoneNumber on $tier" }
        inZone.remove(slot)
        ionBattery += 1
        refillInPlayIfRoom()
    }

    /** Destroys every in-play token currently at [position] in one sweep — stacking is legal
     * (landing on a token doesn't destroy it, so more than one may share a square), so this can
     * remove more than one at once. Used by whole-square-sweep effects like Plasma Burst. A
     * no-op if nothing is there. */
    fun destroyAllAt(position: Int) {
        val slots = inPlay.filter { it.position == position }
        if (slots.isEmpty()) return
        inPlay.removeAll(slots)
        ionBattery += slots.size
        refillInPlayIfRoom()
    }

    /** Destroys every token currently resident in Zone [zoneNumber] in one sweep — more than one
     * player's token can occupy the same Zone, since nothing about [enterZone] prevents it. Used
     * by named rule-12 exceptions that reach a whole Zone at once, like Plasma Burst via its own
     * entry square. A no-op if none are there; same legality caveat as [destroyInZone]. */
    fun destroyAllInZone(zoneNumber: Int) {
        val slots = inZone.filter { it.zoneNumber == zoneNumber }
        if (slots.isEmpty()) return
        inZone.removeAll(slots)
        ionBattery += slots.size
        refillInPlayIfRoom()
    }

    /**
     * Refills empty in-play slots after a token leaves play (destroyed, staged, promoted, or
     * moved into a Zone) — looping since a single event can free more than one slot at once
     * (e.g. a 1st Tier pool sitting at zero in-play tokens after its lone token is destroyed).
     *
     * For every Tier, a Hatchery-waiting token takes the slot first (Gameboard Rules: "Any
     * extra Tier tokens... must remain in that Tier's Hatchery until one of the tokens in play
     * on that Tier has been destroyed... at which point a token may be moved to the Birth
     * Canal/Start square"). The 1st Tier has no such Hatchery, per its own, more specific rule:
     * "On the First Tier, all tokens beyond the two in play... remain in the player's Ion
     * Battery... When there are less than two 1st Tier tokens in play..., a new... token is
     * taken from the Ion Battery and placed on Start." So once Hatchery is exhausted, only the
     * 1st Tier additionally falls back to pulling straight from [ionBattery] — without this, a
     * 1st Tier player whose sole in-play token was destroyed would never get another 1st Tier
     * turn for the rest of the game (`inPlayCount` staying at 0 forever), a genuine soft-lock.
     */
    private fun refillInPlayIfRoom() {
        while (inPlayCount < tier.maxInPlay) {
            when {
                hatchery > 0 -> hatchery -= 1
                tier == TierLevel.FIRST && ionBattery > 0 -> ionBattery -= 1
                else -> return
            }
            inPlay += InPlaySlot(TokenIdGenerator.next(owner, TokenKind.TIER_TOKEN, tier), 0)
        }
    }
}
