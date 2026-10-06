package jbro.cobblemon.mcc.league.server

import jbro.cobblemon.mcc.api.presentation.TrainerScenes
import jbro.cobblemon.mcc.league.system.Challenge

/** A challenge's scene lines in MCC's terms; the catalog parser has already checked the moment ids. */
internal fun trainerScenes(challenge: Challenge): TrainerScenes = TrainerScenes(
    challenge.scenes.mapKeys { (moment, _) -> requireNotNull(TrainerScenes.Moment.fromId(moment)) { "Unknown scene moment: $moment" } },
)
