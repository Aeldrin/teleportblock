package com.aeldrin.teleportblock;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

// Настройки мода (2.1). Два одинаковых по структуре набора, каждый в своём файле:
//   - SINGLEPLAYER -> config/teleportblock-singleplayer.toml, регистрируется только у клиента.
//     Действует в одиночной игре и в мире, открытом для LAN (это мир хоста).
//   - SERVER       -> config/teleportblock-server.toml, регистрируется только на выделенном сервере.
//     Правится админом вручную; клиент его не видит и не создаёт.
// Каждая сторона регистрирует РОВНО ОДИН файл (см. TeleportBlockMod), поэтому get() просто
// выбирает набор по среде запуска. Вся игровая логика, читающая настройки, выполняется на
// логическом сервере: в одиночной игре это встроенный сервер в том же процессе клиента
// (-> SINGLEPLAYER), на выделенном сервере - сам сервер (-> SERVER).
//
// Тип COMMON, а не SERVER, осознанно: SERVER-конфиг NeoForge хранит в папке КАЖДОГО мира, а нужен
// один общий файл настроек одиночной игры, редактируемый в том числе из главного меню.
//
// Диапазоны целых чисел подобраны меньше 256 значений там, где это возможно: встроенный экран
// конфигов NeoForge показывает такие значения ползунком, а бОльшие диапазоны - полем ввода.
// По той же причине цена хранится целым числом "очков за 100 блоков", а не дробным "за блок".
//
// Файл teleportblock-common.toml из версий до 2.1 больше не читается.
public class ModConfig {

    public static final class Settings {
        public final ModConfigSpec spec;

        public final ModConfigSpec.IntValue cooldownSeconds;
        public final ModConfigSpec.IntValue pearlCooldownSeconds;
        public final ModConfigSpec.IntValue maxLinkDistance;
        public final ModConfigSpec.BooleanValue checkDistanceOnTeleport;
        public final ModConfigSpec.IntValue xpPer100Blocks;
        public final ModConfigSpec.IntValue maxXpCost;
        public final ModConfigSpec.BooleanValue ownerOnly;
        public final ModConfigSpec.BooleanValue crossDimension;
        public final ModConfigSpec.ConfigValue<List<? extends String>> dimensionBlacklist;
        public final ModConfigSpec.IntValue crossDimensionXpCost;

        private Settings() {
            ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

            cooldownSeconds = builder
                    .comment("Cooldown in seconds between teleportations (per link pair)")
                    .defineInRange("cooldown_seconds", 2, 0, 120);

            pearlCooldownSeconds = builder
                    .comment("Cooldown in seconds for ender pearl teleportation through a linked block")
                    .defineInRange("pearl_cooldown_seconds", 1, 0, 60);

            maxLinkDistance = builder
                    .comment("Maximum distance between two linked blocks (in blocks)")
                    .defineInRange("max_link_distance", 1024, 1, 100000);

            checkDistanceOnTeleport = builder
                    .comment("Also check max_link_distance on every teleport, not only when linking.",
                            "Useful with Create: Aeronautics - a ship can fly any distance away after linking.")
                    .define("check_distance_on_teleport", false);

            xpPer100Blocks = builder
                    .comment("Experience POINTS (not levels) charged per 100 blocks of straight-line distance.",
                            "0 = teleportation is free. Creative and spectator players are never charged.")
                    .defineInRange("xp_per_100_blocks", 0, 0, 200);

            maxXpCost = builder
                    .comment("Maximum experience points a single teleport can cost. 0 = no limit.")
                    .defineInRange("max_xp_cost", 0, 0, 100000);

            ownerOnly = builder
                    .comment("Only the player who linked a pair (or a server operator) can re-link it,",
                            "toggle its passive mode or rename it. Links made before 2.1 have no owner and stay shared.")
                    .define("owner_only", false);

            // === Кросс-дименшен (2.2) ===
            // Расстояние между измерениями считается в координатах Верхнего мира: x и z умножаются
            // на масштаб измерения (Незер = 8, Верхний мир и Энд = 1, модовые измерения - свой).
            // Так связь Незер <-> Верхний мир работает как ванильный портал. Энд не ограничивается
            // отдельно: чтобы поставить там блок, игрок должен туда попасть - прогресс не пропускается.
            crossDimension = builder
                    .comment("Allow linking Teleport Blocks in different dimensions.",
                            "Distance between dimensions uses Overworld-scale coordinates (the Nether counts x8, like vanilla portals).",
                            "Existing cross-dimension links stop working while this is off, but are kept.")
                    .define("cross_dimension", true);

            dimensionBlacklist = builder
                    .comment("Dimensions that cannot be linked to or from another dimension, e.g. [\"minecraft:the_end\"].",
                            "Links inside such a dimension still work.")
                    .defineListAllowEmpty("dimension_blacklist", List.of(), () -> "",
                            entry -> entry instanceof String id && ResourceLocation.tryParse(id) != null);

            crossDimensionXpCost = builder
                    .comment("Extra experience POINTS charged for a teleport between dimensions, on top of the distance cost.",
                            "0 = no extra cost. Creative and spectator players are never charged.")
                    .defineInRange("cross_dimension_xp_cost", 0, 0, 200);

            spec = builder.build();
        }

        // Измерение в чёрном списке межмировых связей
        public boolean isDimensionBlacklisted(ResourceKey<Level> dimension) {
            return dimensionBlacklist.get().contains(dimension.location().toString());
        }
    }

    public static final Settings SINGLEPLAYER = new Settings();
    public static final Settings SERVER = new Settings();

    // Активный набор настроек для текущей среды. Вызывать только из серверной логики.
    public static Settings get() {
        return FMLEnvironment.dist.isDedicatedServer() ? SERVER : SINGLEPLAYER;
    }
}
