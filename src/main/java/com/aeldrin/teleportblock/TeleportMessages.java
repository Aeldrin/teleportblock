package com.aeldrin.teleportblock;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import java.util.Map;

// Оформление сообщений игроку (2.2): иконка блока по краям, цвет по типу сообщения, аргументы
// (секунды, очки опыта, дальность, имена) выделены белым.
// Оформление задаётся здесь, в коде, а не в файлах перевода: переводы на 14 языков остаются
// чистым текстом, а цвета и иконка одинаковые во всех языках.
//
// Иконка - символ U+E000 собственного шрифта teleportblock:icons
// (assets/teleportblock/font/icons.json). Шрифт берёт картинку маркера карты
// (textures/map/decorations/teleport_block.png) и масштабирует её под высоту строки.
// Отдельной текстуры нет: если ресурспак заменит маркер карты, иконка в сообщениях сменится тоже.
// Иконка рисуется белым цветом - цвет шрифта умножается на картинку, и белый оставляет её
// исходные цвета (иначе она перекрасилась бы в цвет сообщения).
//
// Тип определяется по ключу сообщения (KINDS), а не в месте вызова: часть ключей выбирается
// переменными (режим, причина отказа), и так тип всегда один и тот же. Новый ключ без записи
// в KINDS считается подсказкой (INFO).
public final class TeleportMessages {

    public enum Kind {
        ERROR(ChatFormatting.RED),           // запрет / ошибка
        WAIT(ChatFormatting.YELLOW),         // ожидание (кулдаун)
        SUCCESS(ChatFormatting.GREEN),       // успех
        INFO(ChatFormatting.LIGHT_PURPLE);   // подсказка / процесс (цвет мода)

        private final ChatFormatting color;

        Kind(ChatFormatting color) {
            this.color = color;
        }
    }

    private static final String PREFIX = "teleportblock.message.";

    private static final Map<String, Kind> KINDS = Map.ofEntries(
            Map.entry("cannot_self_link", Kind.ERROR),
            Map.entry("first_not_found", Kind.ERROR),
            Map.entry("too_far", Kind.ERROR),
            Map.entry("blocked", Kind.ERROR),
            Map.entry("dismount", Kind.ERROR),
            Map.entry("waystone_not_found", Kind.ERROR),
            Map.entry("waystone_lost", Kind.ERROR),
            Map.entry("chunk_protected", Kind.ERROR),
            Map.entry("target_unloaded", Kind.ERROR),
            Map.entry("link_broken", Kind.ERROR),
            Map.entry("unsafe", Kind.ERROR),
            Map.entry("different_dimension", Kind.ERROR),
            Map.entry("not_enough_xp", Kind.ERROR),
            Map.entry("not_owner", Kind.ERROR),
            Map.entry("cross_dimension_disabled", Kind.ERROR),
            Map.entry("dimension_blacklisted", Kind.ERROR),
            Map.entry("cooldown", Kind.WAIT),
            Map.entry("linked", Kind.SUCCESS),
            Map.entry("named", Kind.SUCCESS),
            Map.entry("linked_to_waystone", Kind.SUCCESS),
            Map.entry("first_selected", Kind.INFO),
            Map.entry("not_linked", Kind.INFO),
            Map.entry("no_pending_block", Kind.INFO),
            Map.entry("passive_enabled", Kind.INFO),
            Map.entry("passive_disabled", Kind.INFO)
    );

    private static final ResourceLocation ICON_FONT =
            ResourceLocation.fromNamespaceAndPath(TeleportBlockMod.MODID, "icons");
    private static final String ICON_CHAR = "\uE000";

    private TeleportMessages() {}

    public static Kind kindOf(String key) {
        String shortKey = key.startsWith(PREFIX) ? key.substring(PREFIX.length()) : key;
        return KINDS.getOrDefault(shortKey, Kind.INFO);
    }

    // Готовое оформленное сообщение: [иконка] текст [иконка]
    public static Component format(String key, Object... args) {
        Object[] styledArgs = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            styledArgs[i] = args[i] instanceof Component c
                    ? c.copy().withStyle(ChatFormatting.WHITE)
                    : Component.literal(String.valueOf(args[i])).withStyle(ChatFormatting.WHITE);
        }
        MutableComponent text = Component.translatable(key, styledArgs).withStyle(kindOf(key).color);
        return Component.empty()
                .append(icon())
                .append(Component.literal(" "))
                .append(text)
                .append(Component.literal(" "))
                .append(icon());
    }

    // Сообщение в строке действий (над хотбаром) - основной способ у мода
    public static void actionBar(Player player, String key, Object... args) {
        player.displayClientMessage(format(key, args), true);
    }

    // Сообщение в чат - для линковки к Waystone, как было раньше
    public static void chat(Player player, String key, Object... args) {
        player.sendSystemMessage(format(key, args));
    }

    private static Component icon() {
        return Component.literal(ICON_CHAR).withStyle(Style.EMPTY.withFont(ICON_FONT).withColor(ChatFormatting.WHITE));
    }
}
