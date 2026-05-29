package com.example.teleportblock;

import com.example.teleportblock.block.entity.TeleportBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, TeleportBlockMod.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TeleportBlockEntity>> TELEPORT_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("teleport_block_entity", () ->
                    BlockEntityType.Builder.of(TeleportBlockEntity::new, ModBlocks.TELEPORT_BLOCK.get())
                            .build(null));
}