package jbro.cobblemon.bettermusic.client;

import com.cobblemon.mod.common.client.battle.ClientBattleActor;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;
import jbro.cobblemon.bettermusic.battle.RctTrainerRoleMapper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import org.slf4j.Logger;

final class RctTrainerRoleAdapter {
    private static final String RCT_MOD_ID = "rctmod";
    private static final String RCT_TRAINER_ENTITY_ID = "rctmod:trainer";
    private static final String RCT_API_CLASS = "com.gitlab.srcmc.rctmod.api.RCTMod";

    private final Logger logger;
    private final boolean installed;
    private boolean failureReported;

    RctTrainerRoleAdapter(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.installed = FabricLoader.getInstance().isModLoaded(RCT_MOD_ID);
    }

    Optional<String> resolve(Minecraft client, ClientBattleActor actor) {
        if (!installed || client.level == null) {
            return Optional.empty();
        }
        for (var entity : client.level.entitiesForRendering()) {
            if (!entity.getUUID().equals(actor.getUuid())) {
                continue;
            }
            String entityId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            if (!RCT_TRAINER_ENTITY_ID.equals(entityId)) {
                return Optional.empty();
            }
            try {
                String trainerId = (String) entity.getClass().getMethod("getTrainerId").invoke(entity);
                Class<?> apiType = Class.forName(RCT_API_CLASS, false, entity.getClass().getClassLoader());
                Object api = apiType.getMethod("getInstance").invoke(null);
                Object manager = apiType.getMethod("getTrainerManager").invoke(api);
                Method getData = manager.getClass().getMethod("getData", String.class);
                Object data = getData.invoke(manager, trainerId);
                if (data == null) {
                    return Optional.empty();
                }
                Object type = data.getClass().getMethod("getType").invoke(data);
                if (type == null) {
                    return Optional.empty();
                }
                String typeId = (String) type.getClass().getMethod("id").invoke(type);
                return RctTrainerRoleMapper.map(typeId);
            } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
                reportFailureOnce(exception);
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private void reportFailureOnce(Throwable failure) {
        if (failureReported) {
            return;
        }
        failureReported = true;
        logger.warn("RCT trainer role integration failed; using the generic trainer playlist", failure);
    }
}
