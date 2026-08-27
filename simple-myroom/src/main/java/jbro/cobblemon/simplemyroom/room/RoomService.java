package jbro.cobblemon.simplemyroom.room;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import jbro.cobblemon.simplemyroom.SimpleMyRoom;
import jbro.cobblemon.simplemyroom.config.ConfigManager;
import jbro.cobblemon.simplemyroom.config.SimpleMyRoomMessages;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class RoomService {
    private final RoomWorldInitializer worldInitializer = new RoomWorldInitializer();
    private final CommandCooldown enterCooldown = new CommandCooldown();
    private final CommandCooldown activeCooldown = new CommandCooldown();
    private final SafeTeleportResolver safeTeleportResolver = new SafeTeleportResolver();
    private final ActiveRoomService activeRoomService;
    private final RoomPreparationService roomPreparationService;

    public RoomService(ActiveRoomService activeRoomService, RoomPreparationService roomPreparationService) {
        this.activeRoomService = activeRoomService;
        this.roomPreparationService = roomPreparationService;
    }

    public void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (server.getLevel(RoomDimensions.KEY) == null) {
                SimpleMyRoom.LOGGER.error("Simple MyRoom dimension {} was not loaded.", RoomDimensions.ID);
                return;
            }
            RoomStorage storage = RoomStorage.get(server);
            activeRoomService.start(server);
            if (!storage.configuredLayoutMatchesStored()) {
                SimpleMyRoom.LOGGER.warn(
                    "Configured room layout differs from the layout stored in this world. The stored layout {} remains active to protect existing rooms.",
                    storage.effectiveLayout()
                );
            }
            SimpleMyRoom.LOGGER.info("Simple MyRoom dimension {} is ready with {} known rooms.", RoomDimensions.ID, storage.roomCount());
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> activeRoomService.stop());
        ServerPlayerEvents.AFTER_RESPAWN.register(this::handleRespawn);
    }

    private void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (String rootName : SimpleMyRoom.config().commands.roots) {
            dispatcher.register(buildRoot(rootName));
        }
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildRoot(String rootName) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(rootName)
            .requires(source -> source.isPlayer() && SimpleMyRoom.enabled())
            .executes(context -> showRoomInfo(context.getSource().getPlayerOrException(), rootName));

        root.then(Commands.literal("enter")
            .executes(context -> enterOwnRoom(context.getSource().getPlayerOrException()))
            .then(Commands.argument("player", StringArgumentType.word())
                .suggests((context, builder) -> suggestRoomOwners(context.getSource().getServer(), builder))
                .executes(context -> enterNamedRoom(
                    context.getSource().getPlayerOrException(),
                    StringArgumentType.getString(context, "player")
                ))));
        root.then(Commands.literal("exit").executes(context -> exitRoom(context.getSource().getPlayerOrException())));
        root.then(Commands.literal("public").then(Commands.argument("allowed", BoolArgumentType.bool())
            .executes(context -> setPublicAccess(
                context.getSource().getPlayerOrException(),
                BoolArgumentType.getBool(context, "allowed")
            ))));
        root.then(Commands.literal("ban").then(playerNameArgument().executes(context -> banVisitor(
            context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "player")))));
        root.then(Commands.literal("unban").then(playerNameArgument().executes(context -> unbanVisitor(
            context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "player")))));
        root.then(Commands.literal("trust").then(playerNameArgument().executes(context -> trustPlayer(
            context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "player")))));
        root.then(Commands.literal("untrust").then(playerNameArgument().executes(context -> untrustPlayer(
            context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "player")))));
        root.then(Commands.literal("kick").then(playerNameArgument().executes(context -> kickVisitor(
            context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "player")))));
        root.then(Commands.literal("active")
            .executes(context -> showActiveStatus(context.getSource().getPlayerOrException()))
            .then(Commands.literal("status").executes(context -> showActiveStatus(context.getSource().getPlayerOrException())))
            .then(Commands.literal("on").executes(context -> setActive(context.getSource().getPlayerOrException(), true)))
            .then(Commands.literal("off").executes(context -> setActive(context.getSource().getPlayerOrException(), false))));
        root.then(Commands.literal("setspawn")
            .executes(context -> setCustomSpawn(context.getSource().getPlayerOrException()))
            .then(Commands.literal("reset").executes(context -> resetCustomSpawn(context.getSource().getPlayerOrException()))));
        root.then(Commands.literal("visitors")
            .executes(context -> showVisitors(context.getSource().getPlayerOrException())));
        root.then(Commands.literal("notify")
            .executes(context -> showVisitorNotificationStatus(context.getSource().getPlayerOrException()))
            .then(Commands.literal("on").executes(context -> setVisitorNotifications(context.getSource().getPlayerOrException(), true)))
            .then(Commands.literal("off").executes(context -> setVisitorNotifications(context.getSource().getPlayerOrException(), false))));

        if (SimpleMyRoom.config().commands.enableConfigReloadCommand) {
            root.then(Commands.literal("reload")
                .requires(source -> source.hasPermission(SimpleMyRoom.config().commands.adminPermissionLevel))
                .executes(context -> reloadConfig(context.getSource().getPlayerOrException())));
        }
        return root;
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> playerNameArgument() {
        return Commands.argument("player", StringArgumentType.word()).suggests((context, builder) -> {
            if (!SimpleMyRoom.config().commands.suggestOnlinePlayers) return builder.buildFuture();
            return SharedSuggestionProvider.suggest(Arrays.stream(context.getSource().getServer().getPlayerNames()), builder);
        });
    }

    private java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestRoomOwners(
        MinecraftServer server,
        com.mojang.brigadier.suggestion.SuggestionsBuilder builder
    ) {
        Stream<String> names = Stream.empty();
        if (SimpleMyRoom.config().commands.suggestOnlinePlayers) names = Arrays.stream(server.getPlayerNames());
        if (SimpleMyRoom.config().commands.suggestKnownRoomOwners) {
            names = Stream.concat(names, RoomStorage.get(server).ownerNames().stream());
        }
        return SharedSuggestionProvider.suggest(names.distinct(), builder);
    }

    private int enterOwnRoom(ServerPlayer player) {
        RoomStorage storage = RoomStorage.get(player.getServer());
        RoomRecord room = storage.getOrCreateOwnRoom(player.getUUID(), player.getGameProfile().getName());
        return enterRoom(player, room, true);
    }

    private int enterNamedRoom(ServerPlayer player, String ownerName) {
        if (player.getGameProfile().getName().equalsIgnoreCase(ownerName)) return enterOwnRoom(player);
        Optional<RoomRecord> targetRoom = RoomStorage.get(player.getServer()).findRoom(ownerName);
        if (targetRoom.isEmpty()) return fail(player, SimpleMyRoom.messages().roomNotFound);
        if (!RoomAccess.isVisitAdmin(player)) {
            String playerName = player.getGameProfile().getName();
            if (targetRoom.get().isBanned(playerName)) return fail(player, SimpleMyRoom.messages().visitBanned);
            if (!RoomAccess.canVisit(targetRoom.get(), playerName)) return fail(player, SimpleMyRoom.messages().roomPrivate);
        }
        return enterRoom(player, targetRoom.get(), false);
    }

    private int enterRoom(ServerPlayer player, RoomRecord room, boolean ownerEntry) {
        if (!RoomAccess.hasAdminPermission(player)) {
            long duration = SimpleMyRoom.config().commands.enterCooldownSeconds * 1000L;
            CommandCooldown.Result cooldown = enterCooldown.tryAcquire(player.getUUID(), System.currentTimeMillis(), duration);
            if (!cooldown.allowed()) {
                long seconds = Math.max(1, (cooldown.remainingMillis() + 999) / 1000);
                return fail(player, message(SimpleMyRoom.messages().enterCooldown, "seconds", seconds));
            }
        }
        ServerLevel target = player.getServer().getLevel(RoomDimensions.KEY);
        if (target == null) return fail(player, SimpleMyRoom.messages().dimensionUnavailable);
        RoomStorage storage = RoomStorage.get(player.getServer());
        RoomLayout layout = storage.effectiveLayout();
        if (!room.initialized() && !ownerEntry) return fail(player, SimpleMyRoom.messages().roomNotInitialized);
        boolean needsPreparation = !room.initialized() || room.layoutVersion() < SimpleMyRoom.config().world.currentLayoutVersion
            && (SimpleMyRoom.config().world.upgradeLegacyRoomsOnAnyEntry || ownerEntry);
        if (needsPreparation) return queueRoomPreparation(player, room, target, room.initialized());
        return teleportIntoRoom(player, room, target, storage, layout);
    }

    private int teleportIntoRoom(
        ServerPlayer player,
        RoomRecord room,
        ServerLevel target,
        RoomStorage storage,
        RoomLayout layout
    ) {
        RoomArea area = layout.areaFor(room.index());
        RoomSpawnPoint destination = entryPoint(target, player, room, area);
        if (destination == null) return fail(player, SimpleMyRoom.messages().customSpawnUnsafe);
        if (!RoomDimensions.isRoom(player.level())) saveReturnPoint(player, storage);
        boolean teleported = player.teleportTo(
            target,
            destination.x(),
            destination.y(),
            destination.z(),
            Set.of(),
            destination.yaw(),
            destination.pitch()
        );
        if (!teleported) return fail(player, SimpleMyRoom.messages().teleportFailed);
        player.sendSystemMessage(Component.literal(message(SimpleMyRoom.messages().enteredRoom, "owner", room.ownerName())));
        return 1;
    }

    private int queueRoomPreparation(ServerPlayer player, RoomRecord room, ServerLevel target, boolean upgrade) {
        MinecraftServer server = player.getServer();
        UUID requesterId = player.getUUID();
        UUID ownerId = room.ownerId();
        int targetLayoutVersion = SimpleMyRoom.config().world.currentLayoutVersion;
        RoomArea area = RoomStorage.get(server).effectiveLayout().areaFor(room.index());
        RoomWorldInitializer.PreparationTask task = worldInitializer.createTask(target, area, upgrade);
        RoomPreparationService.QueueResult result = roomPreparationService.enqueue(
            room.index(), requesterId, task,
            () -> finishRoomPreparation(server, requesterId, ownerId, targetLayoutVersion),
            () -> notifyPreparationFailure(server, requesterId)
        );
        if (result == RoomPreparationService.QueueResult.FULL) {
            return fail(player, SimpleMyRoom.messages().roomPreparationQueueFull);
        }
        if (result == RoomPreparationService.QueueResult.FAILED) return 0;
        if (SimpleMyRoom.config().roomPreparation.notifyWhenQueued) {
            String text = result == RoomPreparationService.QueueResult.JOINED
                ? SimpleMyRoom.messages().roomPreparationJoined
                : SimpleMyRoom.messages().roomPreparationQueued;
            if (result != RoomPreparationService.QueueResult.COMPLETED_SYNCHRONOUSLY) {
                player.sendSystemMessage(Component.literal(text));
            }
        }
        return 1;
    }

    private void finishRoomPreparation(MinecraftServer server, UUID requesterId, UUID ownerId, int targetLayoutVersion) {
        RoomStorage storage = RoomStorage.get(server);
        RoomRecord current = storage.findRoom(ownerId).orElse(null);
        if (current == null) return;
        boolean newlyInitialized = !current.initialized();
        boolean upgraded = current.layoutVersion() < targetLayoutVersion;
        if (newlyInitialized || upgraded) current = storage.markInitialized(current, targetLayoutVersion);
        if (newlyInitialized) {
            activeRoomService.onRoomInitialized(current);
            if (SimpleMyRoom.config().general.logRoomCreation) {
                SimpleMyRoom.LOGGER.info("Created room {} for {} at slot {}.", current.ownerId(), current.ownerName(), current.index());
            }
        } else if (upgraded && SimpleMyRoom.config().general.logRoomUpgrade) {
            SimpleMyRoom.LOGGER.info("Upgraded room {} for {} to layout version {}.", current.ownerId(), current.ownerName(), current.layoutVersion());
        }
        ServerPlayer requester = server.getPlayerList().getPlayer(requesterId);
        if (requester == null) return;
        if (SimpleMyRoom.config().roomPreparation.notifyWhenComplete) {
            requester.sendSystemMessage(Component.literal(SimpleMyRoom.messages().roomPreparationComplete));
        }
        if (!SimpleMyRoom.config().roomPreparation.teleportRequesterOnComplete || RoomDimensions.isRoom(requester.level())) return;
        if (!requesterId.equals(current.ownerId()) && !RoomAccess.isVisitAdmin(requester)
            && !RoomAccess.canVisit(current, requester.getGameProfile().getName())) return;
        ServerLevel target = server.getLevel(RoomDimensions.KEY);
        if (target != null) teleportIntoRoom(requester, current, target, storage, storage.effectiveLayout());
    }

    private void notifyPreparationFailure(MinecraftServer server, UUID requesterId) {
        ServerPlayer requester = server.getPlayerList().getPlayer(requesterId);
        if (requester != null) requester.sendSystemMessage(Component.literal(SimpleMyRoom.messages().roomPreparationFailed));
    }

    private RoomSpawnPoint entryPoint(ServerLevel target, ServerPlayer player, RoomRecord room, RoomArea area) {
        var layout = SimpleMyRoom.config().layout;
        RoomSpawnPoint fallback = new RoomSpawnPoint(
            area.spawnX(layout.spawnXOffset), area.spawnY(layout.spawnYOffset), area.spawnZ(layout.spawnZOffset),
            layout.spawnYaw, layout.spawnPitch
        );
        if (!SimpleMyRoom.config().customSpawn.requireSafePosition) {
            return SimpleMyRoom.config().customSpawn.enabled ? room.customSpawn().orElse(fallback) : fallback;
        }
        var config = SimpleMyRoom.config().customSpawn;
        if (config.enabled && room.customSpawn().isPresent()) {
            Optional<RoomSpawnPoint> custom = findSafeRoomPoint(target, player, room.customSpawn().orElseThrow(), area);
            if (custom.isPresent()) return custom.get();
            if (!config.fallbackToDefaultWhenUnsafe) return null;
            player.sendSystemMessage(Component.literal(SimpleMyRoom.messages().customSpawnFallback));
        }
        return findSafeRoomPoint(target, player, fallback, area).orElse(null);
    }

    private Optional<RoomSpawnPoint> findSafeRoomPoint(ServerLevel target, ServerPlayer player, RoomSpawnPoint requested, RoomArea area) {
        var config = SimpleMyRoom.config().customSpawn;
        return safeTeleportResolver.find(
            target, player, requested, config.horizontalSearchRadius, config.verticalSearchRange,
            config.requireSolidFloor, config.allowFluid, point -> point.isInside(area)
        );
    }

    private void saveReturnPoint(ServerPlayer player, RoomStorage storage) {
        ResourceLocation sourceDimension = player.level().dimension().location();
        if (!ReturnDimensionPolicy.shouldSave(sourceDimension)) return;
        boolean exact = SimpleMyRoom.config().returnBehavior.saveExactPosition;
        double x = exact ? player.getX() : player.blockPosition().getX() + 0.5;
        double y = exact ? player.getY() : player.blockPosition().getY();
        double z = exact ? player.getZ() : player.blockPosition().getZ() + 0.5;
        float yaw = SimpleMyRoom.config().returnBehavior.saveYaw ? player.getYRot() : 0.0f;
        float pitch = SimpleMyRoom.config().returnBehavior.savePitch ? player.getXRot() : 0.0f;
        storage.putReturnPoint(player.getUUID(), new ReturnPoint(sourceDimension.toString(), x, y, z, yaw, pitch));
    }

    private int exitRoom(ServerPlayer player) {
        if (!RoomDimensions.isRoom(player.level())) return fail(player, SimpleMyRoom.messages().notInsideRoom);
        return returnToSavedPoint(player, SimpleMyRoom.config().returnBehavior.fallbackToOverworldSpawn);
    }

    private int returnToSavedPoint(ServerPlayer player, boolean allowSpawnFallback) {
        RoomStorage storage = RoomStorage.get(player.getServer());
        Optional<ReturnPoint> pointResult = storage.findReturnPoint(player.getUUID());
        if (pointResult.isEmpty()) {
            if (!allowSpawnFallback) return fail(player, SimpleMyRoom.messages().noReturnPoint);
            int result = teleportToSpawn(player);
            if (result == 1) player.sendSystemMessage(Component.literal(SimpleMyRoom.messages().noReturnPointFallback));
            return result;
        }
        ReturnPoint point = pointResult.get();
        ResourceLocation dimensionId = ResourceLocation.tryParse(point.dimension());
        if (dimensionId == null) {
            clearFailedReturnPointWhenConfigured(storage, player);
            return allowSpawnFallback ? teleportToSpawn(player) : fail(player, SimpleMyRoom.messages().invalidReturnDimension);
        }
        ServerLevel target = player.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, dimensionId));
        if (target == null) {
            clearFailedReturnPointWhenConfigured(storage, player);
            return allowSpawnFallback ? teleportToSpawn(player) : fail(player, SimpleMyRoom.messages().returnDimensionMissing);
        }
        RoomSpawnPoint requested = new RoomSpawnPoint(point.x(), point.y(), point.z(), point.yaw(), point.pitch());
        Optional<RoomSpawnPoint> safe = safeReturnPoint(target, player, requested);
        if (safe.isEmpty()) {
            if (!allowSpawnFallback) return fail(player, SimpleMyRoom.messages().safeReturnPositionMissing);
            player.sendSystemMessage(Component.literal(SimpleMyRoom.messages().safeReturnFallback));
            return teleportToSpawn(player);
        }
        RoomSpawnPoint destination = safe.get();
        boolean teleported = player.teleportTo(target, destination.x(), destination.y(), destination.z(), Set.of(), destination.yaw(), destination.pitch());
        if (!teleported) {
            clearFailedReturnPointWhenConfigured(storage, player);
            return fail(player, SimpleMyRoom.messages().returnFailed);
        }
        if (SimpleMyRoom.config().returnBehavior.clearPointAfterSuccessfulReturn) storage.removeReturnPoint(player.getUUID());
        player.sendSystemMessage(Component.literal(SimpleMyRoom.messages().returned));
        return 1;
    }

    private int setPublicAccess(ServerPlayer owner, boolean allowed) {
        RoomStorage storage = RoomStorage.get(owner.getServer());
        storage.getOrCreateOwnRoom(owner.getUUID(), owner.getGameProfile().getName());
        storage.setPublicAccess(owner.getUUID(), allowed);
        owner.sendSystemMessage(Component.literal(allowed ? SimpleMyRoom.messages().publicEnabled : SimpleMyRoom.messages().publicDisabled));
        if (!allowed && SimpleMyRoom.config().access.ejectVisitorsWhenRoomBecomesPrivate) ejectVisitors(owner);
        return 1;
    }

    private int banVisitor(ServerPlayer owner, String playerName) {
        if (rejectSelfTarget(owner, playerName)) return 0;
        RoomStorage storage = RoomStorage.get(owner.getServer());
        storage.getOrCreateOwnRoom(owner.getUUID(), owner.getGameProfile().getName());
        storage.banVisitor(owner.getUUID(), playerName);
        owner.sendSystemMessage(Component.literal(message(SimpleMyRoom.messages().playerBanned, "player", playerName)));
        if (SimpleMyRoom.config().access.ejectPlayerImmediatelyWhenBanned) {
            ServerPlayer target = owner.getServer().getPlayerList().getPlayerByName(playerName);
            if (target != null) ejectIfInside(owner, target);
        }
        return 1;
    }

    private int unbanVisitor(ServerPlayer owner, String playerName) {
        if (RoomStorage.get(owner.getServer()).unbanVisitor(owner.getUUID(), playerName).isEmpty()) {
            return fail(owner, SimpleMyRoom.messages().roomRequired);
        }
        owner.sendSystemMessage(Component.literal(message(SimpleMyRoom.messages().playerUnbanned, "player", playerName)));
        return 1;
    }

    private int trustPlayer(ServerPlayer owner, String playerName) {
        if (rejectSelfTarget(owner, playerName)) return 0;
        RoomStorage storage = RoomStorage.get(owner.getServer());
        storage.getOrCreateOwnRoom(owner.getUUID(), owner.getGameProfile().getName());
        storage.trustPlayer(owner.getUUID(), playerName);
        owner.sendSystemMessage(Component.literal(message(SimpleMyRoom.messages().playerTrusted, "player", playerName)));
        return 1;
    }

    private int untrustPlayer(ServerPlayer owner, String playerName) {
        RoomStorage storage = RoomStorage.get(owner.getServer());
        RoomRecord room = storage.untrustPlayer(owner.getUUID(), playerName).orElse(null);
        if (room == null) return fail(owner, SimpleMyRoom.messages().roomRequired);
        owner.sendSystemMessage(Component.literal(message(SimpleMyRoom.messages().playerUntrusted, "player", playerName)));
        if (!room.publicAccess() && SimpleMyRoom.config().access.ejectUntrustedPlayerFromPrivateRoom) {
            ServerPlayer target = owner.getServer().getPlayerList().getPlayerByName(playerName);
            if (target != null) ejectIfInside(owner, target);
        }
        return 1;
    }

    private boolean rejectSelfTarget(ServerPlayer owner, String playerName) {
        if (SimpleMyRoom.config().access.ownerCanTargetSelf || !owner.getGameProfile().getName().equalsIgnoreCase(playerName)) return false;
        fail(owner, SimpleMyRoom.messages().cannotTargetSelf);
        return true;
    }

    private int setCustomSpawn(ServerPlayer owner) {
        if (!SimpleMyRoom.config().customSpawn.enabled) {
            return fail(owner, SimpleMyRoom.messages().customSpawnDisabled);
        }
        RoomStorage storage = RoomStorage.get(owner.getServer());
        RoomRecord room = storage.findRoom(owner.getUUID()).orElse(null);
        if (room == null || !room.initialized() || !RoomDimensions.isRoom(owner.level())) {
            return fail(owner, SimpleMyRoom.messages().customSpawnOwnerRoomRequired);
        }
        long currentIndex = storage.effectiveLayout()
            .indexAt((int) Math.floor(owner.getX()), (int) Math.floor(owner.getZ())).orElse(-1L);
        if (currentIndex != room.index()) return fail(owner, SimpleMyRoom.messages().customSpawnOwnerRoomRequired);
        RoomSpawnPoint point = new RoomSpawnPoint(
            owner.getX(), owner.getY(), owner.getZ(), owner.getYRot(), owner.getXRot()
        );
        var spawnConfig = SimpleMyRoom.config().customSpawn;
        if (spawnConfig.requireSafePosition && safeTeleportResolver.find(
            (ServerLevel) owner.level(), owner, point, 0, 0,
            spawnConfig.requireSolidFloor, spawnConfig.allowFluid, candidate -> candidate.isInside(storage.effectiveLayout().areaFor(room.index()))
        ).isEmpty()) {
            return fail(owner, SimpleMyRoom.messages().customSpawnUnsafe);
        }
        storage.setCustomSpawn(owner.getUUID(), point);
        owner.sendSystemMessage(Component.literal(SimpleMyRoom.messages().customSpawnSet));
        return 1;
    }

    private int resetCustomSpawn(ServerPlayer owner) {
        if (!SimpleMyRoom.config().customSpawn.allowReset) {
            return fail(owner, SimpleMyRoom.messages().customSpawnResetDisabled);
        }
        RoomStorage storage = RoomStorage.get(owner.getServer());
        RoomRecord room = storage.findRoom(owner.getUUID()).orElse(null);
        if (room == null) return fail(owner, SimpleMyRoom.messages().roomRequired);
        if (room.customSpawn().isEmpty()) return fail(owner, SimpleMyRoom.messages().customSpawnNotSet);
        storage.clearCustomSpawn(owner.getUUID());
        owner.sendSystemMessage(Component.literal(SimpleMyRoom.messages().customSpawnReset));
        return 1;
    }

    private int showVisitors(ServerPlayer owner) {
        RoomRecord room = RoomStorage.get(owner.getServer()).findRoom(owner.getUUID()).orElse(null);
        if (room == null) return fail(owner, SimpleMyRoom.messages().roomRequired);
        List<String> names = RoomOccupancy.visitorsIn(
            owner.getServer(), room, SimpleMyRoom.config().visitorNotifications.includeOwnerInVisitorsList
        ).stream()
            .map(player -> player.getGameProfile().getName())
            .toList();
        int limit = Math.min(names.size(), SimpleMyRoom.config().visitorNotifications.maxVisitorsShown);
        String shown = limit == 0 ? SimpleMyRoom.messages().stateEmpty : String.join(", ", names.subList(0, limit));
        if (limit < names.size()) {
            shown += ", " + message(SimpleMyRoom.messages().listTruncated, "count", names.size() - limit);
        }
        owner.sendSystemMessage(Component.literal(SimpleMyRoomMessages.format(
            SimpleMyRoom.messages().visitors, Map.of("count", names.size(), "players", shown)
        )));
        return 1;
    }

    private int showVisitorNotificationStatus(ServerPlayer owner) {
        RoomRecord room = RoomStorage.get(owner.getServer()).findRoom(owner.getUUID()).orElse(null);
        if (room == null) return fail(owner, SimpleMyRoom.messages().roomRequired);
        String state = room.notifyVisitors() ? SimpleMyRoom.messages().stateEnabled : SimpleMyRoom.messages().stateDisabled;
        owner.sendSystemMessage(Component.literal(message(SimpleMyRoom.messages().visitorNotificationStatus, "state", state)));
        return 1;
    }

    private int setVisitorNotifications(ServerPlayer owner, boolean enabled) {
        if (!SimpleMyRoom.config().visitorNotifications.enabled) {
            return fail(owner, SimpleMyRoom.messages().visitorNotificationsGloballyDisabled);
        }
        RoomStorage storage = RoomStorage.get(owner.getServer());
        storage.getOrCreateOwnRoom(owner.getUUID(), owner.getGameProfile().getName());
        storage.setVisitorNotifications(owner.getUUID(), enabled);
        owner.sendSystemMessage(Component.literal(enabled
            ? SimpleMyRoom.messages().visitorNotificationsEnabled
            : SimpleMyRoom.messages().visitorNotificationsDisabled));
        return 1;
    }

    private int showRoomInfo(ServerPlayer owner, String rootName) {
        RoomStorage storage = RoomStorage.get(owner.getServer());
        RoomRecord room = storage.getOrCreateOwnRoom(owner.getUUID(), owner.getGameProfile().getName());
        RoomArea area = storage.effectiveLayout().areaFor(room.index());
        owner.sendSystemMessage(Component.literal(SimpleMyRoom.messages().infoHeader));
        owner.sendSystemMessage(Component.literal(message(SimpleMyRoom.messages().infoPublic, "state",
            room.publicAccess() ? SimpleMyRoom.messages().statePublic : SimpleMyRoom.messages().statePrivate)));
        owner.sendSystemMessage(Component.literal(message(SimpleMyRoom.messages().infoTrusted, "players", formatNames(room.trustedPlayers()))));
        owner.sendSystemMessage(Component.literal(message(SimpleMyRoom.messages().infoBanned, "players", formatNames(room.bannedVisitors()))));
        owner.sendSystemMessage(Component.literal(SimpleMyRoomMessages.format(SimpleMyRoom.messages().infoLayout, Map.of(
            "index", room.index(), "minX", area.minX(), "maxX", area.maxX(), "minZ", area.minZ(), "maxZ", area.maxZ()))));
        owner.sendSystemMessage(Component.literal(message(SimpleMyRoom.messages().infoActive, "state", activeState(room))));
        owner.sendSystemMessage(Component.literal(message(SimpleMyRoom.messages().infoNotify, "state",
            room.notifyVisitors() ? SimpleMyRoom.messages().stateEnabled : SimpleMyRoom.messages().stateDisabled)));
        if (SimpleMyRoom.config().commands.showHelpOnRoot) showHelp(owner, rootName);
        return 1;
    }

    private String formatNames(Set<String> names) {
        List<String> sorted = names.stream().sorted().toList();
        int limit = Math.min(sorted.size(), SimpleMyRoom.config().commands.maxNamesInInfo);
        if (limit == 0) return SimpleMyRoom.messages().stateEmpty;
        String shown = String.join(", ", sorted.subList(0, limit));
        if (limit < sorted.size()) {
            shown += ", " + message(SimpleMyRoom.messages().listTruncated, "count", sorted.size() - limit);
        }
        return shown;
    }

    private void showHelp(ServerPlayer player, String root) {
        player.sendSystemMessage(Component.literal(SimpleMyRoom.messages().helpHeader));
        for (String line : List.of(
            "/" + root + " enter [플레이어] - 마이룸 입장",
            "/" + root + " exit - 입장 전 위치로 복귀",
            "/" + root + " public <true|false> - 공개 여부 변경",
            "/" + root + " trust <플레이어> - 행동 권한 허용",
            "/" + root + " untrust <플레이어> - 행동 권한 해제",
            "/" + root + " ban <플레이어> - 방문 차단",
            "/" + root + " unban <플레이어> - 방문 차단 해제",
            "/" + root + " kick <플레이어> - 현재 방문자 퇴장",
            "/" + root + " active <on|off|status> - 마이룸 상시 활성화",
            "/" + root + " setspawn [reset] - 입장 위치와 방향 지정 또는 초기화",
            "/" + root + " visitors - 현재 내 방 방문자 확인",
            "/" + root + " notify <on|off> - 방문자 입장·퇴장 알림"
        )) player.sendSystemMessage(Component.literal(line));
        if (RoomAccess.hasAdminPermission(player)) {
            if (SimpleMyRoom.config().commands.enableConfigReloadCommand) {
                player.sendSystemMessage(Component.literal("/" + root + " reload - JSON 설정 다시 불러오기"));
            }
        }
    }

    private int reloadConfig(ServerPlayer player) {
        ConfigManager.ReloadResult result = SimpleMyRoom.reloadConfig();
        if (!result.successful()) return fail(player, message(SimpleMyRoom.messages().configReloadFailed, "reason", result.error()));
        activeRoomService.reconcile();
        player.sendSystemMessage(Component.literal(SimpleMyRoom.messages().configReloaded));
        if (SimpleMyRoom.config().general.logConfigReload) SimpleMyRoom.LOGGER.info("Simple MyRoom configuration reloaded by {}.", player.getGameProfile().getName());
        return 1;
    }

    private int showActiveStatus(ServerPlayer player) {
        RoomRecord room = RoomStorage.get(player.getServer()).findRoom(player.getUUID()).orElse(null);
        if (room == null) return fail(player, SimpleMyRoom.messages().roomRequired);
        int chunks = activeRoomService.chunkCount(room);
        String text = SimpleMyRoom.config().keepActive.showChunkCountInStatus
            ? SimpleMyRoomMessages.format(SimpleMyRoom.messages().activeStatus, Map.of("state", activeState(room), "chunks", chunks))
            : message(SimpleMyRoom.messages().activeStatusWithoutChunks, "state", activeState(room));
        player.sendSystemMessage(Component.literal(text));
        return 1;
    }

    private int setActive(ServerPlayer player, boolean enabled) {
        if (!RoomAccess.hasAdminPermission(player)) {
            long duration = SimpleMyRoom.config().keepActive.commandCooldownSeconds * 1000L;
            CommandCooldown.Result cooldown = activeCooldown.tryAcquire(player.getUUID(), System.currentTimeMillis(), duration);
            if (!cooldown.allowed()) {
                long seconds = Math.max(1, (cooldown.remainingMillis() + 999) / 1000);
                return fail(player, message(SimpleMyRoom.messages().activeCooldown, "seconds", seconds));
            }
        }
        ActiveRoomService.ChangeResult result = activeRoomService.change(player, enabled);
        String response = switch (result) {
            case ENABLED -> SimpleMyRoom.messages().activeEnabled;
            case DISABLED -> SimpleMyRoom.messages().activeDisabled;
            case ALREADY_ENABLED -> SimpleMyRoom.messages().activeAlreadyEnabled;
            case ALREADY_DISABLED -> SimpleMyRoom.messages().activeAlreadyDisabled;
            case GLOBALLY_DISABLED -> SimpleMyRoom.messages().activeGloballyDisabled;
            case TOGGLE_NOT_ALLOWED -> SimpleMyRoom.messages().activeToggleNotAllowed;
            case ROOM_REQUIRED -> SimpleMyRoom.messages().roomRequired;
            case ROOM_NOT_INITIALIZED -> SimpleMyRoom.messages().activeRoomNotInitialized;
            case ACTIVE_ROOM_LIMIT_REACHED -> message(SimpleMyRoom.messages().activeRoomLimitReached,
                "limit", SimpleMyRoom.config().keepActive.maxActiveRooms);
            case CHUNK_LIMIT_EXCEEDED -> message(SimpleMyRoom.messages().activeChunkLimitExceeded,
                "limit", SimpleMyRoom.config().keepActive.maxChunksPerRoom);
        };
        if (result == ActiveRoomService.ChangeResult.ENABLED || result == ActiveRoomService.ChangeResult.DISABLED
            || result == ActiveRoomService.ChangeResult.ALREADY_ENABLED || result == ActiveRoomService.ChangeResult.ALREADY_DISABLED) {
            player.sendSystemMessage(Component.literal(response));
            return 1;
        }
        return fail(player, response);
    }

    private String activeState(RoomRecord room) {
        if (!room.keepActive()) return SimpleMyRoom.messages().stateInactive;
        return activeRoomService.isApplied(room.ownerId())
            ? SimpleMyRoom.messages().stateActive
            : SimpleMyRoom.messages().statePending;
    }

    private int kickVisitor(ServerPlayer owner, String playerName) {
        ServerPlayer target = owner.getServer().getPlayerList().getPlayerByName(playerName);
        if (target == null || target.getUUID().equals(owner.getUUID()) || !ejectIfInside(owner, target)) {
            return fail(owner, SimpleMyRoom.messages().playerNotInRoom);
        }
        owner.sendSystemMessage(Component.literal(message(SimpleMyRoom.messages().playerKicked, "player", playerName)));
        return 1;
    }

    private void ejectVisitors(ServerPlayer owner) {
        RoomRecord room = RoomStorage.get(owner.getServer()).findRoom(owner.getUUID()).orElse(null);
        if (room == null) return;
        for (ServerPlayer target : owner.getServer().getPlayerList().getPlayers()) {
            if (!RoomAccess.canOccupy(room, target)) ejectIfInside(owner, target);
        }
    }

    private boolean ejectIfInside(ServerPlayer owner, ServerPlayer target) {
        if (!RoomDimensions.isRoom(target.level())) return false;
        RoomStorage storage = RoomStorage.get(owner.getServer());
        Optional<RoomRecord> ownerRoom = storage.findRoom(owner.getUUID());
        long currentIndex = storage.effectiveLayout().indexAt((int) Math.floor(target.getX()), (int) Math.floor(target.getZ())).orElse(-1L);
        if (ownerRoom.isEmpty() || currentIndex != ownerRoom.get().index()) return false;
        target.sendSystemMessage(Component.literal(SimpleMyRoom.messages().kickedByOwner));
        return returnToSavedPoint(target, SimpleMyRoom.config().returnBehavior.fallbackToOverworldSpawn) == 1;
    }

    public boolean ejectUnauthorizedPhysicalEntrant(ServerPlayer target, RoomRecord room) {
        if (!RoomDimensions.isRoom(target.level()) || RoomOccupancy.roomIndex(
            target, RoomStorage.get(target.getServer()).effectiveLayout()) != room.index()) return false;
        target.sendSystemMessage(Component.literal(SimpleMyRoom.messages().unauthorizedRoomEntry));
        return returnToSavedPoint(target, SimpleMyRoom.config().returnBehavior.fallbackToOverworldSpawn) == 1;
    }

    private int teleportToSpawn(ServerPlayer player) {
        ServerLevel overworld = player.getServer().overworld();
        BlockPos spawn = overworld.getSharedSpawnPos();
        RoomSpawnPoint requested = new RoomSpawnPoint(
            spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, overworld.getSharedSpawnAngle(), 0.0f
        );
        Optional<RoomSpawnPoint> safe = safeReturnPoint(overworld, player, requested);
        if (safe.isEmpty()) return fail(player, SimpleMyRoom.messages().safeSpawnPositionMissing);
        RoomSpawnPoint destination = safe.get();
        boolean teleported = player.teleportTo(
            overworld, destination.x(), destination.y(), destination.z(), Set.of(), destination.yaw(), destination.pitch());
        if (teleported && SimpleMyRoom.config().returnBehavior.clearPointAfterSuccessfulReturn) {
            RoomStorage.get(player.getServer()).removeReturnPoint(player.getUUID());
        }
        return teleported ? 1 : 0;
    }

    private Optional<RoomSpawnPoint> safeReturnPoint(ServerLevel target, ServerPlayer player, RoomSpawnPoint requested) {
        var config = SimpleMyRoom.config().returnBehavior;
        if (!config.findSafeReturnPosition) return Optional.of(requested);
        return safeTeleportResolver.find(
            target, player, requested, config.safeSearchHorizontalRadius, config.safeSearchVerticalRange,
            config.requireSolidFloor, config.allowFluid, point -> true
        );
    }

    private void clearFailedReturnPointWhenConfigured(RoomStorage storage, ServerPlayer player) {
        if (!SimpleMyRoom.config().returnBehavior.preservePointWhenTeleportFails) {
            storage.removeReturnPoint(player.getUUID());
        }
    }

    private void handleRespawn(ServerPlayer oldPlayer, ServerPlayer newPlayer, boolean alive) {
        boolean diedInRoom = RoomDimensions.isRoom(oldPlayer.level());
        boolean respawnedInRoom = RoomDimensions.isRoom(newPlayer.level());
        boolean targetRoomAllowsPlayer = !respawnedInRoom || RoomAccess.roomAt(
            newPlayer, (int) Math.floor(newPlayer.getX()), (int) Math.floor(newPlayer.getZ())
        ).map(room -> RoomAccess.canOccupy(room, newPlayer)).orElse(false);
        RoomRespawnPolicy.Decision decision = RoomRespawnPolicy.decide(diedInRoom, respawnedInRoom, targetRoomAllowsPlayer);
        RoomStorage storage = RoomStorage.get(newPlayer.getServer());
        if (decision.clearReturnPoint() && SimpleMyRoom.config().returnBehavior.clearStalePointAfterRespawnOutsideRoom) {
            storage.removeReturnPoint(newPlayer.getUUID());
        }
        if (decision.ejectToOverworld() && SimpleMyRoom.config().returnBehavior.ejectUnauthorizedRoomRespawn
            && teleportToSpawn(newPlayer) == 1) {
            newPlayer.sendSystemMessage(Component.literal(SimpleMyRoom.messages().invalidRoomRespawn));
        }
    }

    private int fail(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal(text));
        return 0;
    }

    private static String message(String template, String key, Object value) {
        return SimpleMyRoomMessages.format(template, Map.of(key, value));
    }
}
