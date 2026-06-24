package com.aeldrin.teleportblock.map;

import com.aeldrin.teleportblock.ModMapDecorations;
import com.aeldrin.teleportblock.TeleportBlockMod;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.saveddata.maps.MapDecorationType;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles TeleportBlock map marker persistence and decoration refresh.
 *
 * Markers are stored in the map ItemStack's CUSTOM_DATA component
 * under the key "teleportblock_markers". Each tick (throttled),
 * markers are re-applied as map decorations so they survive save/reload.
 */
@EventBusSubscriber(modid = TeleportBlockMod.MODID)
public class TeleportMapHandler {

    private static final String NBT_KEY = "teleportblock_markers";

    // === Data record for a single marker ===
    public record TeleportMarker(int x, int z, int color, @Nullable String name) {

        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("x", x);
            tag.putInt("z", z);
            tag.putInt("color", color);
            if (name != null) tag.putString("name", name);
            return tag;
        }

        public static TeleportMarker fromTag(CompoundTag tag) {
            return new TeleportMarker(
                    tag.getInt("x"),
                    tag.getInt("z"),
                    tag.getInt("color"),
                    tag.contains("name") ? tag.getString("name") : null
            );
        }
    }

    // === Store markers on the map ItemStack ===

    /**
     * Saves a pair of teleport markers to the map ItemStack.
     * Called when the player right-clicks a TeleportBlock with a filled map.
     */
    public static void addMarkersToMap(ItemStack mapStack, int x1, int z1, int x2, int z2,
                                        int color, @Nullable String linkName) {
        // Read existing markers
        List<TeleportMarker> markers = readMarkers(mapStack);

        // Remove old markers at same positions (update scenario)
        markers.removeIf(m -> (m.x() == x1 && m.z() == z1) || (m.x() == x2 && m.z() == z2));

        // Add new pair
        markers.add(new TeleportMarker(x1, z1, color, linkName));
        markers.add(new TeleportMarker(x2, z2, color, linkName));

        // Write back
        writeMarkers(mapStack, markers);
    }

    public static List<TeleportMarker> readMarkers(ItemStack mapStack) {
        List<TeleportMarker> result = new ArrayList<>();
        CustomData customData = mapStack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        CompoundTag root = customData.copyTag();
        if (root.contains(NBT_KEY, Tag.TAG_LIST)) {
            ListTag list = root.getList(NBT_KEY, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                result.add(TeleportMarker.fromTag(list.getCompound(i)));
            }
        }
        return result;
    }

    private static void writeMarkers(ItemStack mapStack, List<TeleportMarker> markers) {
        CustomData customData = mapStack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        CompoundTag root = customData.copyTag();
        ListTag list = new ListTag();
        for (TeleportMarker m : markers) {
            list.add(m.toTag());
        }
        root.put(NBT_KEY, list);
        mapStack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
    }

    // === Tick handler: re-apply decorations from stored markers ===

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;
        if (!(player instanceof ServerPlayer)) return;

        // Throttle: every 40 ticks (5 seconds)
        if (player.tickCount % 100 != 0) return;

        // Check both hands + inventory for filled maps
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(Items.FILLED_MAP)) {
                refreshDecorations(player, stack);
            }
        }
    }

    private static void refreshDecorations(Player player, ItemStack mapStack) {
        List<TeleportMarker> markers = readMarkers(mapStack);
        if (markers.isEmpty()) return;

        MapItemSavedData mapData = MapItem.getSavedData(mapStack, player.level());
        if (mapData == null) return;

        Holder<MapDecorationType> holder = ModMapDecorations.TELEPORT_BLOCK;

        for (TeleportMarker marker : markers) {
            String colorHex = String.format("%06X", marker.color() & 0xFFFFFF);
            String key = "tp_" + colorHex + "_" + marker.x() + "_" + marker.z();

            // Build name component — ALWAYS carries color in style for the frame mixin
            // Component.empty() with color style won't render text but carries tint data
            Component name;
            if (marker.name() != null) {
                name = Component.literal(marker.name())
                        .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(marker.color())));
            } else {
                name = Component.empty()
                        .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(marker.color())));
            }

            mapData.addDecoration(holder, null, key,
                    (double) marker.x(), (double) marker.z(), 0.0, name);
        }
    }
}