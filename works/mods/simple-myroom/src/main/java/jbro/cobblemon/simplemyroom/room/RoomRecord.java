package jbro.cobblemon.simplemyroom.room;

import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public record RoomRecord(
    UUID ownerId,
    String ownerName,
    long index,
    boolean initialized,
    boolean publicAccess,
    Set<String> bannedVisitors,
    Set<String> trustedPlayers,
    int layoutVersion,
    boolean keepActive,
    RoomSpawnPoint spawnPoint,
    boolean notifyVisitors
) {
    public RoomRecord {
        if (ownerId == null || ownerName == null || ownerName.isBlank() || index < 0 || layoutVersion < 0) {
            throw new IllegalArgumentException("Room record is invalid.");
        }
        bannedVisitors = normalizeAll(bannedVisitors);
        trustedPlayers = normalizeAll(trustedPlayers);
    }

    public static RoomRecord create(
        UUID ownerId,
        String ownerName,
        long index,
        boolean publicAccess,
        Set<String> defaultTrustedPlayers,
        Set<String> defaultBannedPlayers
    ) {
        return create(ownerId, ownerName, index, publicAccess, defaultTrustedPlayers, defaultBannedPlayers, false, true);
    }

    public static RoomRecord create(
        UUID ownerId,
        String ownerName,
        long index,
        boolean publicAccess,
        Set<String> defaultTrustedPlayers,
        Set<String> defaultBannedPlayers,
        boolean keepActive
    ) {
        return create(ownerId, ownerName, index, publicAccess, defaultTrustedPlayers, defaultBannedPlayers, keepActive, true);
    }

    public static RoomRecord create(
        UUID ownerId,
        String ownerName,
        long index,
        boolean publicAccess,
        Set<String> defaultTrustedPlayers,
        Set<String> defaultBannedPlayers,
        boolean keepActive,
        boolean notifyVisitors
    ) {
        Set<String> trusted = normalizeAll(defaultTrustedPlayers);
        Set<String> banned = normalizeAll(defaultBannedPlayers);
        trusted = new HashSet<>(trusted);
        trusted.removeAll(banned);
        return new RoomRecord(ownerId, ownerName, index, false, publicAccess, banned, trusted, 0, keepActive, null, notifyVisitors);
    }

    public RoomRecord withOwnerName(String newName) {
        return copy(newName, initialized, publicAccess, bannedVisitors, trustedPlayers, layoutVersion, keepActive, spawnPoint, notifyVisitors);
    }

    public RoomRecord asInitialized(int newLayoutVersion) {
        return copy(ownerName, true, publicAccess, bannedVisitors, trustedPlayers, newLayoutVersion, keepActive, spawnPoint, notifyVisitors);
    }

    public RoomRecord withPublicAccess(boolean allowed) {
        return copy(ownerName, initialized, allowed, bannedVisitors, trustedPlayers, layoutVersion, keepActive, spawnPoint, notifyVisitors);
    }

    public RoomRecord withBannedVisitor(String playerName) {
        String normalized = normalize(playerName);
        Set<String> updatedBans = new HashSet<>(bannedVisitors);
        updatedBans.add(normalized);
        Set<String> updatedTrusted = new HashSet<>(trustedPlayers);
        updatedTrusted.remove(normalized);
        return copy(ownerName, initialized, publicAccess, updatedBans, updatedTrusted, layoutVersion, keepActive, spawnPoint, notifyVisitors);
    }

    public RoomRecord withoutBannedVisitor(String playerName) {
        Set<String> updated = new HashSet<>(bannedVisitors);
        updated.remove(normalize(playerName));
        return copy(ownerName, initialized, publicAccess, updated, trustedPlayers, layoutVersion, keepActive, spawnPoint, notifyVisitors);
    }

    public RoomRecord withTrustedPlayer(String playerName) {
        String normalized = normalize(playerName);
        Set<String> updatedTrusted = new HashSet<>(trustedPlayers);
        updatedTrusted.add(normalized);
        Set<String> updatedBans = new HashSet<>(bannedVisitors);
        updatedBans.remove(normalized);
        return copy(ownerName, initialized, publicAccess, updatedBans, updatedTrusted, layoutVersion, keepActive, spawnPoint, notifyVisitors);
    }

    public RoomRecord withoutTrustedPlayer(String playerName) {
        Set<String> updated = new HashSet<>(trustedPlayers);
        updated.remove(normalize(playerName));
        return copy(ownerName, initialized, publicAccess, bannedVisitors, updated, layoutVersion, keepActive, spawnPoint, notifyVisitors);
    }

    public RoomRecord withKeepActive(boolean enabled) {
        return copy(ownerName, initialized, publicAccess, bannedVisitors, trustedPlayers, layoutVersion, enabled, spawnPoint, notifyVisitors);
    }

    public Optional<RoomSpawnPoint> customSpawn() {
        return Optional.ofNullable(spawnPoint);
    }

    public RoomRecord withCustomSpawn(RoomSpawnPoint point) {
        if (point == null) throw new IllegalArgumentException("Room spawn point must not be null.");
        return copy(ownerName, initialized, publicAccess, bannedVisitors, trustedPlayers, layoutVersion, keepActive, point, notifyVisitors);
    }

    public RoomRecord withoutCustomSpawn() {
        return copy(ownerName, initialized, publicAccess, bannedVisitors, trustedPlayers, layoutVersion, keepActive, null, notifyVisitors);
    }

    public RoomRecord withVisitorNotifications(boolean enabled) {
        return copy(ownerName, initialized, publicAccess, bannedVisitors, trustedPlayers, layoutVersion, keepActive, spawnPoint, enabled);
    }

    private RoomRecord copy(
        String newOwnerName,
        boolean newInitialized,
        boolean newPublicAccess,
        Set<String> newBannedVisitors,
        Set<String> newTrustedPlayers,
        int newLayoutVersion,
        boolean newKeepActive,
        RoomSpawnPoint newSpawnPoint,
        boolean newNotifyVisitors
    ) {
        return new RoomRecord(ownerId, newOwnerName, index, newInitialized, newPublicAccess, newBannedVisitors,
            newTrustedPlayers, newLayoutVersion, newKeepActive, newSpawnPoint, newNotifyVisitors);
    }

    public boolean isBanned(String playerName) {
        return bannedVisitors.contains(normalize(playerName));
    }

    public boolean isTrusted(String playerName) {
        return trustedPlayers.contains(normalize(playerName));
    }

    public boolean canVisit(String playerName, boolean trustedCanVisitPrivate) {
        return !isBanned(playerName) && (publicAccess || trustedCanVisitPrivate && isTrusted(playerName));
    }

    public boolean canOccupy(UUID playerId, String playerName, boolean trustedCanVisitPrivate) {
        return ownerId.equals(playerId) || canVisit(playerName, trustedCanVisitPrivate);
    }

    public boolean canModify(UUID playerId, String playerName, boolean trustedCanModify) {
        return ownerId.equals(playerId) || !isBanned(playerName) && trustedCanModify && isTrusted(playerName);
    }

    private static Set<String> normalizeAll(Set<String> names) {
        if (names == null || names.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new HashSet<>();
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                result.add(normalize(name));
            }
        }
        return Set.copyOf(result);
    }

    private static String normalize(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            throw new IllegalArgumentException("Player name must not be blank.");
        }
        return playerName.toLowerCase(Locale.ROOT);
    }
}
