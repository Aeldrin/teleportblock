package com.aeldrin.teleportblock.compat.opac;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import xaero.pac.common.server.api.OpenPACServerAPI;

// Совместимость с Open Parties and Claims (2.2) - то же, что FTBChunksCompat, для второго
// популярного мода приватов на NeoForge. Вызывать только под ModList.isLoaded("openpartiesandclaims").
// Проверено по исходникам OPAC (ветка 1.21, версия 0.32.7):
//   - Ничейный чанк - телепорт разрешён. Проверять его через hasChunkAccess НЕЛЬЗЯ: для ничейного
//     чанка OPAC берёт конфиг "дикой местности", и доступ к нему у обычного игрока не гарантирован.
//   - Чанк в привате - решает hasChunkAccess: владелец, его пати и союзники (по настройкам привата
//     владельца) - да, остальные - нет. Серверные приваты (спавн и т.п.) обычным игрокам закрыты.
public class OpenPACCompat {

    public static boolean canTeleportTo(ServerPlayer player, ServerLevel level, BlockPos pos) {
        OpenPACServerAPI api = OpenPACServerAPI.get(level.getServer());
        ResourceLocation dimension = level.dimension().location();
        if (api.getServerClaimsManager().get(dimension, pos) == null) {
            return true;
        }
        return api.getChunkProtection().hasChunkAccess(player, dimension, pos.getX() >> 4, pos.getZ() >> 4);
    }
}
