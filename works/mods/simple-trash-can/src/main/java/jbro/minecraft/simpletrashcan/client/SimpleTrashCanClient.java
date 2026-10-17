package jbro.minecraft.simpletrashcan.client;

import jbro.minecraft.simpletrashcan.SimpleTrashCan;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

public final class SimpleTrashCanClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // A resource pack change can restyle the inventory texture the panel is drawn from.
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override
            public ResourceLocation getFabricId() {
                return SimpleTrashCan.id("panel_skin");
            }

            @Override
            public void onResourceManagerReload(ResourceManager manager) {
                TrashPanel.invalidate();
            }
        });
    }
}
