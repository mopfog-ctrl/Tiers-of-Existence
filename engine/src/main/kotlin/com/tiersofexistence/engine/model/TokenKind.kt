package com.tiersofexistence.engine.model

/** Which kind of game piece something is — a Tier token or a Marauder. Lives in `model` (not
 * `rules`) so both the state layer ([com.tiersofexistence.engine.state.TokenId]) and the rules
 * layer ([com.tiersofexistence.engine.rules.TokenRef]) can depend on it without a circular
 * package dependency. */
enum class TokenKind { TIER_TOKEN, MARAUDER }
