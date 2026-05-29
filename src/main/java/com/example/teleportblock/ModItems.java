package com.example.teleportblock;

import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Unit;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(TeleportBlockMod.MODID);

    public static final DeferredItem<BlockItem> TELEPORT_BLOCK_ITEM =
            ITEMS.register("teleport_block", () ->
                    new BlockItem(ModBlocks.TELEPORT_BLOCK.get(),
                            new Item.Properties()
                                    .component(DataComponents.FIRE_RESISTANT, Unit.INSTANCE)));
}