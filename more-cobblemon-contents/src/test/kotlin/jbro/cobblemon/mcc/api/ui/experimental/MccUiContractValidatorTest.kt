package jbro.cobblemon.mcc.api.ui.experimental

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalMccUi::class)
class MccUiContractValidatorTest {
    @Test
    fun `valid typed screen contract has no issues`() {
        val definition = MccUiScreenDefinition(
            id = MccUiScreenId("league_home"),
            root = MccUiGroup(
                id = MccUiComponentId("root"),
                children = listOf(
                    MccUiText(
                        id = MccUiComponentId("rank"),
                        textKey = "screen.league.rank",
                        visibleWhen = MccUiStateKey("rank_visible")
                    ),
                    MccUiAction(
                        id = MccUiComponentId("challenge"),
                        labelKey = "screen.league.challenge",
                        actionId = MccUiActionId("open_challenge"),
                        enabledWhen = MccUiStateKey("challenge_available")
                    )
                )
            ),
            declaredStateKeys = setOf(
                MccUiStateKey("rank_visible"),
                MccUiStateKey("challenge_available")
            ),
            registeredActions = setOf(MccUiActionId("open_challenge"))
        )

        assertTrue(MccUiContractValidator.validate(definition).isEmpty())
    }

    @Test
    fun `duplicate component ids are rejected across the whole tree`() {
        val duplicate = MccUiComponentId("duplicate")
        val definition = screen(
            MccUiGroup(
                id = MccUiComponentId("root"),
                children = listOf(
                    MccUiText(duplicate, "first"),
                    MccUiGroup(duplicate, emptyList())
                )
            )
        )

        assertEquals(
            listOf(MccUiContractIssue.DuplicateComponentId(duplicate)),
            MccUiContractValidator.validate(definition)
        )
    }

    @Test
    fun `unregistered actions and state bindings are rejected`() {
        val missingVisible = MccUiStateKey("missing_visible")
        val missingEnabled = MccUiStateKey("missing_enabled")
        val missingAction = MccUiActionId("missing_action")
        val definition = screen(
            MccUiGroup(
                id = MccUiComponentId("root"),
                visibleWhen = missingVisible,
                children = listOf(
                    MccUiAction(
                        id = MccUiComponentId("action"),
                        labelKey = "action",
                        actionId = missingAction,
                        enabledWhen = missingEnabled
                    )
                )
            )
        )

        assertEquals(
            setOf(
                MccUiContractIssue.UnknownStateKey(MccUiComponentId("root"), missingVisible),
                MccUiContractIssue.UnknownAction(MccUiComponentId("action"), missingAction),
                MccUiContractIssue.UnknownStateKey(MccUiComponentId("action"), missingEnabled)
            ),
            MccUiContractValidator.validate(definition).toSet()
        )
    }

    @Test
    fun `experimental contract does not expose a rendering backend`() {
        val sourceRoot = Path.of("src/main/kotlin/jbro/cobblemon/mcc/api/ui/experimental")
        val source = Files.walk(sourceRoot).use { paths ->
            paths.filter(Files::isRegularFile).map(Files::readString).toList().joinToString("\n")
        }

        assertTrue("io.wispforest.owo" !in source)
        assertTrue("GuiGraphics" !in source)
        assertTrue("ResourceManager" !in source)
    }

    private fun screen(root: MccUiNode): MccUiScreenDefinition = MccUiScreenDefinition(
        id = MccUiScreenId("test"),
        root = root,
        declaredStateKeys = emptySet(),
        registeredActions = emptySet()
    )
}
