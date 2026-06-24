package com.aeldrin.teleportblock.client;

import com.aeldrin.teleportblock.TeleportBlockMod;
import com.aeldrin.teleportblock.block.entity.TeleportBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;

@EventBusSubscriber(modid = TeleportBlockMod.MODID, value = Dist.CLIENT)
public class DebugOverlayHandler {

    @SubscribeEvent
    public static void onDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        HitResult hit = mc.hitResult;
        if (!(hit instanceof BlockHitResult blockHit)) return;

        BlockPos pos = blockHit.getBlockPos();
        if (!(mc.level.getBlockEntity(pos) instanceof TeleportBlockEntity be)) return;

        event.getRight().add("");
        event.getRight().add("[TeleportBlock]");

        if (be.getTarget() != null) {
            BlockPos t = be.getTarget();
            event.getRight().add("Target: " + t.getX() + ", " + t.getY() + ", " + t.getZ());
        } else {
            event.getRight().add("Target: none");
        }

        if (be.getLinkName() != null) {
            event.getRight().add("Name: " + be.getLinkName());
        }

        if (be.hasLinkColor()) {
            event.getRight().add("Color: #" + String.format("%06X", be.getLinkColor()));
        }

        event.getRight().add("Uses: " + be.getTeleportCount());
    }
}