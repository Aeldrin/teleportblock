package com.aeldrin.teleportblock.compat.journeymap;

import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.common.waypoint.Waypoint;
import journeymap.api.v2.common.waypoint.WaypointFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

public class JourneyMapCompat {

    private static IClientAPI api;
    private static final Map<BlockPos, Waypoint> ACTIVE_WAYPOINTS = new HashMap<>();
    private static final String MOD_ID = "teleportblock";

    public static void setApi(IClientAPI clientApi) {
        api = clientApi;
    }

    public static void updateWaypoint(BlockPos pos, @Nullable BlockPos target,
                                       int color, @Nullable String name) {
        if (api == null) return;
        removeWaypoint(pos);
        if (target == null) return;

        try {
            String displayName = name != null ? name : "Portal " + pos.getX() + ", " + pos.getZ();

            Waypoint wp = WaypointFactory.createWaypoint(
                    MOD_ID,
                    pos,
                    displayName,
                    Level.OVERWORLD,
                    false  // non-persistent, we manage lifecycle ourselves
            );
            wp.setColor(color);

            api.addWaypoint(MOD_ID, wp);
            ACTIVE_WAYPOINTS.put(pos, wp);
        } catch (Exception e) {
            // API not ready or version mismatch
        }
    }

    public static void removeWaypoint(BlockPos pos) {
        if (api == null) return;
        Waypoint old = ACTIVE_WAYPOINTS.remove(pos);
        if (old != null) {
            try {
                api.removeWaypoint(MOD_ID, old);
            } catch (Exception ignored) {}
        }
    }
}