package jbro.cobblemon.simplemyroom.room;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import jbro.cobblemon.simplemyroom.SimpleMyRoom;
import jbro.cobblemon.simplemyroom.config.SimpleMyRoomConfig;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

public final class RoomStorage extends SavedData {
    private static final String STORAGE_KEY = "myroom_rooms";
    private static final SavedData.Factory<RoomStorage> FACTORY = new SavedData.Factory<>(RoomStorage::new, RoomStorage::load, null);
    private final Map<UUID, RoomRecord> rooms = new HashMap<>();
    private final Map<Long, UUID> roomOwnersByIndex = new HashMap<>();
    private final Map<UUID, ReturnPoint> returnPoints = new HashMap<>();
    private long nextRoomIndex;
    private RoomLayout storedLayout;

    public RoomStorage() {
    }

    public static RoomStorage get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, STORAGE_KEY);
    }

    public RoomLayout effectiveLayout() {
        if (storedLayout != null) return storedLayout;
        SimpleMyRoomConfig.Layout configured = SimpleMyRoom.config().layout;
        return new RoomLayout(configured.size, configured.spacing, configured.gridWidth, configured.floorY);
    }

    public boolean configuredLayoutMatchesStored() {
        if (storedLayout == null) return true;
        SimpleMyRoomConfig.Layout configured = SimpleMyRoom.config().layout;
        return storedLayout.equals(new RoomLayout(configured.size, configured.spacing, configured.gridWidth, configured.floorY));
    }

    public int roomCount() {
        return rooms.size();
    }

    public Collection<RoomRecord> rooms() {
        return ListCopy.rooms(rooms.values());
    }

    public RoomRecord getOrCreateOwnRoom(UUID ownerId, String ownerName) {
        RoomRecord existing = rooms.get(ownerId);
        if (existing != null) {
            if (!existing.ownerName().equals(ownerName)) {
                existing = existing.withOwnerName(ownerName);
                rooms.put(ownerId, existing);
                setDirty();
            }
            return existing;
        }
        if (storedLayout == null) storedLayout = effectiveLayout();
        var defaults = SimpleMyRoom.config().roomDefaults;
        RoomRecord created = RoomRecord.create(
            ownerId,
            ownerName,
            nextRoomIndex++,
            defaults.publicAccess,
            Set.copyOf(defaults.trustedPlayers),
            Set.copyOf(defaults.bannedPlayers),
            SimpleMyRoom.config().keepActive.defaultEnabled,
            SimpleMyRoom.config().visitorNotifications.defaultEnabled
        );
        rooms.put(ownerId, created);
        roomOwnersByIndex.put(created.index(), ownerId);
        setDirty();
        return created;
    }

    public Optional<RoomRecord> findRoom(UUID ownerId) {
        return Optional.ofNullable(rooms.get(ownerId));
    }

    public Optional<RoomRecord> findRoom(String ownerName) {
        String normalized = ownerName.toLowerCase(Locale.ROOT);
        return rooms.values().stream().filter(room -> room.ownerName().toLowerCase(Locale.ROOT).equals(normalized)).findFirst();
    }

    public Optional<RoomRecord> findRoom(long roomIndex) {
        return Optional.ofNullable(roomOwnersByIndex.get(roomIndex)).map(rooms::get);
    }

    public Collection<String> ownerNames() {
        return rooms.values().stream().map(RoomRecord::ownerName).toList();
    }

    public RoomRecord markInitialized(RoomRecord room, int layoutVersion) {
        RoomRecord updated = room.asInitialized(layoutVersion);
        rooms.put(updated.ownerId(), updated);
        setDirty();
        return updated;
    }

    public Optional<RoomRecord> setPublicAccess(UUID ownerId, boolean allowed) {
        return updateRoom(ownerId, room -> room.withPublicAccess(allowed));
    }

    public Optional<RoomRecord> setKeepActive(UUID ownerId, boolean enabled) {
        return updateRoom(ownerId, room -> room.withKeepActive(enabled));
    }

    public Optional<RoomRecord> setCustomSpawn(UUID ownerId, RoomSpawnPoint point) {
        return updateRoom(ownerId, room -> room.withCustomSpawn(point));
    }

    public Optional<RoomRecord> clearCustomSpawn(UUID ownerId) {
        return updateRoom(ownerId, RoomRecord::withoutCustomSpawn);
    }

    public Optional<RoomRecord> setVisitorNotifications(UUID ownerId, boolean enabled) {
        return updateRoom(ownerId, room -> room.withVisitorNotifications(enabled));
    }

    public long keepActiveRoomCount() {
        return rooms.values().stream().filter(room -> room.initialized() && room.keepActive()).count();
    }

    public Optional<RoomRecord> banVisitor(UUID ownerId, String playerName) {
        return updateRoom(ownerId, room -> room.withBannedVisitor(playerName));
    }

    public Optional<RoomRecord> unbanVisitor(UUID ownerId, String playerName) {
        return updateRoom(ownerId, room -> room.withoutBannedVisitor(playerName));
    }

    public Optional<RoomRecord> trustPlayer(UUID ownerId, String playerName) {
        return updateRoom(ownerId, room -> room.withTrustedPlayer(playerName));
    }

    public Optional<RoomRecord> untrustPlayer(UUID ownerId, String playerName) {
        return updateRoom(ownerId, room -> room.withoutTrustedPlayer(playerName));
    }

    private Optional<RoomRecord> updateRoom(UUID ownerId, UnaryOperator<RoomRecord> update) {
        RoomRecord current = rooms.get(ownerId);
        if (current == null) return Optional.empty();
        RoomRecord updated = update.apply(current);
        rooms.put(ownerId, updated);
        setDirty();
        return Optional.of(updated);
    }

    public void putReturnPoint(UUID playerId, ReturnPoint point) {
        returnPoints.put(playerId, point);
        setDirty();
    }

    public Optional<ReturnPoint> findReturnPoint(UUID playerId) {
        return Optional.ofNullable(returnPoints.get(playerId));
    }

    public void removeReturnPoint(UUID playerId) {
        if (returnPoints.remove(playerId) != null) setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag root, HolderLookup.Provider registries) {
        root.putLong("next_room_index", nextRoomIndex);
        RoomLayout layout = effectiveLayout();
        root.putInt("layout_size", layout.size());
        root.putInt("layout_spacing", layout.spacing());
        root.putInt("layout_grid_width", layout.gridWidth());
        root.putInt("layout_floor_y", layout.floorY());

        ListTag roomEntries = new ListTag();
        rooms.values().stream().sorted(Comparator.comparingLong(RoomRecord::index)).forEach(room -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("owner", room.ownerId());
            entry.putString("owner_name", room.ownerName());
            entry.putLong("index", room.index());
            entry.putBoolean("initialized", room.initialized());
            entry.putInt("layout_version", room.layoutVersion());
            entry.putBoolean("public_access", room.publicAccess());
            entry.putBoolean("keep_active", room.keepActive());
            entry.putBoolean("notify_visitors", room.notifyVisitors());
            room.customSpawn().ifPresent(point -> {
                CompoundTag spawn = new CompoundTag();
                spawn.putDouble("x", point.x());
                spawn.putDouble("y", point.y());
                spawn.putDouble("z", point.z());
                spawn.putFloat("yaw", point.yaw());
                spawn.putFloat("pitch", point.pitch());
                entry.put("custom_spawn", spawn);
            });
            entry.put("banned_visitors", namesToTag(room.bannedVisitors()));
            entry.put("trusted_players", namesToTag(room.trustedPlayers()));
            roomEntries.add(entry);
        });
        root.put("rooms", roomEntries);

        ListTag returnEntries = new ListTag();
        returnPoints.forEach((playerId, point) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("player", playerId);
            entry.putString("dimension", point.dimension());
            entry.putDouble("x", point.x());
            entry.putDouble("y", point.y());
            entry.putDouble("z", point.z());
            entry.putFloat("yaw", point.yaw());
            entry.putFloat("pitch", point.pitch());
            returnEntries.add(entry);
        });
        root.put("return_points", returnEntries);

        return root;
    }

    private static ListTag namesToTag(Set<String> names) {
        ListTag entries = new ListTag();
        names.stream().sorted().forEach(name -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("name", name);
            entries.add(entry);
        });
        return entries;
    }

    static RoomStorage load(CompoundTag root, HolderLookup.Provider registries) {
        RoomStorage storage = new RoomStorage();
        storage.nextRoomIndex = root.getLong("next_room_index");
        ListTag roomEntries = root.getList("rooms", Tag.TAG_COMPOUND);
        for (int index = 0; index < roomEntries.size(); index++) {
            CompoundTag entry = roomEntries.getCompound(index);
            Set<String> banned = namesFromTag(entry.getList("banned_visitors", Tag.TAG_COMPOUND));
            Set<String> trusted = namesFromTag(entry.getList("trusted_players", Tag.TAG_COMPOUND));
            boolean initialized = entry.getBoolean("initialized");
            RoomSpawnPoint customSpawn = null;
            if (entry.contains("custom_spawn", Tag.TAG_COMPOUND)) {
                CompoundTag spawn = entry.getCompound("custom_spawn");
                try {
                    customSpawn = new RoomSpawnPoint(
                        spawn.getDouble("x"), spawn.getDouble("y"), spawn.getDouble("z"),
                        spawn.getFloat("yaw"), spawn.getFloat("pitch")
                    );
                } catch (IllegalArgumentException exception) {
                    SimpleMyRoom.LOGGER.warn("Ignored invalid custom spawn for room owner {}.", entry.getString("owner_name"));
                }
            }
            RoomRecord room = new RoomRecord(
                entry.getUUID("owner"),
                entry.getString("owner_name"),
                entry.getLong("index"),
                initialized,
                !entry.contains("public_access") || entry.getBoolean("public_access"),
                banned,
                trusted,
                entry.contains("layout_version") ? entry.getInt("layout_version") : initialized ? 1 : 0,
                entry.contains("keep_active") && entry.getBoolean("keep_active"),
                customSpawn,
                !entry.contains("notify_visitors") || entry.getBoolean("notify_visitors")
            );
            storage.rooms.put(room.ownerId(), room);
            storage.roomOwnersByIndex.put(room.index(), room.ownerId());
            storage.nextRoomIndex = Math.max(storage.nextRoomIndex, room.index() + 1);
        }
        if (root.contains("layout_size", Tag.TAG_INT)) {
            storage.storedLayout = new RoomLayout(
                root.getInt("layout_size"),
                root.getInt("layout_spacing"),
                root.getInt("layout_grid_width"),
                root.getInt("layout_floor_y")
            );
        } else if (!storage.rooms.isEmpty()) {
            storage.storedLayout = RoomLayout.legacyDefaults();
        }

        ListTag returnEntries = root.getList("return_points", Tag.TAG_COMPOUND);
        for (int index = 0; index < returnEntries.size(); index++) {
            CompoundTag entry = returnEntries.getCompound(index);
            storage.returnPoints.put(entry.getUUID("player"), new ReturnPoint(
                entry.getString("dimension"), entry.getDouble("x"), entry.getDouble("y"), entry.getDouble("z"),
                entry.getFloat("yaw"), entry.getFloat("pitch")
            ));
        }
        if (root.contains("allowed_return_dimensions")) storage.setDirty();
        return storage;
    }

    private static Set<String> namesFromTag(ListTag entries) {
        Set<String> names = new HashSet<>();
        for (int index = 0; index < entries.size(); index++) names.add(entries.getCompound(index).getString("name"));
        return names;
    }

    private static final class ListCopy {
        private static Collection<RoomRecord> rooms(Collection<RoomRecord> rooms) {
            return List.copyOf(rooms);
        }
    }
}
