package com.example.teleportblock.block.entity;

import com.example.teleportblock.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class TeleportBlockEntity extends BlockEntity {
    @Nullable
    private BlockPos target;

    public TeleportBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.TELEPORT_BLOCK_ENTITY.get(), pos, state);
    }

    public @Nullable BlockPos getTarget() { return target; }

    public void setTarget(BlockPos target) {
        this.target = target;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (target != null) {
            tag.putInt("target_x", target.getX());
            tag.putInt("target_y", target.getY());
            tag.putInt("target_z", target.getZ());
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("target_x")) {
            target = new BlockPos(
                tag.getInt("target_x"),
                tag.getInt("target_y"),
                tag.getInt("target_z")
            );
        }
    }
}