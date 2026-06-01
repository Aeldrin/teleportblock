package com.example.teleportblock.compat.waystones;

import net.blay09.mods.waystones.api.WaystonesAPI;
import net.blay09.mods.waystones.api.Waystone;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import java.util.UUID;

public class WaystoneCompat {

    @Nullable
    public static UUID getWaystoneIdAt(ServerLevel level, BlockPos pos) {
        return WaystonesAPI.getWaystoneAt(level, pos)
                .map(Waystone::getWaystoneUid)
                .orElse(null);
    }

    @Nullable
    public static BlockPos getWaystonePos(ServerLevel level, UUID waystoneId) {
        return WaystonesAPI.getWaystone(level.getServer(), waystoneId)
                .filter(Waystone::isValid)
                .map(Waystone::getPos)
                .orElse(null);
    }

    @Nullable
    public static String getWaystoneName(ServerLevel level, UUID waystoneId) {
        return WaystonesAPI.getWaystone(level.getServer(), waystoneId)
                .filter(Waystone::isValid)
                .map(w -> w.getName().getString())
                .orElse(null);
    }
}