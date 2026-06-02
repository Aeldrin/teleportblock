package com.example.teleportblock.block;

import com.example.teleportblock.ModConfig;
import com.example.teleportblock.block.entity.TeleportBlockEntity;
import com.example.teleportblock.compat.sable.SableCompat;
import com.example.teleportblock.compat.waystones.WaystoneCompat;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TeleportBlock extends BaseEntityBlock {
    private static final Map<UUID, BlockPos> PENDING_LINKS = new HashMap<>();
    private static final Map<UUID, Long> COOLDOWNS = new HashMap<>();

    // Полный куб для визуала и хитбокса
    private static final VoxelShape SHAPE = Shapes.block();

    public TeleportBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(TeleportBlock::new);
    }

    // Заборы и панели проверяют эту форму — возвращаем пустую
    @Override
    public VoxelShape getBlockSupportShape(BlockState state, BlockGetter level, BlockPos pos) {
        return Shapes.empty();
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextFloat() < 0.25f) {
            level.addParticle(ParticleTypes.PORTAL,
                    pos.getX() + random.nextDouble(),
                    pos.getY() + random.nextDouble(),
                    pos.getZ() + random.nextDouble(),
                    0, 0, 0);
        }
    }

    public static @Nullable BlockPos getPendingLink(UUID playerId) {
        return PENDING_LINKS.get(playerId);
    }

    public static void removePendingLink(UUID playerId) {
        PENDING_LINKS.remove(playerId);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level,
                                                BlockPos pos, Player player,
                                                BlockHitResult hitResult) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;

        TeleportBlockEntity be = (TeleportBlockEntity) level.getBlockEntity(pos);
        if (be == null) return InteractionResult.FAIL;

        if (player.isShiftKeyDown()) {
            UUID id = player.getUUID();

            if (PENDING_LINKS.containsKey(id)) {
                BlockPos firstPos = PENDING_LINKS.remove(id);
                if (firstPos.equals(pos)) {
                    player.sendSystemMessage(Component.translatable("teleportblock.message.cannot_self_link"));
                    return InteractionResult.FAIL;
                }

                int maxDist = ModConfig.MAX_LINK_DISTANCE.get();
                Vec3 a = Vec3.atCenterOf(firstPos);
                Vec3 b = Vec3.atCenterOf(pos);
                if (SableCompat.distanceSqr(level, a, b) > (double) maxDist * maxDist) {
                    player.sendSystemMessage(Component.translatable("teleportblock.message.too_far", maxDist));
                    return InteractionResult.FAIL;
                }

                TeleportBlockEntity firstBe = (TeleportBlockEntity) level.getBlockEntity(firstPos);
                if (firstBe != null) {
                    firstBe.setTarget(pos);
                    firstBe.setRealTarget(BlockPos.containing(SableCompat.toGlobalPos(level, Vec3.atCenterOf(pos))));
                    be.setTarget(firstPos);
                    be.setRealTarget(BlockPos.containing(SableCompat.toGlobalPos(level, Vec3.atCenterOf(firstPos))));
                    level.playSound(null, pos, SoundEvents.END_PORTAL_FRAME_FILL, SoundSource.BLOCKS, 1.0f, 1.0f);
                    player.sendSystemMessage(Component.translatable("teleportblock.message.linked"));
                } else {
                    player.sendSystemMessage(Component.translatable("teleportblock.message.first_not_found"));
                }
            } else {
                PENDING_LINKS.put(id, pos);
                player.sendSystemMessage(Component.translatable("teleportblock.message.first_selected"));
            }
        } else {
            if (player.isPassenger()) {
                player.sendSystemMessage(Component.translatable("teleportblock.message.dismount"));
                return InteractionResult.FAIL;
            }

            long cooldownMs = ModConfig.COOLDOWN_SECONDS.get() * 1000L;
            long now = System.currentTimeMillis();
            Long lastUse = COOLDOWNS.get(player.getUUID());
            if (cooldownMs > 0 && lastUse != null && now - lastUse < cooldownMs) {
                long remaining = (cooldownMs - (now - lastUse) + 999) / 1000;
                player.sendSystemMessage(Component.translatable("teleportblock.message.cooldown", remaining));
                return InteractionResult.FAIL;
            }

            UUID waystoneTarget = be.getWaystoneTarget();
            if (waystoneTarget != null
                    && level instanceof ServerLevel serverLevel
                    && ModList.get().isLoaded("waystones")) {
                BlockPos waystonePos = WaystoneCompat.getWaystonePos(serverLevel, waystoneTarget);
                if (waystonePos == null) {
                    player.sendSystemMessage(Component.translatable("teleportblock.message.waystone_lost"));
                    be.setWaystoneTarget(null);
                    return InteractionResult.FAIL;
                }

                COOLDOWNS.put(player.getUUID(), now);

                if (level instanceof ServerLevel sl) {
                    sl.sendParticles(ParticleTypes.PORTAL,
                            pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                            40, 0.3, 0.5, 0.3, 0.08);
                    sl.sendParticles(ParticleTypes.PORTAL,
                            waystonePos.getX() + 0.5, waystonePos.getY() + 1.0, waystonePos.getZ() + 0.5,
                            40, 0.3, 0.5, 0.3, 0.08);
                }

                level.playSound(null, pos, SoundEvents.ENDERMAN_TELEPORT, SoundSource.BLOCKS, 1.0f, 1.0f);
                level.playSound(null, waystonePos, SoundEvents.ENDERMAN_TELEPORT, SoundSource.BLOCKS, 1.0f, 1.0f);
                player.teleportTo(waystonePos.getX() + 0.5, waystonePos.getY() + 1.0, waystonePos.getZ() + 0.5);
                return InteractionResult.SUCCESS;
            }

            BlockPos target = be.getTarget();
            if (target != null) {
                BlockPos feet = target.above();
                BlockPos head = target.above(2);

                boolean feetFree = level.getBlockState(feet).isAir() ||
                        level.getBlockState(feet).getCollisionShape(level, feet).isEmpty();
                boolean headFree = level.getBlockState(head).isAir() ||
                        level.getBlockState(head).getCollisionShape(level, head).isEmpty();

                if (!feetFree || !headFree) {
                    player.sendSystemMessage(Component.translatable("teleportblock.message.blocked"));
                    return InteractionResult.FAIL;
                }

                COOLDOWNS.put(player.getUUID(), now);

                if (level instanceof ServerLevel serverLevel) {
                    serverLevel.sendParticles(ParticleTypes.PORTAL,
                            pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                            40, 0.3, 0.5, 0.3, 0.08);
                    serverLevel.sendParticles(ParticleTypes.PORTAL,
                            target.getX() + 0.5, target.getY() + 1.0, target.getZ() + 0.5,
                            40, 0.3, 0.5, 0.3, 0.08);
                }

                level.playSound(null, pos, SoundEvents.ENDERMAN_TELEPORT, SoundSource.BLOCKS, 1.0f, 1.0f);
                level.playSound(null, target, SoundEvents.ENDERMAN_TELEPORT, SoundSource.BLOCKS, 1.0f, 1.0f);

                Vec3 targetVec = new Vec3(target.getX() + 0.5, target.getY() + 1.0, target.getZ() + 0.5);
                Vec3 globalTarget = SableCompat.toGlobalPos(level, targetVec);
                player.teleportTo(globalTarget.x, globalTarget.y, globalTarget.z);
            } else {
                player.sendSystemMessage(Component.translatable("teleportblock.message.not_linked"));
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos,
                         BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide()) {
            PENDING_LINKS.values().removeIf(pending -> pending.equals(pos));

            TeleportBlockEntity be = (TeleportBlockEntity) level.getBlockEntity(pos);
            if (be != null && be.getTarget() != null) {
                BlockEntity paired = level.getBlockEntity(be.getTarget());
                if (paired instanceof TeleportBlockEntity pairedBe) {
                    pairedBe.setTarget(null);
                }
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TeleportBlockEntity(pos, state);
    }
}