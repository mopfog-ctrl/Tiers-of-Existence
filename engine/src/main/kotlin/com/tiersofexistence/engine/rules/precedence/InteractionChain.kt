package com.tiersofexistence.engine.rules.precedence

import com.tiersofexistence.engine.cards.play.CardPlayRequest
import com.tiersofexistence.engine.model.PlayerColor

/** The name Annulment is catalogued under — see `FateHarvestCatalog.kt`. Compared by name (not a
 * dedicated boolean on [com.tiersofexistence.engine.cards.FateHarvestCard]) since Annulment's
 * special handling is a property of the interaction engine, not the catalog data model. */
internal const val ANNULMENT_CARD_NAME = "Annulment (Antimatter)"

/** What a chain is currently blocking — the action suspended until the chain closes and its
 * surviving entries resolve (rule 23: a rolling player "may not move their token until after the
 * card has been played and enacted"). */
sealed class SuspendedAction {
    /** A die roll is pending — a Precedence response may act before the roll is used to move. */
    data class PendingRoll(val player: PlayerColor) : SuspendedAction()

    /** A roll has resolved and a move is pending. */
    data class PendingMove(val player: PlayerColor) : SuspendedAction()

    /** A non-Precedence card is about to resolve — Annulment can respond to cancel it directly
     * (see [InteractionChain]'s class doc on the "no prior entry" Annulment case). */
    data class PendingCardResolution(val request: CardPlayRequest) : SuspendedAction()
}

enum class ChainState { OPEN, CLOSED, RESOLVING, RESOLVED }

/**
 * One Precedence card played into the chain, in play order. Never removed from
 * [InteractionChain]'s history once added — an Annulment "cancellation" is recorded as
 * [cancelledByEntryId] rather than deleting the entry, so the chain stays fully inspectable
 * (the UI/network layer needs to show what happened, not just the surviving effects) and so a
 * *later* Annulment can still find "the immediately preceding applicable card" by walking
 * entries and skipping already-cancelled ones, per rule 22's splice (see [InteractionChain]'s
 * class doc on double-Annulment).
 */
data class ChainEntry(
    val id: Long,
    val player: PlayerColor,
    val request: CardPlayRequest,
    var cancelledByEntryId: Long? = null,
) {
    val isCancelled: Boolean get() = cancelledByEntryId != null
    val isAnnulment: Boolean get() = request.card.name == ANNULMENT_CARD_NAME
}

/**
 * A genuine stateful Precedence interaction window (rulebook rules 20-23), not `List<Card>` plus
 * reverse iteration — the eventual UI/network layer needs to know at any point who's currently
 * allowed to act, what they're responding to, whether they've passed, whether the window is
 * still open, and what the chain currently contains, which a bare list can't answer.
 *
 * Lifecycle: [open] with the action it suspends and the players eligible to respond → each
 * eligible player [respond]s (adding another Precedence card, which re-opens the response round
 * for everyone) or [pass]es → once every eligible player has passed since the last new entry,
 * the window auto-[close]s → [resolve] returns the surviving entries in resolution order for the
 * caller to actually apply (this class only sequences the chain; it never mutates
 * [com.tiersofexistence.engine.state.GameState] itself) → the caller resumes [suspendedAction],
 * checking [isSuspendedActionCancelled] first.
 *
 * **Annulment** (rule 22) is the one structural special case: playing it cancels the immediately
 * preceding *still-standing* entry (an already-cancelled entry "didn't exist" for this purpose,
 * so a later Annulment skips past it to find the next one back — this is the chain-splice
 * behavior confirmed canon describes, not a plain "cancel whatever's directly before me in the
 * list" skip). If Annulment is the very FIRST entry in a chain opened around a plain
 * (non-Precedence) card about to resolve ([SuspendedAction.PendingCardResolution]), there is no
 * preceding chain entry to cancel — in that case it cancels the suspended action's own card
 * instead (see [isSuspendedActionCancelled]), matching rule 20's "Precedence cards supersede
 * other cards... at any time," not just other *chain* entries.
 *
 * **Double Annulment** (a second Annulment cancelling a first Annulment that had itself
 * cancelled some earlier card X): this implementation's answer is that X stays cancelled — a
 * cancellation, once applied, is a permanent removal from the resolution order, not something
 * that gets undone by later cancelling the card that caused it. This is flagged as
 * `docs/card-mechanics-matrix.md` §4 Q11 — the rulebook only describes the two-card case
 * explicitly, so this is a deliberate, documented engine choice pending user confirmation, not a
 * neutral default; see [InteractionChainTest] for the exact behavior this locks in.
 */
class InteractionChain private constructor(
    val id: Long,
    val suspendedAction: SuspendedAction,
    val eligiblePlayers: List<PlayerColor>,
) {
    private val entries: MutableList<ChainEntry> = mutableListOf()
    private val passedSinceLastEntry: MutableSet<PlayerColor> = mutableSetOf()
    private var nextEntryId: Long = 0

    var state: ChainState = ChainState.OPEN
        private set

    /** True once an Annulment played as the chain's very first entry has cancelled
     * [suspendedAction] itself (only meaningful when [suspendedAction] is a
     * [SuspendedAction.PendingCardResolution] — the other [SuspendedAction] kinds have no "card"
     * for Annulment to cancel this way). */
    var isSuspendedActionCancelled: Boolean = false
        private set

    val isOpen: Boolean get() = state == ChainState.OPEN

    /** The chain's full history in play order, cancelled entries included — a read-only view for
     * UI/inspection/logging. Use [resolutionOrder] for what actually resolves. */
    fun entriesSnapshot(): List<ChainEntry> = entries.toList()

    /** Players still eligible to act right now — everyone in [eligiblePlayers] who hasn't passed
     * since the most recently added entry (a new entry re-opens the round for everyone,
     * including players who'd already passed). Empty once the window has auto-closed. */
    fun currentlyEligibleToAct(): List<PlayerColor> =
        if (!isOpen) emptyList() else eligiblePlayers.filterNot { it in passedSinceLastEntry }

    fun hasPassed(player: PlayerColor): Boolean = player in passedSinceLastEntry

    /**
     * [player] adds [request] (a Precedence card) to the chain. Requires the chain still be
     * [isOpen] and [player] to be in [eligiblePlayers] and not to have already passed this round
     * — call [pass] instead once they're done, they can't re-enter until a new entry resets the
     * round. Requires [request]'s card to have Precedence (only a Precedence card can respond in
     * a chain) — full target/color/Phase-limit legality is the caller's responsibility (see
     * `com.tiersofexistence.engine.cards.play.TargetValidator`), not this class's.
     */
    fun respond(player: PlayerColor, request: CardPlayRequest): ChainEntry {
        check(isOpen) { "Interaction chain $id is not open" }
        require(player in eligiblePlayers) { "$player is not eligible to respond in chain $id" }
        require(request.card.hasPrecedence) { "${request.card.name} does not have Precedence and cannot respond in a chain" }

        val entry = ChainEntry(nextEntryId++, player, request)
        entries += entry
        passedSinceLastEntry.clear()
        if (entry.isAnnulment) applyAnnulment(entry)
        return entry
    }

    /** [player] declines to respond right now. Closes the window once every eligible player has
     * passed since the last new entry. */
    fun pass(player: PlayerColor) {
        check(isOpen) { "Interaction chain $id is not open" }
        require(player in eligiblePlayers) { "$player is not eligible to act in chain $id" }
        passedSinceLastEntry += player
        if (passedSinceLastEntry.containsAll(eligiblePlayers)) state = ChainState.CLOSED
    }

    /**
     * The surviving entries (not cancelled, not Annulment entries themselves — an Annulment
     * alters the chain but has no effect of its own to resolve) in RESOLUTION order: reverse of
     * play order among the survivors (rule 22), NOT reverse of the full original list — a
     * cancelled entry is simply absent, it doesn't leave a gap that shifts anything.
     */
    fun resolutionOrder(): List<ChainEntry> =
        entries.filterNot { it.isCancelled || it.isAnnulment }.asReversed()

    /** Transitions [state] to [ChainState.RESOLVING] and returns [resolutionOrder] for the
     * caller to actually apply, one entry at a time, to [com.tiersofexistence.engine.state.GameState].
     * Call [finishResolving] once every returned entry (and, if applicable, the resumed
     * [suspendedAction]) has been applied. */
    fun resolve(): List<ChainEntry> {
        check(state == ChainState.CLOSED) { "Chain $id must be closed before resolving (currently $state)" }
        state = ChainState.RESOLVING
        return resolutionOrder()
    }

    fun finishResolving() {
        check(state == ChainState.RESOLVING) { "Chain $id is not currently resolving (currently $state)" }
        state = ChainState.RESOLVED
    }

    private fun applyAnnulment(annulmentEntry: ChainEntry) {
        val target = entries.lastOrNull { it !== annulmentEntry && !it.isCancelled }
        if (target != null) {
            target.cancelledByEntryId = annulmentEntry.id
        } else if (suspendedAction is SuspendedAction.PendingCardResolution) {
            isSuspendedActionCancelled = true
        }
    }

    companion object {
        private var nextChainId: Long = 0

        /** Opens a new interaction window. [eligiblePlayers] should already be in the order
         * they'd get to respond (typically turn/seating order) — this class doesn't reorder them. */
        fun open(suspendedAction: SuspendedAction, eligiblePlayers: List<PlayerColor>): InteractionChain =
            InteractionChain(nextChainId++, suspendedAction, eligiblePlayers)
    }
}
