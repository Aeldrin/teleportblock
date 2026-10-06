package com.aeldrin.teleportblock.compat.rei;

import com.aeldrin.teleportblock.ModItems;
import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.api.common.util.EntryStacks;
import me.shedaniel.rei.forge.REIPluginClient;
import me.shedaniel.rei.plugin.common.displays.DefaultInformationDisplay;
import net.minecraft.network.chat.Component;

// На NeoForge REI находит плагины только по аннотации (на Fabric - по entrypoint), без неё плагин не загружается.
@REIPluginClient
public class TeleportBlockReiPlugin implements REIClientPlugin {

    @Override
    public void registerDisplays(DisplayRegistry registry) {
        // Info-описание аналогичное JEI/EMI
        DefaultInformationDisplay info = DefaultInformationDisplay.createFromEntry(
                EntryStacks.of(ModItems.TELEPORT_BLOCK_ITEM.get()),
                Component.translatable("block.teleportblock.teleport_block")
        );
        info.line(Component.translatable("teleportblock.jei.description"));
        registry.add(info);
    }
}
