package com.aeldrin.teleportblock.block.entity;

import com.aeldrin.teleportblock.ModBlockEntities;
import com.aeldrin.teleportblock.ModSounds;
import com.aeldrin.teleportblock.block.TeleportBlock;
import com.aeldrin.teleportblock.compat.sable.SableCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class TeleportBlockEntity extends BlockEntity {
    @Nullable
    private BlockPos target;
    @Nullable
    private BlockPos realTarget;

    // Измерение партнёра (2.2, кросс-дименшен). null = то же измерение, что у этого блока:
    // так хранятся все связи внутри одного мира, включая все связи, сделанные до 2.2, -
    // миграция старых миров не нужна. target всегда хранится в координатах мира партнёра.
    @Nullable
    private ResourceKey<Level> targetDimension;
    @Nullable
    private UUID waystoneTarget;
    private int teleportCount = 0;

    @Nullable
    private String linkName;
    private int linkColor = -1;

    // Владелец пары (2.1) - игрок, который её связал. Используется опцией owner_only в конфиге.
    // Записывается всегда, даже если опция выключена, чтобы включение опции сразу работало для
    // уже связанных пар. null = владельца нет (пара связана до 2.1 или связь разорвана) -
    // такую пару может менять кто угодно. Логике нужен только на сервере (на клиент попадает лишь
    // вместе с остальными данными блока через getUpdateTag - это безвредно).
    @Nullable
    private UUID owner;

    public TeleportBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.TELEPORT_BLOCK_ENTITY.get(), pos, state);
    }

    public @Nullable BlockPos getTarget() { return target; }

    // Связь внутри этого же измерения (и отвязка при target == null) - измерение сбрасывается.
    public void setTarget(@Nullable BlockPos target) {
        setTarget(target, null);
    }

    // Связь с партнёром в указанном измерении. Если оно совпадает с измерением этого блока,
    // сохраняется null - так связь внутри мира выглядит одинаково, как бы её ни создали.
    public void setTarget(@Nullable BlockPos target, @Nullable ResourceKey<Level> dimension) {
        this.target = target;
        this.targetDimension = (target == null || dimension == null
                || (level != null && dimension.equals(level.dimension()))) ? null : dimension;
        this.waystoneTarget = null;
        setChanged();
        syncToClient();
    }

    public @Nullable ResourceKey<Level> getTargetDimension() { return targetDimension; }

    // Партнёр в другом измерении
    public boolean isCrossDimension() {
        return target != null && targetDimension != null;
    }

    // Мир, в котором стоит партнёр. Только для серверной логики. null - связи нет, мы на клиенте
    // или измерения партнёра больше не существует (например, удалили мод с этим измерением) -
    // такую связь doTeleport считает разорванной и снимает.
    // server.getLevel не грузит чанки: все измерения сервера существуют всегда, загружены они
    // или нет - отдельный вопрос (см. canAccessPartner).
    public @Nullable Level getPartnerLevel() {
        if (target == null || level == null) return null;
        if (targetDimension == null) return level;
        if (level instanceof ServerLevel serverLevel) {
            return serverLevel.getServer().getLevel(targetDimension);
        }
        return null;
    }

    public @Nullable BlockPos getRealTarget() { return realTarget; }

    // syncToClient (2.2): клиент берёт realTarget для направления следа к партнёру. Раньше он
    // только сохранялся на сервере, а setTarget отправлял данные клиенту ДО setRealTarget - и у
    // клиента оставался realTarget от прошлой связи: след летел не в ту сторону.
    public void setRealTarget(@Nullable BlockPos realTarget) {
        this.realTarget = realTarget;
        setChanged();
        syncToClient();
    }

    // Стоит ли партнёр на Sable sub-level - по данным, сохранённым при линковке:
    // realTarget = глобальная проекция позиции партнёра на момент линковки. Для обычного
    // наземного блока она совпадает с target, для блока на корабле - отличается (плот-координаты
    // против мировых). Не требует вызовов API Sable и работает без установленного Sable
    // (тогда проекция - no-op и realTarget всегда равен target).
    public boolean isPartnerOnSubLevel() {
        return target != null && realTarget != null && !realTarget.equals(target);
    }

    // Можно ли обращаться к блоку партнёра (getBlockState/getBlockEntity) из разовых действий
    // игрока (переключение режима, имя, отвязка), не рискуя загрузить плот-чанк выгруженного
    // корабля (issue #1). Наземного партнёра считаем доступным всегда: загрузить его чанк ради
    // разового действия допустимо - в том числе в другом измерении (2.2). Обращаться к партнёру
    // всегда через getPartnerLevel(), а не через level. Для фоновой логики (редстоун-реле) этого
    // НЕ достаточно - там проверяется partnerLevel.isLoaded(target) для любого партнёра.
    public boolean canAccessPartner() {
        Level partnerLevel = getPartnerLevel();
        if (partnerLevel == null) return false;
        return !isPartnerOnSubLevel() || partnerLevel.isLoaded(target);
    }

    public @Nullable UUID getWaystoneTarget() { return waystoneTarget; }

    public void setWaystoneTarget(@Nullable UUID uuid) {
        this.waystoneTarget = uuid;
        this.target = null;
        this.targetDimension = null;
        setChanged();
        syncToClient();
    }

    public int getTeleportCount() { return teleportCount; }

    public void incrementTeleportCount() {
        teleportCount++;
        setChanged();
    }

    public @Nullable String getLinkName() { return linkName; }

    public void setLinkName(@Nullable String linkName) {
        this.linkName = linkName;
        setChanged();
        syncToClient();
    }

    public int getLinkColor() { return linkColor; }

    public void setLinkColor(int newColor) {
        releaseColor(this.linkColor);
        this.linkColor = newColor;
        registerColor(newColor);
        setChanged();
        syncToClient();
    }

    public boolean hasLinkColor() { return linkColor != -1; }

    public @Nullable UUID getOwner() { return owner; }

    public void setOwner(@Nullable UUID owner) {
        this.owner = owner;
        setChanged();
    }

    // === Unique color tracking (best-effort, per-session) ===
    // Не переживает перезагрузку сервера и не видит блоки в незагруженных чанках -
    // это осознанное ограничение, а не баг: на 16M цветов коллизия практически
    // невозможна, а полная персистенция потребовала бы world-saved-data и трекинга
    // загрузки/выгрузки чанков, что не стоит сложности.
    private static final java.util.Set<Integer> USED_COLORS = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    // Собственная реализация HSB->RGB, не зависит от java.awt (которого может
    // не быть на headless dedicated серверах с урезанной JVM).
    private static int hsbToRgb(float hue, float saturation, float brightness) {
        float h = (hue - (float) Math.floor(hue)) * 6.0f;
        float f = h - (float) Math.floor(h);
        float p = brightness * (1.0f - saturation);
        float q = brightness * (1.0f - saturation * f);
        float t = brightness * (1.0f - saturation * (1.0f - f));
        int r, g, b;
        switch ((int) h) {
            case 0 -> { r = Math.round(brightness * 255); g = Math.round(t * 255); b = Math.round(p * 255); }
            case 1 -> { r = Math.round(q * 255); g = Math.round(brightness * 255); b = Math.round(p * 255); }
            case 2 -> { r = Math.round(p * 255); g = Math.round(brightness * 255); b = Math.round(t * 255); }
            case 3 -> { r = Math.round(p * 255); g = Math.round(q * 255); b = Math.round(brightness * 255); }
            case 4 -> { r = Math.round(t * 255); g = Math.round(p * 255); b = Math.round(brightness * 255); }
            default -> { r = Math.round(brightness * 255); g = Math.round(p * 255); b = Math.round(q * 255); }
        }
        return ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    public static int generateRandomLinkColor() {
        java.util.concurrent.ThreadLocalRandom rng = java.util.concurrent.ThreadLocalRandom.current();
        int color;
        int attempts = 0;
        do {
            float hue = rng.nextFloat();
            float saturation = 0.6f + rng.nextFloat() * 0.4f;
            float brightness = 0.7f + rng.nextFloat() * 0.3f;
            color = hsbToRgb(hue, saturation, brightness);
            attempts++;
        } while (USED_COLORS.contains(color) && attempts < 1000);
        USED_COLORS.add(color);
        return color;
    }

    public static void releaseColor(int color) {
        if (color != -1) USED_COLORS.remove(color);
    }

    public static void registerColor(int color) {
        if (color != -1) USED_COLORS.add(color);
    }

    public void setLinkNameWithSync(@Nullable String name) {
        this.setLinkName(name);
        // canAccessPartner - см. выше: не грузим плот-чанк выгруженного корабля
        // getPartnerLevel - партнёр может быть в другом измерении (2.2)
        if (target != null && level != null && !level.isClientSide() && canAccessPartner()) {
            BlockEntity paired = getPartnerLevel().getBlockEntity(target);
            if (paired instanceof TeleportBlockEntity pairedBe) {
                pairedBe.setLinkName(name);
            }
        }
    }

    // ===========================================================================================
    // Клиентские эффекты (2.2): частицы по состоянию блока, тихое гудение и след к партнёру.
    // Раньше частицы рисовал Block.animateTick, но клиент вызывает его не каждый тик, а для
    // случайных блоков вокруг игрока - для одиночного блока примерно раз в 2-3 секунды, поэтому
    // эффектов почти не было видно. Клиентский тикер block entity (как у ванильного проводника)
    // работает каждый тик - плотность частиц и ритм звука стабильные. Плотность задаётся
    // константами *_PER_SECOND ниже (частиц в секунду) - их и подкручивать.
    // Нагрузка: блоки дальше EFFECT_RANGE от ближайшего игрока выходят после одной проверки.
    // Ванильная настройка "Частицы" (все/меньше/минимум) учитывается движком частиц сама.
    // Используются только общие API (addParticle / playLocalSound / getNearestPlayer), поэтому
    // класс безопасно загружается и на выделенном сервере. Регистрация - TeleportBlock.getTicker.
    // ===========================================================================================
    private static final double EFFECT_RANGE = 24.0;
    private static final double TRAIL_PLAYER_RANGE = 16.0;
    // Длина следа к партнёру в блоках (см. spawnTrailTowardPartner)
    private static final double TRAIL_LENGTH = 6.0;

    // === Плотность частиц, штук в секунду ===
    // Сколько частиц видно одновременно = частиц в секунду * время жизни частицы.
    // PORTAL живёт ~2.2 с, REVERSE_PORTAL (след) ~3 с. В скобках - сколько примерно видно сразу.
    // Несвязанный блок: редкие частицы, блок "спит" (~3)
    private static final float UNLINKED_PER_SECOND = 1.5f;
    // Связанный блок в обычном режиме: втягивание с 1.2-2.0 блока (~18)
    private static final float LINKED_PER_SECOND = 8f;
    // Пассивный режим: втягивание с кольца 1.6-2.4 блока (~26)
    private static final float PASSIVE_PER_SECOND = 12f;
    // Связь через измерения: втягивание со сферы 2.6-3.4 блока - добавляется к частицам режима (~35)
    private static final float CROSS_DIMENSION_PER_SECOND = 16f;
    // След к партнёру (~9)
    private static final float TRAIL_PER_SECOND = 3f;

    // Сколько частиц выпустить в этот тик при заданной плотности "в секунду". Дробная часть
    // разыгрывается случайно, поэтому в среднем получается ровно perSecond, и значения больше
    // 20 в секунду (больше одной частицы за тик) тоже работают.
    private static int particlesThisTick(RandomSource random, float perSecond) {
        float perTick = perSecond / 20f;
        int count = (int) perTick;
        if (random.nextFloat() < perTick - count) count++;
        return count;
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, TeleportBlockEntity be) {
        Vec3 center = Vec3.atCenterOf(pos);
        // Для блока на корабле Sable pos - плот-координаты. Расстояние до игрока и звук считаем
        // по мировой проекции, иначе на кораблях эффекты никогда бы не включались.
        Vec3 worldCenter = SableCompat.toGlobalPos(level, center);
        if (level.getNearestPlayer(worldCenter.x, worldCenter.y, worldCenter.z, EFFECT_RANGE, false) == null) return;

        RandomSource random = level.getRandom();
        boolean linked = be.target != null || be.waystoneTarget != null;

        if (!linked) {
            // Не связан - редкие одиночные частицы: видно, что блок "спит"
            for (int i = particlesThisTick(random, UNLINKED_PER_SECOND); i > 0; i--) {
                spawnAmbientPortal(level, pos, random);
            }
            return;
        }

        boolean passive = state.hasProperty(TeleportBlock.PASSIVE) && state.getValue(TeleportBlock.PASSIVE);
        if (passive) {
            for (int i = particlesThisTick(random, PASSIVE_PER_SECOND); i > 0; i--) {
                spawnSuction(level, center, random);
            }
        } else {
            for (int i = particlesThisTick(random, LINKED_PER_SECOND); i > 0; i--) {
                spawnLinkedInflow(level, center, random);
            }
        }

        if (be.isCrossDimension()) {
            // Связь через измерения: частицы втягиваются в блок со всех сторон по сфере
            // и тихое гудение - в среднем раз в 5 секунд, как у ванильного проводника
            for (int i = particlesThisTick(random, CROSS_DIMENSION_PER_SECOND); i > 0; i--) {
                spawnSphereInflow(level, center, random);
            }
            if (random.nextInt(100) == 0) {
                level.playLocalSound(worldCenter.x, worldCenter.y, worldCenter.z, ModSounds.AMBIENT.get(),
                        SoundSource.BLOCKS, 0.35f, 0.9f + random.nextFloat() * 0.2f, false);
            }
        } else if (be.target != null
                && level.getNearestPlayer(worldCenter.x, worldCenter.y, worldCenter.z, TRAIL_PLAYER_RANGE, false) != null) {
            for (int i = particlesThisTick(random, TRAIL_PER_SECOND); i > 0; i--) {
                spawnTrailTowardPartner(level, center, worldCenter, be, random);
            }
        }
    }

    // Частица портала в случайной точке блока - только у несвязанного блока ("спит").
    // Нулевое смещение: PORTAL просто медленно опускается на блок из точки на 1 блок выше.
    private static void spawnAmbientPortal(Level level, BlockPos pos, RandomSource random) {
        level.addParticle(ParticleTypes.PORTAL,
                pos.getX() + random.nextDouble(),
                pos.getY() + random.nextDouble(),
                pos.getZ() + random.nextDouble(),
                0, 0, 0);
    }

    // Пассивный режим: частицы по кольцу вокруг блока втягиваются в его центр.
    // Особенность PORTAL: переданная "скорость" - это смещение НАЧАЛЬНОЙ точки, частица летит из
    // (старт + смещение + 1 по Y) в старт (см. ванильный PortalParticle.tick). Поэтому стартом
    // служит центр блока, а смещением - вектор от центра до точки на кольце (Y минус 1, чтобы
    // частица появилась ровно на кольце). Формула до 2.2 ставила старт на кольцо, и частицы
    // оседали на кольцо, а не втягивались внутрь.
    // Радиус 1.6-2.4 блока (2.2): частицы прилетают издалека и пролетают заметный путь.
    private static void spawnSuction(Level level, Vec3 center, RandomSource random) {
        double radius = 1.6 + random.nextDouble() * 0.8;
        double angle = random.nextDouble() * Math.PI * 2;
        double dx = Math.cos(angle) * radius;
        double dz = Math.sin(angle) * radius;
        double dy = random.nextDouble() * 1.2 - 0.6;
        level.addParticle(ParticleTypes.PORTAL, center.x, center.y, center.z, dx, dy - 1.0, dz);
    }

    // Связанный блок в обычном режиме: частицы прилетают в блок из случайных точек на расстоянии
    // 1.2-2.0 блока. Ближе и реже, чем у межмировой связи, чтобы состояния отличались на глаз.
    private static void spawnLinkedInflow(Level level, Vec3 center, RandomSource random) {
        spawnInflowFromSphere(level, center, random, 1.2, 0.8);
    }

    // Связь через измерения: частицы со случайных точек сферы радиусом 2.6-3.4 блока летят в центр.
    private static void spawnSphereInflow(Level level, Vec3 center, RandomSource random) {
        spawnInflowFromSphere(level, center, random, 2.6, 0.8);
    }

    // Общая часть: случайная точка на сфере (radiusMin .. radiusMin + radiusSpread) летит в центр.
    // Та же техника смещения PORTAL, что в spawnSuction.
    private static void spawnInflowFromSphere(Level level, Vec3 center, RandomSource random,
                                              double radiusMin, double radiusSpread) {
        double x = random.nextGaussian();
        double y = random.nextGaussian();
        double z = random.nextGaussian();
        double len = Math.sqrt(x * x + y * y + z * z);
        if (len < 1.0E-6) return;
        double radius = radiusMin + random.nextDouble() * radiusSpread;
        double scale = radius / len;
        level.addParticle(ParticleTypes.PORTAL, center.x, center.y, center.z,
                x * scale, y * scale - 1.0, z * scale);
    }

    // След к партнёру в том же мире: фиолетовые "эндер"-частицы REVERSE_PORTAL (как у плачущего
    // обсидиана) вылетают из блока строго в сторону партнёра и пролетают около 6 блоков.
    // REVERSE_PORTAL летит по прямой без дуги и отскока (в отличие от PORTAL) и уменьшается к концу
    // жизни, поэтому направление читается чётко. Разброса нет: только крошечное смещение точки
    // вылета, чтобы частицы не сливались в одну.
    // Движение REVERSE_PORTAL: каждый тик x += скорость * (возраст / время жизни), то есть за всю
    // жизнь (~61 тик) частица пролетает примерно скорость * 31 блок.
    // Если партнёр ближе 6 блоков, скорость уменьшается, чтобы частицы его не перелетали.
    // Направление - в мировых координатах: для партнёра на корабле берётся realTarget (мировая
    // позиция на момент линковки).
    private static void spawnTrailTowardPartner(Level level, Vec3 center, Vec3 worldCenter,
                                                TeleportBlockEntity be, RandomSource random) {
        BlockPos partner = be.realTarget != null ? be.realTarget : be.target;
        Vec3 toPartner = Vec3.atCenterOf(partner).subtract(worldCenter);
        double distance = toPartner.length();
        if (distance < 1.5) return;
        Vec3 dir = toPartner.scale(1.0 / distance);
        double travel = Math.min(TRAIL_LENGTH, distance - 0.5);
        double speed = travel / 31.0;
        level.addParticle(ParticleTypes.REVERSE_PORTAL,
                center.x + dir.x * 0.6 + (random.nextDouble() - 0.5) * 0.1,
                center.y + dir.y * 0.6 + (random.nextDouble() - 0.5) * 0.1,
                center.z + dir.z * 0.6 + (random.nextDouble() - 0.5) * 0.1,
                dir.x * speed, dir.y * speed, dir.z * speed);
    }

    // --- Client sync ---

    private void syncToClient() {
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // --- Serialization ---

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (target != null) {
            tag.putInt("target_x", target.getX());
            tag.putInt("target_y", target.getY());
            tag.putInt("target_z", target.getZ());
            if (targetDimension != null) {
                tag.putString("target_dim", targetDimension.location().toString());
            }
        }
        if (realTarget != null) {
            tag.putInt("real_target_x", realTarget.getX());
            tag.putInt("real_target_y", realTarget.getY());
            tag.putInt("real_target_z", realTarget.getZ());
        }
        if (waystoneTarget != null) {
            tag.putUUID("waystone_target", waystoneTarget);
        }
        tag.putInt("teleport_count", teleportCount);
        if (linkName != null) {
            tag.putString("link_name", linkName);
        }
        if (linkColor != -1) {
            tag.putInt("link_color", linkColor);
        }
        if (owner != null) {
            tag.putUUID("owner", owner);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("target_x")) {
            target = new BlockPos(tag.getInt("target_x"), tag.getInt("target_y"), tag.getInt("target_z"));
        } else {
            target = null;
        }
        // tryParse: битая строка не должна ронять загрузку чанка - тогда связь просто считается
        // связью внутри мира, а валидация в doTeleport снимет её, если партнёра там нет.
        ResourceLocation dimId = tag.contains("target_dim") ? ResourceLocation.tryParse(tag.getString("target_dim")) : null;
        targetDimension = (target != null && dimId != null) ? ResourceKey.create(Registries.DIMENSION, dimId) : null;
        if (tag.contains("real_target_x")) {
            realTarget = new BlockPos(tag.getInt("real_target_x"), tag.getInt("real_target_y"), tag.getInt("real_target_z"));
        } else {
            realTarget = null;
        }
        if (tag.contains("waystone_target")) {
            waystoneTarget = tag.getUUID("waystone_target");
        } else {
            waystoneTarget = null;
        }
        teleportCount = tag.getInt("teleport_count");
        linkName = tag.contains("link_name") ? tag.getString("link_name") : null;
        linkColor = tag.contains("link_color") ? tag.getInt("link_color") : -1;
        owner = tag.hasUUID("owner") ? tag.getUUID("owner") : null;
        registerColor(linkColor);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        super.handleUpdateTag(tag, registries);
        if (level != null && level.isClientSide()) {
            updateMinimapWaypoints();
        }
    }

    // handleUpdateTag вызывается только при загрузке чанка (вход в мир).
    // Для инкрементальных обновлений (sendBlockUpdated при линковке/отвязке)
    // нужен onDataPacket — иначе вейпоинты появятся только после перезахода.
    @Override
    public void onDataPacket(net.minecraft.network.Connection net,
                              net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket pkt,
                              HolderLookup.Provider registries) {
        super.onDataPacket(net, pkt, registries);
        if (level != null && level.isClientSide()) {
            updateMinimapWaypoints();
        }
    }

    @Override
    public void setRemoved() {
        if (level != null && level.isClientSide()) {
            removeMinimapWaypoints();
        }
        super.setRemoved();
    }

    private void updateMinimapWaypoints() {
        if (net.neoforged.fml.ModList.get().isLoaded("journeymap")) {
            com.aeldrin.teleportblock.compat.journeymap.JourneyMapCompat
                    .updateWaypoint(worldPosition, target, linkColor, linkName, level.dimension());
        }
    }

    private void removeMinimapWaypoints() {
        if (net.neoforged.fml.ModList.get().isLoaded("journeymap")) {
            com.aeldrin.teleportblock.compat.journeymap.JourneyMapCompat
                    .removeWaypoint(worldPosition);
        }
    }
}
