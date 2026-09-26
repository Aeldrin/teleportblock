package com.aeldrin.teleportblock.block;

import com.aeldrin.teleportblock.ModConfig;
import com.aeldrin.teleportblock.ModStats;
import com.aeldrin.teleportblock.advancement.LinkTrigger;
import com.aeldrin.teleportblock.advancement.TeleportTrigger;
import com.aeldrin.teleportblock.block.entity.TeleportBlockEntity;
import com.aeldrin.teleportblock.compat.ftbchunks.FTBChunksCompat;
import com.aeldrin.teleportblock.compat.sable.SableCompat;
import com.aeldrin.teleportblock.compat.waystones.WaystoneCompat;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
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

    // Кулдаун привязан к конкретному ЗВЕНУ (паре эндпоинтов), а не глобально к игроку -
    // иначе использование одного линка блокировало бы игроку все остальные телепорт-блоки в мире.
    // Значения хранятся в серверных тиках (level.getGameTime), а не в System.currentTimeMillis -
    // это привязывает кулдаун к серверному времени, а не к реальному (при лагах TPS < 20
    // кулдаун не истечёт раньше положенного количества тиков).
    private static final Map<UUID, Map<LinkKey, Long>> COOLDOWNS = new HashMap<>();

    private static final VoxelShape SHAPE = Shapes.block();

    // Идентификатор звена - неупорядоченная пара (эта позиция, конечная точка).
    // Конечная точка бывает либо BlockPos другого TeleportBlock, либо Waystone UUID -
    // оба варианта сводятся к long через identifierFor(...), чтобы ключ был единого типа.
    private record LinkKey(long a, long b) {
        static LinkKey of(long x, long y) {
            return x <= y ? new LinkKey(x, y) : new LinkKey(y, x);
        }
    }

    private static long identifierFor(UUID uuid) {
        return uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
    }

    private static boolean isOnCooldown(UUID playerId, LinkKey key, long cooldownTicks, long now) {
        if (cooldownTicks <= 0) return false;
        Map<LinkKey, Long> perLink = COOLDOWNS.get(playerId);
        if (perLink == null) return false;
        Long last = perLink.get(key);
        return last != null && now - last < cooldownTicks;
    }

    // Безопасен для вызова и без предварительной проверки isOnCooldown -
    // при отсутствии записи возвращает 0.
    private static long remainingCooldownSeconds(UUID playerId, LinkKey key, long cooldownTicks, long now) {
        Map<LinkKey, Long> perLink = COOLDOWNS.get(playerId);
        if (perLink == null) return 0;
        Long last = perLink.get(key);
        if (last == null) return 0;
        long remainingTicks = cooldownTicks - (now - last);
        if (remainingTicks <= 0) return 0;
        // Округление вверх: 1-20 тиков = 1 секунда, 21-40 = 2, и т.д.
        return (remainingTicks + 19) / 20;
    }

    // Технический минимум кулдауна для пассивного режима (1 тик). stepOn вызывается
    // КАЖДЫЙ тик, пока игрок физически стоит на блоке - если в конфиге cooldown_seconds
    // выставлен в 0, без этого ограничителя это будет тысячи вызовов teleportTo в секунду
    // (лишняя нагрузка, спам партиклов/звука, и в принципе непредсказуемое поведение).
    private static final long MIN_PASSIVE_COOLDOWN_TICKS = 1;

    private static void setCooldown(UUID playerId, LinkKey key, long now) {
        COOLDOWNS.computeIfAbsent(playerId, id -> new HashMap<>()).put(key, now);
    }

    // Выходной уровень редстайн-сигнала (0-15), приходит от связанного блока (см. neighborChanged)
    public static final IntegerProperty POWER = IntegerProperty.create("power", 0, 15);

    // Пассивный режим: блок телепортирует игрока при простом заходе внутрь, без клика.
    // Свойство общее для пары блоков (синхронизируется на обоих концах, см. togglePassiveMode).
    public static final BooleanProperty PASSIVE = BooleanProperty.create("passive");

    public TeleportBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(this.stateDefinition.any().setValue(POWER, 0).setValue(PASSIVE, false));
    }

    @Override
    public MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(TeleportBlock::new);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWER, PASSIVE);
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
        if (!state.getValue(PASSIVE)) {
            if (random.nextFloat() < 0.25f) {
                level.addParticle(ParticleTypes.PORTAL,
                        pos.getX() + random.nextDouble(),
                        pos.getY() + random.nextDouble(),
                        pos.getZ() + random.nextDouble(),
                        0, 0, 0);
            }
            return;
        }

        // Пассивный режим: частицы "всасываются" внутрь блока. ParticleTypes.PORTAL на клиенте
        // интерпретирует переданную скорость не как обычную velocity, а как смещение до цели,
        // к которой частица плавно летит всю свою жизнь (тот же приём, что и у портала в Незер) -
        // поэтому достаточно спавнить частицы по кругу вокруг блока со скоростью "к центру".
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.5;
        double cz = pos.getZ() + 0.5;

        for (int i = 0; i < 3; i++) {
            if (random.nextFloat() > 0.5f) continue;

            double radius = 0.9 + random.nextDouble() * 0.6;
            double angle = random.nextDouble() * Math.PI * 2;
            double px = cx + Math.cos(angle) * radius;
            double pz = cz + Math.sin(angle) * radius;
            double py = pos.getY() + random.nextDouble() * 1.2 - 0.1;

            double speed = 0.06;
            level.addParticle(ParticleTypes.PORTAL, px, py, pz,
                    (cx - px) * speed, (cy - py) * speed, (cz - pz) * speed);
        }
    }

    public static @Nullable BlockPos getPendingLink(UUID playerId) {
        return PENDING_LINKS.get(playerId);
    }

    public static void removePendingLink(UUID playerId) {
        PENDING_LINKS.remove(playerId);
    }

    // Вызывается при выходе игрока с сервера (см. ModEventHandlers), чтобы записи
    // в PENDING_LINKS/COOLDOWNS не копились в памяти вечно на долгоживущем сервере.
    public static void clearPlayerData(UUID playerId) {
        PENDING_LINKS.remove(playerId);
        COOLDOWNS.remove(playerId);
    }

    // ===========================================================================================
    // Единый метод телепорта — используется кликом, пассивным stepOn и ender pearl.
    // Возвращает true если телепорт произошёл, false если заблокирован (нет места, FTB Chunks).
    // showMessages=false для пассивного режима (иначе спам каждый тик) и ender pearl.
    // ===========================================================================================
    private boolean doTeleport(Level level, BlockPos sourcePos, BlockPos targetPos,
                               TeleportBlockEntity be, ServerPlayer player,
                               boolean showMessages) {
        BlockPos feet = targetPos.above();
        BlockPos head = targetPos.above(2);

        boolean feetFree = level.getBlockState(feet).isAir() ||
                level.getBlockState(feet).getCollisionShape(level, feet).isEmpty();
        boolean headFree = level.getBlockState(head).isAir() ||
                level.getBlockState(head).getCollisionShape(level, head).isEmpty();

        if (!feetFree || !headFree) {
            if (showMessages) {
                player.displayClientMessage(Component.translatable("teleportblock.message.blocked"), true);
            }
            return false;
        }

        if (level instanceof ServerLevel serverLevel
                && ModList.get().isLoaded("ftbchunks")
                && !FTBChunksCompat.canTeleportTo(player, serverLevel, targetPos)) {
            if (showMessages) {
                player.displayClientMessage(Component.translatable("teleportblock.message.chunk_protected"), true);
            }
            return false;
        }

        // Партиклы и звук на обоих концах
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.PORTAL,
                    sourcePos.getX() + 0.5, sourcePos.getY() + 1.0, sourcePos.getZ() + 0.5,
                    40, 0.3, 0.5, 0.3, 0.08);
            serverLevel.sendParticles(ParticleTypes.PORTAL,
                    targetPos.getX() + 0.5, targetPos.getY() + 1.0, targetPos.getZ() + 0.5,
                    40, 0.3, 0.5, 0.3, 0.08);
        }

        level.playSound(null, sourcePos, SoundEvents.ENDERMAN_TELEPORT, SoundSource.BLOCKS, 1.0f, 1.0f);
        level.playSound(null, targetPos, SoundEvents.ENDERMAN_TELEPORT, SoundSource.BLOCKS, 1.0f, 1.0f);

        // Sable companion (JiJ'd): без Sable возвращает те же координаты (safe no-op),
        // с Sable — конвертирует sub-level координаты в глобальные.
        Vec3 targetVec = new Vec3(targetPos.getX() + 0.5, targetPos.getY() + 1.0, targetPos.getZ() + 0.5);
        Vec3 globalTarget = SableCompat.toGlobalPos(level, targetVec);
        player.teleportTo(globalTarget.x, globalTarget.y, globalTarget.z);

        be.incrementTeleportCount();
        player.awardStat(ModStats.TELEPORTATIONS);
        TeleportTrigger.INSTANCE.trigger(player, be.getTeleportCount());

        return true;
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

    // === Direct redstone signal relay (attenuated x0.5, rounded up) ===
    // Отдельный канал от аналогового сигнала выше: сюда идёт "прямая" мощность
    // для обычных проводов/лампочек, а не только для компаратора.

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return state.getValue(POWER);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos,
                                    Block neighborBlock, BlockPos fromPos, boolean isMoving) {
        super.neighborChanged(state, level, pos, neighborBlock, fromPos, isMoving);
        if (level.isClientSide()) return;

        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof TeleportBlockEntity be)) return;

        BlockPos target = be.getTarget();
        if (target == null) return;

        BlockState targetState = level.getBlockState(target);
        if (!(targetState.getBlock() instanceof TeleportBlock)) return;

        // Не учитываем собственный POWER этого блока как входной сигнал,
        // иначе он "подхватит" то, что сам же излучает.
        int rawSignal = level.getBestNeighborSignal(pos);
        int attenuated = Mth.ceil(rawSignal * 0.5f);

        // Значение не изменилось - выходим, чтобы не плодить лишние апдейты/циклы
        if (targetState.getValue(POWER) == attenuated) return;

        level.setBlock(target, targetState.setValue(POWER, attenuated), Block.UPDATE_ALL);
        level.updateNeighborsAt(target, this);
    }

    // === Passive portal: teleport on touch (standing on top), no click required ===
    // Блок всегда остаётся твёрдым - никакой отмены коллизии. Триггер - именно "касание"
    // (игрок стоит сверху), как у нажимной плиты. Правило мода: переносится только голый
    // игрок, не пассажир. Если над целевым блоком нет места - просто ничего не происходит,
    // никакого поиска альтернативной точки (умышленно, во избежание "бага кровати", когда
    // отсутствие свободного места телепортирует игрока в случайное соседнее место и создаёт ловушку).
    // Две доп. защиты от зависаний/ловушек: (1) зажатый Shift полностью отключает срабатывание;
    // (2) кулдаун всегда не меньше 1 тика (MIN_PASSIVE_COOLDOWN_TICKS), даже если в конфиге стоит 0 -
    // stepOn вызывается каждый тик, пока игрок стоит на блоке, и без этого лимита это будет
    // тысячи вызовов teleportTo в секунду.

    @Override
    public void stepOn(Level level, BlockPos pos, BlockState state, Entity entity) {
        super.stepOn(level, pos, state, entity);
        if (level.isClientSide()) return;
        if (!state.getValue(PASSIVE)) return;
        if (!(entity instanceof ServerPlayer player)) return;
        if (player.isPassenger()) return;

        // Защита от ловушек: зажатый Shift отключает срабатывание. Игрок может спокойно
        // встать/пройти рядом или специально задержаться на портале, не улетая никуда.
        if (player.isShiftKeyDown()) return;

        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof TeleportBlockEntity be)) return;

        BlockPos target = be.getTarget();
        if (target == null) return;

        // Клэмп минимума - см. MIN_PASSIVE_COOLDOWN_TICKS. Именно здесь, а не в isOnCooldown,
        // чтобы клик-телепорт по-прежнему мог честно работать с cooldown_seconds=0 из конфига.
        long cooldownTicks = Math.max(ModConfig.COOLDOWN_SECONDS.get() * 20L, MIN_PASSIVE_COOLDOWN_TICKS);
        long now = level.getGameTime();
        LinkKey key = LinkKey.of(pos.asLong(), target.asLong());

        // В отличие от клика, тут молча игнорируем кулдаун - иначе сообщение будет
        // спамиться каждый тик, пока игрок стоит на блоке.
        if (isOnCooldown(player.getUUID(), key, cooldownTicks, now)) return;

        if (doTeleport(level, pos, target, be, player, false)) {
            setCooldown(player.getUUID(), key, now);
        }
    }

    // Переключение активный/пассивный режим для пары блоков. Свойство синхронизируется
    // сразу на обоих концах линка - это состояние всего линка, а не отдельного блока.
    // Вызывается из TeleportBlockItem.useOn (Shift+ПКМ телепортблоком в руке по уже
    // стоящему блоку) - отдельный жест от линковки пустой рукой, чтобы они не конфликтовали.
    public static void togglePassiveMode(Level level, BlockPos pos, Player player, TeleportBlockEntity be) {
        // Если у игрока была застрявшая PENDING_LINKS-запись (например, забытая после клика
        // пустой рукой) - сбрасываем её здесь. Иначе следующий Shift+ПКМ пустой рукой по
        // любому блоку доиграет ту старую линковку и отвяжет партнёра, хотя игрок ожидал
        // просто переключить режим.
        PENDING_LINKS.remove(player.getUUID());

        BlockPos target = be.getTarget();
        if (target == null) {
            player.displayClientMessage(Component.translatable("teleportblock.message.not_linked"), true);
            return;
        }

        BlockState state = level.getBlockState(pos);
        boolean newPassive = !state.getValue(PASSIVE);

        level.setBlock(pos, state.setValue(PASSIVE, newPassive), Block.UPDATE_ALL);

        BlockState targetState = level.getBlockState(target);
        if (targetState.getBlock() instanceof TeleportBlock && targetState.hasProperty(PASSIVE)) {
            level.setBlock(target, targetState.setValue(PASSIVE, newPassive), Block.UPDATE_ALL);
        }

        level.playSound(null, pos, SoundEvents.BEACON_POWER_SELECT, SoundSource.BLOCKS, 1.0f, newPassive ? 1.4f : 0.8f);
        player.displayClientMessage(Component.translatable(newPassive
                ? "teleportblock.message.passive_enabled"
                : "teleportblock.message.passive_disabled"), true);
    }

    // === Ender Pearl teleport ===
    // Жемчуг попадает в связанный блок — игрок телепортируется к парному блоку (или waystone).
    // Имеет свой отдельный кулдаун (pearl_cooldown_seconds), т.к. жемчуг сам по себе
    // расходный ресурс и дополнительно карается 2.5 сердцами ванильного урона.

    @Override
    protected void onProjectileHit(Level level, BlockState state, BlockHitResult hit, Projectile projectile) {
        if (level.isClientSide()) return;
        if (!(projectile instanceof ThrownEnderpearl pearl)) return;

        net.minecraft.world.entity.Entity owner = pearl.getOwner();
        if (!(owner instanceof ServerPlayer player)) return;

        BlockPos pos = hit.getBlockPos();
        TeleportBlockEntity be = (TeleportBlockEntity) level.getBlockEntity(pos);
        if (be == null) return;

        // Кулдаун жемчуга
        long pearlCooldownTicks = ModConfig.PEARL_COOLDOWN_SECONDS.get() * 20L;
        long now = level.getGameTime();

        // Поддержка и block-линков, и waystone-линков
        BlockPos blockTarget = be.getTarget();
        UUID waystoneTarget = be.getWaystoneTarget();

        if (blockTarget == null && waystoneTarget == null) return;

        // Вычисляем ключ звена для кулдауна
        LinkKey key = waystoneTarget != null
                ? LinkKey.of(pos.asLong(), identifierFor(waystoneTarget))
                : LinkKey.of(pos.asLong(), blockTarget.asLong());

        if (isOnCooldown(player.getUUID(), key, pearlCooldownTicks, now)) return;

        // Waystone-телепорт через жемчуг
        if (waystoneTarget != null
                && level instanceof ServerLevel serverLevel
                && ModList.get().isLoaded("waystones")) {
            BlockPos waystonePos = WaystoneCompat.getWaystonePos(serverLevel, waystoneTarget);
            if (waystonePos == null) return;

            // Убиваем жемчуг до стандартного телепорта (иначе ваниль сама телепортнёт игрока к блоку)
            pearl.discard();

            if (doTeleport(level, pos, waystonePos, be, player, false)) {
                setCooldown(player.getUUID(), key, now);
            }
            return;
        }

        // Block-телепорт через жемчуг
        if (blockTarget != null) {
            pearl.discard();
            if (doTeleport(level, pos, blockTarget, be, player, false)) {
                setCooldown(player.getUUID(), key, now);
            }
        }
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
                    // Если любой из двух блоков уже был к кому-то привязан - у старого партнёра
                    // останется "мёртвая" ссылка, если явно его не отвязать. Это и есть баг
                    // "цепь расвязывается" / блок висит координатами и занимает слот линка.
                    unlinkOldPartner(level, firstPos, firstBe);
                    unlinkOldPartner(level, pos, be);

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

            long cooldownTicks = ModConfig.COOLDOWN_SECONDS.get() * 20L;
            long now = level.getGameTime();

            UUID waystoneTarget = be.getWaystoneTarget();
            BlockPos blockTarget = be.getTarget();

            if (waystoneTarget == null && blockTarget == null) {
                player.displayClientMessage(Component.translatable("teleportblock.message.not_linked"), true);
                return InteractionResult.SUCCESS;
            }

            // Ключ звена вычисляется здесь же, чтобы кулдаун проверялся именно
            // для конкретной пары эндпоинтов, а не глобально по всем блокам игрока.
            LinkKey key = waystoneTarget != null
                    ? LinkKey.of(pos.asLong(), identifierFor(waystoneTarget))
                    : LinkKey.of(pos.asLong(), blockTarget.asLong());

            if (isOnCooldown(player.getUUID(), key, cooldownTicks, now)) {
                long remaining = remainingCooldownSeconds(player.getUUID(), key, cooldownTicks, now);
                player.displayClientMessage(Component.translatable("teleportblock.message.cooldown", remaining), true);
                return InteractionResult.FAIL;
            }

            // Телепорт на Waystone
            if (waystoneTarget != null
                    && level instanceof ServerLevel serverLevel
                    && ModList.get().isLoaded("waystones")) {
                BlockPos waystonePos = WaystoneCompat.getWaystonePos(serverLevel, waystoneTarget);
                if (waystonePos == null) {
                    player.displayClientMessage(Component.translatable("teleportblock.message.waystone_lost"), true);
                    be.setWaystoneTarget(null);
                    return InteractionResult.FAIL;
                }

                if (player instanceof ServerPlayer serverPlayer) {
                    if (doTeleport(level, pos, waystonePos, be, serverPlayer, true)) {
                        setCooldown(player.getUUID(), key, now);
                    }
                }
                return InteractionResult.SUCCESS;
            }

            // Обычный телепорт
            if (blockTarget != null && player instanceof ServerPlayer serverPlayer) {
                if (doTeleport(level, pos, blockTarget, be, serverPlayer, true)) {
                    setCooldown(player.getUUID(), key, now);
                }
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos,
                         BlockState newState, boolean movedByPiston) {
        // КРИТИЧЕСКИ ВАЖНО: onRemove вызывается при ЛЮБОМ изменении BlockState, включая
        // простую смену свойства (PASSIVE/POWER через level.setBlock), а не только при
        // настоящем разрушении блока. Без этой проверки любой toggle пассивного режима
        // или обновление редстайн-сигнала (neighborChanged) СРАЗУ отвязывали партнёра,
        // хотя блок физически никуда не девался.
        if (!level.isClientSide() && !state.is(newState.getBlock())) {
            PENDING_LINKS.values().removeIf(pending -> pending.equals(pos));

            TeleportBlockEntity be = (TeleportBlockEntity) level.getBlockEntity(pos);
            if (be != null) {
                TeleportBlockEntity.releaseColor(be.getLinkColor());

                if (be.getTarget() != null) {
                    BlockPos pairedPos = be.getTarget();
                    BlockEntity paired = level.getBlockEntity(pairedPos);
                    if (paired instanceof TeleportBlockEntity pairedBe) {
                        pairedBe.setTarget(null);
                        pairedBe.setLinkColor(-1);
                        resetLinkState(level, pairedPos);
                    }
                }
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    // Сбрасывает POWER/PASSIVE на блоке, чей линк только что разорвали - иначе сигнал/режим
    // "залипнут" на последнем значении даже после того, как пара разорвана.
    private void resetLinkState(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof TeleportBlock)) return;

        boolean needsPowerReset = state.hasProperty(POWER) && state.getValue(POWER) != 0;
        boolean needsPassiveReset = state.hasProperty(PASSIVE) && state.getValue(PASSIVE);
        if (needsPowerReset || needsPassiveReset) {
            BlockState resetState = state;
            if (needsPowerReset) resetState = resetState.setValue(POWER, 0);
            if (needsPassiveReset) resetState = resetState.setValue(PASSIVE, false);
            level.setBlock(pos, resetState, Block.UPDATE_ALL);
            level.updateNeighborsAt(pos, this);
        }
    }

    // Если блок pos уже был к кому-то привязан - у ЕГО старого партнёра останется мёртвая
    // ссылка (partner.getTarget() == pos), если явно не разорвать связь с обеих сторон перед
    // тем как pos получит нового партнёра. Без этого партнёр "висит" координатами навечно
    // и не может быть перелинкован по новой (баг "цепь расвязывается").
    private void unlinkOldPartner(Level level, BlockPos pos, TeleportBlockEntity be) {
        BlockPos oldTarget = be.getTarget();
        if (oldTarget == null) return;

        BlockEntity oldPartner = level.getBlockEntity(oldTarget);
        if (oldPartner instanceof TeleportBlockEntity oldPartnerBe
                && pos.equals(oldPartnerBe.getTarget())) {
            oldPartnerBe.setTarget(null);
            oldPartnerBe.setLinkColor(-1);
            resetLinkState(level, oldTarget);
        }
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
