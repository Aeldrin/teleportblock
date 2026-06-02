package com.example.teleportblock;

import com.example.teleportblock.block.TeleportBlock;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(TeleportBlockMod.MODID);

    public static final DeferredBlock<TeleportBlock> TELEPORT_BLOCK =
            BLOCKS.register("teleport_block", () -> new TeleportBlock(
                    BlockBehaviour.Properties.of()
                            .strength(3.0f)
                            .explosionResistance(1200f)
                            .lightLevel(state -> 15)
                            .sound(SoundType.AMETHYST)
                            .requiresCorrectToolForDrops()
                            .noOcclusion()
            ));
}