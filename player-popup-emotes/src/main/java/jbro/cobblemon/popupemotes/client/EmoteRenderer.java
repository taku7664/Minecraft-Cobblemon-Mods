package jbro.cobblemon.popupemotes.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import jbro.cobblemon.popupemotes.emote.BuiltInEmote;
import jbro.cobblemon.popupemotes.emote.BuiltInEmotes;
import jbro.cobblemon.popupemotes.client.custom.RemoteEmoteManager;
import jbro.cobblemon.popupemotes.network.ShowEmotePayload;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

final class EmoteRenderer {
    private static final float SIZE = 0.65F;
    private static final ByteBufferBuilder BUFFER = new ByteBufferBuilder(1536);
    private static final MultiBufferSource.BufferSource CONSUMERS = MultiBufferSource.immediate(BUFFER);
    private static final Map<UUID, ActiveEmote> ACTIVE = new HashMap<>();

    private EmoteRenderer() {
    }

    static void show(ShowEmotePayload payload) {
        ACTIVE.put(payload.playerId(), new ActiveEmote(payload.emoteId(), payload.heightOffset()));
        if (!BuiltInEmotes.contains(payload.emoteId())) {
            RemoteEmoteManager.texture(payload.emoteId());
        }
    }

    static void tick(Minecraft client) {
        Iterator<Map.Entry<UUID, ActiveEmote>> iterator = ACTIVE.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, ActiveEmote> entry = iterator.next();
            ActiveEmote active = entry.getValue();
            boolean ready = active.ready();
            if (!ready) {
                active.loadingTicks++;
                if (active.loadingTicks >= 200) {
                    iterator.remove();
                }
                continue;
            }
            Player player = client.level == null ? null : client.level.getPlayerByUUID(entry.getKey());
            if (active.soundGate.shouldPlay(true, player != null)) {
                client.level.playLocalSound(
                    player.getX(),
                    player.getY(),
                    player.getZ(),
                    SoundEvents.BUBBLE_COLUMN_BUBBLE_POP,
                    SoundSource.BLOCKS,
                    5.0F,
                    3.3333F,
                    false
                );
            }
            active.ageTicks++;
            if (active.ageTicks >= EmoteAnimation.TOTAL_TICKS) {
                iterator.remove();
            }
        }
    }

    static void render(WorldRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || context.matrixStack() == null) {
            return;
        }

        PoseStack poseStack = context.matrixStack();
        Vec3 cameraPosition = context.camera().getPosition();
        float partialTick = context.tickCounter().getGameTimeDeltaPartialTick(true);
        for (Map.Entry<UUID, ActiveEmote> entry : ACTIVE.entrySet()) {
            Player player = client.level.getPlayerByUUID(entry.getKey());
            if (player == null) {
                continue;
            }
            if (player == client.player && client.options.getCameraType() == CameraType.FIRST_PERSON) {
                continue;
            }
            ActiveEmote active = entry.getValue();
            if (!active.ready()) {
                continue;
            }
            float animationAge = active.ageTicks + partialTick;
            float alpha = EmoteAnimation.alpha(animationAge);
            float scale = EmoteAnimation.scale(animationAge);
            double x = Mth.lerp(partialTick, player.xOld, player.getX()) - cameraPosition.x;
            double y = Mth.lerp(partialTick, player.yOld, player.getY()) - cameraPosition.y;
            double z = Mth.lerp(partialTick, player.zOld, player.getZ()) - cameraPosition.z;

            poseStack.pushPose();
            poseStack.translate(x, y + EmotePlacement.centerY(player.getBbHeight(), active.heightOffset), z);
            poseStack.mulPose(context.camera().rotation());
            poseStack.scale(scale, scale, scale);

            VertexConsumer vertices = CONSUMERS.getBuffer(EmoteRenderTypes.noDepth(active.texture()));
            int light = LevelRenderer.getLightColor(client.level, player.blockPosition());
            float half = SIZE / 2.0F;
            vertices.addVertex(poseStack.last().pose(), -half, half, 0.0F)
                .setColor(1.0F, 1.0F, 1.0F, alpha).setUv(0.0F, 0.0F)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(0.0F, 0.0F, 1.0F);
            vertices.addVertex(poseStack.last().pose(), -half, -half, 0.0F)
                .setColor(1.0F, 1.0F, 1.0F, alpha).setUv(0.0F, 1.0F)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(0.0F, 0.0F, 1.0F);
            vertices.addVertex(poseStack.last().pose(), half, -half, 0.0F)
                .setColor(1.0F, 1.0F, 1.0F, alpha).setUv(1.0F, 1.0F)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(0.0F, 0.0F, 1.0F);
            vertices.addVertex(poseStack.last().pose(), half, half, 0.0F)
                .setColor(1.0F, 1.0F, 1.0F, alpha).setUv(1.0F, 0.0F)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(0.0F, 0.0F, 1.0F);
            poseStack.popPose();
        }
        CONSUMERS.endBatch();
    }

    static void clear() {
        ACTIVE.clear();
    }

    private static final class ActiveEmote {
        private final String reference;
        private final float heightOffset;
        private final EmoteSoundGate soundGate = new EmoteSoundGate();
        private int ageTicks;
        private int loadingTicks;

        private ActiveEmote(String reference, float heightOffset) {
            this.reference = reference;
            this.heightOffset = heightOffset;
        }

        private boolean ready() {
            if (BuiltInEmotes.contains(reference)) {
                return true;
            }
            RemoteEmoteManager.texture(reference);
            return RemoteEmoteManager.isReady(reference);
        }

        private net.minecraft.resources.ResourceLocation texture() {
            return BuiltInEmotes.contains(reference)
                ? BuiltInEmotes.require(reference).texture()
                : RemoteEmoteManager.texture(reference);
        }
    }
}
