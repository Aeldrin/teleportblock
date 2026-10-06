package com.aeldrin.teleportblock.compat.jade;

import com.aeldrin.teleportblock.block.entity.TeleportBlockEntity;
import com.aeldrin.teleportblock.compat.sable.SableCompat;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
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

        // Show link name with link color if available
        if (data.contains("link_name")) {
            String name = data.getString("link_name");
            MutableComponent nameComponent = Component.literal("\"" + name + "\"");
            if (data.contains("link_color")) {
                int color = data.getInt("link_color");
                nameComponent = nameComponent.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(color)));
            } else {
                nameComponent = nameComponent.withStyle(ChatFormatting.YELLOW);
            }
            tooltip.add(nameComponent);
        }

        if (data.getBoolean("linked")) {
            // Colored "● Linked" indicator
            MutableComponent statusIcon;
            if (data.contains("link_color")) {
                int color = data.getInt("link_color");
                statusIcon = Component.literal("● ")
                        .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(color)));
            } else {
                statusIcon = Component.literal("● ")
                        .withStyle(ChatFormatting.GREEN);
            }

            tooltip.add(statusIcon.append(
                    Component.translatable("teleportblock.jade.linked")
                            .withStyle(ChatFormatting.GREEN)));

            if (data.getBoolean("waystone")) {
                tooltip.add(Component.translatable("teleportblock.jade.linked_waystone")
                        .withStyle(ChatFormatting.AQUA));
            } else {
                MutableComponent coords = Component.literal(
                        data.getInt("tx") + ", " + data.getInt("ty") + ", " + data.getInt("tz"));
                // Партнёр в другом измерении (2.2) - дописываем название измерения.
                // Ключ teleportblock.dimension.<namespace>.<path> переведён для ванильных миров;
                // для модовых измерений показывается их id (например aether:the_aether).
                if (data.contains("tdim")) {
                    String dim = data.getString("tdim");
                    ResourceLocation dimId = ResourceLocation.tryParse(dim);
                    Component dimName = dimId != null
                            ? Component.translatableWithFallback(
                                    "teleportblock.dimension." + dimId.getNamespace() + "." + dimId.getPath(), dim)
                            : Component.literal(dim);
                    coords = coords.append(" (").append(dimName).append(")");
                }
                tooltip.add(coords.withStyle(ChatFormatting.GRAY));
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
                // 2.2: проекция в мире партнёра (он может быть в другом измерении)
                Level partnerLevel = be.getPartnerLevel() != null ? be.getPartnerLevel() : accessor.getLevel();
                Vec3 real = SableCompat.toGlobalPos(partnerLevel, Vec3.atCenterOf(be.getTarget()));
                if (be.isCrossDimension()) {
                    data.putString("tdim", be.getTargetDimension().location().toString());
                }
                data.putInt("tx", (int) real.x);
                data.putInt("ty", (int) real.y);
                data.putInt("tz", (int) real.z);
            }

            // NEW: Send link name and color to client
            if (be.getLinkName() != null) {
                data.putString("link_name", be.getLinkName());
            }
            if (be.hasLinkColor()) {
                data.putInt("link_color", be.getLinkColor());
            }
        }
    }

    @Override
    public ResourceLocation getUid() {
        return UID;
    }
}