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

    // Владелец пары (2.1) - игрок, который её связал. Используется опцией owner_only в конфиге.
    // Записывается всегда, даже если опция выключена, чтобы включение опции сразу работало для
    // уже связанных пар. null = владельца нет (пара связана до 2.1 или связь разорвана) -
    // такую пару может менять кто угодно. Логике нужен только на сервере (на клиент попадает лишь
    // вместе с остальными данными блока через getUpdateTag - это безвредно).
    @Nullable
    private UUID owner;

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

    // Стоит ли партнёр на Sable sub-level - по данным, сохранённым при линковке:
    // realTarget = глобальная проекция позиции партнёра на момент линковки. Для обычного
    // наземного блока она совпадает с target, для блока на корабле - отличается (плот-координаты
    // против мировых). Не требует вызовов API Sable и работает без установленного Sable
    // (тогда проекция - no-op и realTarget всегда равен target).
    public boolean isPartnerOnSubLevel() {
        return target != null && realTarget != null && !realTarget.equals(target);
    }

    // Можно ли обращаться к блоку партнёра (getBlockState/getBlockEntity) из разовых действий
    // игрока (переключение режима, имя, отвязка), не рискуя загрузить плот-чанк выгруженного
    // корабля (issue #1). Наземного партнёра считаем доступным всегда: загрузить его чанк ради
    // разового действия допустимо. Для фоновой логики (редстоун-реле) этого НЕ достаточно -
    // там проверяется level.isLoaded(target) для любого партнёра.
    public boolean canAccessPartner() {
        if (target == null || level == null) return false;
        return !isPartnerOnSubLevel() || level.isLoaded(target);
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

    public @Nullable UUID getOwner() { return owner; }

    public void setOwner(@Nullable UUID owner) {
        this.owner = owner;
        setChanged();
    }

    // === Unique color tracking (best-effort, per-session) ===
    // Не переживает перезагрузку сервера и не видит блоки в незагруженных чанках -
    // это осознанное ограничение, а не баг: на 16M цветов коллизия практически
    // невозможна, а полная персистенция потребовала бы world-saved-data и трекинга
    // загрузки/выгрузки чанков, что не стоит сложности.
    private static final java.util.Set<Integer> USED_COLORS = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    // Собственная реализация HSB->RGB, не зависит от java.awt (которого может
    // не быть на headless dedicated серверах с урезанной JVM).
    private static int hsbToRgb(float hue, float saturation, float brightness) {
        float h = (hue - (float) Math.floor(hue)) * 6.0f;
        float f = h - (float) Math.floor(h);
        float p = brightness * (1.0f - saturation);
        float q = brightness * (1.0f - saturation * f);
        float t = brightness * (1.0f - saturation * (1.0f - f));
        int r, g, b;
        switch ((int) h) {
            case 0 -> { r = Math.round(brightness * 255); g = Math.round(t * 255); b = Math.round(p * 255); }
            case 1 -> { r = Math.round(q * 255); g = Math.round(brightness * 255); b = Math.round(p * 255); }
            case 2 -> { r = Math.round(p * 255); g = Math.round(brightness * 255); b = Math.round(t * 255); }
            case 3 -> { r = Math.round(p * 255); g = Math.round(q * 255); b = Math.round(brightness * 255); }
            case 4 -> { r = Math.round(t * 255); g = Math.round(p * 255); b = Math.round(brightness * 255); }
            default -> { r = Math.round(brightness * 255); g = Math.round(p * 255); b = Math.round(q * 255); }
        }
        return ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    public static int generateRandomLinkColor() {
        java.util.concurrent.ThreadLocalRandom rng = java.util.concurrent.ThreadLocalRandom.current();
        int color;
        int attempts = 0;
        do {
            float hue = rng.nextFloat();
            float saturation = 0.6f + rng.nextFloat() * 0.4f;
            float brightness = 0.7f + rng.nextFloat() * 0.3f;
            color = hsbToRgb(hue, saturation, brightness);
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
        // canAccessPartner - см. выше: не грузим плот-чанк выгруженного корабля
        if (target != null && level != null && !level.isClientSide() && canAccessPartner()) {
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
        if (owner != null) {
            tag.putUUID("owner", owner);
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
        owner = tag.hasUUID("owner") ? tag.getUUID("owner") : null;
        registerColor(linkColor);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        super.handleUpdateTag(tag, registries);
        if (level != null && level.isClientSide()) {
            updateMinimapWaypoints();
        }
    }

    // handleUpdateTag вызывается только при загрузке чанка (вход в мир).
    // Для инкрементальных обновлений (sendBlockUpdated при линковке/отвязке)
    // нужен onDataPacket — иначе вейпоинты появятся только после перезахода.
    @Override
    public void onDataPacket(net.minecraft.network.Connection net,
                              net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket pkt,
                              HolderLookup.Provider registries) {
        super.onDataPacket(net, pkt, registries);
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
                    .updateWaypoint(worldPosition, target, linkColor, linkName, level.dimension());
        }
    }

    private void removeMinimapWaypoints() {
        if (net.neoforged.fml.ModList.get().isLoaded("journeymap")) {
            com.aeldrin.teleportblock.compat.journeymap.JourneyMapCompat
                    .removeWaypoint(worldPosition);
        }
    }
}
