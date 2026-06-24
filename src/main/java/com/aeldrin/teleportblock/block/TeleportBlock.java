package com.aeldrin.teleportblock.block;

import com.aeldrin.teleportblock.ModConfig;
import com.aeldrin.teleportblock.ModStats;
import com.aeldrin.teleportblock.advancement.TeleportTrigger;
import com.aeldrin.teleportblock.block.entity.TeleportBlockEntity;
import com.aeldrin.teleportblock.compat.ftbchunks.FTBChunksCompat;
import com.aeldrin.teleportblock.compat.sable.SableCompat;
import com.aeldrin.teleportblock.compat.waystones.WaystoneCompat;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
import com.aeldrin.teleportblock.advancement.LinkTrigger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TeleportBlock extends BaseEntityBlock {
    private static final Map<UUID, BlockPos> PENDING_LINKS = new HashMap<>();
    private static final Map<UUID, Long> COOLDOWNS = new HashMap<>();
    private static final VoxelShape SHAPE = Shapes.block();

    public TeleportBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(TeleportBlock::new);
    }

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

    // === Comparator output: 8 if linked, 0 if not ===

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof TeleportBlockEntity tbe) {
            if (tbe.getTarget() != null || tbe.getWaystoneTarget() != null) {
                return 8;
            }
        }
        return 0;
    }

    // === Ender Pearl teleport ===

    @Override
    protected void onProjectileHit(Level level, BlockState state, BlockHitResult hit, Projectile projectile) {
        if (level.isClientSide()) return;
        if (!(projectile instanceof ThrownEnderpearl pearl)) return;

        net.minecraft.world.entity.Entity owner = pearl.getOwner();
        if (!(owner instanceof ServerPlayer player)) return;

        BlockPos pos = hit.getBlockPos();
        TeleportBlockEntity be = (TeleportBlockEntity) level.getBlockEntity(pos);
        if (be == null) return;

        BlockPos target = be.getTarget();
        if (target == null) return;

        // Проверка свободного места
        BlockPos feet = target.above();
        BlockPos head = target.above(2);
        boolean feetFree = level.getBlockState(feet).isAir() ||
                level.getBlockState(feet).getCollisionShape(level, feet).isEmpty();
        boolean headFree = level.getBlockState(head).isAir() ||
                level.getBlockState(head).getCollisionShape(level, head).isEmpty();
        if (!feetFree || !headFree) return;

        // FTB Chunks проверка
        if (level instanceof ServerLevel serverLevel
                && ModList.get().isLoaded("ftbchunks")
                && !FTBChunksCompat.canTeleportTo(player, serverLevel, target)) {
            return;
        }

        // Убиваем жемчуг до стандартного телепорта
        pearl.discard();

        // Телепорт
        Vec3 targetVec = new Vec3(target.getX() + 0.5, target.getY() + 1.0, target.getZ() + 0.5);
        Vec3 globalTarget = SableCompat.toGlobalPos(level, targetVec);
        player.teleportTo(globalTarget.x, globalTarget.y, globalTarget.z);

        // Эффекты
        if (level instanceof ServerLevel sl) {
            sl.sendParticles(ParticleTypes.PORTAL,
                    pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                    40, 0.3, 0.5, 0.3, 0.08);
            sl.sendParticles(ParticleTypes.PORTAL,
                    target.getX() + 0.5, target.getY() + 1.0, target.getZ() + 0.5,
                    40, 0.3, 0.5, 0.3, 0.08);
        }

        level.playSound(null, pos, SoundEvents.ENDERMAN_TELEPORT, SoundSource.BLOCKS, 1.0f, 1.0f);
        level.playSound(null, target, SoundEvents.ENDERMAN_TELEPORT, SoundSource.BLOCKS, 1.0f, 1.0f);

        // Счётчик, стат, адвансмент
        be.incrementTeleportCount();
        player.awardStat(ModStats.TELEPORTATIONS);
        TeleportTrigger.INSTANCE.trigger(player, be.getTeleportCount());
    }

    // === Item interactions: Name Tag, Filled Map ===

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                               BlockPos pos, Player player, InteractionHand hand,
                                               BlockHitResult hitResult) {
        // Name Tag: apply name to this block + paired block
        if (stack.is(Items.NAME_TAG) && stack.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME) && !player.isShiftKeyDown()) {
            if (level.isClientSide()) return ItemInteractionResult.SUCCESS;

            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof TeleportBlockEntity be) {
                String name = stack.getHoverName().getString();
                be.setLinkNameWithSync(name);

                if (!player.getAbilities().instabuild) {
                    stack.shrink(1);
                }

                level.playSound(null, pos, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 0.5f, 1.0f);
                player.displayClientMessage(Component.translatable("teleportblock.message.named", name), true);
            }
            return ItemInteractionResult.SUCCESS;
        }

        // Filled Map: add both portals as map decorations (persistent)
        if (stack.is(Items.FILLED_MAP) && !player.isShiftKeyDown()) {
            if (level.isClientSide()) return ItemInteractionResult.SUCCESS;

            TeleportBlockEntity be = (TeleportBlockEntity) level.getBlockEntity(pos);
            if (be == null) return ItemInteractionResult.FAIL;

            BlockPos target = be.getTarget();
            if (target == null) {
                player.displayClientMessage(Component.translatable("teleportblock.message.not_linked"), true);
                return ItemInteractionResult.FAIL;
            }

            int color = be.hasLinkColor() ? be.getLinkColor() : 0xFFFFFF;

            com.aeldrin.teleportblock.map.TeleportMapHandler.addMarkersToMap(
                    stack,
                    pos.getX(), pos.getZ(),
                    target.getX(), target.getZ(),
                    color,
                    be.getLinkName()
            );

            level.playSound(null, pos, SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, SoundSource.BLOCKS, 1.0f, 1.0f);
            return ItemInteractionResult.SUCCESS;
        }

        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    // === Main interaction: linking and teleporting ===

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
                    player.displayClientMessage(Component.translatable("teleportblock.message.cannot_self_link"), true);
                    return InteractionResult.FAIL;
                }

                int maxDist = ModConfig.MAX_LINK_DISTANCE.get();
                Vec3 a = Vec3.atCenterOf(firstPos);
                Vec3 b = Vec3.atCenterOf(pos);
                if (SableCompat.distanceSqr(level, a, b) > (double) maxDist * maxDist) {
                    player.displayClientMessage(Component.translatable("teleportblock.message.too_far", maxDist), true);
                    return InteractionResult.FAIL;
                }

                TeleportBlockEntity firstBe = (TeleportBlockEntity) level.getBlockEntity(firstPos);
                if (firstBe != null) {
                    firstBe.setTarget(pos);
                    firstBe.setRealTarget(BlockPos.containing(SableCompat.toGlobalPos(level, Vec3.atCenterOf(pos))));
                    be.setTarget(firstPos);
                    be.setRealTarget(BlockPos.containing(SableCompat.toGlobalPos(level, Vec3.atCenterOf(firstPos))));

                    int color = TeleportBlockEntity.generateRandomLinkColor();
                    firstBe.setLinkColor(color);
                    be.setLinkColor(color);

                    String existingName = firstBe.getLinkName() != null ? firstBe.getLinkName() : be.getLinkName();
                    if (existingName != null) {
                        firstBe.setLinkName(existingName);
                        be.setLinkName(existingName);
                    }

                    level.playSound(null, pos, SoundEvents.END_PORTAL_FRAME_FILL, SoundSource.BLOCKS, 1.0f, 1.0f);
                    player.displayClientMessage(Component.translatable("teleportblock.message.linked"), true);
                    if (player instanceof ServerPlayer serverPlayer) {
                        LinkTrigger.INSTANCE.trigger(serverPlayer);
                    }
                } else {
                    player.displayClientMessage(Component.translatable("teleportblock.message.first_not_found"), true);
                }
            } else {
                PENDING_LINKS.put(id, pos);
                player.displayClientMessage(Component.translatable("teleportblock.message.first_selected"), true);
            }
        } else {
            if (player.isPassenger()) {
                player.displayClientMessage(Component.translatable("teleportblock.message.dismount"), true);
                return InteractionResult.FAIL;
            }

            long cooldownMs = ModConfig.COOLDOWN_SECONDS.get() * 1000L;
            long now = System.currentTimeMillis();
            Long lastUse = COOLDOWNS.get(player.getUUID());
            if (cooldownMs > 0 && lastUse != null && now - lastUse < cooldownMs) {
                long remaining = (cooldownMs - (now - lastUse) + 999) / 1000;
                player.displayClientMessage(Component.translatable("teleportblock.message.cooldown", remaining), true);
                return InteractionResult.FAIL;
            }

            // Телепорт на Waystone
            UUID waystoneTarget = be.getWaystoneTarget();
            if (waystoneTarget != null
                    && level instanceof ServerLevel serverLevel
                    && ModList.get().isLoaded("waystones")) {
                BlockPos waystonePos = WaystoneCompat.getWaystonePos(serverLevel, waystoneTarget);
                if (waystonePos == null) {
                    player.displayClientMessage(Component.translatable("teleportblock.message.waystone_lost"), true);
                    be.setWaystoneTarget(null);
                    return InteractionResult.FAIL;
                }

                if (level instanceof ServerLevel sl
                        && ModList.get().isLoaded("ftbchunks")
                        && !FTBChunksCompat.canTeleportTo((ServerPlayer) player, sl, waystonePos)) {
                    player.displayClientMessage(Component.translatable("teleportblock.message.chunk_protected"), true);
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

                if (player instanceof ServerPlayer serverPlayer) {
                    be.incrementTeleportCount();
                    player.awardStat(ModStats.TELEPORTATIONS);
                    TeleportTrigger.INSTANCE.trigger(serverPlayer, be.getTeleportCount());
                }

                return InteractionResult.SUCCESS;
            }

            // Обычный телепорт
            BlockPos target = be.getTarget();
            if (target != null) {
                BlockPos feet = target.above();
                BlockPos head = target.above(2);

                boolean feetFree = level.getBlockState(feet).isAir() ||
                        level.getBlockState(feet).getCollisionShape(level, feet).isEmpty();
                boolean headFree = level.getBlockState(head).isAir() ||
                        level.getBlockState(head).getCollisionShape(level, head).isEmpty();

                if (!feetFree || !headFree) {
                    player.displayClientMessage(Component.translatable("teleportblock.message.blocked"), true);
                    return InteractionResult.FAIL;
                }

                if (level instanceof ServerLevel serverLevel
                        && ModList.get().isLoaded("ftbchunks")
                        && !FTBChunksCompat.canTeleportTo((ServerPlayer) player, serverLevel, target)) {
                    player.displayClientMessage(Component.translatable("teleportblock.message.chunk_protected"), true);
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

                if (player instanceof ServerPlayer serverPlayer) {
                    be.incrementTeleportCount();
                    player.awardStat(ModStats.TELEPORTATIONS);
                    TeleportTrigger.INSTANCE.trigger(serverPlayer, be.getTeleportCount());
                }
            } else {
                player.displayClientMessage(Component.translatable("teleportblock.message.not_linked"), true);
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
            if (be != null) {
                TeleportBlockEntity.releaseColor(be.getLinkColor());

                if (be.getTarget() != null) {
                    BlockEntity paired = level.getBlockEntity(be.getTarget());
                    if (paired instanceof TeleportBlockEntity pairedBe) {
                        pairedBe.setTarget(null);
                        pairedBe.setLinkColor(-1);
                    }
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