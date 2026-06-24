package com.aeldrin.teleportblock.block.entity;

import com.aeldrin.teleportblock.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class TeleportBlockEntity extends BlockEntity {
    @Nullable
    private BlockPos target;
    @Nullable
    private BlockPos realTarget;
    @Nullable
    private UUID waystoneTarget;
    private int teleportCount = 0;

    @Nullable
    private String linkName;
    private int linkColor = -1;

    public TeleportBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.TELEPORT_BLOCK_ENTITY.get(), pos, state);
    }

    public @Nullable BlockPos getTarget() { return target; }

    public void setTarget(@Nullable BlockPos target) {
        this.target = target;
        this.waystoneTarget = null;
        setChanged();
        syncToClient();
    }

    public @Nullable BlockPos getRealTarget() { return realTarget; }

    public void setRealTarget(@Nullable BlockPos realTarget) {
        this.realTarget = realTarget;
        setChanged();
    }

    public @Nullable UUID getWaystoneTarget() { return waystoneTarget; }

    public void setWaystoneTarget(@Nullable UUID uuid) {
        this.waystoneTarget = uuid;
        this.target = null;
        setChanged();
        syncToClient();
    }

    public int getTeleportCount() { return teleportCount; }

    public void incrementTeleportCount() {
        teleportCount++;
        setChanged();
    }

    public @Nullable String getLinkName() { return linkName; }

    public void setLinkName(@Nullable String linkName) {
        this.linkName = linkName;
        setChanged();
        syncToClient();
    }

    public int getLinkColor() { return linkColor; }

    public void setLinkColor(int newColor) {
        releaseColor(this.linkColor);
        this.linkColor = newColor;
        registerColor(newColor);
        setChanged();
        syncToClient();
    }

    public boolean hasLinkColor() { return linkColor != -1; }

    // === Unique color tracking ===
    private static final java.util.Set<Integer> USED_COLORS = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    public static int generateRandomLinkColor() {
        java.util.concurrent.ThreadLocalRandom rng = java.util.concurrent.ThreadLocalRandom.current();
        int color;
        int attempts = 0;
        do {
            float hue = rng.nextFloat();
            float saturation = 0.6f + rng.nextFloat() * 0.4f;
            float brightness = 0.7f + rng.nextFloat() * 0.3f;
            color = java.awt.Color.HSBtoRGB(hue, saturation, brightness) & 0xFFFFFF;
            attempts++;
        } while (USED_COLORS.contains(color) && attempts < 1000);
        USED_COLORS.add(color);
        return color;
    }

    public static void releaseColor(int color) {
        if (color != -1) USED_COLORS.remove(color);
    }

    public static void registerColor(int color) {
        if (color != -1) USED_COLORS.add(color);
    }

    public void setLinkNameWithSync(@Nullable String name) {
        this.setLinkName(name);
        if (target != null && level != null && !level.isClientSide()) {
            BlockEntity paired = level.getBlockEntity(target);
            if (paired instanceof TeleportBlockEntity pairedBe) {
                pairedBe.setLinkName(name);
            }
        }
    }

    // --- Client sync ---

    private void syncToClient() {
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // --- Serialization ---

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (target != null) {
            tag.putInt("target_x", target.getX());
            tag.putInt("target_y", target.getY());
            tag.putInt("target_z", target.getZ());
        }
        if (realTarget != null) {
            tag.putInt("real_target_x", realTarget.getX());
            tag.putInt("real_target_y", realTarget.getY());
            tag.putInt("real_target_z", realTarget.getZ());
        }
        if (waystoneTarget != null) {
            tag.putUUID("waystone_target", waystoneTarget);
        }
        tag.putInt("teleport_count", teleportCount);
        if (linkName != null) {
            tag.putString("link_name", linkName);
        }
        if (linkColor != -1) {
            tag.putInt("link_color", linkColor);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("target_x")) {
            target = new BlockPos(tag.getInt("target_x"), tag.getInt("target_y"), tag.getInt("target_z"));
        } else {
            target = null;
        }
        if (tag.contains("real_target_x")) {
            realTarget = new BlockPos(tag.getInt("real_target_x"), tag.getInt("real_target_y"), tag.getInt("real_target_z"));
        } else {
            realTarget = null;
        }
        if (tag.contains("waystone_target")) {
            waystoneTarget = tag.getUUID("waystone_target");
        } else {
            waystoneTarget = null;
        }
        teleportCount = tag.getInt("teleport_count");
        linkName = tag.contains("link_name") ? tag.getString("link_name") : null;
        linkColor = tag.contains("link_color") ? tag.getInt("link_color") : -1;
        registerColor(linkColor);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        super.handleUpdateTag(tag, registries);
        if (level != null && level.isClientSide()) {
            updateMinimapWaypoints();
        }
    }

    @Override
    public void setRemoved() {
        if (level != null && level.isClientSide()) {
            removeMinimapWaypoints();
        }
        super.setRemoved();
    }

    private void updateMinimapWaypoints() {
        if (net.neoforged.fml.ModList.get().isLoaded("journeymap")) {
            com.aeldrin.teleportblock.compat.journeymap.JourneyMapCompat
                    .updateWaypoint(worldPosition, target, linkColor, linkName);
        }
    }

    private void removeMinimapWaypoints() {
        if (net.neoforged.fml.ModList.get().isLoaded("journeymap")) {
            com.aeldrin.teleportblock.compat.journeymap.JourneyMapCompat
                    .removeWaypoint(worldPosition);
        }
    }
}