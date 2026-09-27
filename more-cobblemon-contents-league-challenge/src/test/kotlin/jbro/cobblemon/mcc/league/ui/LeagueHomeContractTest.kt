package jbro.cobblemon.mcc.league.ui

import jbro.cobblemon.mcc.api.ui.experimental.ExperimentalMccUi
import jbro.cobblemon.mcc.api.ui.experimental.MccUiContractValidator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalMccUi::class)
class LeagueHomeContractTest {
    @Test
    fun `league home contract is valid against experimental MccUI`() {
        assertTrue(MccUiContractValidator.validate(LeagueHomeContract.definition).isEmpty())
    }
}
