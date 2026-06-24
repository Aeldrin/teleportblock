package com.aeldrin.teleportblock;

import com.aeldrin.teleportblock.block.TeleportBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.projectile.WitherSkull;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

public class GameEventHandlers {

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getExplosion().getDirectSourceEntity() instanceof WitherSkull)) return;

        BlockPos center = BlockPos.containing(event.getExplosion().center());
        int radius = 2;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-radius, -radius, -radius),
                center.offset(radius, radius, radius))) {
            if (event.getLevel().getBlockState(pos).getBlock() instanceof TeleportBlock) {
                event.getAffectedBlocks().add(pos.immutable());
            }
        }
    }
}