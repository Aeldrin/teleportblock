package com.aeldrin.teleportblock.compat.emi;

import com.aeldrin.teleportblock.ModItems;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.recipe.EmiInfoRecipe;
import dev.emi.emi.api.stack.EmiStack;
import net.minecraft.network.chat.Component;

import java.util.List;

@EmiEntrypoint
public class TeleportBlockEmiPlugin implements EmiPlugin {

    @Override
    public void register(EmiRegistry registry) {
        // Info-описание аналогичное JEI — рецепт подхватывается автоматически из json
        registry.addRecipe(new EmiInfoRecipe(
                List.of(EmiStack.of(ModItems.TELEPORT_BLOCK_ITEM.get())),
                List.of(Component.translatable("teleportblock.jei.description")),
                null
        ));
    }
}
