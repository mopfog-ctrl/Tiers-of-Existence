package com.tiersofexistence.engine.cards

import com.tiersofexistence.engine.model.PlayerColor
import com.tiersofexistence.engine.model.PlayerColor.BLACK
import com.tiersofexistence.engine.model.PlayerColor.BLUE
import com.tiersofexistence.engine.model.PlayerColor.GREEN
import com.tiersofexistence.engine.model.PlayerColor.RED
import com.tiersofexistence.engine.model.PlayerColor.WHITE
import com.tiersofexistence.engine.model.PlayerColor.YELLOW
import com.tiersofexistence.engine.cards.CardRarity.DOUBLE
import com.tiersofexistence.engine.cards.CardRarity.QUADRUPLE
import com.tiersofexistence.engine.cards.CardRarity.SINGLE
import com.tiersofexistence.engine.cards.CardRarity.TRIPLE
import com.tiersofexistence.engine.cards.CardScope.ANY_TIME
import com.tiersofexistence.engine.cards.CardScope.YOUR_TURN
import com.tiersofexistence.engine.cards.CardTiming.HELD
import com.tiersofexistence.engine.cards.CardTiming.IMMEDIATE

/**
 * The full 70-card Fate Harvest deck, transcribed from the rulebook's "Fate Harvest Card
 * List" (p.11+). Card counts cross-check against the Parts List ("70x Fate Harvest Cards"):
 * 10 Singles x1 + 10 Doubles x2 + 8 Triples x3 + 4 Quadruples x4 = 10+20+24+16 = 70.
 */
object FateHarvestCatalog {

    val all: List<FateHarvestCard> = listOf(
        // --- Singles (x1 copy each) ---
        FateHarvestCard(
            name = "Corpuscle Rot",
            rarity = SINGLE,
            timing = HELD,
            scope = ANY_TIME,
            restrictedTo = YELLOW,
            effect = "Yellow player destroys any 4th Tier token and starts a new token on the " +
                "1st and 2nd Tiers (2 tokens in total). Can also destroy 4th Tier tokens on " +
                "Zone of Protection squares.",
            flavorText = "The atoms in most life forms have been part of other life forms before.",
        ),
        FateHarvestCard(
            name = "Galactic Roundabout",
            rarity = SINGLE,
            timing = IMMEDIATE,
            scope = null,
            effect = "Move every token (all tokens including Marauders and Tier tokens in the " +
                "Zone of Protection) forward two spaces.",
            flavorText = "How fast does the Milky Way spin?",
        ),
        FateHarvestCard(
            name = "Dwarf Star",
            rarity = SINGLE,
            timing = HELD,
            scope = ANY_TIME,
            restrictedTo = WHITE,
            effect = "White player places a Marauder on the 4th Tier Birth Canal.",
            flavorText = "Red Dwarf stars exist for much longer than other star types.",
        ),
        FateHarvestCard(
            name = "Radiation Burst",
            rarity = SINGLE,
            timing = HELD,
            scope = YOUR_TURN,
            effect = "All Staging Piles are emptied.",
            flavorText = "Pulsars can generate beams of gamma rays.",
        ),
        FateHarvestCard(
            name = "Materialize Army",
            rarity = SINGLE,
            timing = HELD,
            scope = YOUR_TURN,
            effect = "A Marauder joins you. Place a Marauder on any Tier's Birth Canal.",
            flavorText = "Materializing (or coalescing) matter at long distances may eventually be possible.",
        ),
        FateHarvestCard(
            name = "Graviton Rift",
            rarity = SINGLE,
            timing = HELD,
            scope = ANY_TIME,
            restrictedTo = BLACK,
            hasPrecedence = true,
            effect = "The Black player removes any one token (of any type) from each Tier (up " +
                "to 4 tokens in total). If there are no tokens on a Tier, none is removed from " +
                "that Tier. Can also destroy Tier tokens on Zone of Protection squares.",
            flavorText = "What does \"graviton\" mean?",
        ),
        FateHarvestCard(
            name = "Fluidic Wave",
            rarity = SINGLE,
            timing = HELD,
            scope = ANY_TIME,
            restrictedTo = BLUE,
            hasPrecedence = true,
            effect = "Blue player removes all tokens from the 1st Tier: in play and in Staging " +
                "Piles, but not Tier tokens in the Zone of Protection.",
            flavorText = "Light bends around planets, and cannot escape black holes.",
        ),
        FateHarvestCard(
            name = "Parallel Phasing",
            rarity = SINGLE,
            timing = IMMEDIATE,
            scope = null,
            effect = "Move any one of your tokens (of any type) forward four spaces and move " +
                "any other player's token forward four spaces. You may not move an opponent's " +
                "token if it's in the Zone of Protection.",
            flavorText = "Parallel waves add together when in the same phase.",
        ),
        FateHarvestCard(
            name = "Plasma Burst",
            rarity = SINGLE,
            timing = HELD,
            scope = YOUR_TURN,
            restrictedTo = RED,
            effect = "Red player chooses a Tier, then removes all tokens (of any type) from 3 " +
                "neighboring squares on that Tier. Can also destroy Tier tokens on Zone of " +
                "Protection squares.",
            flavorText = "The plasma phase of any element is hotter than its gas phase.",
        ),
        FateHarvestCard(
            name = "Verdant Growth",
            rarity = SINGLE,
            timing = HELD,
            scope = YOUR_TURN,
            restrictedTo = GREEN,
            effect = "The Green player starts a new token on the 1st, 2nd, and 3rd Tier Birth " +
                "Canals (3 tokens in total).",
            flavorText = "Planets where life flourishes are rarer than planets where life exists.",
        ),

        // --- Doubles (x2 copies each) ---
        FateHarvestCard(
            name = "Infernal Abyss",
            rarity = DOUBLE,
            timing = IMMEDIATE,
            scope = null,
            effect = "Sacrifice any one of your tokens (of any type) from any Tier. You may not " +
                "sacrifice Tier tokens in the Zone of Protection.",
            flavorText = "Only radiation escapes from Black Holes.",
        ),
        FateHarvestCard(
            name = "Divine Assistance",
            rarity = DOUBLE,
            timing = IMMEDIATE,
            scope = null,
            effect = "Choose and destroy any one token (of any type, on any Tier, including " +
                "those in Staging Piles). Can also destroy Tier tokens on Zone of Protection " +
                "squares. Does NOT allow destroying the Turn Indicator token or the Phase " +
                "Clock token (Fate Harvest Card Rules #13).",
            flavorText = "Divine assistance presumes that divinity cares.",
        ),
        FateHarvestCard(
            name = "Planetary Nebula",
            rarity = DOUBLE,
            timing = HELD,
            scope = YOUR_TURN,
            effect = "Place a 2nd Dimensional Token in your 2nd Tier Birth Canal. Only playable " +
                "during your turn of the 2nd Tier Phase.",
            flavorText = "Nebulae with high metallicity and density produce more habitable planets.",
        ),
        FateHarvestCard(
            name = "Luckier Nebula",
            rarity = DOUBLE,
            timing = IMMEDIATE,
            scope = null,
            effect = "Place a 2nd Dimensional Token in your 2nd Tier Staging Pile.",
            flavorText = "Nebulae are formed when a sun goes supernova.",
        ),
        FateHarvestCard(
            name = "Essence Assimilator",
            rarity = DOUBLE,
            timing = IMMEDIATE,
            scope = null,
            effect = "Place a Marauder on the 1st Tier Birth Canal.",
            flavorText = "If atoms can be teleported, could they be projected faster than light?",
        ),
        FateHarvestCard(
            name = "Skip, Hop, and Jump (Dimensional)",
            rarity = DOUBLE,
            timing = HELD,
            scope = YOUR_TURN,
            effect = "Move any token (of any type) forward 3 spaces. You may not move an " +
                "opponent's token if it's in the Zone of Protection.",
            flavorText = "It would truly only be a hop, skip, and a jump to other galaxies.",
        ),
        FateHarvestCard(
            name = "Materialize Help",
            rarity = DOUBLE,
            timing = IMMEDIATE,
            scope = null,
            effect = "A Marauder joins you. Place a Marauder token on the 3rd Tier Birth Canal.",
            flavorText = "Could an android be materialized on another planet and turned on remotely?",
        ),
        FateHarvestCard(
            name = "Tactical Motion",
            rarity = DOUBLE,
            timing = HELD,
            scope = ANY_TIME,
            hasPrecedence = true,
            effect = "Move any token (of any type) forward 2 spaces. You may not move an " +
                "opponent's token if it's in the Zone of Protection.",
            flavorText = "Moving a planet would require a lot of energy.",
        ),
        FateHarvestCard(
            name = "Insidious Flux",
            rarity = DOUBLE,
            timing = HELD,
            scope = YOUR_TURN,
            effect = "Choose a Staging Pile on any Tier. Destroy one token in that Staging Pile.",
            flavorText = "Will future technologies use flux and capacitors?",
        ),
        FateHarvestCard(
            name = "Phase Loss",
            rarity = DOUBLE,
            timing = IMMEDIATE,
            scope = null,
            effect = "You lose the next turn on this Tier only (not other Tiers).",
            flavorText = "The wavelength of light from stars decays the further the star is from Earth.",
        ),

        // --- Triples (x3 copies each) ---
        FateHarvestCard(
            name = "Annulment (Antimatter)",
            rarity = TRIPLE,
            timing = HELD,
            scope = ANY_TIME,
            hasPrecedence = true,
            effect = "Cancel the effect of any card that is played. Takes Precedence over all " +
                "other cards, including cancelling cards played immediately before or after it " +
                "(Fate Harvest Card Rules #22).",
            flavorText = "A large quantity of dark matter may exist between galaxies.",
        ),
        FateHarvestCard(
            name = "Evasive Action",
            rarity = TRIPLE,
            timing = IMMEDIATE,
            scope = null,
            effect = "Move any token (of any type) forward 2 spaces. You may not move an " +
                "opponent's token if it's in the Zone of Protection.",
            flavorText = "Matter can neither be created nor destroyed within our Universe.",
        ),
        FateHarvestCard(
            name = "Lucky Nebula",
            rarity = TRIPLE,
            timing = IMMEDIATE,
            scope = null,
            effect = "Place a 1st Dimensional Token in your 1st Tier Staging Pile.",
            flavorText = "If suns can form in a nebula, planets can also form there.",
        ),
        FateHarvestCard(
            name = "Tactical Step",
            rarity = TRIPLE,
            timing = HELD,
            scope = ANY_TIME,
            hasPrecedence = true,
            effect = "Move any token (of any type) forward 1 space. You may not move an " +
                "opponent's token if it's in the Zone of Protection.",
            flavorText = "The fastest way to cross a galaxy is not a straight line.",
        ),
        FateHarvestCard(
            name = "Elemental Rebirth",
            rarity = TRIPLE,
            timing = IMMEDIATE,
            scope = null,
            effect = "Place a token on the Birth Canal of the 1st Tier.",
            flavorText = "Suns create and recycle elements.",
        ),
        FateHarvestCard(
            name = "Cleansing (Atmospheric)",
            rarity = TRIPLE,
            timing = HELD,
            scope = YOUR_TURN,
            effect = "Choose an opponent. That opponent must choose and discard one of their " +
                "cards (they choose which).",
            flavorText = "Mars has less gravity than the Earth, and its atmosphere has washed away.",
        ),
        FateHarvestCard(
            name = "Delayed Motion",
            rarity = TRIPLE,
            timing = HELD,
            scope = null,
            effect = "Add +2 to your die roll. Must be played after your die roll but before " +
                "you move the token.",
            flavorText = "Gravity, supernovae, and other cosmic events can redirect matter.",
        ),
        FateHarvestCard(
            name = "Emitting Nebula",
            rarity = TRIPLE,
            timing = HELD,
            scope = YOUR_TURN,
            effect = "Place a 1st Dimensional Token in your 1st Tier Staging Pile. Only " +
                "playable during your turn of the 1st Tier Phase.",
            flavorText = "Some nebulae emit much greater quantities of energy into space.",
        ),

        // --- Quadruples (x4 copies each) ---
        FateHarvestCard(
            name = "Last Gasp",
            rarity = QUADRUPLE,
            timing = HELD,
            scope = ANY_TIME,
            hasPrecedence = true,
            effect = "Move any one of your tokens (of any type) 8 spaces. Any tokens you pass " +
                "are destroyed, as well as the moved token, except tokens in the Zone of " +
                "Protection. Along with the Hyperthrust square, this is one of only two ways " +
                "a Tier token can destroy Marauders (Fate Harvest Card Rules #10). Must " +
                "exclaim \"Last Gasp!\" when played.",
            flavorText = "The sun will go supernova a few billion years after roasting the Earth.",
        ),
        FateHarvestCard(
            name = "Phase Control",
            rarity = QUADRUPLE,
            timing = IMMEDIATE,
            scope = null,
            effect = "Choose a Tier and go again on that Tier: an extra turn taken after your " +
                "normal turn there. If your turn on that Tier already ended this Round, play " +
                "it immediately.",
            flavorText = "In dimensions where light can exist, time exists.",
        ),
        FateHarvestCard(
            name = "Circulate (Elemental)",
            rarity = QUADRUPLE,
            timing = HELD,
            scope = YOUR_TURN,
            effect = "Move any Tier token to the next Zone of Protection on that Tier.",
            flavorText = "During planetary formation, heavy elements sink and light elements rise.",
        ),
        FateHarvestCard(
            name = "Sidestep (Extinction Avoidance)",
            rarity = QUADRUPLE,
            timing = HELD,
            scope = YOUR_TURN,
            effect = "Move any token (of any type) forward 1 space. You may not move an " +
                "opponent's token if it's in the Zone of Protection.",
            flavorText = "Species with insufficient resources can't sidestep extinction.",
        ),
    )

    /** Card names restricted to a color, keyed by [PlayerColor] (Fate Harvest Card Rules #18). */
    val colorCards: Map<PlayerColor, List<FateHarvestCard>> =
        all.filter { it.isColorCard }.groupBy { it.restrictedTo!! }

    /** Total physical cards in the deck (should be 70, matching the Parts List). */
    fun deckSize(): Int = all.sumOf { it.rarity.copies }

    /** Expands the catalog into one entry per physical copy, ready to shuffle into a draw pile. */
    fun buildDeck(): List<FateHarvestCard> = all.flatMap { card -> List(card.rarity.copies) { card } }
}
