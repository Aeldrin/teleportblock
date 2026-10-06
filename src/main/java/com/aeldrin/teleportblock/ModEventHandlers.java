package com.aeldrin.teleportblock;

import com.aeldrin.teleportblock.block.TeleportBlock;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

public class ModEventHandlers {

    @SubscribeEvent
    public static void onCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ModItems.TELEPORT_BLOCK_ITEM.get());
        }
    }

    // Чистим PENDING_LINKS/COOLDOWNS при выходе игрока, чтобы карты не росли
    // бесконечно на серверах с большим оборотом игроков (см. TeleportBlock.clearPlayerData)
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        TeleportBlock.clearPlayerData(event.getEntity().getUUID());
    }

    // Снимает отметку "игрок стоит на блоке прибытия", когда он с него сошёл
    // (см. TeleportBlock.ARRIVALS / tickArrival - защита от пинг-понга в пассивном режиме).
    // Только сервер; для игроков без отметки - один lookup в HashMap.
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            TeleportBlock.tickArrival(serverPlayer);
            // 2.2: белая подсветка первого блока, пока игрок выбирает пару для линковки
            TeleportBlock.tickPendingHighlight(serverPlayer);
        }
    }
}
