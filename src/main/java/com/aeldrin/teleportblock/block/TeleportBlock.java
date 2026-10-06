package com.aeldrin.teleportblock.block;

import com.aeldrin.teleportblock.TeleportMessages;
import com.aeldrin.teleportblock.ModBlockEntities;
import com.aeldrin.teleportblock.ModConfig;
import com.aeldrin.teleportblock.ModSounds;
import com.aeldrin.teleportblock.ModStats;
import com.aeldrin.teleportblock.advancement.LinkTrigger;
import com.aeldrin.teleportblock.advancement.TeleportTrigger;
import com.aeldrin.teleportblock.block.entity.TeleportBlockEntity;
import com.aeldrin.teleportblock.compat.ftbchunks.FTBChunksCompat;
import com.aeldrin.teleportblock.compat.opac.OpenPACCompat;
import com.aeldrin.teleportblock.compat.sable.SableCompat;
import com.aeldrin.teleportblock.compat.waystones.WaystoneCompat;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.particles.ParticleTypes;
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
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
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
    // Ожидающая линковка хранит измерение вместе с позицией (fix 2.0.1): раньше тут был голый
    // BlockPos, и если игрок кликал первый блок в Верхнем мире, а второй в Незере, мод искал
    // первый блок по тем же координатам уже в Незере - с синхронной загрузкой/генерацией чанка.
    // С 2.2 измерение первого блока используется по назначению: линковка между мирами разрешена
    // (если не выключена в конфиге), а первый блок ищется в СВОЁМ мире.
    // onSubLevel запоминается в момент клика (sub-level тогда точно загружен, раз по нему кликнули),
    // чтобы при завершении линковки не лезть в плот-чанк улетевшего корабля.
    private record PendingLink(ResourceKey<Level> dimension, BlockPos pos, boolean onSubLevel) {}

    private static final Map<UUID, PendingLink> PENDING_LINKS = new HashMap<>();

    // "Игрок прибыл на этот блок и ещё с него не сошёл" (fix 2.0.1, пинг-понг в пассивном режиме).
    // Раньше после прибытия на пассивный блок B и простого стояния на нём, по истечении кулдауна
    // stepOn отправлял игрока обратно на A, и так по кругу. Теперь, как у ванильного портала в Незер,
    // блок прибытия не срабатывает, пока игрок физически с него не сойдёт. Сброс записи - в tickArrival
    // (вызывается каждый тик из ModEventHandlers), а не в stepOn: stepOn для стоящего игрока
    // приходит нерегулярно (зависит от пакетов движения клиента), на его частоту полагаться нельзя.
    private record Arrival(ResourceKey<Level> dimension, BlockPos pos) {}

    private static final Map<UUID, Arrival> ARRIVALS = new HashMap<>();

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

    // Визуальные эффекты блока (частицы, гудение, след к партнёру) с 2.2 живут в клиентском
    // тикере block entity - TeleportBlockEntity.clientTick. Там же объяснено, почему не animateTick.
    // На сервере тикера нет: эффекты чисто клиентские.
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide()
                ? createTickerHelper(type, ModBlockEntities.TELEPORT_BLOCK_ENTITY.get(), TeleportBlockEntity::clientTick)
                : null;
    }

    public static boolean hasPendingLink(UUID playerId) {
        return PENDING_LINKS.containsKey(playerId);
    }

    // Забирает ожидающую линковку и проверяет, что её можно безопасно завершить в этом level.
    // Возвращает позицию и измерение первого блока или null (сообщение игроку уже отправлено).
    // allowOtherDimension: true - линковка блок-блок (useWithoutItem), межмировая связь разрешена,
    // если её не запрещает конфиг; false - линковка к Waystone (TeleportBlockItem), только в одном мире.
    public static @Nullable GlobalPos takePendingLink(Player player, Level level, boolean allowOtherDimension) {
        PendingLink pending = PENDING_LINKS.remove(player.getUUID());
        if (pending == null) return null;

        if (!pending.dimension().equals(level.dimension())) {
            if (!allowOtherDimension) {
                TeleportMessages.actionBar(player, "teleportblock.message.different_dimension");
                return null;
            }
            String denial = crossDimensionDenial(pending.dimension(), level.dimension(),
                    "teleportblock.message.different_dimension");
            if (denial != null) {
                TeleportMessages.actionBar(player, denial);
                return null;
            }
        }

        // Мир первого блока. null возможен, только если измерение исчезло, пока шла линковка.
        Level firstLevel = level instanceof ServerLevel serverLevel
                ? serverLevel.getServer().getLevel(pending.dimension()) : null;
        if (firstLevel == null) {
            TeleportMessages.actionBar(player, "teleportblock.message.first_not_found");
            return null;
        }
        // Первый блок стоял на корабле, и корабль с тех пор выгрузился - не грузим плот-чанк
        if (pending.onSubLevel() && !firstLevel.isLoaded(pending.pos())) {
            TeleportMessages.actionBar(player, "teleportblock.message.target_unloaded");
            return null;
        }
        return GlobalPos.of(pending.dimension(), pending.pos());
    }

    // === Кросс-дименшен (2.2) ===

    // Можно ли связывать/телепортировать между измерениями a и b. null = можно, иначе ключ
    // сообщения для игрока. disabledKey - какое сообщение показать, если межмировые связи
    // выключены: при линковке это "блоки должны быть в одном измерении", при телепорте по
    // уже существующей связи - "межмировые телепорты выключены" (связь при этом не снимается,
    // чтобы снова заработать, если опцию включат обратно).
    private static @Nullable String crossDimensionDenial(ResourceKey<Level> a, ResourceKey<Level> b, String disabledKey) {
        if (a.equals(b)) return null;
        ModConfig.Settings cfg = ModConfig.get();
        if (!cfg.crossDimension.get()) return disabledKey;
        if (cfg.isDimensionBlacklisted(a) || cfg.isDimensionBlacklisted(b)) {
            return "teleportblock.message.dimension_blacklisted";
        }
        return null;
    }

    // Квадрат расстояния между двумя блоками, в том числе в разных измерениях.
    // Один мир - как раньше, через SableCompat.distanceSqr (учитывает корабли).
    // Разные миры - обе точки сначала проецируются из sub-level в мировые координаты своего мира,
    // затем x и z переводятся в масштаб Верхнего мира через coordinateScale (Незер = 8,
    // Верхний мир и Энд = 1, модовые измерения задают свой). Так связь Незер <-> Верхний мир
    // считается как ванильный портал: (125, 0) в Незере и (1000, 0) наверху - расстояние 0.
    // Y не масштабируется. Для Энда расстояние формально считается, но условно: его координаты
    // с Верхним миром не связаны - поэтому есть отдельная доплата cross_dimension_xp_cost.
    private static double linkDistanceSqr(Level levelA, BlockPos a, Level levelB, BlockPos b) {
        if (levelA == levelB) {
            return SableCompat.distanceSqr(levelA, Vec3.atCenterOf(a), Vec3.atCenterOf(b));
        }
        Vec3 ga = SableCompat.toGlobalPos(levelA, Vec3.atCenterOf(a));
        Vec3 gb = SableCompat.toGlobalPos(levelB, Vec3.atCenterOf(b));
        double scaleA = levelA.dimensionType().coordinateScale();
        double scaleB = levelB.dimensionType().coordinateScale();
        double dx = ga.x * scaleA - gb.x * scaleB;
        double dy = ga.y - gb.y;
        double dz = ga.z * scaleA - gb.z * scaleB;
        return dx * dx + dy * dy + dz * dz;
    }

    // Мир, где стоит Waystone, на который ведёт be (2.2). WaystoneCompat.getWaystonePos ищет камень
    // по всему серверу, поэтому телепортировать нужно в ЕГО мир, а не в мир этого блока.
    // Если Waystones нет или камень не найден - текущий мир (как до 2.2).
    private static @Nullable Level resolveWaystoneLevel(Level level, TeleportBlockEntity be) {
        UUID waystoneId = be.getWaystoneTarget();
        if (waystoneId == null || !(level instanceof ServerLevel serverLevel)
                || !ModList.get().isLoaded("waystones")) {
            return level;
        }
        ResourceKey<Level> dimension = WaystoneCompat.getWaystoneDimension(serverLevel, waystoneId);
        return dimension != null ? serverLevel.getServer().getLevel(dimension) : level;
    }

    // Указывает ли partner (стоящий в partnerLevel) обратно на блок pos в мире level.
    // Учитывает измерение (2.2): у партнёра null-измерение означает "мой собственный мир".
    private static boolean pointsBackTo(TeleportBlockEntity partner, Level partnerLevel, BlockPos pos, Level level) {
        if (!pos.equals(partner.getTarget())) return false;
        ResourceKey<Level> backDimension = partner.getTargetDimension() != null
                ? partner.getTargetDimension() : partnerLevel.dimension();
        return backDimension.equals(level.dimension());
    }

    // Стоит ли блок на загруженном Sable sub-level прямо сейчас. Без Sable проекция - no-op,
    // поэтому всегда false.
    private static boolean isOnLoadedSubLevel(Level level, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        return SableCompat.toGlobalPos(level, center).distanceToSqr(center) > 1.0E-4;
    }

    // Вызывается при выходе игрока с сервера (см. ModEventHandlers), чтобы записи
    // в PENDING_LINKS/COOLDOWNS/ARRIVALS не копились в памяти вечно на долгоживущем сервере.
    public static void clearPlayerData(UUID playerId) {
        PENDING_LINKS.remove(playerId);
        COOLDOWNS.remove(playerId);
        ARRIVALS.remove(playerId);
    }

    // Вызывается каждый тик для каждого игрока (ModEventHandlers.onPlayerTick). Для игроков
    // без записи в ARRIVALS - один lookup в HashMap, нагрузка пренебрежимая.
    // Запись снимается, когда игрок сошёл с блока прибытия по горизонтали (его хитбокс 0.6
    // больше не пересекается с блоком: 0.5 + 0.3 = 0.8, берём 0.85 с запасом), улетел по
    // вертикали или сменил измерение. Прыжок на месте НЕ снимает запись - как у ванильного
    // портала, нужно именно сойти. Координаты блока проецируются через Sable, чтобы корректно
    // работать и для блока на корабле (для наземного блока проекция - no-op).
    public static void tickArrival(ServerPlayer player) {
        Arrival arrival = ARRIVALS.get(player.getUUID());
        if (arrival == null) return;

        Level level = player.level();
        if (!arrival.dimension().equals(level.dimension())) {
            ARRIVALS.remove(player.getUUID());
            return;
        }

        Vec3 top = SableCompat.toGlobalPos(level, Vec3.atBottomCenterOf(arrival.pos().above()));
        double dx = Math.abs(player.getX() - top.x);
        double dz = Math.abs(player.getZ() - top.z);
        double dy = Math.abs(player.getY() - top.y);
        if (dx > 0.85 || dz > 0.85 || dy > 2.5) {
            ARRIVALS.remove(player.getUUID());
        }
    }

    // Подсветка первого блока во время линковки (2.2): аккуратные белые искры END_ROD по рёбрам
    // блока, видимые только этому игроку. Раньше было лишь сообщение, и его легко пропустить.
    // Вызывается каждый тик из ModEventHandlers.onPlayerTick; одна искра раз в 4 тика, каждая живёт
    // около 3 секунд - на рёбрах одновременно светится примерно 15 искр, без "гирлянды".
    // Чанк не грузим: если выбранный блок выгружен (игрок ушёл далеко), подсветка просто не нужна.
    public static void tickPendingHighlight(ServerPlayer player) {
        if (player.tickCount % 4 != 0) return;
        PendingLink pending = PENDING_LINKS.get(player.getUUID());
        if (pending == null) return;
        if (!(player.level() instanceof ServerLevel level) || !pending.dimension().equals(level.dimension())) return;
        BlockPos p = pending.pos();
        if (!level.isLoaded(p)) return;
        Vec3 worldCenter = SableCompat.toGlobalPos(level, Vec3.atCenterOf(p));
        if (player.distanceToSqr(worldCenter) > 32.0 * 32.0) return;

        // Случайная точка на одном из 12 рёбер: одна координата бежит вдоль ребра, две другие -
        // на гранях (чуть снаружи блока, чтобы искра не пряталась в текстуре)
        RandomSource random = level.getRandom();
        double along = random.nextDouble();
        double a = random.nextBoolean() ? -0.03 : 1.03;
        double b = random.nextBoolean() ? -0.03 : 1.03;
        double x, y, z;
        switch (random.nextInt(3)) {
            case 0 -> { x = along; y = a; z = b; }
            case 1 -> { x = a; y = along; z = b; }
            default -> { x = a; y = b; z = along; }
        }
        level.sendParticles(player, ParticleTypes.END_ROD, false,
                p.getX() + x, p.getY() + y, p.getZ() + z, 1, 0, 0, 0, 0);
    }

    // Ключ позиции для кулдауна с учётом измерения (2.2): две пары на одинаковых координатах
    // в разных мирах больше не делят один кулдаун.
    private static long posKey(ResourceKey<Level> dimension, BlockPos pos) {
        return pos.asLong() * 31L + dimension.location().hashCode();
    }

    private static LinkKey blockLinkKey(Level level, BlockPos pos, TeleportBlockEntity be, BlockPos target) {
        ResourceKey<Level> targetDimension = be.getTargetDimension() != null ? be.getTargetDimension() : level.dimension();
        return LinkKey.of(posKey(level.dimension(), pos), posKey(targetDimension, target));
    }

    private static boolean isStillOnArrival(ServerPlayer player, Level level, BlockPos pos) {
        Arrival arrival = ARRIVALS.get(player.getUUID());
        return arrival != null && arrival.pos().equals(pos) && arrival.dimension().equals(level.dimension());
    }

    // Блок, в котором не должно оказаться тело игрока после телепорта, хотя коллизии у него нет
    // (fix 2.0.1). Раньше проверка смотрела только на коллизию, и лава/огонь/порталы/паутина
    // считались "свободным местом" - над парным блоком можно было устроить ловушку.
    // Вода умышленно НЕ считается опасной: подводные базы - нормальный сценарий.
    // Теги (FIRE, PORTALS) выбраны вместо конкретных блоков, чтобы подхватывать модовые огни и порталы.
    private static boolean isHazard(BlockState state) {
        return state.getFluidState().is(FluidTags.LAVA)
                || state.is(BlockTags.FIRE)
                || state.is(BlockTags.PORTALS)
                || state.is(Blocks.COBWEB)
                || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.WITHER_ROSE);
    }

    private static boolean isObstructed(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && !state.getCollisionShape(level, pos).isEmpty();
    }

    // Снимает связь с ЭТОЙ стороны и сбрасывает POWER/PASSIVE. Используется, когда партнёр
    // оказался "мёртвым" (см. validateLink в doTeleport) - сам партнёр либо уже не существует,
    // либо смотрит на другой блок, поэтому его не трогаем.
    private void unlinkSelf(Level level, BlockPos pos, TeleportBlockEntity be) {
        be.setTarget(null);
        be.setLinkColor(-1);
        be.setOwner(null);
        resetLinkState(level, pos);
    }

    // === Владелец пары (2.1, опция owner_only) ===
    // Может ли игрок менять пару: перелинковать, переключить режим, переименовать.
    // Пользоваться (телепортироваться) может кто угодно - опция защищает только от изменений.
    // Без владельца (связь до 2.1 или разорвана) - может кто угодно. Операторы сервера - всегда.
    // Ломание блока опция НЕ защищает: для этого есть приваты (FTB Chunks и т.п.).
    public static boolean canModify(Player player, TeleportBlockEntity be) {
        if (!ModConfig.get().ownerOnly.get()) return true;
        UUID owner = be.getOwner();
        return owner == null || owner.equals(player.getUUID()) || player.hasPermissions(2);
    }

    // canModify + сообщение игроку при отказе. Используется и TeleportBlockItem (линковка к Waystone).
    public static boolean checkCanModify(Player player, TeleportBlockEntity be) {
        if (canModify(player, be)) return true;
        TeleportMessages.actionBar(player, "teleportblock.message.not_owner");
        return false;
    }

    // === Цена телепорта в очках опыта (2.1, опции xp_per_100_blocks / max_xp_cost) ===

    // Сколько очков нужно, чтобы перейти с уровня level на следующий. Копия ванильной
    // Player.getXpNeededForNextLevel, но для произвольного уровня, а не только текущего.
    private static long xpForLevel(int level) {
        if (level >= 30) return 112L + (level - 30) * 9L;
        if (level >= 15) return 37L + (level - 15) * 5L;
        return 7L + level * 2L;
    }

    // Полный запас очков опыта игрока. Считается из уровня и прогресса, а не из поля
    // totalExperience: ванильные /xp ... levels и некоторые моды меняют уровень, не обновляя
    // totalExperience, и оно бывает неточным.
    private static long getTotalXpPoints(Player player) {
        long total = 0;
        for (int lvl = 0; lvl < player.experienceLevel; lvl++) {
            total += xpForLevel(lvl);
        }
        return total + Math.round(player.experienceProgress * xpForLevel(player.experienceLevel));
    }

    // Цена конкретного прыжка: расстояние по прямой между блоками (в мировых координатах, т.е.
    // корректно и для кораблей Sable; между измерениями - в масштабе Верхнего мира, см.
    // linkDistanceSqr) * xp_per_100_blocks / 100, с округлением вверх, плюс доплата
    // cross_dimension_xp_cost за прыжок между мирами (2.2), и потолок max_xp_cost на итог
    // (0 = без потолка). Креатив и наблюдатель не платят.
    private static int computeXpCost(Level level, BlockPos sourcePos, Level targetLevel, BlockPos targetPos, Player player) {
        if (player.isCreative() || player.isSpectator()) return 0;
        ModConfig.Settings cfg = ModConfig.get();

        long cost = 0;
        int per100 = cfg.xpPer100Blocks.get();
        if (per100 > 0) {
            double distance = Math.sqrt(linkDistanceSqr(level, sourcePos, targetLevel, targetPos));
            cost = (long) Math.ceil(distance * per100 / 100.0);
        }
        if (targetLevel != level) {
            cost += cfg.crossDimensionXpCost.get();
        }
        if (cost <= 0) return 0;

        int cap = cfg.maxXpCost.get();
        if (cap > 0) cost = Math.min(cost, cap);
        return (int) Math.min(cost, Integer.MAX_VALUE);
    }

    // ===========================================================================================
    // Единый метод телепорта — используется кликом, пассивным stepOn, ender pearl и waystone.
    // Возвращает true если телепорт произошёл, false если отменён (нет места, опасная точка,
    // FTB Chunks, выгруженный Sable sub-level, точка за границей мира, мёртвая ссылка).
    // showMessages=false для пассивного режима (иначе спам каждый тик) и ender pearl.
    //
    // Порядок проверок важен:
    //   1) sub-level guard - ДО любого getBlockState по targetPos (не грузим плот-чанк);
    //   2) граница мира;
    //   3) валидация ссылки (партнёр жив и смотрит на нас);
    //   4) место и опасности;
    //   5) FTB Chunks по МИРОВЫМ координатам;
    //   6) только потом эффекты и сам телепорт.
    // С 2.2 цель может быть в другом измерении: всё, что касается точки прибытия, делается в
    // targetLevel (мир партнёра), а не в level (мир этого блока). Для Waystone targetLevel = level.
    // ===========================================================================================
    private boolean doTeleport(Level level, BlockPos sourcePos, BlockPos targetPos,
                               TeleportBlockEntity be, ServerPlayer player,
                               boolean showMessages) {
        // Цель - другой TeleportBlock (а не waystone). У waystone-линка getTarget() == null.
        boolean blockLink = targetPos.equals(be.getTarget());
        boolean targetOnSubLevel = blockLink && be.isPartnerOnSubLevel();

        // Мир точки прибытия (2.2). null - измерения партнёра больше нет (например, удалён мод с
        // этим измерением): связь мёртвая, снимаем её так же, как при валидации ниже.
        // Для Waystone - мир самого камня: камень может быть в другом измерении.
        Level targetLevel = blockLink ? be.getPartnerLevel() : resolveWaystoneLevel(level, be);
        if (targetLevel == null) {
            unlinkSelf(level, sourcePos, be);
            TeleportMessages.actionBar(player, "teleportblock.message.link_broken");
            return false;
        }

        // Межмировая связь, но её запретили в конфиге уже после линковки (выключили опцию или
        // добавили измерение в чёрный список). Связь не снимаем - заработает, если вернуть опцию.
        // Сообщение показываем всегда (строка действий): иначе в пассивном режиме непонятно,
        // почему ничего не происходит.
        String denial = crossDimensionDenial(level.dimension(), targetLevel.dimension(),
                "teleportblock.message.cross_dimension_disabled");
        if (denial != null) {
            TeleportMessages.actionBar(player, denial);
            return false;
        }

        Vec3 targetVec = new Vec3(targetPos.getX() + 0.5, targetPos.getY() + 1.0, targetPos.getZ() + 0.5);

        // Fix GitHub issue #1: партнёр стоит на Sable sub-level (корабль Create: Aeronautics),
        // а корабль улетел за прорисовку. Sable выгружает sub-level, блок остаётся в "плот-зоне"
        // Sable на огромных координатах, и toGlobalPos уже не может их спроецировать - возвращает
        // координаты как есть. Без этой проверки игрока кидало в плот-зону (краш / зависание),
        // а level.getBlockState ниже синхронно грузил чанки в этой зоне.
        // Проверка isLoaded идёт ПЕРВОЙ, до любого getBlockState по targetPos.
        if (targetOnSubLevel) {
            if (!targetLevel.isLoaded(targetPos)) {
                if (showMessages) {
                    TeleportMessages.actionBar(player, "teleportblock.message.target_unloaded");
                }
                return false;
            }
            Vec3 projected = SableCompat.toGlobalPos(targetLevel, targetVec);
            if (projected.distanceToSqr(targetVec) < 1.0E-4) {
                // Проекция ничего не сдвинула - sub-level отсутствует/не загружен
                if (showMessages) {
                    TeleportMessages.actionBar(player, "teleportblock.message.target_unloaded");
                }
                return false;
            }
        }

        // Sable companion (JiJ'd): без Sable возвращает те же координаты (safe no-op),
        // с Sable — конвертирует sub-level координаты в глобальные.
        Vec3 globalTarget = SableCompat.toGlobalPos(targetLevel, targetVec);

        // Финальная страховка: итоговая точка обязана быть внутри границы мира.
        // Ловит любые другие случаи неудачной проекции (в т.ч. если sub-level не был
        // распознан при линковке, например у старых линков без real_target).
        // Стоит ДО партиклов/звука, чтобы эффекты не проигрывались при отмене.
        if (!targetLevel.getWorldBorder().isWithinBounds(globalTarget.x, globalTarget.z)) {
            if (showMessages) {
                TeleportMessages.actionBar(player, "teleportblock.message.target_unloaded");
            }
            return false;
        }

        // Валидация ссылки (fix 2.0.1). Ссылка могла "умереть" в обход onRemove: блок перенесли
        // Carry On / WorldEdit, корабль собрали ПОСЛЕ линковки (блок уехал в плот-зону), партнёр
        // сломали, пока наш чанк был выгружен, или партнёра перелинковали на Waystone.
        // Живая ссылка = на targetPos стоит TeleportBlock, и его target указывает обратно на нас.
        // Мёртвую снимаем только с нашей стороны. Сообщение показываем всегда (и в пассивном
        // режиме тоже) - после отвязки оно не повторится, спама не будет.
        // Для наземного партнёра getBlockEntity может загрузить его чанк - это нормально,
        // мы и так собираемся туда телепортироваться (ванильный teleport тоже грузит чанк).
        // С 2.2 партнёр ищется в своём мире и должен указывать обратно и на наш мир (pointsBackTo).
        if (blockLink) {
            BlockEntity targetBe = targetLevel.getBlockEntity(targetPos);
            if (!(targetBe instanceof TeleportBlockEntity partner) || !pointsBackTo(partner, targetLevel, sourcePos, level)) {
                unlinkSelf(level, sourcePos, be);
                TeleportMessages.actionBar(player, "teleportblock.message.link_broken");
                return false;
            }
        }

        // Опция check_distance_on_teleport (2.1): лимит дальности проверяется не только при линковке,
        // но и при каждом телепорте. Нужна для кораблей Create: Aeronautics - после линковки корабль
        // может улететь сколь угодно далеко. linkDistanceSqr считает в мировых координатах и
        // учитывает масштаб измерений (2.2).
        if (blockLink && ModConfig.get().checkDistanceOnTeleport.get()) {
            int maxDist = ModConfig.get().maxLinkDistance.get();
            if (linkDistanceSqr(level, sourcePos, targetLevel, targetPos) > (double) maxDist * maxDist) {
                if (showMessages) {
                    TeleportMessages.actionBar(player, "teleportblock.message.too_far", maxDist);
                }
                return false;
            }
        }

        BlockPos feet = targetPos.above();
        BlockPos head = targetPos.above(2);

        if (isObstructed(targetLevel, feet) || isObstructed(targetLevel, head)) {
            if (showMessages) {
                TeleportMessages.actionBar(player, "teleportblock.message.blocked");
            }
            return false;
        }

        if (isHazard(targetLevel.getBlockState(feet)) || isHazard(targetLevel.getBlockState(head))) {
            if (showMessages) {
                TeleportMessages.actionBar(player, "teleportblock.message.unsafe");
            }
            return false;
        }

        // FTB Chunks проверяется по МИРОВЫМ координатам точки прибытия (fix 2.0.1). Раньше
        // передавался targetPos, а для блока на корабле это плот-координаты, которые никто
        // не приватил - можно было запарковать корабль в чужом привате и телепортироваться туда.
        // Для наземного блока globalTarget совпадает с targetPos.above(), чанк тот же.
        // С 2.2 - в мире точки прибытия (targetLevel), приват в другом измерении тоже учитывается.
        if (targetLevel instanceof ServerLevel targetServerLevel
                && ModList.get().isLoaded("ftbchunks")
                && !FTBChunksCompat.canTeleportTo(player, targetServerLevel, BlockPos.containing(globalTarget))) {
            if (showMessages) {
                TeleportMessages.actionBar(player, "teleportblock.message.chunk_protected");
            }
            return false;
        }

        // Open Parties and Claims (2.2) - то же правило, что для FTB Chunks
        if (targetLevel instanceof ServerLevel targetServerLevel
                && ModList.get().isLoaded("openpartiesandclaims")
                && !OpenPACCompat.canTeleportTo(player, targetServerLevel, BlockPos.containing(globalTarget))) {
            if (showMessages) {
                TeleportMessages.actionBar(player, "teleportblock.message.chunk_protected");
            }
            return false;
        }

        // Цена телепорта (2.1). Проверяется последней, прямо перед эффектами, чтобы опыт не
        // списывался и не требовался, если телепорт всё равно отменится по другой причине.
        // Сообщение показывается всегда (даже в пассивном режиме и для жемчуга): без него игрок
        // не поймёт, почему телепорт не сработал. Это строка действий, а не чат - спама не будет.
        // Жемчуг платит так же, иначе цену можно было бы обойти, кидая жемчуг вместо клика.
        int xpCost = computeXpCost(level, sourcePos, targetLevel, targetPos, player);
        if (xpCost > 0 && getTotalXpPoints(player) < xpCost) {
            TeleportMessages.actionBar(player, "teleportblock.message.not_enough_xp", xpCost);
            return false;
        }

        // Партиклы на обоих концах (у точки прибытия - в её мире, 2.2). Звуки - ниже, см. комментарии.
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.PORTAL,
                    sourcePos.getX() + 0.5, sourcePos.getY() + 1.0, sourcePos.getZ() + 0.5,
                    40, 0.3, 0.5, 0.3, 0.08);
        }
        if (targetLevel instanceof ServerLevel targetServerLevel) {
            targetServerLevel.sendParticles(ParticleTypes.PORTAL,
                    targetPos.getX() + 0.5, targetPos.getY() + 1.0, targetPos.getZ() + 0.5,
                    40, 0.3, 0.5, 0.3, 0.08);
        }

        // Звук отправления (2.2): игрок передан исключением - его слышат только окружающие.
        // Сервер рассылает звук только тем, кто в этот момент рядом, а сам игрок через мгновение
        // окажется далеко - для него звук обрывался. Он услышит звук прибытия ниже, целиком.
        // Координаты - мировые (через Sable), иначе у блока на корабле звук звучал бы в плот-зоне.
        Vec3 sourceSound = SableCompat.toGlobalPos(level, Vec3.atCenterOf(sourcePos));
        level.playSound(player, sourceSound.x, sourceSound.y, sourceSound.z,
                SoundEvents.ENDERMAN_TELEPORT, SoundSource.BLOCKS, 1.0f, 1.0f);

        // globalTarget уже спроецирован и проверен выше
        if (targetLevel == level) {
            player.teleportTo(globalTarget.x, globalTarget.y, globalTarget.z);
        } else {
            // Межмировой телепорт (2.2): ServerPlayer.teleportTo(ServerLevel, ...) делает
            // changeDimension. Его может отменить другой мод (событие перехода между мирами
            // NeoForge) - тогда игрок остаётся на месте, и мы не списываем опыт, не ставим
            // кулдаун и не засчитываем телепорт.
            player.teleportTo((ServerLevel) targetLevel, globalTarget.x, globalTarget.y, globalTarget.z,
                    player.getYRot(), player.getXRot());
            if (player.level() != targetLevel) {
                return false;
            }
            // Звук перехода в другой мир - только этому игроку и тихо, как у ванильного портала.
            // Дополняет обычный звук прибытия ниже.
            player.playNotifySound(ModSounds.CROSS_DIMENSION.get(), SoundSource.BLOCKS,
                    0.25f, 0.8f + player.getRandom().nextFloat() * 0.4f);
        }

        // Звук прибытия (2.2) - ПОСЛЕ переноса: теперь игрок рядом с точкой прибытия и слышит его
        // целиком (раньше он проигрывался до переноса и до игрока не доходил). Слышат и окружающие.
        targetLevel.playSound(null, globalTarget.x, globalTarget.y, globalTarget.z,
                SoundEvents.ENDERMAN_TELEPORT, SoundSource.BLOCKS, 1.0f, 1.0f);

        // Отрицательный giveExperiencePoints - так же, как ванильная /xp add <игрок> -N points:
        // корректно откатывает уровни и синхронизирует опыт с клиентом.
        if (xpCost > 0) {
            player.giveExperiencePoints(-xpCost);
        }

        // Прибыли на другой TeleportBlock - он не сработает в пассивном режиме, пока игрок
        // с него не сойдёт (см. ARRIVALS / tickArrival). Для waystone не нужно.
        if (blockLink) {
            ARRIVALS.put(player.getUUID(), new Arrival(targetLevel.dimension(), targetPos));
        }

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

        // Fix 2.0.1: neighborChanged - фоновая логика, вызывается на каждое редстоун-обновление.
        // Раньше getBlockState(target) синхронно грузил чанк партнёра (редстоун-часы рядом с блоком
        // постоянно дёргали загрузку чанка за 1000 блоков, а для партнёра на улетевшем корабле -
        // плот-чанк, как в issue #1). Теперь, как ванильный редстоун, в выгруженные чанки не лезем.
        // Реле работает, когда оба конца загружены (в т.ч. чанклоадером). Состояние партнёра
        // обновится при следующем изменении сигнала у этого блока.
        // С 2.2 партнёр может быть в другом измерении - всё делаем в его мире (partnerLevel).
        // Межмировое реле работает, только когда чанк партнёра загружен (например, чанклоадером).
        Level partnerLevel = be.getPartnerLevel();
        if (partnerLevel == null || !partnerLevel.isLoaded(target)) return;

        BlockState targetState = partnerLevel.getBlockState(target);
        if (!(targetState.getBlock() instanceof TeleportBlock)) return;

        // Не учитываем собственный POWER этого блока как входной сигнал,
        // иначе он "подхватит" то, что сам же излучает.
        int rawSignal = level.getBestNeighborSignal(pos);
        int attenuated = Mth.ceil(rawSignal * 0.5f);

        // Значение не изменилось - выходим, чтобы не плодить лишние апдейты/циклы
        if (targetState.getValue(POWER) == attenuated) return;

        partnerLevel.setBlock(target, targetState.setValue(POWER, attenuated), Block.UPDATE_ALL);
        partnerLevel.updateNeighborsAt(target, this);
    }

    // === Passive portal: teleport on touch (standing on top), no click required ===
    // Блок всегда остаётся твёрдым - никакой отмены коллизии. Триггер - именно "касание"
    // (игрок стоит сверху), как у нажимной плиты. Правило мода: переносится только голый
    // игрок, не пассажир. Если над целевым блоком нет места - просто ничего не происходит,
    // никакого поиска альтернативной точки (умышленно, во избежание "бага кровати", когда
    // отсутствие свободного места телепортирует игрока в случайное соседнее место и создаёт ловушку).
    // Три доп. защиты от зависаний/ловушек: (1) зажатый Shift полностью отключает срабатывание;
    // (2) кулдаун всегда не меньше 1 тика (MIN_PASSIVE_COOLDOWN_TICKS), даже если в конфиге стоит 0 -
    // stepOn вызывается каждый тик, пока игрок стоит на блоке, и без этого лимита это будет
    // тысячи вызовов teleportTo в секунду; (3) fix 2.0.1 - блок прибытия не срабатывает, пока
    // игрок с него не сошёл (ARRIVALS), иначе стояние на B отправляло обратно на A по кругу.

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

        // Игрок только что прибыл на этот блок и ещё с него не сошёл
        if (isStillOnArrival(player, level, pos)) return;

        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof TeleportBlockEntity be)) return;

        BlockPos target = be.getTarget();
        if (target == null) return;

        // Клэмп минимума - см. MIN_PASSIVE_COOLDOWN_TICKS. Именно здесь, а не в isOnCooldown,
        // чтобы клик-телепорт по-прежнему мог честно работать с cooldown_seconds=0 из конфига.
        long cooldownTicks = Math.max(ModConfig.get().cooldownSeconds.get() * 20L, MIN_PASSIVE_COOLDOWN_TICKS);
        long now = level.getGameTime();
        LinkKey key = blockLinkKey(level, pos, be, target);

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
            TeleportMessages.actionBar(player, "teleportblock.message.not_linked");
            return;
        }

        if (!checkCanModify(player, be)) return;

        // Fix 2.0.1: партнёр на выгруженном корабле - отказываем целиком, а не переключаем
        // только свою сторону: режим - общее состояние пары, рассинхрон хуже, чем отказ.
        // Наземного партнёра грузим как раньше - это разовое действие игрока, не фоновое.
        if (!be.canAccessPartner()) {
            TeleportMessages.actionBar(player, "teleportblock.message.target_unloaded");
            return;
        }

        BlockState state = level.getBlockState(pos);
        boolean newPassive = !state.getValue(PASSIVE);

        level.setBlock(pos, state.setValue(PASSIVE, newPassive), Block.UPDATE_ALL);

        // Партнёр - в своём мире (2.2). canAccessPartner выше гарантирует, что partnerLevel не null.
        Level partnerLevel = be.getPartnerLevel();
        BlockState targetState = partnerLevel.getBlockState(target);
        if (targetState.getBlock() instanceof TeleportBlock && targetState.hasProperty(PASSIVE)) {
            partnerLevel.setBlock(target, targetState.setValue(PASSIVE, newPassive), Block.UPDATE_ALL);
        }

        level.playSound(null, pos, SoundEvents.BEACON_POWER_SELECT, SoundSource.BLOCKS, 1.0f, newPassive ? 1.4f : 0.8f);
        TeleportMessages.actionBar(player, newPassive ? "teleportblock.message.passive_enabled" : "teleportblock.message.passive_disabled");
    }

    // === Ender Pearl teleport ===
    // Жемчуг попадает в связанный блок — игрок телепортируется к парному блоку (или waystone).
    // Имеет свой отдельный кулдаун (pearl_cooldown_seconds), т.к. жемчуг сам по себе расходный ресурс.
    // Ванильный урон от жемчуга при успешном телепорте НЕ наносится: жемчуг удаляется внутри
    // onHitBlock, и ванильный ThrownEnderpearl.onHit видит его уже removed и ничего не делает.
    // Fix 2.0.1: жемчуг удаляется только ПОСЛЕ успешного doTeleport. Раньше он удалялся заранее,
    // и при любой отмене (нет места, опасная точка, мёртвая ссылка, выгруженный корабль) пропадал
    // молча. Теперь при отмене жемчуг срабатывает как обычный: игрок приземляется у этого блока
    // и получает ванильный урон от жемчуга.

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
        long pearlCooldownTicks = ModConfig.get().pearlCooldownSeconds.get() * 20L;
        long now = level.getGameTime();

        // Поддержка и block-линков, и waystone-линков
        BlockPos blockTarget = be.getTarget();
        UUID waystoneTarget = be.getWaystoneTarget();

        if (blockTarget == null && waystoneTarget == null) return;

        // Вычисляем ключ звена для кулдауна
        LinkKey key = waystoneTarget != null
                ? LinkKey.of(posKey(level.dimension(), pos), identifierFor(waystoneTarget))
                : blockLinkKey(level, pos, be, blockTarget);

        if (isOnCooldown(player.getUUID(), key, pearlCooldownTicks, now)) return;

        // Waystone-телепорт через жемчуг
        if (waystoneTarget != null
                && level instanceof ServerLevel serverLevel
                && ModList.get().isLoaded("waystones")) {
            BlockPos waystonePos = WaystoneCompat.getWaystonePos(serverLevel, waystoneTarget);
            if (waystonePos == null) return;

            // Убиваем жемчуг только после успешного телепорта (иначе ваниль сама телепортнёт игрока к блоку)
            if (doTeleport(level, pos, waystonePos, be, player, false)) {
                pearl.discard();
                setCooldown(player.getUUID(), key, now);
            }
            return;
        }

        // Block-телепорт через жемчуг
        if (blockTarget != null) {
            if (doTeleport(level, pos, blockTarget, be, player, false)) {
                pearl.discard();
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
                // Владелец (2.1): бирка не тратится при отказе
                if (!checkCanModify(player, be)) return ItemInteractionResult.FAIL;

                // Fix 2.0.1: партнёр на выгруженном корабле - имя не синхронизировать, отказываем,
                // бирка не тратится (иначе имена у пары разъедутся)
                if (be.getTarget() != null && !be.canAccessPartner()) {
                    TeleportMessages.actionBar(player, "teleportblock.message.target_unloaded");
                    return ItemInteractionResult.FAIL;
                }

                String name = stack.getHoverName().getString();
                be.setLinkNameWithSync(name);

                if (!player.getAbilities().instabuild) {
                    stack.shrink(1);
                }

                level.playSound(null, pos, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 0.5f, 1.0f);
                TeleportMessages.actionBar(player, "teleportblock.message.named", name);
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
                TeleportMessages.actionBar(player, "teleportblock.message.not_linked");
                return ItemInteractionResult.FAIL;
            }

            int color = be.hasLinkColor() ? be.getLinkColor() : 0xFFFFFF;

            // Маркеры ставятся по МИРОВЫМ координатам (2.2): раньше брались сырые позиции, и для
            // блока на корабле Sable маркер уезжал в плот-зону, далеко за пределы карты.
            // Свой блок проецируется прямо сейчас; партнёр - по realTarget (мировая позиция на
            // момент линковки; корабль мог с тех пор сдвинуться, но это лучшее, что известно).
            BlockPos ownPos = BlockPos.containing(SableCompat.toGlobalPos(level, Vec3.atCenterOf(pos)));
            BlockPos partnerPos = be.getRealTarget() != null ? be.getRealTarget() : target;

            if (be.isCrossDimension()) {
                // Партнёр в другом измерении: на карте этого мира его маркер не имеет смысла
                com.aeldrin.teleportblock.map.TeleportMapHandler.addMarkerToMap(
                        stack,
                        ownPos.getX(), ownPos.getZ(),
                        color,
                        be.getLinkName()
                );
            } else {
                com.aeldrin.teleportblock.map.TeleportMapHandler.addMarkersToMap(
                        stack,
                        ownPos.getX(), ownPos.getZ(),
                        partnerPos.getX(), partnerPos.getZ(),
                        color,
                        be.getLinkName()
                );
            }

            // Сразу рисуем маркеры на карте (2.2) - раньше они появлялись только при следующем
            // обновлении TeleportMapHandler, с задержкой до 5 секунд
            com.aeldrin.teleportblock.map.TeleportMapHandler.refreshDecorations(player, stack);

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

            if (hasPendingLink(id)) {
                // takePendingLink проверяет измерение (межмировые связи, чёрный список - 2.2)
                // и выгруженный корабль; при отказе сообщение уже отправлено
                GlobalPos first = takePendingLink(player, level, true);
                if (first == null) return InteractionResult.FAIL;

                // Мир первого блока (2.2) - может отличаться от мира второго. takePendingLink
                // уже проверил, что это измерение существует.
                Level firstLevel = ((ServerLevel) level).getServer().getLevel(first.dimension());
                BlockPos firstPos = first.pos();

                if (firstLevel == level && firstPos.equals(pos)) {
                    TeleportMessages.actionBar(player, "teleportblock.message.cannot_self_link");
                    return InteractionResult.FAIL;
                }

                // Между измерениями - в масштабе Верхнего мира (см. linkDistanceSqr)
                int maxDist = ModConfig.get().maxLinkDistance.get();
                if (linkDistanceSqr(firstLevel, firstPos, level, pos) > (double) maxDist * maxDist) {
                    TeleportMessages.actionBar(player, "teleportblock.message.too_far", maxDist);
                    return InteractionResult.FAIL;
                }

                // instanceof вместо приведения: на месте первого блока мог оказаться другой блок с BE
                TeleportBlockEntity firstBe = firstLevel.getBlockEntity(firstPos) instanceof TeleportBlockEntity tbe ? tbe : null;
                if (firstBe != null) {
                    // Владелец (2.1): перелинковка чужой пары запрещена с обеих сторон
                    if (!checkCanModify(player, firstBe) || !checkCanModify(player, be)) {
                        return InteractionResult.FAIL;
                    }

                    // Если любой из двух блоков уже был к кому-то привязан - у старого партнёра
                    // останется "мёртвая" ссылка, если явно его не отвязать. Это и есть баг
                    // "цепь расвязывается" / блок висит координатами и занимает слот линка.
                    unlinkOldPartner(firstLevel, firstPos, firstBe);
                    unlinkOldPartner(level, pos, be);

                    // Каждый блок хранит позицию партнёра и его измерение (для одного мира
                    // setTarget сам сохранит null). realTarget проецируется в мире партнёра.
                    firstBe.setTarget(pos, level.dimension());
                    firstBe.setRealTarget(BlockPos.containing(SableCompat.toGlobalPos(level, Vec3.atCenterOf(pos))));
                    be.setTarget(firstPos, firstLevel.dimension());
                    be.setRealTarget(BlockPos.containing(SableCompat.toGlobalPos(firstLevel, Vec3.atCenterOf(firstPos))));

                    int color = TeleportBlockEntity.generateRandomLinkColor();
                    firstBe.setLinkColor(color);
                    be.setLinkColor(color);

                    // Владелец пары - тот, кто её связал
                    firstBe.setOwner(player.getUUID());
                    be.setOwner(player.getUUID());

                    String existingName = firstBe.getLinkName() != null ? firstBe.getLinkName() : be.getLinkName();
                    if (existingName != null) {
                        firstBe.setLinkName(existingName);
                        be.setLinkName(existingName);
                    }

                    level.playSound(null, pos, SoundEvents.END_PORTAL_FRAME_FILL, SoundSource.BLOCKS, 1.0f, 1.0f);
                    TeleportMessages.actionBar(player, "teleportblock.message.linked");
                    if (player instanceof ServerPlayer serverPlayer) {
                        LinkTrigger.INSTANCE.trigger(serverPlayer);
                    }
                } else {
                    TeleportMessages.actionBar(player, "teleportblock.message.first_not_found");
                }
            } else {
                // Владелец (2.1): сразу говорим, что эту пару менять нельзя, а не после второго клика
                if (!checkCanModify(player, be)) return InteractionResult.FAIL;

                PENDING_LINKS.put(id, new PendingLink(level.dimension(), pos, isOnLoadedSubLevel(level, pos)));
                TeleportMessages.actionBar(player, "teleportblock.message.first_selected");
            }
        } else {
            if (player.isPassenger()) {
                TeleportMessages.actionBar(player, "teleportblock.message.dismount");
                return InteractionResult.FAIL;
            }

            long cooldownTicks = ModConfig.get().cooldownSeconds.get() * 20L;
            long now = level.getGameTime();

            UUID waystoneTarget = be.getWaystoneTarget();
            BlockPos blockTarget = be.getTarget();

            if (waystoneTarget == null && blockTarget == null) {
                TeleportMessages.actionBar(player, "teleportblock.message.not_linked");
                return InteractionResult.SUCCESS;
            }

            // Ключ звена вычисляется здесь же, чтобы кулдаун проверялся именно
            // для конкретной пары эндпоинтов, а не глобально по всем блокам игрока.
            LinkKey key = waystoneTarget != null
                    ? LinkKey.of(posKey(level.dimension(), pos), identifierFor(waystoneTarget))
                    : blockLinkKey(level, pos, be, blockTarget);

            if (isOnCooldown(player.getUUID(), key, cooldownTicks, now)) {
                long remaining = remainingCooldownSeconds(player.getUUID(), key, cooldownTicks, now);
                TeleportMessages.actionBar(player, "teleportblock.message.cooldown", remaining);
                return InteractionResult.FAIL;
            }

            // Телепорт на Waystone
            if (waystoneTarget != null
                    && level instanceof ServerLevel serverLevel
                    && ModList.get().isLoaded("waystones")) {
                BlockPos waystonePos = WaystoneCompat.getWaystonePos(serverLevel, waystoneTarget);
                if (waystonePos == null) {
                    TeleportMessages.actionBar(player, "teleportblock.message.waystone_lost");
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
            PENDING_LINKS.values().removeIf(pending ->
                    pending.pos().equals(pos) && pending.dimension().equals(level.dimension()));

            TeleportBlockEntity be = (TeleportBlockEntity) level.getBlockEntity(pos);
            if (be != null) {
                TeleportBlockEntity.releaseColor(be.getLinkColor());

                // Fix 2.0.1: партнёр на выгруженном корабле не трогаем (иначе грузим плот-чанк).
                // Его ссылка на нас станет мёртвой и будет снята автоматически при следующей
                // попытке телепорта с его стороны (валидация ссылки в doTeleport).
                // С 2.2 партнёр ищется в своём мире (может быть другое измерение), и отвязывается,
                // только если он действительно указывает обратно на нас (pointsBackTo) - чтобы
                // разрушение блока с устаревшей ссылкой не разорвало чужую, уже новую пару партнёра.
                if (be.getTarget() != null && be.canAccessPartner()) {
                    BlockPos pairedPos = be.getTarget();
                    Level pairedLevel = be.getPartnerLevel();
                    BlockEntity paired = pairedLevel.getBlockEntity(pairedPos);
                    if (paired instanceof TeleportBlockEntity pairedBe
                            && pointsBackTo(pairedBe, pairedLevel, pos, level)) {
                        pairedBe.setTarget(null);
                        pairedBe.setLinkColor(-1);
                        pairedBe.setOwner(null);
                        resetLinkState(pairedLevel, pairedPos);
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
    // Fix 2.0.1: старый партнёр на выгруженном корабле не трогаем - см. комментарий в onRemove,
    // его мёртвая ссылка будет снята лениво валидацией в doTeleport.
    // С 2.2 старый партнёр может быть в другом измерении - ищем его в его мире.
    private void unlinkOldPartner(Level level, BlockPos pos, TeleportBlockEntity be) {
        BlockPos oldTarget = be.getTarget();
        if (oldTarget == null) return;
        if (!be.canAccessPartner()) return;

        Level oldLevel = be.getPartnerLevel();
        BlockEntity oldPartner = oldLevel.getBlockEntity(oldTarget);
        if (oldPartner instanceof TeleportBlockEntity oldPartnerBe
                && pointsBackTo(oldPartnerBe, oldLevel, pos, level)) {
            oldPartnerBe.setTarget(null);
            oldPartnerBe.setLinkColor(-1);
            oldPartnerBe.setOwner(null);
            resetLinkState(oldLevel, oldTarget);
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
