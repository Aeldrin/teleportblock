package com.aeldrin.teleportblock.item;

import com.aeldrin.teleportblock.block.TeleportBlock;
import com.aeldrin.teleportblock.block.entity.TeleportBlockEntity;
import com.aeldrin.teleportblock.compat.waystones.WaystoneCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.ModList;

import java.util.UUID;

public class TeleportBlockItem extends BlockItem {

    public TeleportBlockItem(Block block, Item.Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!context.getPlayer().isShiftKeyDown()) return super.useOn(context);
        if (!ModList.get().isLoaded("waystones")) return super.useOn(context);
        if (!(level instanceof ServerLevel serverLevel)) return super.useOn(context);

        BlockPos clickedPos = context.getClickedPos();
        UUID waystoneId = WaystoneCompat.getWaystoneIdAt(serverLevel, clickedPos);
        if (waystoneId == null) return super.useOn(context);

        UUID playerId = context.getPlayer().getUUID();
        BlockPos pendingPos = TeleportBlock.getPendingLink(playerId);

        if (pendingPos == null) {
            context.getPlayer().sendSystemMessage(
                Component.translatable("teleportblock.message.no_pending_block"));
            return InteractionResult.SUCCESS;
        }

        TeleportBlock.removePendingLink(playerId);

        TeleportBlockEntity be = (TeleportBlockEntity) level.getBlockEntity(pendingPos);
        if (be == null) {
            context.getPlayer().sendSystemMessage(
                Component.translatable("teleportblock.message.first_not_found"));
            return InteractionResult.FAIL;
        }

        BlockPos waystonePos = WaystoneCompat.getWaystonePos(serverLevel, waystoneId);
        if (waystonePos == null) {
            context.getPlayer().sendSystemMessage(
                Component.translatable("teleportblock.message.waystone_not_found"));
            return InteractionResult.FAIL;
        }

        be.setWaystoneTarget(waystoneId);
        String waystoneName = WaystoneCompat.getWaystoneName(serverLevel, waystoneId);
        level.playSound(null, pendingPos, SoundEvents.END_PORTAL_FRAME_FILL, SoundSource.BLOCKS, 1.0f, 1.0f);
        context.getPlayer().sendSystemMessage(
            Component.translatable("teleportblock.message.linked_to_waystone",
                waystoneName != null ? waystoneName : "Waystone"));

        return InteractionResult.SUCCESS;
    }
}