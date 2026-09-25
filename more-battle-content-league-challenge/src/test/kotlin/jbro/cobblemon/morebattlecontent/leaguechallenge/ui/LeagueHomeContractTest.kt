package jbro.cobblemon.morebattlecontent.leaguechallenge.ui

import jbro.cobblemon.morebattlecontent.api.ui.experimental.ExperimentalMbcUi
import jbro.cobblemon.morebattlecontent.api.ui.experimental.MbcUiContractValidator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalMbcUi::class)
class LeagueHomeContractTest {
    @Test
    fun `league home contract is valid against experimental MbcUI`() {
        assertTrue(MbcUiContractValidator.validate(LeagueHomeContract.definition).isEmpty())
    }
}
