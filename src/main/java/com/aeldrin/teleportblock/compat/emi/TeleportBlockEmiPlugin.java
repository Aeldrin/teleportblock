package com.aeldrin.teleportblock.compat.emi;

import com.aeldrin.teleportblock.ModItems;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.stack.EmiStack;

@EmiEntrypoint
public class TeleportBlockEmiPlugin implements EmiPlugin {

    @Override
    public void register(EmiRegistry registry) {
        // рецепт подхватывается автоматически из json
    }
}