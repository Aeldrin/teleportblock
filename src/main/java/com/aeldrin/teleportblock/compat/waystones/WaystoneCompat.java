package com.aeldrin.teleportblock.compat.waystones;

import net.blay09.mods.waystones.api.WaystonesAPI;
import net.blay09.mods.waystones.api.Waystone;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import java.util.UUID;

public class WaystoneCompat {

    @Nullable
    public static UUID getWaystoneIdAt(ServerLevel level, BlockPos pos) {
        return WaystonesAPI.getWaystoneAt(level, pos)
                .map(Waystone::getWaystoneUid)
                .orElse(null);
    }

    // Позиция камня. ВАЖНО: Waystones ищет камень по всему серверу, а не только в мире level -
    // позиция относится к измерению камня (см. getWaystoneDimension), а не к level.
    @Nullable
    public static BlockPos getWaystonePos(ServerLevel level, UUID waystoneId) {
        return WaystonesAPI.getWaystone(level.getServer(), waystoneId)
                .filter(Waystone::isValid)
                .map(Waystone::getPos)
                .orElse(null);
    }

    // Измерение камня (2.2). Нужно, чтобы телепортировать в мир камня, а не в текущий мир:
    // до 2.2 связь с камнем была возможна только в одном мире, и это не имело значения.
    @Nullable
    public static ResourceKey<Level> getWaystoneDimension(ServerLevel level, UUID waystoneId) {
        return WaystonesAPI.getWaystone(level.getServer(), waystoneId)
                .filter(Waystone::isValid)
                .map(Waystone::getDimension)
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