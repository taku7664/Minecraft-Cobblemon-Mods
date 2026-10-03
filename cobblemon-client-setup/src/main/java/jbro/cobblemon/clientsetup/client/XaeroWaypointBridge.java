package jbro.cobblemon.clientsetup.client;

import java.lang.reflect.InvocationTargetException;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** Calls Xaero's installed runtime so the waypoint belongs to its current world and set. */
final class XaeroWaypointBridge {
    private XaeroWaypointBridge() {}

    static String create(Minecraft client, String requestedName) throws ReflectiveOperationException {
        String name = normalizeName(requestedName);
        if (client.player == null || client.level == null) throw new IllegalStateException("Not in a world");
        Class<?> modulesType = Class.forName("xaero.hud.minimap.BuiltInHudModules");
        Object module = modulesType.getField("MINIMAP").get(null);
        Object session = module.getClass().getMethod("getCurrentSession").invoke(module);
        if (session == null) throw new IllegalStateException("Xaero minimap session is not ready");
        Object manager = session.getClass().getMethod("getWorldManager").invoke(session);
        Object world = manager.getClass().getMethod("getCurrentWorld").invoke(manager);
        if (world == null) throw new IllegalStateException("Xaero waypoint world is not ready");
        Object set = world.getClass().getMethod("getCurrentWaypointSet").invoke(world);
        if (set == null) throw new IllegalStateException("Xaero waypoint set is not ready");

        Class<?> worldType = Class.forName("xaero.hud.minimap.world.MinimapWorld");
        Object dimensionHelper = session.getClass().getMethod("getDimensionHelper").invoke(session);
        double worldScale = (double) dimensionHelper.getClass().getMethod("getDimCoordinateScale", worldType).invoke(dimensionHelper, world);
        if (worldScale <= 0) throw new IllegalStateException("Invalid Xaero dimension scale");
        double ratio = client.level.dimensionType().coordinateScale() / worldScale;
        BlockPos pos = client.player.blockPosition();
        int x = (int) Math.floor(pos.getX() * ratio);
        int z = (int) Math.floor(pos.getZ() * ratio);
        String symbol = name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase(java.util.Locale.ROOT);

        Class<?> waypointType = Class.forName("xaero.common.minimap.waypoints.Waypoint");
        Object waypoint = waypointType.getConstructor(int.class, int.class, int.class, String.class, String.class, int.class)
            .newInstance(x, pos.getY(), z, name, symbol, 12);
        Class<?> setType = Class.forName("xaero.hud.minimap.waypoint.set.WaypointSet");
        setType.getMethod("add", waypointType).invoke(set, waypoint);

        Object io = session.getClass().getMethod("getWorldManagerIO").invoke(session);
        try {
            io.getClass().getMethod("saveWorld", worldType).invoke(io, world);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("Xaero waypoint was added but could not be saved", exception.getCause());
        }
        return name;
    }

    /** Xaero's waypoint teleport gate is stored per server/world root, not in the profile cfg. */
    static void disableTeleportForCurrentWorld() throws ReflectiveOperationException {
        Object world = currentWorld();
        if (world == null) return;
        Object container = world.getClass().getMethod("getContainer").invoke(world);
        Object root = container.getClass().getMethod("getRoot").invoke(container);
        Object config = root.getClass().getMethod("getConfig").invoke(root);
        if (!(boolean) config.getClass().getMethod("isTeleportationEnabled").invoke(config)) return;
        config.getClass().getMethod("setTeleportationEnabled", boolean.class).invoke(config, false);
        Object session = root.getClass().getMethod("getSession").invoke(root);
        Object io = session.getClass().getMethod("getWorldManagerIO").invoke(session);
        Object rootIo = io.getClass().getMethod("getRootConfigIO").invoke(io);
        Class<?> rootType = Class.forName("xaero.hud.minimap.world.container.MinimapWorldRootContainer");
        rootIo.getClass().getMethod("save", rootType).invoke(rootIo, root);
    }

    private static Object currentWorld() throws ReflectiveOperationException {
        Object module = Class.forName("xaero.hud.minimap.BuiltInHudModules").getField("MINIMAP").get(null);
        Object session = module.getClass().getMethod("getCurrentSession").invoke(module);
        if (session == null) return null;
        Object manager = session.getClass().getMethod("getWorldManager").invoke(session);
        return manager.getClass().getMethod("getCurrentWorld").invoke(manager);
    }

    static String normalizeName(String requested) {
        String name = requested == null || requested.isBlank() ? "Waypoint" : requested.strip();
        if (name.length() > 64 || name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0 || name.indexOf(':') >= 0)
            throw new IllegalArgumentException("Invalid waypoint name");
        return name;
    }
}
