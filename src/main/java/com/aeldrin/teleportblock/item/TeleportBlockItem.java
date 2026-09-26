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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
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

        Player player = context.getPlayer();
        if (player == null || !player.isShiftKeyDown()) return super.useOn(context);

        BlockPos clickedPos = context.getClickedPos();
        BlockState clickedState = level.getBlockState(clickedPos);

        // Shift+ПКМ телепортблоком В РУКЕ по уже стоящему TeleportBlock - переключение
        // активного/пассивного режима (см. TeleportBlock.togglePassiveMode). Специально
        // отдельный жест от обычной линковки пустой рукой (TeleportBlock.useWithoutItem) -
        // раньше оба действия делили одну и ту же Shift+ПКМ ветку и конфликтовали друг
        // с другом через общий PENDING_LINKS, ломая уже существующие связи.
        if (clickedState.getBlock() instanceof TeleportBlock teleportBlock) {
            BlockEntity blockEntity = level.getBlockEntity(clickedPos);
            if (blockEntity instanceof TeleportBlockEntity tbe) {
                TeleportBlock.togglePassiveMode(level, clickedPos, player, tbe);
            }
            return InteractionResult.SUCCESS;
        }

        if (!ModList.get().isLoaded("waystones")) return super.useOn(context);
        if (!(level instanceof ServerLevel serverLevel)) return super.useOn(context);

        UUID waystoneId = WaystoneCompat.getWaystoneIdAt(serverLevel, clickedPos);
        if (waystoneId == null) return super.useOn(context);

        UUID playerId = player.getUUID();
        BlockPos pendingPos = TeleportBlock.getPendingLink(playerId);

        if (pendingPos == null) {
            player.sendSystemMessage(
                Component.translatable("teleportblock.message.no_pending_block"));
            return InteractionResult.SUCCESS;
        }

        TeleportBlock.removePendingLink(playerId);

        TeleportBlockEntity be = (TeleportBlockEntity) level.getBlockEntity(pendingPos);
        if (be == null) {
            player.sendSystemMessage(
                Component.translatable("teleportblock.message.first_not_found"));
            return InteractionResult.FAIL;
        }

        BlockPos waystonePos = WaystoneCompat.getWaystonePos(serverLevel, waystoneId);
        if (waystonePos == null) {
            player.sendSystemMessage(
                Component.translatable("teleportblock.message.waystone_not_found"));
            return InteractionResult.FAIL;
        }

        be.setWaystoneTarget(waystoneId);
        String waystoneName = WaystoneCompat.getWaystoneName(serverLevel, waystoneId);
        level.playSound(null, pendingPos, SoundEvents.END_PORTAL_FRAME_FILL, SoundSource.BLOCKS, 1.0f, 1.0f);
        player.sendSystemMessage(
            Component.translatable("teleportblock.message.linked_to_waystone",
                waystoneName != null ? waystoneName : "Waystone"));

        return InteractionResult.SUCCESS;
    }
}