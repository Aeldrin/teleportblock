package com.aeldrin.teleportblock;

import com.aeldrin.teleportblock.advancement.ModAdvancements;
import com.aeldrin.teleportblock.compat.ponder.TeleportBlockPonderPlugin;
import net.createmod.ponder.foundation.PonderIndex;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig.Type;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(TeleportBlockMod.MODID)
public class TeleportBlockMod {
    public static final String MODID = "teleportblock";

    public TeleportBlockMod(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(Type.COMMON, ModConfig.SPEC);
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModAdvancements.register(modEventBus);
        ModMapDecorations.MAP_DECORATION_TYPES.register(modEventBus);
        ModStats.CUSTOM_STATS.register(modEventBus);
            modEventBus.addListener((net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent event) -> {
                event.enqueueWork(ModStats::init);
            });

        modEventBus.addListener(ModEventHandlers::onCreativeTab);
        NeoForge.EVENT_BUS.addListener(GameEventHandlers::onExplosion);

        modEventBus.addListener((FMLClientSetupEvent event) -> {
            if (net.neoforged.fml.loading.FMLEnvironment.dist == Dist.CLIENT) {
                PonderIndex.addPlugin(new TeleportBlockPonderPlugin());
            }
        });
    }
}