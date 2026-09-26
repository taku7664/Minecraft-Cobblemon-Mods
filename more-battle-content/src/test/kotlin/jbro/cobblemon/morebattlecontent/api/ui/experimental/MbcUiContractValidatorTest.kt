package jbro.cobblemon.morebattlecontent.api.ui.experimental

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalMbcUi::class)
class MbcUiContractValidatorTest {
    @Test
    fun `valid typed screen contract has no issues`() {
        val definition = MbcUiScreenDefinition(
            id = MbcUiScreenId("league_home"),
            root = MbcUiGroup(
                id = MbcUiComponentId("root"),
                children = listOf(
                    MbcUiText(
                        id = MbcUiComponentId("rank"),
                        textKey = "screen.league.rank",
                        visibleWhen = MbcUiStateKey("rank_visible")
                    ),
                    MbcUiAction(
                        id = MbcUiComponentId("challenge"),
                        labelKey = "screen.league.challenge",
                        actionId = MbcUiActionId("open_challenge"),
                        enabledWhen = MbcUiStateKey("challenge_available")
                    )
                )
            ),
            declaredStateKeys = setOf(
                MbcUiStateKey("rank_visible"),
                MbcUiStateKey("challenge_available")
            ),
            registeredActions = setOf(MbcUiActionId("open_challenge"))
        )

        assertTrue(MbcUiContractValidator.validate(definition).isEmpty())
    }

    @Test
    fun `duplicate component ids are rejected across the whole tree`() {
        val duplicate = MbcUiComponentId("duplicate")
        val definition = screen(
            MbcUiGroup(
                id = MbcUiComponentId("root"),
                children = listOf(
                    MbcUiText(duplicate, "first"),
                    MbcUiGroup(duplicate, emptyList())
                )
            )
        )

        assertEquals(
            listOf(MbcUiContractIssue.DuplicateComponentId(duplicate)),
            MbcUiContractValidator.validate(definition)
        )
    }

    @Test
    fun `unregistered actions and state bindings are rejected`() {
        val missingVisible = MbcUiStateKey("missing_visible")
        val missingEnabled = MbcUiStateKey("missing_enabled")
        val missingAction = MbcUiActionId("missing_action")
        val definition = screen(
            MbcUiGroup(
                id = MbcUiComponentId("root"),
                visibleWhen = missingVisible,
                children = listOf(
                    MbcUiAction(
                        id = MbcUiComponentId("action"),
                        labelKey = "action",
                        actionId = missingAction,
                        enabledWhen = missingEnabled
                    )
                )
            )
        )

        assertEquals(
            setOf(
                MbcUiContractIssue.UnknownStateKey(MbcUiComponentId("root"), missingVisible),
                MbcUiContractIssue.UnknownAction(MbcUiComponentId("action"), missingAction),
                MbcUiContractIssue.UnknownStateKey(MbcUiComponentId("action"), missingEnabled)
            ),
            MbcUiContractValidator.validate(definition).toSet()
        )
    }

    @Test
    fun `experimental contract does not expose a rendering backend`() {
        val sourceRoot = Path.of("src/main/kotlin/jbro/cobblemon/morebattlecontent/api/ui/experimental")
        val source = Files.walk(sourceRoot).use { paths ->
            paths.filter(Files::isRegularFile).map(Files::readString).toList().joinToString("\n")
        }

        assertTrue("io.wispforest.owo" !in source)
        assertTrue("GuiGraphics" !in source)
        assertTrue("ResourceManager" !in source)
    }

    private fun screen(root: MbcUiNode): MbcUiScreenDefinition = MbcUiScreenDefinition(
        id = MbcUiScreenId("test"),
        root = root,
        declaredStateKeys = emptySet(),
        registeredActions = emptySet()
    )
}
