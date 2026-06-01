package com.example.teleportblock.compat.jei;

import com.example.teleportblock.ModItems;
import com.example.teleportblock.TeleportBlockMod;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

@JeiPlugin
public class TeleportBlockJeiPlugin implements IModPlugin {

    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(TeleportBlockMod.MODID, "jei_plugin");
    }

    @Override
    public void registerRecipes(IRecipeRegistration reg) {
        // Добавляем описание предмета в JEI
        reg.addIngredientInfo(
                new ItemStack(ModItems.TELEPORT_BLOCK_ITEM.get()),
                VanillaTypes.ITEM_STACK,
                Component.translatable("teleportblock.jei.description")
        );
    }
}