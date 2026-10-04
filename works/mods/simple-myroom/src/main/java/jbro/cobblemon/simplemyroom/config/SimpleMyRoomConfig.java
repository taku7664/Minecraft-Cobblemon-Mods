package jbro.cobblemon.simplemyroom.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class SimpleMyRoomConfig {
    private static final Pattern COMMAND_NAME = Pattern.compile("[a-z0-9_]+$");

    public int schemaVersion = 1;
    public General general = new General();
    public Commands commands = new Commands();
    public RoomDefaults roomDefaults = new RoomDefaults();
    public Access access = new Access();
    public Layout layout = new Layout();
    public Protection protection = new Protection();
    public VisitorInteractions visitorInteractions = new VisitorInteractions();
    public CustomSpawn customSpawn = new CustomSpawn();
    public VisitorNotifications visitorNotifications = new VisitorNotifications();
    public RoomPreparation roomPreparation = new RoomPreparation();
    public KeepActive keepActive = new KeepActive();
    public ReturnBehavior returnBehavior = new ReturnBehavior();
    public WorldBehavior world = new WorldBehavior();

    public static SimpleMyRoomConfig defaults() {
        return new SimpleMyRoomConfig();
    }

    public void normalize() {
        if (general == null) general = new General();
        if (commands == null) commands = new Commands();
        if (roomDefaults == null) roomDefaults = new RoomDefaults();
        if (access == null) access = new Access();
        if (layout == null) layout = new Layout();
        if (protection == null) protection = new Protection();
        if (visitorInteractions == null) visitorInteractions = new VisitorInteractions();
        if (customSpawn == null) customSpawn = new CustomSpawn();
        if (visitorNotifications == null) visitorNotifications = new VisitorNotifications();
        if (roomPreparation == null) roomPreparation = new RoomPreparation();
        if (keepActive == null) keepActive = new KeepActive();
        if (returnBehavior == null) returnBehavior = new ReturnBehavior();
        if (world == null) world = new WorldBehavior();

        commands.roots = normalizeNames(commands.roots, List.of("room", "myroom"));
        roomDefaults.trustedPlayers = normalizeNames(roomDefaults.trustedPlayers, List.of());
        roomDefaults.bannedPlayers = normalizeNames(roomDefaults.bannedPlayers, List.of());
        visitorInteractions.allowedBlockIds = normalizeNames(visitorInteractions.allowedBlockIds, VisitorInteractions.DEFAULT_BLOCK_IDS);
        returnBehavior.otherHubDimensions = normalizeNames(returnBehavior.otherHubDimensions, List.of());
        layout.platformBlock = defaultString(layout.platformBlock, "minecraft:grass_block");
        layout.boundaryBlock = defaultString(layout.boundaryBlock, "minecraft:barrier");
    }

    public List<String> validate() {
        normalize();
        List<String> errors = new ArrayList<>();
        if (schemaVersion != 1) errors.add("schemaVersion must be 1.");
        if (commands.roots.isEmpty() || commands.roots.stream().anyMatch(root -> !COMMAND_NAME.matcher(root).matches())) {
            errors.add("commands.roots must contain unique lowercase command names using a-z, 0-9, or underscore.");
        }
        if (commands.enterCooldownSeconds < 0 || commands.enterCooldownSeconds > 3600) {
            errors.add("commands.enterCooldownSeconds must be between 0 and 3600.");
        }
        if (commands.adminPermissionLevel < 0 || commands.adminPermissionLevel > 4) {
            errors.add("commands.adminPermissionLevel must be between 0 and 4.");
        }
        if (commands.maxNamesInInfo < 1 || commands.maxNamesInInfo > 1000) {
            errors.add("commands.maxNamesInInfo must be between 1 and 1000.");
        }
        if (layout.size <= 0 || layout.spacing < layout.size || layout.gridWidth <= 0) {
            errors.add("layout.spacing must be at least layout.size, and size/gridWidth must be positive.");
        }
        if (layout.floorY < -64 || layout.floorY > 319) {
            errors.add("layout.floorY must be between -64 and 319 for Minecraft 1.21.1.");
        }
        if (layout.spawnYOffset < 0.0 || layout.spawnYOffset > 32.0) {
            errors.add("layout.spawnYOffset must be between 0 and 32.");
        }
        if (protection.denialMessageCooldownMillis < 0 || protection.denialMessageCooldownMillis > 60000) {
            errors.add("protection.denialMessageCooldownMillis must be between 0 and 60000.");
        }
        if (keepActive.maxActiveRooms < 1 || keepActive.maxActiveRooms > 10000) {
            errors.add("keepActive.maxActiveRooms must be between 1 and 10000.");
        }
        if (keepActive.maxChunksPerRoom < 1 || keepActive.maxChunksPerRoom > 4096) {
            errors.add("keepActive.maxChunksPerRoom must be between 1 and 4096.");
        }
        if (keepActive.commandCooldownSeconds < 0 || keepActive.commandCooldownSeconds > 3600) {
            errors.add("keepActive.commandCooldownSeconds must be between 0 and 3600.");
        }
        if (visitorNotifications.maxVisitorsShown < 1 || visitorNotifications.maxVisitorsShown > 1000) {
            errors.add("visitorNotifications.maxVisitorsShown must be between 1 and 1000.");
        }
        if (customSpawn.horizontalSearchRadius < 0 || customSpawn.horizontalSearchRadius > 32) {
            errors.add("customSpawn.horizontalSearchRadius must be between 0 and 32.");
        }
        if (customSpawn.verticalSearchRange < 0 || customSpawn.verticalSearchRange > 32) {
            errors.add("customSpawn.verticalSearchRange must be between 0 and 32.");
        }
        if (returnBehavior.safeSearchHorizontalRadius < 0 || returnBehavior.safeSearchHorizontalRadius > 32) {
            errors.add("returnBehavior.safeSearchHorizontalRadius must be between 0 and 32.");
        }
        if (returnBehavior.safeSearchVerticalRange < 0 || returnBehavior.safeSearchVerticalRange > 32) {
            errors.add("returnBehavior.safeSearchVerticalRange must be between 0 and 32.");
        }
        if (roomPreparation.blocksPerTick < 1 || roomPreparation.blocksPerTick > 1_000_000) {
            errors.add("roomPreparation.blocksPerTick must be between 1 and 1000000.");
        }
        if (roomPreparation.maxQueuedRooms < 1 || roomPreparation.maxQueuedRooms > 10000) {
            errors.add("roomPreparation.maxQueuedRooms must be between 1 and 10000.");
        }
        if (visitorInteractions.allowedBlockIds.stream().anyMatch(id -> !id.contains(":"))) {
            errors.add("visitorInteractions.allowedBlockIds entries must be namespaced block IDs.");
        }
        if (world.currentLayoutVersion < 2) {
            errors.add("world.currentLayoutVersion must be at least 2 to preserve the expanded legacy room layout.");
        }
        return List.copyOf(errors);
    }

    private static List<String> normalizeNames(List<String> values, List<String> defaults) {
        List<String> source = values == null ? defaults : values;
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : source) {
            if (value != null && !value.isBlank()) normalized.add(value.trim().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(normalized);
    }

    private static String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toLowerCase(Locale.ROOT);
    }

    public static final class General {
        public boolean enabled = true;
        public boolean logRoomCreation = true;
        public boolean logRoomUpgrade = true;
        public boolean logConfigReload = true;
    }

    public static final class Commands {
        public List<String> roots = List.of("room", "myroom");
        public int enterCooldownSeconds = 0;
        public int adminPermissionLevel = 2;
        public int maxNamesInInfo = 100;
        public boolean suggestOnlinePlayers = true;
        public boolean suggestKnownRoomOwners = true;
        public boolean showHelpOnRoot = true;
        public boolean enableConfigReloadCommand = true;
    }

    public static final class RoomDefaults {
        public boolean publicAccess = true;
        public List<String> trustedPlayers = List.of();
        public List<String> bannedPlayers = List.of();
    }

    public static final class Access {
        public boolean adminsBypassProtection = true;
        public boolean adminsBypassVisitRules = true;
        public boolean trustedPlayersCanModify = true;
        public boolean trustedPlayersCanVisitPrivateRooms = true;
        public boolean ejectVisitorsWhenRoomBecomesPrivate = true;
        public boolean ejectPlayerImmediatelyWhenBanned = true;
        public boolean ejectUntrustedPlayerFromPrivateRoom = true;
        public boolean ejectUnauthorizedPhysicalEntrants = true;
        public boolean ownerCanTargetSelf = false;
    }

    public static final class Layout {
        public int size = 100;
        public int spacing = 512;
        public int gridWidth = 1024;
        public int floorY = 64;
        public String platformBlock = "minecraft:grass_block";
        public String boundaryBlock = "minecraft:barrier";
        public boolean createPlatform = true;
        public boolean createBoundary = true;
        public boolean preserveExistingBlocksOnUpgrade = true;
        public boolean boundaryFromMinToMaxBuildHeight = true;
        public double spawnXOffset = 0.0;
        public double spawnYOffset = 1.0;
        public double spawnZOffset = 0.0;
        public float spawnYaw = 0.0f;
        public float spawnPitch = 0.0f;
    }

    public static final class Protection {
        public boolean enabled = true;
        public boolean preventUnauthorizedBlockBreak = true;
        public boolean preventUnauthorizedPlacement = true;
        public boolean preventUnauthorizedBlockUse = true;
        public boolean preventUnauthorizedEntityAttack = true;
        public boolean preventUnauthorizedEntityUse = true;
        public boolean preventPlayerDamage = true;
        public boolean preventPetDamageToPlayers = true;
        public boolean preventExplosionsFromBreakingBlocks = true;
        public boolean preventFireSpread = true;
        public boolean allowNetherPortalCreation = true;
        public boolean disablePistons = true;
        public boolean preventVisitorFarmlandTrample = true;
        public boolean preventVisitorTurtleEggDamage = true;
        public boolean preventNaturalMobSpawning = true;
        public boolean preventChangesOutsideAllocatedRooms = true;
        public boolean preventEntitySpawnsOutsideAllocatedRooms = true;
        public boolean protectBoundaryBlocks = true;
        public boolean showDenialInActionBar = true;
        public long denialMessageCooldownMillis = 750;
    }

    public static final class VisitorInteractions {
        private static final List<String> DEFAULT_BLOCK_IDS = List.of(
            "minecraft:chest",
            "minecraft:trapped_chest",
            "minecraft:crafting_table",
            "minecraft:anvil",
            "minecraft:chipped_anvil",
            "minecraft:damaged_anvil",
            "minecraft:grindstone",
            "minecraft:ender_chest"
        );

        public boolean enabled = true;
        public boolean requireEmptyHand = true;
        public boolean denyPlacementAgainstAllowedBlocks = true;
        public boolean includeOriginalSafeBlockClasses = true;
        public List<String> allowedBlockIds = DEFAULT_BLOCK_IDS;
    }

    public static final class CustomSpawn {
        public boolean enabled = true;
        public boolean allowReset = true;
        public boolean requireSafePosition = true;
        public boolean fallbackToDefaultWhenUnsafe = true;
        public int horizontalSearchRadius = 4;
        public int verticalSearchRange = 4;
        public boolean requireSolidFloor = true;
        public boolean allowFluid = false;
    }

    public static final class VisitorNotifications {
        public boolean enabled = true;
        public boolean defaultEnabled = true;
        public boolean notifyOnEnter = true;
        public boolean notifyOnExit = true;
        public boolean includeTrustedPlayers = true;
        public boolean includeOwnerInVisitorsList = false;
        public int maxVisitorsShown = 100;
    }

    public static final class RoomPreparation {
        public boolean enabled = true;
        public int blocksPerTick = 4096;
        public int maxQueuedRooms = 32;
        public boolean teleportRequesterOnComplete = true;
        public boolean notifyWhenQueued = true;
        public boolean notifyWhenComplete = true;
    }

    public static final class KeepActive {
        public boolean enabled = true;
        public boolean defaultEnabled = false;
        public boolean restoreOnServerStart = true;
        public boolean allowOwnerToggle = true;
        public boolean adminsBypassActiveRoomLimit = true;
        public int maxActiveRooms = 20;
        public int maxChunksPerRoom = 64;
        public int commandCooldownSeconds = 3;
        public boolean showChunkCountInStatus = true;
        public boolean logStateChanges = true;
        public boolean logRestoration = true;
    }

    public static final class ReturnBehavior {
        public boolean saveExactPosition = true;
        public boolean saveYaw = true;
        public boolean savePitch = true;
        public boolean fallbackToOverworldSpawn = true;
        public boolean clearPointAfterSuccessfulReturn = true;
        public boolean preservePointWhenTeleportFails = true;
        public boolean clearStalePointAfterRespawnOutsideRoom = true;
        public boolean ejectUnauthorizedRoomRespawn = true;
        public boolean findSafeReturnPosition = true;
        public int safeSearchHorizontalRadius = 4;
        public int safeSearchVerticalRange = 4;
        public boolean requireSolidFloor = false;
        public boolean allowFluid = true;
        /** Try a solid, dry spot first and fall back to the requirements above only when none is near. */
        public boolean preferSolidFloor = true;
        /** Save the ground under a player who enters while flying, falling or riding, not the point in the air. */
        public boolean saveGroundPosition = true;
        /**
         * Other hub dimensions players hop between, such as a server plaza. Entering from one keeps the return point
         * already saved, and leaving every hub clears it, so exits never bounce between two hubs.
         */
        public List<String> otherHubDimensions = List.of("jbro_policy:plaza");
    }

    public static final class WorldBehavior {
        public int currentLayoutVersion = 2;
        public boolean upgradeLegacyRoomsOnAnyEntry = true;
    }
}
