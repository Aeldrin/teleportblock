package com.aeldrin.teleportblock;

import com.aeldrin.teleportblock.block.TeleportBlock;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

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
}
