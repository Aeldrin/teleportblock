package com.aeldrin.teleportblock;

import com.aeldrin.teleportblock.item.TeleportBlockItem;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Unit;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(TeleportBlockMod.MODID);

    public static final DeferredItem<TeleportBlockItem> TELEPORT_BLOCK_ITEM =
            ITEMS.register("teleport_block", () ->
                    new TeleportBlockItem(ModBlocks.TELEPORT_BLOCK.get(),
                            new Item.Properties()
                                    .component(DataComponents.FIRE_RESISTANT, Unit.INSTANCE)
                                    .rarity(Rarity.EPIC)));
}