package com.aeldrin.teleportblock;

import com.aeldrin.teleportblock.block.TeleportBlock;
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
                            // Свой набор звуков (2.2): установка/разрушение - зарядка/разрядка якоря
                            // возрождения, шаги/удар/падение - магнетит. См. ModSounds.
                            .sound(ModSounds.TELEPORT_BLOCK_SOUNDS)
                            .requiresCorrectToolForDrops()
                            .noOcclusion()
                            .pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK)
            ));
}
