package com.aeldrin.teleportblock.compat.sable;

import dev.ryanhcode.sable.companion.SableCompanion;
import net.minecraft.core.Position;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public class SableCompat {

    public static Vec3 toGlobalPos(Level level, Vec3 pos) {
        return SableCompanion.INSTANCE.projectOutOfSubLevel(level, (Position) pos);
    }

    public static double distanceSqr(Level level, Vec3 a, Vec3 b) {
        return SableCompanion.INSTANCE.distanceSquaredWithSubLevels(level, a, b);
    }
}