package com.example.teleportblock.compat.jade;

import com.example.teleportblock.block.entity.TeleportBlockEntity;
import com.example.teleportblock.compat.sable.SableCompat;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

public class TeleportBlockJadeProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {

    public static final TeleportBlockJadeProvider INSTANCE = new TeleportBlockJadeProvider();
    public static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath("teleportblock", "teleport_block");

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        CompoundTag data = accessor.getServerData();
        if (data.getBoolean("linked")) {
            tooltip.add(Component.translatable("teleportblock.jade.linked")
                    .withStyle(ChatFormatting.GREEN));
            if (data.getBoolean("waystone")) {
                tooltip.add(Component.translatable("teleportblock.jade.linked_waystone")
                        .withStyle(ChatFormatting.AQUA));
            } else {
                tooltip.add(Component.literal(
                        data.getInt("tx") + ", " + data.getInt("ty") + ", " + data.getInt("tz"))
                        .withStyle(ChatFormatting.GRAY));
            }
        } else {
            tooltip.add(Component.translatable("teleportblock.jade.not_linked")
                    .withStyle(ChatFormatting.RED));
        }
    }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        if (accessor.getBlockEntity() instanceof TeleportBlockEntity be) {
            boolean linkedToBlock = be.getTarget() != null;
            boolean linkedToWaystone = be.getWaystoneTarget() != null;
            boolean linked = linkedToBlock || linkedToWaystone;
            data.putBoolean("linked", linked);
            data.putBoolean("waystone", linkedToWaystone);

            if (linkedToBlock) {
                Vec3 real = SableCompat.toGlobalPos(accessor.getLevel(), Vec3.atCenterOf(be.getTarget()));
                data.putInt("tx", (int) real.x);
                data.putInt("ty", (int) real.y);
                data.putInt("tz", (int) real.z);
            }
        }
    }

    @Override
    public ResourceLocation getUid() {
        return UID;
    }
}