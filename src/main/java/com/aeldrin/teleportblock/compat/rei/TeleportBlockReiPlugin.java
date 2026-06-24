package com.aeldrin.teleportblock.compat.rei;

import com.aeldrin.teleportblock.ModItems;
import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.client.registry.entry.EntryRegistry;
import me.shedaniel.rei.api.common.util.EntryStacks;
import net.minecraft.network.chat.Component;

public class TeleportBlockReiPlugin implements REIClientPlugin {

    @Override
    public void registerEntries(EntryRegistry registry) {
        // рецепт подхватывается автоматически
    }
}