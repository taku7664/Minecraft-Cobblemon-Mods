package jbro.minecraft.roundingblock.client.command;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.mojang.brigadier.CommandDispatcher;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import org.junit.jupiter.api.Test;

class RoundingBlockCommandsTest {
    @Test
    void commandTreeExposesEveryJsonSettingAndReload() {
        CommandDispatcher<FabricClientCommandSource> dispatcher = new CommandDispatcher<>();
        dispatcher.register(RoundingBlockCommands.build(Path.of("rounding-block.json")));
        var root = dispatcher.getRoot().getChild("roundingblock");

        assertNotNull(root.getChild("show"));
        assertNotNull(root.getChild("reload"));
        assertNotNull(root.getChild("enabled").getChild("value"));
        assertNotNull(root.getChild("quality").getChild("radius").getChild("value"));
        assertNotNull(root.getChild("quality").getChild("segments").getChild("value"));
        assertNotNull(root.getChild("cache").getChild("fullBlockPlans").getChild("value"));
        assertNotNull(root.getChild("cache").getChild("slabPlans").getChild("value"));
        assertNotNull(root.getChild("cache").getChild("complexShapePlans").getChild("value"));
        assertNotNull(root.getChild("cache").getChild("fluidContactPlans").getChild("value"));
        assertNotNull(root.getChild("cache").getChild("weightedModelVariants").getChild("value"));
        assertNotNull(root.getChild("debug").getChild("diagnosticLogging").getChild("value"));
    }
}
